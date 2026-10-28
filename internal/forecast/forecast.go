// Package forecast is the "in 30 min ≈" shown with the reading: the trend of
// the last half hour, damped, and how often such a forecast came true on this
// child's own recent readings. It is not what the alarms use (they keep
// Nightscout's AR2, glucose.AR2).
//
// Tried on two days of real readings, the 30-minute forecast missed by:
// AR2 13.8 mg/dL on average, "no change" 12.8, this trend 12.0. What happens
// next is decided by food and insulin, which no trend sees; hence the
// measured accuracy next to the number.
package forecast

import (
	"math"
	"time"

	"dialife/internal/glucose"
)

const (
	trendSpan = 30 * time.Minute // the slope is fitted over this much
	damp      = 0.4              // the trend fades: a rise rarely keeps its full speed for half an hour
	horizon   = 30 * time.Minute
	step      = 5 * time.Minute
	minPoints = 3
	// a forecast "came true" within this much of what happened (relative above 100 mg/dL)
	hitAbs = 15.0
	hitRel = 0.15
)

type Point struct {
	T int64 // unix ms
	V int   // mg/dL
}

// Quality is how the forecast did on recent readings: Err80 is the error that
// 80% of forecasts stayed within; HitPct the share that came true.
type Quality struct {
	Err80  int `json:"err80"`
	HitPct int `json:"hit_pct"`
	N      int `json:"n"`
}

// Trend forecasts +5…+30 min from the readings up to the newest (oldest first).
// The cone is the measured error, growing with the horizon.
func Trend(rs []Point, q Quality) (glucose.Forecast, bool) {
	if len(rs) == 0 {
		return glucose.Forecast{}, false
	}
	last := rs[len(rs)-1]
	s, ok := slope(rs, last.T)
	if !ok {
		return glucose.Forecast{}, false
	}
	spread := float64(q.Err80)
	if q.N == 0 {
		spread = 25 // nothing measured yet: a cautious band
	}
	var f glucose.Forecast
	for m := step; m <= horizon; m += step {
		min := m.Minutes()
		v := float64(last.V) + s*min*damp
		t := last.T + m.Milliseconds()
		f.Predicted = append(f.Predicted, glucose.Point{T: t, SGV: clamp(v)})
		w := math.Max(5, spread*min/horizon.Minutes())
		f.Cone = append(f.Cone, glucose.ConePoint{T: t, Lo: clamp(v - w), Hi: clamp(v + w)})
	}
	return f, true
}

// Backtest replays the forecast over readings (oldest first): from every
// reading, what it would have said for 30 minutes later, against what came.
func Backtest(rs []Point) Quality {
	return score(rs, func(i int) (int, bool) {
		f, ok := Trend(rs[:i+1], Quality{N: 1})
		if !ok {
			return 0, false
		}
		return f.Predicted[len(f.Predicted)-1].SGV, true
	})
}

// slope is the least-squares rate (mg/dL per minute) over the last trendSpan before to.
func slope(rs []Point, to int64) (float64, bool) {
	var n, sx, sy, sxx, sxy float64
	for i := len(rs) - 1; i >= 0 && to-rs[i].T <= trendSpan.Milliseconds(); i-- {
		x := float64(rs[i].T-to) / 60000
		y := float64(rs[i].V)
		n++
		sx += x
		sy += y
		sxx += x * x
		sxy += x * y
	}
	d := n*sxx - sx*sx
	if n < minPoints || d == 0 {
		return 0, false
	}
	return (n*sxy - sx*sy) / d, true
}

func clamp(v float64) int { return int(math.Round(math.Max(39, math.Min(400, v)))) }

func cmp(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
