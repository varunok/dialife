package questions

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/store"
	"dialife/internal/trust"
)

// step moves an open question on: closes it when its reason went away or an
// entry explains it, records a parent's vote, gives up when nobody answered,
// or reminds again.
func (e *Engine) step(ctx context.Context, q store.Question, logged []loggedEntry, now time.Time) error {
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	switch q.Kind {
	case "planned":
		return e.stepPlanned(ctx, q, devs, now)
	case "double":
		// someone deleted one of the two: nothing left to ask
		gone, err := e.eitherGone(ctx, q.TreatmentID, q.RefID)
		if err != nil {
			return err
		}
		if gone {
			q.State, q.Outcome = "answered", "one"
			q.ClosedAt = ptr(now.UnixMilli())
			return e.save(ctx, q, now, false)
		}
	}
	if done, err := e.settleByEntry(ctx, q, devs, logged, now); done || err != nil {
		return err
	}
	if now.UnixMilli()-q.To >= unanswered.Milliseconds() {
		q.State = "unknown"
		q.ClosedAt = ptr(now.UnixMilli())
		return e.save(ctx, q, now, false)
	}
	return e.remindIfDue(ctx, q, now)
}

// stepPlanned: a planned dose given, skipped or gone closes its question with
// that outcome (and who closed it); while it is still planned, a reminder.
func (e *Engine) stepPlanned(ctx context.Context, q store.Question, devs []store.Device, now time.Time) error {
	p, err := e.Store.PlannedDose(ctx, q.RefID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return err
	}
	if err == nil && p.State == "planned" {
		return e.remindIfDue(ctx, q, now)
	}
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

// eitherGone: one of the two entries no longer exists.
func (e *Engine) eitherGone(ctx context.Context, ids ...string) (bool, error) {
	for _, id := range ids {
		_, ok, err := e.treatment(ctx, id)
		if err != nil || !ok {
			return err == nil, err
		}
	}
	return false, nil
}

// settleByEntry: an entry that explains it settles the question when it came
// from xDrip+, and is its parent's vote when a parent logged it (once a round).
// done: the question was saved or voted on.
func (e *Engine) settleByEntry(ctx context.Context, q store.Question, devs []store.Device, logged []loggedEntry, now time.Time) (bool, error) {
	answer := "food"
	if q.Kind == "fall" || q.Kind == "bolus" {
		answer = "insulin"
	}
	for _, l := range logged {
		if l.T > now.UnixMilli() || l.ID == q.TreatmentID || !explainsQuestion(q, l, logged) {
			continue
		}
		dev, voter := caregiverByID(devs, l.DeviceID)
		if !voter {
			q.State, q.Outcome, q.TreatmentID, q.ClosedBy = "answered", answer, l.ID, "xDrip"
			q.ClosedAt = ptr(now.UnixMilli())
			return true, e.save(ctx, q, now, false)
		}
		as, err := e.Store.QuestionAnswers(ctx, q.ID)
		if err != nil {
			return true, err
		}
		if !answeredThisRound(as, q.Round, dev.Person) {
			return true, e.vote(ctx, q, dev, answer, l.ID, now)
		}
	}
	return false, nil
}

// explainsQuestion: the entry is what the question asks about; for a missed
// bolus, also a dose linked to the meal it was for.
func explainsQuestion(q store.Question, l loggedEntry, logged []loggedEntry) bool {
	if Explains(q.Kind, q.From, l.Entry) {
		return true
	}
	return q.Kind == "bolus" && linked([]Entry{l.Entry}, mealAtLogged(logged, q.From))
}

// remindIfDue reminds again once remindEvery has passed since the last time.
func (e *Engine) remindIfDue(ctx context.Context, q store.Question, now time.Time) error {
	if q.RemindedAt != nil && now.UnixMilli()-*q.RemindedAt < remindEvery.Milliseconds() {
		return nil
	}
	q.RemindedAt = ptr(now.UnixMilli())
	return e.save(ctx, q, now, true)
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
	var untrusted []trust.Window
	for i, l := range logged {
		entries[i] = l.Entry
		if l.start {
			starts = append(starts, l.T)
		}
		if l.shower != nil {
			untrusted = append(untrusted, *l.shower)
		}
	}
	// a shower moves the sensor, not the sugar: its readings ask nothing
	// (left out, they leave a hole Detect does not reach across)
	sgv = trust.Drop(sgv, func(p Point) int64 { return p.T }, untrusted)
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
