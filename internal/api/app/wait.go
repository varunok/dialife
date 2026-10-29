package app

import (
	"context"
	"log/slog"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

// How long after a rapid dose to start eating: a suggestion on the food form,
// never a rule and never recorded. Rapid analogues given 15–20 min before a
// meal give a lower peak after it; a higher sugar wants a little longer, a low
// or falling one wants the food now. No study ties the wait to the size of
// the dose or of the meal, so neither changes it: they only feed the
// "more insulin than food" warning in the app.
const (
	waitWithin   = time.Hour        // a dose further back has no wait
	waitInRange  = 15 * time.Minute //
	waitHigh     = 20 * time.Minute // from waitHighFrom up
	waitHighFrom = 180              // mg/dL
	waitLowBelow = 80               // mg/dL, now or by the trend in 15 min: eat now
)

type waitState struct {
	DoseID  string  `json:"dose_id"`
	DoseAt  int64   `json:"dose_at"`
	Units   float64 `json:"units"`
	EatFrom int64   `json:"eat_from"` // unix ms; at or before now: eat now
	// "range" | "high": waiting by the sugar; "low" | "falling": eat now;
	// "unseen": no fresh reading, the usual wait
	Reason string  `json:"reason"`
	SGV    *int    `json:"sgv,omitempty"`
	Covers float64 `json:"covers_g"` // carbs the dose covers at the meal ratio
	Food   float64 `json:"food_g"`   // carbs logged since half an hour before it
}

// waitFor is the latest rapid dose of the last hour not marked a correction,
// unless the food came first (then nothing is waited for).
func waitFor(ts []store.Treatment, now time.Time, sgv *int, in15 *int, ratio float64) *waitState {
	var dose *store.Treatment
	for i := range ts {
		t := &ts[i]
		if t.Insulin == nil || *t.Insulin <= 0 || now.UnixMilli()-t.CreatedAt > waitWithin.Milliseconds() || t.CreatedAt > now.UnixMilli() {
			continue
		}
		if typ, _ := insulin.Kind(t.Raw); typ == "basal" {
			continue
		}
		if _, role := insulinFor(t.Raw); role == "corr" {
			continue
		}
		if dose == nil || t.CreatedAt > dose.CreatedAt {
			dose = t
		}
	}
	if dose == nil {
		return nil
	}
	w := &waitState{DoseID: dose.ID, DoseAt: dose.CreatedAt, Units: *dose.Insulin, Covers: *dose.Insulin * ratio, SGV: sgv}
	for _, t := range ts {
		if t.Carbs == nil || *t.Carbs <= 0 || t.CreatedAt < dose.CreatedAt-30*time.Minute.Milliseconds() {
			continue
		}
		if t.CreatedAt < dose.CreatedAt {
			return nil // eaten before the dose: no wait
		}
		w.Food += *t.Carbs
	}
	wait := waitInRange
	switch {
	case sgv == nil:
		w.Reason = "unseen"
	case *sgv < waitLowBelow:
		w.Reason, wait = "low", 0
	case in15 != nil && *in15 < waitLowBelow:
		w.Reason, wait = "falling", 0
	case *sgv > waitHighFrom:
		w.Reason, wait = "high", waitHigh
	default:
		w.Reason = "range"
	}
	w.EatFrom = dose.CreatedAt + wait.Milliseconds()
	if wait == 0 {
		w.EatFrom = now.UnixMilli()
	}
	return w
}

func (h *Handlers) wait(ctx context.Context, st glucose.State, fresh bool, f *glucose.Forecast, ratio float64, now time.Time) *waitState {
	from, to := now.Add(-waitWithin-30*time.Minute).UnixMilli(), now.UnixMilli()
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: 100})
	if err != nil {
		slog.Error("wait", "err", err)
		return nil
	}
	var sgv, in15 *int
	if fresh {
		v := st.Last.SGV
		sgv = &v
		if f != nil {
			for _, p := range f.Predicted {
				if p.T-st.Last.Date >= 15*time.Minute.Milliseconds() {
					v := p.SGV
					in15 = &v
					break
				}
			}
		}
	}
	return waitFor(ts, now, sgv, in15, ratio)
}
