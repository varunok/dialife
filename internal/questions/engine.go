package questions

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync"
	"time"

	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/glucose"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

const (
	EventQuestion = "question" // SSE: a question opened, changed, closed, or asks again ("remind")

	unanswered  = 12 * time.Hour   // then it becomes a suspect mark
	remindEvery = 30 * time.Minute // re-ask whoever has not answered
	sameEpisode = 30 * time.Minute // a rise that keeps going is still the first question
	nightFrom   = 23               // local hours when asking is silent
	nightTo     = 7
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
	Quiet       bool     `json:"quiet,omitempty"` // night: show it without sound
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
	return e.detect(ctx, qs, logged, now)
}

func (e *Engine) step(ctx context.Context, q store.Question, logged []loggedEntry, now time.Time) error {
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	// an entry that explains it: from xDrip+ it settles the question, from a parent it is their vote
	for _, l := range logged {
		if l.T > now.UnixMilli() || !Explains(q.Kind, q.From, l.Entry) || l.ID == q.TreatmentID {
			continue
		}
		answer := "food"
		if q.Kind == "fall" {
			answer = "insulin"
		}
		dev, voter := caregiverByID(devs, l.DeviceID)
		if !voter {
			q.State, q.Outcome, q.TreatmentID, q.ClosedBy = "answered", answer, l.ID, "xDrip"
			q.ClosedAt = ptr(now.UnixMilli())
			return e.save(ctx, q, now, false)
		}
		as, err := e.Store.QuestionAnswers(ctx, q.ID)
		if err != nil {
			return err
		}
		if !answeredThisRound(as, q.Round, dev.Person) {
			return e.vote(ctx, q, dev, answer, l.ID, now)
		}
	}
	if now.UnixMilli()-q.To >= unanswered.Milliseconds() {
		q.State = "unknown"
		q.ClosedAt = ptr(now.UnixMilli())
		return e.save(ctx, q, now, false)
	}
	if q.RemindedAt == nil || now.UnixMilli()-*q.RemindedAt >= remindEvery.Milliseconds() {
		q.RemindedAt = ptr(now.UnixMilli())
		return e.save(ctx, q, now, true)
	}
	return nil
}

func (e *Engine) detect(ctx context.Context, qs []store.Question, logged []loggedEntry, now time.Time) error {
	from := now.Add(-2 * time.Hour).UnixMilli()
	to := now.UnixMilli()
	es, err := e.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: 100, Asc: true})
	if err != nil {
		return err
	}
	var sgv []Point
	for _, x := range es {
		if x.SGV != nil && *x.SGV > glucose.MaxErrorCode {
			sgv = append(sgv, Point{T: x.Date, V: *x.SGV})
		}
	}
	entries := make([]Entry, len(logged))
	var starts []int64
	for i, l := range logged {
		entries[i] = l.Entry
		if l.start {
			starts = append(starts, l.T)
		}
	}
	loc := e.Loc
	if loc == nil {
		loc = time.UTC
	}
	s, ok := Detect(sgv, entries, starts, now.In(loc))
	if !ok {
		return nil
	}
	for _, q := range qs {
		if q.Kind == s.Kind && q.To >= s.From-sameEpisode.Milliseconds() {
			return nil // the same rise or fall, already asked (or answered)
		}
	}
	q, err := e.Store.InsertQuestion(ctx, store.Question{Kind: s.Kind, From: s.From, To: s.To, SGVFrom: s.SGVFrom, SGVTo: s.SGVTo}, now)
	if err != nil {
		return err
	}
	slog.Info("question", "id", q.ID, "kind", q.Kind, "from", s.SGVFrom, "to", s.SGVTo)
	return e.publish(ctx, q, now, false)
}

// Answer records a parent's answer and settles the question once everyone agrees.
func (e *Engine) Answer(ctx context.Context, id int64, by store.Device, answer, treatmentID string) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if !slices.Contains([]string{"food", "food_unknown", "insulin", "nothing"}, answer) {
		return View{}, ErrAnswer
	}
	q, err := e.open(ctx, id)
	if err != nil {
		return View{}, err
	}
	now := e.Now()
	if err := e.vote(ctx, q, by, answer, treatmentID, now); err != nil {
		return View{}, err
	}
	q, err = e.Store.Question(ctx, id)
	if err != nil {
		return View{}, err
	}
	return e.View(ctx, q, now)
}

// Dismiss clears a suspect mark (a question nobody answered in time).
func (e *Engine) Dismiss(ctx context.Context, id int64, by store.Device) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	q, err := e.Store.Question(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return View{}, ErrNotFound
	}
	if err != nil {
		return View{}, err
	}
	if q.State != "unknown" {
		return View{}, ErrClosed
	}
	now := e.Now()
	q.State, q.ClosedBy = "dismissed", by.Person
	q.ClosedAt = ptr(now.UnixMilli())
	if err := e.save(ctx, q, now, false); err != nil {
		return View{}, err
	}
	return e.View(ctx, q, now)
}

func (e *Engine) vote(ctx context.Context, q store.Question, by store.Device, answer, treatmentID string, now time.Time) error {
	if err := e.Store.InsertQuestionAnswer(ctx, store.QuestionAnswer{
		QuestionID: q.ID, Round: q.Round, Person: by.Person, DeviceID: by.ID, Answer: answer, TreatmentID: treatmentID, At: now.UnixMilli(),
	}); err != nil {
		return err
	}
	if treatmentID != "" {
		q.TreatmentID = treatmentID
	}
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	as, err := e.Store.QuestionAnswers(ctx, q.ID)
	if err != nil {
		return err
	}
	voters := people(devs)
	latest := map[string]string{}
	for _, a := range as {
		if a.Round == q.Round {
			latest[a.Person] = a.Answer
		}
	}
	for _, p := range voters {
		if _, ok := latest[p]; !ok {
			return e.save(ctx, q, now, false) // still waiting for someone
		}
	}
	outcome, agree := agreed(latest, q.TreatmentID != "")
	if agree {
		q.State, q.Outcome = "answered", outcome
		q.ClosedAt = ptr(now.UnixMilli())
		slog.Info("question answered", "id", q.ID, "outcome", outcome)
		return e.save(ctx, q, now, false)
	}
	// they disagree: everyone answers again, seeing what the others said
	q.Round++
	q.RemindedAt = ptr(now.UnixMilli())
	slog.Info("question disagreement", "id", q.ID, "round", q.Round)
	return e.save(ctx, q, now, false)
}

// agreed: everyone said the same thing. "food" and "food, amount unknown" agree
// that she ate; with an entry made for it, the answer is "food".
func agreed(latest map[string]string, hasEntry bool) (string, bool) {
	seen := map[string]bool{}
	for _, a := range latest {
		if a == "food_unknown" {
			a = "food"
		}
		seen[a] = true
	}
	if len(seen) != 1 {
		return "", false
	}
	for a := range seen {
		if a == "food" && !hasEntry {
			for _, raw := range latest {
				if raw == "food_unknown" {
					return "food_unknown", true
				}
			}
		}
		return a, true
	}
	return "", false
}

func (e *Engine) open(ctx context.Context, id int64) (store.Question, error) {
	q, err := e.Store.Question(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return q, ErrNotFound
	}
	if err != nil {
		return q, err
	}
	if q.State != "open" {
		return q, ErrClosed
	}
	return q, nil
}

func (e *Engine) save(ctx context.Context, q store.Question, now time.Time, remind bool) error {
	if err := e.Store.UpdateQuestion(ctx, q); err != nil {
		return err
	}
	return e.publish(ctx, q, now, remind)
}

func (e *Engine) publish(ctx context.Context, q store.Question, now time.Time, remind bool) error {
	v, err := e.View(ctx, q, now)
	if err != nil {
		return err
	}
	v.Remind = remind
	e.Bus.Publish(EventQuestion, v, now)
	return nil
}

// View is a question as the app shows it, with who is still to answer.
func (e *Engine) View(ctx context.Context, q store.Question, now time.Time) (View, error) {
	v := View{
		ID: q.ID, Kind: q.Kind, State: q.State, From: q.From, To: q.To, SGVFrom: q.SGVFrom, SGVTo: q.SGVTo,
		Round: q.Round, Outcome: q.Outcome, TreatmentID: q.TreatmentID, ClosedBy: q.ClosedBy,
		Answers: []Answer{}, Waiting: []string{}, WaitingIDs: []int64{},
	}
	as, err := e.Store.QuestionAnswers(ctx, q.ID)
	if err != nil {
		return v, err
	}
	for _, a := range as {
		v.Answers = append(v.Answers, Answer{Person: a.Person, Answer: a.Answer, Round: a.Round, At: a.At, TreatmentID: a.TreatmentID})
	}
	if q.State == "open" {
		devs, err := e.Store.ListDevices(ctx)
		if err != nil {
			return v, err
		}
		for _, p := range people(devs) {
			if answeredThisRound(as, q.Round, p) {
				continue
			}
			v.Waiting = append(v.Waiting, p)
			for _, d := range devs {
				if isCaregiver(d) && d.Person == p {
					v.WaitingIDs = append(v.WaitingIDs, d.ID)
				}
			}
		}
	}
	loc := e.Loc
	if loc == nil {
		loc = time.UTC
	}
	h := now.In(loc).Hour()
	v.Quiet = h >= nightFrom || h < nightTo
	return v, nil
}

// Recent: open questions and suspect marks of the last day, for the state snapshot.
func (e *Engine) Recent(ctx context.Context) ([]View, error) {
	now := e.Now()
	qs, err := e.Store.QuestionsSince(ctx, now.Add(-24*time.Hour).UnixMilli())
	if err != nil {
		return nil, err
	}
	out := []View{}
	for _, q := range qs {
		if q.State != "open" && q.State != "unknown" {
			continue
		}
		v, err := e.View(ctx, q, now)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

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
		out = append(out, loggedEntry{
			Entry: Entry{
				T:        tr.CreatedAt,
				Carbs:    tr.Carbs != nil && *tr.Carbs > 0,
				Rapid:    insulin && !isBasal(tr.Raw),
				Exercise: tr.EventType == "Exercise",
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

func isBasal(raw []byte) bool {
	typ, _ := insulin.Kind(raw)
	return typ == "basal"
}

func ptr[T any](v T) *T { return &v }
