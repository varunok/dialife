package app

import (
	"net/http"
	"strconv"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/insulin"
	"dialife/internal/stats"
	"dialife/internal/store"
)

const maxStatsDays = 90

// Stats serves GET /api/stats?days=1|7|14|30 (up to 90): time in range,
// mean, GMI, CV and the AGP day for the period ending now.
func (h *Handlers) Stats(w http.ResponseWriter, r *http.Request) {
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
	rs := make([]stats.Reading, 0, len(es))
	for _, e := range es {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			rs = append(rs, stats.Reading{Mills: e.Date, SGV: *e.SGV})
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
	doses := make([]stats.Dose, 0, len(ts))
	for _, tr := range ts {
		typ, _ := insulin.Kind(tr.Raw)
		d := stats.Dose{Mills: tr.CreatedAt, Basal: typ == "basal"}
		if tr.Insulin != nil {
			d.Insulin = *tr.Insulin
		}
		if tr.Carbs != nil {
			d.Carbs = *tr.Carbs
		}
		doses = append(doses, d)
	}
	httpx.WriteJSON(w, http.StatusOK, statsResponse{
		Summary: stats.Compute(rs, from, to, h.thresholds(ctx), loc),
		Daily:   stats.Totals(doses, loc),
	})
}

type statsResponse struct {
	stats.Summary
	Daily []stats.DayTotal `json:"daily"` // insulin and carbs per day, newest first
}
