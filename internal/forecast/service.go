package forecast

import (
	"context"
	"log/slog"
	"math"
	"sync"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/insulin"
	"dialife/internal/store"
)

const (
	MaxAge       = 15 * time.Minute   // a reading older than this gets no forecast
	qualityEvery = time.Hour          // accuracy is measured once an hour, not on every reading
	qualityOver  = 7 * 24 * time.Hour // over the last week
	blindAfter   = 2 * time.Hour      // food or insulin this recent: the trend cannot see it
	maxRows      = 20000              // 31 days at 5 min is ~9000
)

// Service is the forecasts the phones see: the trend for the chart and what
// the logged food and rapid insulin will still do, each with how it did.
type Service struct {
	Store *store.Store

	quality qualityCache // the trend's
	iobQ    iobQualityCache
}

// OnBoardState is what the logged food and rapid insulin will still do: shown
// next to the trend forecast, never used by the alarms.
type OnBoardState struct {
	Insulin  float64        `json:"insulin"` // units of rapid still to act
	Carbs    float64        `json:"carbs"`   // grams still to absorb
	ISF      float64        `json:"isf"`     // the mg/dL per unit it used
	Forecast []OnBoardPoint `json:"forecast"`
}

type OnBoardPoint struct {
	T int64 `json:"t"`
	V int   `json:"v"`
}

// how its forecast did, measured like the trend's: once an hour over a week
type iobQualityCache struct {
	mu   sync.Mutex
	at   time.Time
	last Quality
}

// Info is the trend forecast's measured accuracy and what it cannot see.
type Info struct {
	Quality
	// the same, scored apart: the quiet hours and the 3 hours after food
	Quiet     Quality   `json:"quiet"`
	AfterFood Quality   `json:"after_food"`
	Ignores   []Ignored `json:"ignores,omitempty"` // food or rapid insulin of the last 2 hours: the trend does not see them
}

// Ignored is food or rapid insulin the trend does not see.
type Ignored struct {
	Kind string `json:"kind"` // "food" | "insulin"
	T    int64  `json:"t"`
}

// quality is measured on the last week once an hour, not on every reading.
type qualityCache struct {
	mu               sync.Mutex
	at               time.Time
	last             Quality
	quiet, afterFood Quality
}

// Chart is the "in 30 min ≈" with its measured accuracy, from the readings of the last half hour.
func (s *Service) Chart(ctx context.Context, st glucose.State, now time.Time) (*glucose.Forecast, *Info) {
	if st.Error || now.UnixMilli()-st.Last.Date > MaxAge.Milliseconds() {
		return nil, nil
	}
	q, quiet, fed := s.trendQuality(ctx, now)
	from := st.Last.Date - 35*time.Minute.Milliseconds()
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &st.Last.Date, Count: 50, Asc: true})
	if err != nil {
		slog.Error("forecast readings", "err", err)
		return nil, nil
	}
	f, ok := Trend(points(es), q)
	if !ok {
		return nil, nil
	}
	info := &Info{Quality: q, Quiet: quiet, AfterFood: fed}
	since := now.Add(-blindAfter).UnixMilli()
	nowMs := now.UnixMilli()
	if ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &since, Lte: &nowMs, Count: 50}); err == nil {
		for _, t := range ts {
			typ, _ := insulin.Kind(t.Raw)
			switch {
			case t.Carbs != nil && *t.Carbs > 0:
				info.Ignores = append(info.Ignores, Ignored{Kind: "food", T: t.CreatedAt})
			case t.Insulin != nil && *t.Insulin > 0 && typ != "basal":
				info.Ignores = append(info.Ignores, Ignored{Kind: "insulin", T: t.CreatedAt})
			}
		}
	}
	return &f, info
}

// trendQuality is how the trend did over the last week, overall and apart
// for the quiet hours and the hours after food.
func (s *Service) trendQuality(ctx context.Context, now time.Time) (all, quiet, afterFood Quality) {
	c := &s.quality
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && now.Sub(c.at) < qualityEvery {
		return c.last, c.quiet, c.afterFood
	}
	from, to := now.Add(-qualityOver).UnixMilli(), now.UnixMilli()
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: maxRows, Asc: true})
	if err != nil {
		slog.Error("forecast quality", "err", err)
		return c.last, c.quiet, c.afterFood
	}
	fedFrom := from - AfterFood.Milliseconds()
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &fedFrom, Lte: &to, Count: maxRows})
	if err != nil {
		slog.Error("forecast quality food", "err", err)
		return c.last, c.quiet, c.afterFood
	}
	var foods []int64
	for _, t := range ts {
		if t.Carbs != nil && *t.Carbs > 0 {
			foods = append(foods, t.CreatedAt)
		}
	}
	rs := points(es)
	c.last, c.at = Backtest(rs), now
	c.quiet, c.afterFood = BacktestSplit(rs, foods)
	return c.last, c.quiet, c.afterFood
}

func points(es []store.Entry) []Point {
	out := make([]Point, 0, len(es))
	for _, e := range es {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			out = append(out, Point{T: e.Date, V: *e.SGV})
		}
	}
	return out
}

// entries: rapid insulin and carbs logged in [from, to], oldest first.
func (s *Service) entries(ctx context.Context, from, to int64) ([]Dose, []Meal, error) {
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: maxRows})
	if err != nil {
		return nil, nil, err
	}
	var ds []Dose
	var ms []Meal
	for i := len(ts) - 1; i >= 0; i-- { // newest first from the store
		t := ts[i]
		if typ, _ := insulin.Kind(t.Raw); t.Insulin != nil && *t.Insulin > 0 && typ != "basal" {
			ds = append(ds, Dose{T: t.CreatedAt, U: *t.Insulin})
		}
		if t.Carbs != nil && *t.Carbs > 0 {
			ms = append(ms, Meal{T: t.CreatedAt, G: *t.Carbs})
		}
	}
	return ds, ms, nil
}

// OnBoard is what is on board now and, with a fresh reading, the forecast from it;
// nil when nothing logged is still acting.
func (s *Service) OnBoard(ctx context.Context, st glucose.State, fresh bool, p Params, now time.Time) *OnBoardState {
	back := max(p.DIA, p.CarbFor+15*time.Minute)
	ds, ms, err := s.entries(ctx, now.Add(-back).UnixMilli(), now.UnixMilli())
	if err != nil {
		slog.Error("iob entries", "err", err)
		return nil
	}
	iob, cob := OnBoard(ds, ms, p, now.UnixMilli())
	if iob < 0.05 && cob < 0.5 {
		return nil
	}
	out := &OnBoardState{Insulin: math.Round(iob*10) / 10, Carbs: math.Round(cob), ISF: math.Round(p.ISF), Forecast: []OnBoardPoint{}}
	if fresh {
		for _, f := range Physiological(st.Last.SGV, ds, ms, p, st.Last.Date, Horizons) {
			out.Forecast = append(out.Forecast, OnBoardPoint(f))
		}
	}
	return out
}

// OnBoardQuality is how the on-board forecast did, measured like the trend's.
func (s *Service) OnBoardQuality(ctx context.Context, p Params, now time.Time) Quality {
	c := &s.iobQ
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && now.Sub(c.at) < qualityEvery {
		return c.last
	}
	from, to := now.Add(-qualityOver).UnixMilli(), now.UnixMilli()
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: maxRows, Asc: true})
	if err != nil {
		slog.Error("iob quality", "err", err)
		return c.last
	}
	ds, ms, err := s.entries(ctx, from-max(p.DIA, p.CarbFor).Milliseconds(), to)
	if err != nil {
		slog.Error("iob quality entries", "err", err)
		return c.last
	}
	c.last, c.at = BacktestPhysiological(points(es), ds, ms, p), now
	return c.last
}
