package forecast

import (
	"math"
	"slices"
	"time"
)

// What the logged food and rapid insulin will still do: insulin on board
// with the exponential activity curve of oref0 and Loop, carbs on board
// absorbed evenly, and the sugar they move from the current reading. Unlike
// the trend it sees a dose given or a meal eaten; it is only as good as the
// entries. Nothing here feeds the alarms.

// Dose is rapid insulin given at T.
type Dose struct {
	T int64 // unix ms
	U float64
}

// Meal is carbohydrate eaten at T.
type Meal struct {
	T int64
	G float64
}

type Params struct {
	ISF     float64       // mg/dL one unit of rapid lowers
	Ratio   float64       // grams of carbohydrate one unit covers
	DIA     time.Duration // how long rapid acts
	CarbFor time.Duration // how long a meal takes to absorb
}

const (
	insulinPeak = 75 * time.Minute // rapid-acting analogues (oref0's "rapid-acting" curve)
	carbDelay   = 15 * time.Minute // a meal starts to raise the sugar after this
)

// IOBFraction is the share of a dose still to act, t after it was given.
func IOBFraction(t, dia time.Duration) float64 {
	if t <= 0 {
		return 1
	}
	if t >= dia {
		return 0
	}
	td, tp, x := dia.Minutes(), insulinPeak.Minutes(), t.Minutes()
	tau := tp * (1 - tp/td) / (1 - 2*tp/td)
	a := 2 * tau / td
	s := 1 / (1 - a + (1+a)*math.Exp(-td/tau))
	return 1 - s*(1-a)*((x*x/(tau*td*(1-a))-x/tau-1)*math.Exp(-x/tau)+1)
}

// COBFraction is the share of a meal not absorbed yet, t after it was eaten.
func COBFraction(t, absorb time.Duration) float64 {
	if t <= carbDelay {
		return 1
	}
	return math.Max(0, 1-float64(t-carbDelay)/float64(absorb))
}

// OnBoard is the insulin and carbohydrate still to act at now.
func OnBoard(doses []Dose, meals []Meal, p Params, now int64) (iob, cob float64) {
	for _, d := range doses {
		if d.T <= now {
			iob += d.U * IOBFraction(time.Duration(now-d.T)*time.Millisecond, p.DIA)
		}
	}
	for _, m := range meals {
		if m.T <= now {
			cob += m.G * COBFraction(time.Duration(now-m.T)*time.Millisecond, p.CarbFor)
		}
	}
	return iob, cob
}

// Horizons the forecast gives.
var Horizons = []time.Duration{15 * time.Minute, 30 * time.Minute, 60 * time.Minute}

// Physiological forecasts from a reading v at now: v, plus what the carbs
// still to absorb by then raise, less what the insulin still to act lowers.
func Physiological(v int, doses []Dose, meals []Meal, p Params, now int64, at []time.Duration) []Point {
	if p.ISF <= 0 || p.Ratio <= 0 {
		return nil
	}
	iob0, cob0 := OnBoard(doses, meals, p, now)
	out := make([]Point, 0, len(at))
	for _, h := range at {
		t := now + h.Milliseconds()
		iob, cob := OnBoard(doses, meals, p, t)
		f := float64(v) + (cob0-cob)*p.ISF/p.Ratio - (iob0-iob)*p.ISF
		out = append(out, Point{T: t, V: clamp(f)})
	}
	return out
}

// BacktestPhysiological is Backtest for the physiological forecast: from
// every reading, what it would have said for 30 minutes later knowing only
// the entries made by then, against what came.
func BacktestPhysiological(rs []Point, doses []Dose, meals []Meal, p Params) Quality {
	if p.ISF <= 0 || p.Ratio <= 0 {
		return Quality{}
	}
	return score(rs, func(i int) (int, bool) {
		now := rs[i].T
		known := func(t int64) bool { return t <= now }
		var ds []Dose
		for _, d := range doses {
			if known(d.T) {
				ds = append(ds, d)
			}
		}
		var ms []Meal
		for _, m := range meals {
			if known(m.T) {
				ms = append(ms, m)
			}
		}
		f := Physiological(rs[i].V, ds, ms, p, now, []time.Duration{horizon})
		return f[0].V, true
	})
}

// score: for each reading with one about 30 min later, the forecast made at
// it against what came; how far off, and how often it came true.
func score(rs []Point, predict func(i int) (int, bool)) Quality {
	var errs []float64
	hits := 0
	for i := range rs {
		target := rs[i].T + horizon.Milliseconds()
		j, _ := slices.BinarySearchFunc(rs, target, func(p Point, t int64) int { return cmp(p.T, t) })
		// the reading nearest to +30 min, within 2.5 minutes of it
		best := -1
		for _, k := range []int{j - 1, j} {
			if k > i && k < len(rs) && abs64(rs[k].T-target) <= 150_000 && (best < 0 || abs64(rs[k].T-target) < abs64(rs[best].T-target)) {
				best = k
			}
		}
		if best < 0 {
			continue
		}
		v, ok := predict(i)
		if !ok {
			continue
		}
		actual := float64(rs[best].V)
		e := math.Abs(float64(v) - actual)
		errs = append(errs, e)
		if e <= math.Max(hitAbs, hitRel*actual) {
			hits++
		}
	}
	if len(errs) == 0 {
		return Quality{}
	}
	slices.Sort(errs)
	return Quality{
		Err80:  int(math.Round(errs[int(float64(len(errs))*0.8)])),
		HitPct: int(math.Round(100 * float64(hits) / float64(len(errs)))),
		N:      len(errs),
	}
}
