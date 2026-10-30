package questions

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/store"
)

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
	ps, err := e.Store.PlannedDosesSince(ctx, meal.T-BolusBefore.Milliseconds())
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
