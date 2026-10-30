package stats

import (
	"context"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"dialife/internal/config"
	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

const maxStatsDays = 90

// HTTP is the statistics' part of the API: the AGP report for a period.
type HTTP struct {
	Store      *store.Store
	Now        func() time.Time
	Loc        *time.Location                          // the family's time zone, for the AGP day; nil: UTC
	Thresholds func(context.Context) config.Thresholds // the alarm thresholds, the ranges are counted by
}

// Routes adds GET /api/stats for every role.
func (h HTTP) Routes(mux *http.ServeMux, anyRole func(http.Handler) http.Handler) {
	mux.Handle("GET /api/stats", anyRole(http.HandlerFunc(h.get)))
}

// get serves GET /api/stats?days=1|7|14|30 (up to 90): time in range,
// mean, GMI, CV and the AGP day for the period ending now.
func (h HTTP) get(w http.ResponseWriter, r *http.Request) {
	days := 14
	if v := r.URL.Query().Get("days"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > maxStatsDays {
			httpx.Error(w, http.StatusBadRequest)
			return
		}
		days = n
	}
	ctx := r.Context()
	to := h.Now()
	from := to.Add(-time.Duration(days) * 24 * time.Hour)
	f, t := from.UnixMilli(), to.UnixMilli()
	es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &f, Lte: &t, Count: maxStatsDays * 288 * 2, Asc: true})
	if err != nil {
		fail(w, "stats entries", err)
		return
	}
	rs := make([]Reading, 0, len(es))
	for _, e := range es {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			rs = append(rs, Reading{Mills: e.Date, SGV: *e.SGV})
		}
	}
	loc := h.Loc
	if loc == nil {
		loc = time.UTC
	}
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &f, Lte: &t, Count: maxStatsDays * 50})
	if err != nil {
		fail(w, "stats treatments", err)
		return
	}
	doses := make([]Dose, 0, len(ts))
	for _, tr := range ts {
		typ, _ := insulin.Kind(tr.Raw)
		d := Dose{Mills: tr.CreatedAt, Basal: typ == "basal"}
		if tr.Insulin != nil {
			d.Insulin = *tr.Insulin
		}
		if tr.Carbs != nil {
			d.Carbs = *tr.Carbs
		}
		doses = append(doses, d)
	}
	httpx.WriteJSON(w, http.StatusOK, statsResponse{
		Summary: Compute(rs, from, to, h.Thresholds(ctx), loc),
		Daily:   Totals(doses, loc),
	})
}

type statsResponse struct {
	Summary
	Daily []DayTotal `json:"daily"` // insulin and carbs per day, newest first
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}
