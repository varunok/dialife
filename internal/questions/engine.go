package questions

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"slices"
	"sync"
	"time"

	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/insulin"
	"dialife/internal/planned"
	"dialife/internal/store"
)

const (
	EventQuestion = "question" // SSE: a question opened, changed, closed, or asks again ("remind")

	unanswered  = 12 * time.Hour   // then it becomes a suspect mark
	remindEvery = 30 * time.Minute // re-ask whoever has not answered
	sameEpisode = 30 * time.Minute // a rise that keeps going is still the first question
	nightFrom   = 23               // local hours when asking is silent
	nightTo     = 7

	// a planned dose: asked about after an hour, gone (expired) after three
	PlannedAsk    = time.Hour
	PlannedExpire = planned.Expire
	// "asleep" for a rise: no more rise questions this long from its start
	asleepFor = 2 * time.Hour
)

var (
	ErrNotFound = errors.New("no such question")
	ErrClosed   = errors.New("question already closed")
	ErrAnswer   = errors.New("unknown answer")
)

type Engine struct {
	Store *store.Store
	Bus   *bus.Bus
	Now   func() time.Time
	Loc   *time.Location // night hours; nil: UTC
	// what the family calls the xDrip+ phone: who gave a dose that came from it
	Uploader string
	// called after planned doses expired, so the phones get a fresh state; nil: nothing
	OnPlanned func()

	mu sync.Mutex
}

type Answer struct {
	Person      string `json:"person"`
	Answer      string `json:"answer"`
	Round       int    `json:"round"`
	At          int64  `json:"at"`
	TreatmentID string `json:"treatment_id,omitempty"`
}

type View struct {
	ID          int64    `json:"id"`
	Kind        string   `json:"kind"`
	State       string   `json:"state"`
	From        int64    `json:"from"`
	To          int64    `json:"to"`
	SGVFrom     int      `json:"sgv_from"`
	SGVTo       int      `json:"sgv_to"`
	Round       int      `json:"round"`
	Outcome     string   `json:"outcome,omitempty"`
	TreatmentID string   `json:"treatment_id,omitempty"`
	ClosedBy    string   `json:"closed_by,omitempty"`
	Answers     []Answer `json:"answers"`
	Waiting     []string `json:"waiting"`     // people yet to answer this round
	WaitingIDs  []int64  `json:"waiting_ids"` // their phones: a phone asks when its id is here
	Remind      bool     `json:"remind,omitempty"`
	// "double": the two doses, the one "one injection" would remove first;
	// Insulin is "rapid" or "basal"; WatchUntil, after "two", ends the watch for a low
	Doses      []DoseView `json:"doses,omitempty"`
	Insulin    string     `json:"insulin,omitempty"`
	WatchUntil int64      `json:"watch_until,omitempty"`
	Quiet      bool       `json:"quiet,omitempty"` // night: show it without sound
	// "planned": the dose asked about (From is its meal's time)
	PlannedID string  `json:"planned_id,omitempty"`
	Dose      float64 `json:"dose,omitempty"`
}

type DoseView struct {
	ID    string  `json:"id"`
	T     int64   `json:"t"`
	Units float64 `json:"units"`
	By    string  `json:"by"`
}

// Run checks once a minute: timeouts and reminders happen without new readings.
func (e *Engine) Run(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		if err := e.Evaluate(ctx); err != nil && ctx.Err() == nil {
			slog.Error("questions", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Evaluate closes what a new entry explains, times out and re-asks, and opens
// a question for a fresh unexplained rise or fall.
func (e *Engine) Evaluate(ctx context.Context) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	now := e.Now()
	qs, err := e.Store.QuestionsSince(ctx, now.Add(-36*time.Hour).UnixMilli())
	if err != nil {
		return err
	}
	logged, err := e.logged(ctx, now.Add(-36*time.Hour), now)
	if err != nil {
		return err
	}
	for _, q := range qs {
		if q.State != "open" {
			continue
		}
		if err := e.step(ctx, q, logged, now); err != nil {
			return err
		}
	}
	if err := e.detect(ctx, qs, logged, now); err != nil {
		return err
	}
	if err := e.detectBolus(ctx, qs, logged, now); err != nil {
		return err
	}
	if err := e.detectPlanned(ctx, qs, now); err != nil {
		return err
	}
	return e.detectDouble(ctx, qs, now)
}

// detectPlanned ends the planned doses nobody confirmed in three hours, and
// asks about those still waiting after an hour.
type loggedEntry struct {
	Entry
	ID       string
	DeviceID int64
	start    bool // a sensor start
}

func (e *Engine) logged(ctx context.Context, from, to time.Time) ([]loggedEntry, error) {
	f, t := from.UnixMilli(), to.UnixMilli()
	ts, err := e.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &f, Lte: &t, Count: 1000})
	if err != nil {
		return nil, err
	}
	out := make([]loggedEntry, 0, len(ts))
	for _, tr := range ts {
		insulin := tr.Insulin != nil && *tr.Insulin > 0
		mealID, role := insulinFor(tr.Raw)
		out = append(out, loggedEntry{
			Entry: Entry{
				T:        tr.CreatedAt,
				Carbs:    tr.Carbs != nil && *tr.Carbs > 0,
				Rapid:    insulin && !isBasal(tr.Raw),
				Exercise: tr.EventType == "Exercise",
				ID:       tr.ID,
				MealID:   mealID,
				Corr:     role == "corr",
			},
			ID:       tr.ID,
			DeviceID: tr.DeviceID,
			start:    tr.EventType == "Sensor Start" || tr.EventType == "Sensor Change",
		})
	}
	return out, nil
}

func people(devs []store.Device) []string {
	var out []string
	for _, d := range devs {
		if isCaregiver(d) && !slices.Contains(out, d.Person) {
			out = append(out, d.Person)
		}
	}
	return out
}

func isCaregiver(d store.Device) bool {
	return d.Role == string(auth.RoleCaregiver) && d.RevokedAt == nil
}

func caregiverByID(devs []store.Device, id int64) (store.Device, bool) {
	for _, d := range devs {
		if d.ID == id && isCaregiver(d) {
			return d, true
		}
	}
	return store.Device{}, false
}

func answeredThisRound(as []store.QuestionAnswer, round int, person string) bool {
	for _, a := range as {
		if a.Round == round && a.Person == person {
			return true
		}
	}
	return false
}

func mealAtLogged(logged []loggedEntry, t int64) Entry {
	es := make([]Entry, len(logged))
	for i, l := range logged {
		es[i] = l.Entry
	}
	return MealAt(es, t)
}

// insulinFor: the meal a dose was given for and "meal" or "corr", as the app sent them.
func insulinFor(raw []byte) (mealID, role string) {
	var d struct {
		MealID string `json:"mealId"`
		Role   string `json:"insulinRole"`
	}
	_ = json.Unmarshal(raw, &d)
	return d.MealID, d.Role
}

func isBasal(raw []byte) bool {
	typ, _ := insulin.Kind(raw)
	return typ == "basal"
}

func ptr[T any](v T) *T { return &v }
