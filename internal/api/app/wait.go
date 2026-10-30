package app

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"dialife/internal/auth"
	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

// How long after a rapid dose to start eating: a suggestion on home, never a
// rule and never recorded. Rapid analogues given 15–20 min before a meal give
// a lower peak after it; a higher sugar wants a little longer, a low or
// falling one wants the food now. No study ties the wait to the size of the
// dose or of the meal, so neither changes it: they only feed the "more insulin
// than food" warning, a card of its own. The minutes and bounds are the
// family's, in the dose settings (15 / 20 min, 80 / 180 mg/dL by default).
//
// Both cards open for a rapid dose, not a correction, with any food entry
// within pairGap of it, before or after. Each goes with its own button, on
// every phone, or an hour after the dose.
const (
	waitWithin = time.Hour       // a dose further back has no wait
	pairGap    = 5 * time.Minute // food this close to the dose is its meal
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
	// "already ate" / the warning closed, on any phone
	Closed     bool `json:"closed"`
	MoreClosed bool `json:"more_closed"`
}

// waitFor is the latest rapid dose of the last hour not marked a correction
// with food within pairGap of it; nil when there is none.
func waitFor(ts []store.Treatment, now time.Time, sgv *int, in15 *int, d doseSettings) *waitState {
	gap := pairGap.Milliseconds()
	withFood := func(at int64) bool {
		for _, t := range ts {
			if t.Carbs != nil && *t.Carbs > 0 && t.CreatedAt >= at-gap && t.CreatedAt <= at+gap {
				return true
			}
		}
		return false
	}
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
		if (dose == nil || t.CreatedAt > dose.CreatedAt) && withFood(t.CreatedAt) {
			dose = t
		}
	}
	if dose == nil {
		return nil
	}
	w := &waitState{DoseID: dose.ID, DoseAt: dose.CreatedAt, Units: *dose.Insulin, Covers: *dose.Insulin * d.Ratio, SGV: sgv}
	for _, t := range ts {
		if t.Carbs != nil && *t.Carbs > 0 && t.CreatedAt >= dose.CreatedAt-30*time.Minute.Milliseconds() {
			w.Food += *t.Carbs
		}
	}
	wait := time.Duration(d.WaitMin) * time.Minute
	switch {
	case sgv == nil:
		w.Reason = "unseen"
	case *sgv < d.WaitLowBelow:
		w.Reason, wait = "low", 0
	case in15 != nil && *in15 < d.WaitLowBelow:
		w.Reason, wait = "falling", 0
	case *sgv > d.WaitHighFrom:
		w.Reason, wait = "high", time.Duration(d.WaitHighMin)*time.Minute
	default:
		w.Reason = "range"
	}
	w.EatFrom = dose.CreatedAt + wait.Milliseconds()
	if w.Reason == "low" || w.Reason == "falling" {
		w.EatFrom = now.UnixMilli()
	}
	return w
}

func (h *Handlers) wait(ctx context.Context, st glucose.State, fresh bool, f *glucose.Forecast, d doseSettings, now time.Time) *waitState {
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
	w := waitFor(ts, now, sgv, in15, d)
	if w == nil {
		return nil
	}
	if w.Closed, w.MoreClosed, err = h.Store.WaitClosed(ctx, w.DoseID); err != nil {
		slog.Error("wait closed", "err", err)
	}
	if w.Closed && w.MoreClosed {
		return nil
	}
	return w
}

type waitCloseRequest struct {
	Kind string `json:"kind"` // "wait": already ate; "more": the warning
}

// CloseWait serves POST /api/wait/{dose_id}/close: gone on every phone.
// Closing it twice (two phones at once) is fine.
func (h *Handlers) CloseWait(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var req waitCloseRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	id := strings.TrimSpace(r.PathValue("dose_id"))
	if id == "" || len(id) > 64 || (req.Kind != "wait" && req.Kind != "more") {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(ctx)
	closed, err := h.Store.CloseWait(ctx, id, req.Kind, &dev.ID, h.Now().UnixMilli())
	if err != nil {
		fail(w, "wait close", err)
		return
	}
	if closed {
		slog.Info("wait closed", "dose", id, "kind", req.Kind, "by", dev.Person)
		h.PublishState(ctx)
	}
	w.WriteHeader(http.StatusNoContent)
}
