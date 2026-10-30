package questions

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/store"
)

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
