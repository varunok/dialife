package questions

import (
	"context"
	"errors"
	"time"

	"dialife/internal/store"
)

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

// Recent returns open questions and suspect marks of the last day, for the state snapshot.
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
