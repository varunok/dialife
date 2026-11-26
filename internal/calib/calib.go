// Package calib says when the sensor should be calibrated, and with what:
// only ever a glucometer check just taken, never a number worked out here.
//
// When: three checks in a row outside Dexcom's 20/20 agreement (20% from
// 70 mg/dL, 20 mg/dL below), all on one side, hours apart (Dexcom's advice
// for its CGM), on the current sensor since its start
// or its last calibration. In a sensor's first 12 hours only when it is far
// off (40 mg/dL or 40%), or reads LOW while the glucometer shows 70 or more.
//
// With what: a check of 90–150 mg/dL at most 5 minutes old, glucose steady
// (Dexcom: not while it changes fast; the 90–120 first point of the xDrip+
// docs, widened by the family), and the family's absorption time after food
// and rapid insulin. See docs/METHODS.md, "Calibration advice".
package calib

import (
	"math"
	"time"
)

const (
	// Dexcom's 20/20: within 20% of the meter at 70 mg/dL or more, within 20 mg/dL below
	agreeFrom = 70
	agreePct  = 0.20
	agreeMg   = 20.0
	// in a row, all on one side
	InARow = 3

	// our own, from our observations:
	// checks this close are one: a second check confirms the moment, not the trend
	sameCheck = time.Hour
	// a new sensor settles: no advice for its first hours unless it is far off
	settle      = 12 * time.Hour
	strongPct   = 0.40
	strongMg    = 40.0
	lowMeterMin = 70 // the sensor says LOW while the meter shows at least this

	// calibrate now: the family's range, a fresh check, glucose steady
	NowFrom  = 90
	NowTo    = 150
	Fresh    = 5 * time.Minute
	steadyIn = 15 * time.Minute
	steadyMg = 10
	// a check is compared with the sensor this far around (after the delay)
	reach = 10 * time.Minute
	// faster than this the sensor's delay dominates (ADA 2026, section 7)
	maxRate = 2.0 // mg/dL per minute
)

// Point is a reading or a check: unix ms, mg/dL.
type Point struct {
	T  int64 `json:"t"`
	MG int   `json:"mg"`
}

// Group is one or more checks within an hour, against the sensor.
type Group struct {
	T      int64   `json:"t"`      // the newest check's time
	Meter  float64 `json:"meter"`  // mean, mg/dL
	Sensor float64 `json:"sensor"` // mean, at the checks plus the delay; 0 when LOW
	Low    bool    `json:"low,omitempty"`
}

// Diff is sensor minus meter, mg/dL.
func (g Group) Diff() float64 { return g.Sensor - g.Meter }

// out: outside Dexcom's 20/20, and which side (-1 the sensor reads lower).
func (g Group) out() (bool, int) {
	if g.Low {
		return g.Meter >= lowMeterMin, -1
	}
	limit := agreeMg
	if g.Meter >= agreeFrom {
		limit = agreePct * g.Meter
	}
	side := 1
	if g.Diff() < 0 {
		side = -1
	}
	return math.Abs(g.Diff()) > limit, side
}

// strength: how far off against 40 mg/dL (below 70) or 40% (from 70); 1 or
// more is far off. LOW at 70 or more is far off.
func (g Group) strength() float64 {
	if g.Low {
		if g.Meter >= lowMeterMin {
			return 1
		}
		return 0
	}
	if g.Meter >= agreeFrom {
		return math.Abs(g.Diff()) / (strongPct * g.Meter)
	}
	return math.Abs(g.Diff()) / strongMg
}

// Input is everything the rule looks at, oldest first.
type Input struct {
	Now         int64
	SensorStart int64   // 0: unknown
	Since       int64   // the stretch: the sensor's start or its last calibration
	Checks      []Point // glucometer checks, no calibrations, none after a shower
	SGV         []Point // valid sensor readings
	Lows        []int64 // LOW readings
	Lag         time.Duration
	Carbs       []int64 // when food was logged
	Rapid       []int64 // when rapid insulin was given
	AbsorbFor   time.Duration
	Untrusted   bool // a shower: the sensor is not believed now
	Sensorless  bool
}

// Advice is what the phones show; nil from Decide: no advice.
type Advice struct {
	Side   string  `json:"side"`  // "low": the sensor reads lower than the glucometer; "high"
	Since  int64   `json:"since"` // the stretch it is about
	Groups []Group `json:"groups"`
	// sensor minus meter over the groups: mg/dL and percent of the meter
	DiffMg  float64 `json:"diff_mg"`
	DiffPct float64 `json:"diff_pct"`
	LowRead bool    `json:"low_read,omitempty"` // the sensor said LOW at a check of 70 or more
	// calibrate with this check now; nil: wait, for the reasons in Wait
	Now  *NowCheck `json:"now,omitempty"`
	Wait []string  `json:"wait,omitempty"` // "no_check" | "old" | "range" | "moving" | "no_trend" | "food" | "insulin" | "shower"
	// the newest check, for the reasons
	Check *Point `json:"check,omitempty"`
}

// NowCheck is the check to enter in xDrip+ before Until.
type NowCheck struct {
	MG    int   `json:"mg"`
	T     int64 `json:"t"`
	Until int64 `json:"until"`
}

// Decide is the advice for the stretch, or nil.
func Decide(in Input) *Advice {
	if in.Sensorless {
		return nil
	}
	gs := Groups(in)
	if len(gs) == 0 {
		return nil
	}
	newest := gs[len(gs)-1]
	var take []Group
	switch out, _ := newest.out(); {
	case newest.Low && out:
		take = gs[len(gs)-1:] // LOW at 70 or more: at once
	case len(gs) >= InARow:
		last := gs[len(gs)-InARow:]
		_, side := last[0].out()
		for _, g := range last {
			o, sd := g.out()
			if !o || sd != side {
				return nil
			}
		}
		take = last
	default:
		return nil
	}
	settling := in.SensorStart > 0 && in.Now < in.SensorStart+settle.Milliseconds()
	if settling { // only far off on average: 40 mg/dL or 40%, or LOW
		var sum float64
		for _, g := range take {
			sum += g.strength()
		}
		if sum/float64(len(take)) < 1 {
			return nil
		}
	}
	a := &Advice{Side: "low", Since: in.Since, Groups: take, LowRead: newest.Low}
	if _, side := take[0].out(); side > 0 {
		a.Side = "high"
	}
	var dm, dp float64
	n := 0.0
	for _, g := range take {
		if g.Low {
			continue
		}
		dm += g.Diff()
		dp += g.Diff() / g.Meter * 100
		n++
	}
	if n > 0 {
		a.DiffMg, a.DiffPct = math.Round(dm/n), math.Round(dp/n)
	}
	a.Now, a.Wait, a.Check = calibrateNow(in)
	return a
}

// calibrateNow: the newest check, if it may be entered in xDrip+ now; else why not.
func calibrateNow(in Input) (*NowCheck, []string, *Point) {
	var c *Point
	for i := len(in.Checks) - 1; i >= 0; i-- {
		if in.Checks[i].T <= in.Now && in.Checks[i].T >= in.Since {
			c = &in.Checks[i]
			break
		}
	}
	if c == nil {
		return nil, []string{"no_check"}, nil
	}
	var why []string
	if in.Now-c.T > Fresh.Milliseconds() {
		why = append(why, "old")
	}
	if c.MG < NowFrom || c.MG > NowTo {
		why = append(why, "range")
	}
	switch n, ok := steady(in.SGV, c.T); {
	case n < 2:
		why = append(why, "no_trend") // LOW or a gap: nothing shows whether it is steady
	case !ok:
		why = append(why, "moving")
	}
	if within(in.Carbs, c.T, in.AbsorbFor) {
		why = append(why, "food")
	}
	if within(in.Rapid, c.T, in.AbsorbFor) {
		why = append(why, "insulin")
	}
	if in.Untrusted {
		why = append(why, "shower")
	}
	if len(why) > 0 {
		return nil, why, c
	}
	return &NowCheck{MG: c.MG, T: c.T, Until: c.T + Fresh.Milliseconds()}, nil, c
}

// steady: the sensor moved at most 10 mg/dL in the 15 minutes up to t; n is
// how many valid readings that rests on (fewer than 2: it cannot tell).
func steady(sgv []Point, t int64) (n int, ok bool) {
	lo, hi := math.MaxInt, math.MinInt
	for _, p := range sgv {
		if p.T >= t-steadyIn.Milliseconds() && p.T <= t {
			lo, hi, n = min(lo, p.MG), max(hi, p.MG), n+1
		}
	}
	return n, n >= 2 && hi-lo <= steadyMg
}

// within: something in ts during the window before t.
func within(ts []int64, t int64, window time.Duration) bool {
	for _, x := range ts {
		if x <= t && x > t-window.Milliseconds() {
			return true
		}
	}
	return false
}

// Groups are the stretch's checks against the sensor the delay later, checks
// within an hour of a group's first joined; a check with no sensor around
// it, or with glucose moving fast, is left out.
func Groups(in Input) []Group {
	lag := in.Lag.Milliseconds()
	var out []Group
	var first int64
	var sumM, sumS, n float64
	var low bool
	flush := func(t int64) {
		if n > 0 {
			g := Group{T: t, Meter: math.Round(sumM / n), Low: low}
			if !low {
				g.Sensor = math.Round(sumS / n)
			}
			out = append(out, g)
		}
		sumM, sumS, n, low = 0, 0, 0, false
	}
	var lastT int64
	for _, c := range in.Checks {
		if c.T < in.Since || c.T > in.Now {
			continue
		}
		at := c.T + lag
		isLow := lowNear(in.Lows, at)
		s, rate, ok := sensorAt(in.SGV, at)
		if !ok && !isLow {
			// the reading the delay later is not here yet: the newest one stands in,
			// so a check just taken counts while it can still be entered in xDrip+
			s, rate, ok = newestFor(in.SGV, c.T, at)
		}
		if !isLow && (!ok || math.Abs(rate) > maxRate) {
			continue
		}
		if n > 0 && c.T-first > sameCheck.Milliseconds() {
			flush(lastT)
		}
		if n == 0 {
			first = c.T
		}
		sumM += float64(c.MG)
		if isLow {
			low = true
		} else {
			sumS += s
		}
		n++
		lastT = c.T
	}
	flush(lastT)
	return out
}

// newestFor: the newest reading, when nothing after it has come yet and it
// is at most 5 minutes older than the check at t; the rate before it.
func newestFor(sgv []Point, t, at int64) (v, rate float64, ok bool) {
	n := len(sgv)
	if n == 0 || sgv[n-1].T >= at || sgv[n-1].T < t-Fresh.Milliseconds() {
		return 0, 0, false
	}
	last := sgv[n-1]
	if n > 1 && last.T-sgv[n-2].T <= reach.Milliseconds() && last.T > sgv[n-2].T {
		rate = float64(last.MG-sgv[n-2].MG) / (float64(last.T-sgv[n-2].T) / 60000)
	}
	return float64(last.MG), rate, true
}

// lowNear: the sensor said LOW within 5 minutes of t.
func lowNear(lows []int64, t int64) bool {
	for _, l := range lows {
		if l >= t-5*60_000 && l <= t+5*60_000 {
			return true
		}
	}
	return false
}

// sensorAt is the sensor at t between the readings around it, and the rate there.
func sensorAt(sgv []Point, t int64) (v, rate float64, ok bool) {
	for i := 1; i < len(sgv); i++ {
		a, b := sgv[i-1], sgv[i]
		if a.T <= t && t <= b.T {
			if t-a.T > reach.Milliseconds() || b.T-t > reach.Milliseconds() || b.T == a.T {
				return 0, 0, false
			}
			f := float64(t-a.T) / float64(b.T-a.T)
			rate = float64(b.MG-a.MG) / (float64(b.T-a.T) / 60000)
			return float64(a.MG) + f*float64(b.MG-a.MG), rate, true
		}
	}
	return 0, 0, false
}
