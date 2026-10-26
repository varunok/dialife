package app

import (
	"net/http"
	"slices"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/meter"
	"dialife/internal/store"
)

// all of it: the comparison gets better with every check, old sensors included
const meterRows = 100000

// Meter serves GET /api/meter: sensor vs glucometer over all checks ever made,
// and, with enough of them, the model the app draws "≈ meter" with.
func (h *Handlers) Meter(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Count: meterRows, Asc: true})
	if err != nil {
		fail(w, "meter entries", err)
		return
	}
	var checks []meter.Point
	for _, e := range es {
		if e.MBG != nil && *e.MBG > 0 {
			checks = append(checks, meter.Point{T: e.Date, V: *e.MBG, Src: meter.Source(e.Device)})
		}
	}
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Count: meterRows})
	if err != nil {
		fail(w, "meter treatments", err)
		return
	}
	var starts []int64
	for _, t := range ts {
		if v := bgCheck(t.Raw); v != nil {
			checks = append(checks, meter.Point{T: t.CreatedAt, V: *v, Src: t.EnteredBy})
		}
		if t.EventType == "Sensor Start" || t.EventType == "Sensor Change" {
			starts = append(starts, t.CreatedAt)
		}
	}
	slices.Sort(starts)
	checks = meter.Dedup(checks)

	// the sensor around each check only, not years of readings
	var sgv []meter.Point
	reach := (15 * time.Minute).Milliseconds()
	for _, c := range checks {
		from, to := c.T-reach, c.T+reach
		if n := len(sgv); n > 0 && sgv[n-1].T >= from {
			from = sgv[n-1].T + 1 // overlaps the previous check's window
		}
		rs, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: 20, Asc: true})
		if err != nil {
			fail(w, "meter sgv", err)
			return
		}
		for _, e := range rs {
			if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
				sgv = append(sgv, meter.Point{T: e.Date, V: *e.SGV})
			}
		}
	}
	httpx.WriteJSON(w, http.StatusOK, meter.Fit(meter.Pairs(checks, sgv, starts), starts, h.Now()))
}
