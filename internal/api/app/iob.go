package app

import (
	"context"
	"log/slog"
	"math"
	"sync"
	"time"

	"dialife/internal/forecast"
	"dialife/internal/glucose"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

// iobState is what the logged food and rapid insulin will still do: shown
// next to the trend forecast, never used by the alarms.
type iobState struct {
	Insulin  float64    `json:"insulin"` // units of rapid still to act
	Carbs    float64    `json:"carbs"`   // grams still to absorb
	ISF      float64    `json:"isf"`     // the mg/dL per unit it used
	Forecast []iobPoint `json:"forecast"`
}

type iobPoint struct {
	T int64 `json:"t"`
	V int   `json:"v"`
}

// how its forecast did, measured like the trend's: once an hour over a week
type iobQualityCache struct {
	mu   sync.Mutex
	at   time.Time
	last forecast.Quality
}

func (d doseSettings) params() forecast.Params {
	return forecast.Params{
		ISF: d.isf(), Ratio: d.Ratio,
		DIA: time.Duration(d.DIAMin) * time.Minute, CarbFor: time.Duration(d.CarbMin) * time.Minute,
	}
}

// entries: rapid insulin and carbs logged in [from, to], oldest first.
func (h *Handlers) entries(ctx context.Context, from, to int64) ([]forecast.Dose, []forecast.Meal, error) {
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: maxHistoryRows})
	if err != nil {
		return nil, nil, err
	}
	var ds []forecast.Dose
	var ms []forecast.Meal
	for i := len(ts) - 1; i >= 0; i-- { // newest first from the store
		t := ts[i]
		if typ, _ := insulin.Kind(t.Raw); t.Insulin != nil && *t.Insulin > 0 && typ != "basal" {
			ds = append(ds, forecast.Dose{T: t.CreatedAt, U: *t.Insulin})
		}
		if t.Carbs != nil && *t.Carbs > 0 {
			ms = append(ms, forecast.Meal{T: t.CreatedAt, G: *t.Carbs})
		}
	}
	return ds, ms, nil
}

// iob is on board now and, with a fresh reading, the forecast from it;
// nil when nothing logged is still acting.
func (h *Handlers) iob(ctx context.Context, st glucose.State, fresh bool, d doseSettings, now time.Time) *iobState {
	p := d.params()
	back := max(p.DIA, p.CarbFor+15*time.Minute)
	ds, ms, err := h.entries(ctx, now.Add(-back).UnixMilli(), now.UnixMilli())
	if err != nil {
		slog.Error("iob entries", "err", err)
		return nil
	}
	iob, cob := forecast.OnBoard(ds, ms, p, now.UnixMilli())
	if iob < 0.05 && cob < 0.5 {
		return nil
	}
	out := &iobState{Insulin: math.Round(iob*10) / 10, Carbs: math.Round(cob), ISF: math.Round(p.ISF), Forecast: []iobPoint{}}
	if fresh {
		for _, f := range forecast.Physiological(st.Last.SGV, ds, ms, p, st.Last.Date, forecast.Horizons) {
			out.Forecast = append(out.Forecast, iobPoint{T: f.T, V: f.V})
		}
	}
	return out
}

func (h *Handlers) iobQuality(ctx context.Context, d doseSettings, now time.Time) forecast.Quality {
	c := &h.iobQ
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && now.Sub(c.at) < qualityEvery {
		return c.last
	}
	from, to := now.Add(-qualityOver).UnixMilli(), now.UnixMilli()
	es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: maxHistoryRows, Asc: true})
	if err != nil {
		slog.Error("iob quality", "err", err)
		return c.last
	}
	p := d.params()
	ds, ms, err := h.entries(ctx, from-max(p.DIA, p.CarbFor).Milliseconds(), to)
	if err != nil {
		slog.Error("iob quality entries", "err", err)
		return c.last
	}
	c.last, c.at = forecast.BacktestPhysiological(points(es), ds, ms, p), now
	return c.last
}
