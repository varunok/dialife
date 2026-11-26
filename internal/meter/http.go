package meter

import (
	"log/slog"
	"net/http"
	"slices"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/store"
	"dialife/internal/trust"
)

// all of it: the comparison gets better with every check, old sensors included
const meterRows = 100000

// HTTP is the glucometer comparison's part of the API.
type HTTP struct {
	Store *store.Store
	Now   func() time.Time
}

// Routes adds GET /api/meter for every role.
func (h HTTP) Routes(mux *http.ServeMux, anyRole func(http.Handler) http.Handler) {
	mux.Handle("GET /api/meter", anyRole(http.HandlerFunc(h.get)))
}

// get serves GET /api/meter: sensor vs glucometer over all checks ever made,
// and, with enough of them, the model the app draws "≈ meter" with.
func (h HTTP) get(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Count: meterRows, Asc: true})
	if err != nil {
		fail(w, "meter entries", err)
		return
	}
	var checks []Point
	var bounds []int64 // where a stretch of one sensor's behaviour begins: starts and calibrations
	for _, e := range es {
		if IsCalibration(e.Device) {
			bounds = append(bounds, e.Date)
			continue
		}
		if e.MBG != nil && *e.MBG > 0 {
			checks = append(checks, Point{T: e.Date, V: *e.MBG, Src: Source(e.Device)})
		}
	}
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Count: meterRows})
	if err != nil {
		fail(w, "meter treatments", err)
		return
	}
	var starts []int64
	var untrusted []trust.Window // a check after a shower says nothing about the sensor
	for _, t := range ts {
		if w, ok := trust.Of(t); ok {
			untrusted = append(untrusted, w)
		}
		if v := CheckOf(t.Raw); v != nil {
			checks = append(checks, Point{T: t.CreatedAt, V: *v, Src: t.EnteredBy})
		}
		if t.EventType == "Sensor Start" || t.EventType == "Sensor Change" {
			starts = append(starts, t.CreatedAt)
		}
	}
	slices.Sort(starts)
	bounds = append(bounds, starts...)
	checks = trust.Drop(Dedup(checks), func(p Point) int64 { return p.T }, untrusted)

	// the sensor around each check only, not years of readings: up to the
	// longest delay tried, and a reading beyond it to interpolate from
	var sgv []Point
	reach := (15 * time.Minute).Milliseconds()
	for _, c := range checks {
		from, to := c.T-reach, c.T+(maxLag*time.Minute).Milliseconds()+reach
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
				sgv = append(sgv, Point{T: e.Date, V: *e.SGV})
			}
		}
	}
	lag := MeasureLag(checks, sgv, bounds)
	res := Fit(Pairs(checks, sgv, starts, lag.Duration()), starts, h.Now())
	res.Lag = lag
	httpx.WriteJSON(w, http.StatusOK, res)
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}
