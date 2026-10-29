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

	// a planned dose: asked about after an hour, gone (expired) after three
	PlannedAsk    = time.Hour
	PlannedExpire = 3 * time.Hour
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
func (e *Engine) detectPlanned(ctx context.Context, qs []store.Question, now time.Time) error {
	gone, err := e.Store.ExpirePlannedDoses(ctx, now.Add(-PlannedExpire).UnixMilli(), now.UnixMilli())
	if err != nil {
		return err
	}
	for _, p := range gone {
		slog.Info("planned dose expired", "id", p.ID)
		if err := e.settlePlanned(ctx, p.ID, now); err != nil {
			return err
		}
	}
	if len(gone) > 0 && e.OnPlanned != nil {
		e.OnPlanned()
	}
	ps, err := e.Store.PlannedDoses(ctx)
	if err != nil {
		return err
	}
	for _, p := range ps {
		if now.UnixMilli()-p.CreatedAt < PlannedAsk.Milliseconds() || slices.ContainsFunc(qs, func(q store.Question) bool { return q.Kind == "planned" && q.RefID == p.ID }) {
			continue
		}
		at := p.CreatedAt
		if meal, ok, err := e.treatment(ctx, p.MealID); err != nil {
			return err
		} else if ok {
			at = meal.CreatedAt
		}
		q, err := e.Store.InsertQuestion(ctx, store.Question{Kind: "planned", From: at, To: now.UnixMilli(), RefID: p.ID}, now)
		if err != nil {
			return err
		}
		slog.Info("question", "id", q.ID, "kind", "planned", "dose", p.ID)
		if err := e.publish(ctx, q, now, false); err != nil {
			return err
		}
	}
	return nil
}

// SettlePlanned closes the question about a planned dose once it was given,
// skipped or expired; nothing when there is none.
func (e *Engine) SettlePlanned(ctx context.Context, plannedID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.settlePlanned(ctx, plannedID, e.Now())
}

func (e *Engine) settlePlanned(ctx context.Context, plannedID string, now time.Time) error {
	qs, err := e.Store.QuestionsSince(ctx, now.Add(-PlannedExpire-36*time.Hour).UnixMilli())
	if err != nil {
		return err
	}
	for _, q := range qs {
		if q.Kind == "planned" && q.RefID == plannedID && q.State == "open" {
			if err := e.step(ctx, q, nil, now); err != nil {
				return err
			}
		}
	}
	return nil
}

// detectDouble asks about two doses too close together when xDrip+ sent one of them.
func (e *Engine) detectDouble(ctx context.Context, qs []store.Question, now time.Time) error {
	ds, err := e.doses(ctx, now.Add(-doubleRecent-DoubleBasal), now)
	if err != nil {
		return err
	}
	for _, p := range Doubles(ds, now) {
		dup, other := p[0], p[1]
		asked := false
		for _, q := range qs {
			if q.Kind == "double" && q.TreatmentID == dup.ID {
				asked = true
				break
			}
		}
		if asked {
			continue
		}
		q, err := e.Store.InsertQuestion(ctx, store.Question{
			Kind: "double", From: min(dup.T, other.T), To: max(dup.T, other.T), TreatmentID: dup.ID, RefID: other.ID,
		}, now)
		if err != nil {
			return err
		}
		qs = append(qs, q)
		slog.Info("question", "id", q.ID, "kind", "double", "dose", dup.ID, "other", other.ID)
		if err := e.publish(ctx, q, now, false); err != nil {
			return err
		}
	}
	return nil
}

// doses: the insulin entries between from and to, oldest first.
func (e *Engine) doses(ctx context.Context, from, to time.Time) ([]Dose, error) {
	f, t := from.UnixMilli(), to.UnixMilli()
	ts, err := e.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &f, Lte: &t, Count: 1000})
	if err != nil {
		return nil, err
	}
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return nil, err
	}
	var out []Dose
	for i := len(ts) - 1; i >= 0; i-- {
		if d, ok := doseOf(ts[i], devs); ok {
			out = append(out, d)
		}
	}
	return out, nil
}

func doseOf(tr store.Treatment, devs []store.Device) (Dose, bool) {
	if tr.Insulin == nil || *tr.Insulin <= 0 {
		return Dose{}, false
	}
	var raw struct {
		Checked string `json:"confirmedNew"`
	}
	_ = json.Unmarshal(tr.Raw, &raw)
	_, parent := caregiverByID(devs, tr.DeviceID)
	return Dose{ID: tr.ID, T: tr.CreatedAt, Units: *tr.Insulin, Basal: isBasal(tr.Raw), XDrip: !parent, Checked: raw.Checked}, true
}

// treatment: a live entry by id; false once deleted.
func (e *Engine) treatment(ctx context.Context, id string) (store.Treatment, bool, error) {
	ts, err := e.Store.ListTreatments(ctx, store.TreatmentQuery{ID: id, Count: 1})
	if err != nil || len(ts) == 0 {
		return store.Treatment{}, false, err
	}
	return ts[0], true, nil
}

// detectBolus asks about a meal logged without rapid insulin.
func (e *Engine) detectBolus(ctx context.Context, qs []store.Question, logged []loggedEntry, now time.Time) error {
	entries := make([]Entry, len(logged))
	for i, l := range logged {
		entries[i] = l.Entry
	}
	sgvAt := func(t int64) int {
		from, to := t-10*time.Minute.Milliseconds(), t+10*time.Minute.Milliseconds()
		es, err := e.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: 5, Asc: true})
		if err != nil {
			return 0
		}
		best, gap := 0, int64(-1)
		for _, x := range es {
			if x.SGV == nil || *x.SGV <= glucose.MaxErrorCode {
				continue
			}
			if d := max(x.Date-t, t-x.Date); gap < 0 || d < gap {
				best, gap = *x.SGV, d
			}
		}
		return best
	}
	meal, ok := MissingBolus(entries, now, sgvAt)
	if !ok {
		return nil
	}
	// a dose was worked out for this meal: the planned-dose question asks instead
	ps, err := e.Store.PlannedDosesSince(ctx, meal.T-bolusBefore.Milliseconds())
	if err != nil {
		return err
	}
	if len(ps) > 0 {
		return nil
	}
	for _, q := range qs {
		if q.Kind == "bolus" && q.From == meal.T {
			return nil // asked already
		}
	}
	nowSGV := sgvAt(now.UnixMilli())
	q, err := e.Store.InsertQuestion(ctx, store.Question{Kind: "bolus", From: meal.T, To: now.UnixMilli(), SGVFrom: sgvAt(meal.T), SGVTo: nowSGV}, now)
	if err != nil {
		return err
	}
	slog.Info("question", "id", q.ID, "kind", "bolus", "meal", meal.T)
	return e.publish(ctx, q, now, false)
}

func (e *Engine) step(ctx context.Context, q store.Question, logged []loggedEntry, now time.Time) error {
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	if q.Kind == "planned" {
		p, err := e.Store.PlannedDose(ctx, q.RefID)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return err
		}
		if err != nil || p.State != "planned" {
			q.State, q.Outcome = "answered", p.State
			if q.Outcome == "" {
				q.Outcome = "expired"
			}
			q.TreatmentID = p.InsulinID
			q.ClosedAt = ptr(now.UnixMilli())
			if p.ClosedBy != nil {
				if d, ok := caregiverByID(devs, *p.ClosedBy); ok {
					q.ClosedBy = d.Person
				}
			}
			return e.save(ctx, q, now, false)
		}
		if q.RemindedAt == nil || now.UnixMilli()-*q.RemindedAt >= remindEvery.Milliseconds() {
			q.RemindedAt = ptr(now.UnixMilli())
			return e.save(ctx, q, now, true)
		}
		return nil
	}
	if q.Kind == "double" {
		// someone deleted one of the two: nothing left to ask
		for _, id := range []string{q.TreatmentID, q.RefID} {
			_, ok, err := e.treatment(ctx, id)
			if err != nil {
				return err
			}
			if !ok {
				q.State, q.Outcome = "answered", "one"
				q.ClosedAt = ptr(now.UnixMilli())
				return e.save(ctx, q, now, false)
			}
		}
	}
	// an entry that explains it: from xDrip+ it settles the question, from a parent it is their vote
	for _, l := range logged {
		explains := Explains(q.Kind, q.From, l.Entry)
		if q.Kind == "bolus" && !explains {
			explains = linked([]Entry{l.Entry}, mealAtLogged(logged, q.From))
		}
		if l.T > now.UnixMilli() || !explains || l.ID == q.TreatmentID {
			continue
		}
		answer := "food"
		if q.Kind == "fall" || q.Kind == "bolus" {
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
		if s.Kind == "rise" && q.Kind == "rise" && q.Outcome == "asleep" && s.From >= q.From && s.From < q.From+asleepFor.Milliseconds() {
			return nil // she sleeps: a rise now is not food
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
	q, err := e.open(ctx, id)
	if err != nil {
		return View{}, err
	}
	valid := []string{"food", "food_unknown", "insulin", "nothing"}
	switch q.Kind {
	case "double":
		valid = []string{"one", "two"}
	case "rise":
		valid = append(valid, "asleep")
	case "planned":
		valid = nil // settled by confirming or skipping the dose itself
	}
	if !slices.Contains(valid, answer) {
		return View{}, ErrAnswer
	}
	now := e.Now()
	if answer == "asleep" {
		// one parent who knows she was asleep settles it
		if err := e.Store.InsertQuestionAnswer(ctx, store.QuestionAnswer{
			QuestionID: q.ID, Round: q.Round, Person: by.Person, DeviceID: by.ID, Answer: answer, At: now.UnixMilli(),
		}); err != nil {
			return View{}, err
		}
		q.State, q.Outcome, q.ClosedBy = "answered", "asleep", by.Person
		q.ClosedAt = ptr(now.UnixMilli())
		slog.Info("question answered", "id", q.ID, "outcome", "asleep")
		if err := e.save(ctx, q, now, false); err != nil {
			return View{}, err
		}
		return e.View(ctx, q, now)
	}
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
		if q.Kind == "double" && outcome == "one" {
			if err := e.dropDuplicate(ctx, q.TreatmentID, now); err != nil {
				return err
			}
		}
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

// dropDuplicate: "one injection" removes the second entry of it, from the chart and the stats.
func (e *Engine) dropDuplicate(ctx context.Context, id string, now time.Time) error {
	deleted, err := e.Store.DeleteTreatment(ctx, id, now)
	if err != nil || !deleted {
		return err
	}
	slog.Info("treatment delete", "id", id, "by", "double-dose answer")
	// the same event the treatments API sends (app.EventTreatment)
	e.Bus.Publish("treatment", map[string]any{"op": "delete", "ids": []string{id}}, now)
	return nil
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
	if q.Kind == "double" {
		if err := e.describeDoses(ctx, q, &v); err != nil {
			return v, err
		}
	}
	if q.Kind == "planned" {
		v.PlannedID = q.RefID
		if p, err := e.Store.PlannedDose(ctx, q.RefID); err == nil {
			v.Dose = p.Dose
		} else if !errors.Is(err, store.ErrNotFound) {
			return v, err
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

// describeDoses fills in the two doses: how much, when, who; deleted ones are left out.
func (e *Engine) describeDoses(ctx context.Context, q store.Question, v *View) error {
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	v.Insulin = "rapid"
	for _, id := range []string{q.TreatmentID, q.RefID} {
		tr, ok, err := e.treatment(ctx, id)
		if err != nil {
			return err
		}
		if !ok {
			continue
		}
		d, _ := doseOf(tr, devs)
		if d.Basal {
			v.Insulin = "basal"
		}
		by := e.Uploader
		if dev, parent := caregiverByID(devs, tr.DeviceID); parent {
			by = dev.Person
		} else if by == "" {
			by = "xDrip+"
		}
		v.Doses = append(v.Doses, DoseView{ID: tr.ID, T: tr.CreatedAt, Units: d.Units, By: by})
	}
	if q.Outcome == "two" {
		v.WatchUntil = q.To + watchFor(v.Insulin == "basal").Milliseconds()
	}
	return nil
}

// Recent: open questions and suspect marks of the last day, for the state snapshot.
func (e *Engine) Recent(ctx context.Context) ([]View, error) {
	now := e.Now()
	// a long-acting pair may begin 16 hours before its day-long watch for a low
	qs, err := e.Store.QuestionsSince(ctx, now.Add(-DoubleBasal-watchBasal).UnixMilli())
	if err != nil {
		return nil, err
	}
	out := []View{}
	for _, q := range qs {
		watch := q.Kind == "double" && q.State == "answered" && q.Outcome == "two"
		if q.State != "open" && q.State != "unknown" && !watch || q.State == "unknown" && q.From < now.Add(-24*time.Hour).UnixMilli() {
			continue
		}
		v, err := e.View(ctx, q, now)
		if err != nil {
			return nil, err
		}
		if watch && v.WatchUntil <= now.UnixMilli() {
			continue // the watch for a low is over
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
