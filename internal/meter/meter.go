// Package meter compares the sensor with glucometer checks: how far apart the
// two usually are, and what the glucometer would most likely show for a
// sensor value. It never changes a reading and never feeds the alarms.
//
// The model: meter ≈ sensor·(1+b) + a, plus a per-sensor offset. Errors are
// treated as relative (they grow with the level, as with any CGM), but in
// mg/dL below 100, where 10% would be a few units only. Every pair of every
// sensor counts, older ones a little less; each sensor's own offset is pulled
// towards the common model until it has a few pairs of its own.
package meter

import (
	"math"
	"slices"
	"strings"
	"time"
)

const (
	// pairs needed before the estimate is shown at all
	MinPairs = 8
	// a meter check pairs with sensor readings no further than this on each side
	pairReach = 10 * time.Minute
	// faster than this the sensor's lag (5–10 min) dominates the gap: kept, not used
	maxRate = 2.0 // mg/dL per minute
	// below this level errors are counted in mg/dL, above it in percent
	relFloor = 100.0
	// older pairs fade: half the weight after this long
	halfLife = 90 * 24 * time.Hour
	// a sensor's own offset counts as much as the common model after this many pairs
	shrinkPairs = 3.0
	// one check often arrives twice (BG check + calibration, or echoed back)
	sameReading = 15 * time.Minute
)

type Point struct {
	T   int64  // unix ms
	V   int    // mg/dL
	Src string // see Source
}

// Source names where a check came from, from xDrip+'s device field:
// "Bluetooth Glucose Meter:\nRoche   00:00:5E:…" is "Roche 00:00:5E:00:53:01"
// (the meter itself sent it), "Manual Entry" is "manual" (typed into xDrip+).
func Source(device string) string {
	d := strings.TrimSpace(device)
	switch {
	case d == "":
		return ""
	case strings.EqualFold(d, "Manual Entry"):
		return "manual"
	case strings.HasPrefix(d, "Bluetooth Glucose Meter:"):
		return strings.Join(strings.Fields(strings.TrimPrefix(d, "Bluetooth Glucose Meter:")), " ")
	}
	return d
}

// Pair is one glucometer check with the sensor value at that moment.
type Pair struct {
	T        int64   `json:"t"`
	Meter    int     `json:"meter"`
	Sensor   float64 `json:"sensor"`        // interpolated between the readings around the check
	Rate     float64 `json:"rate"`          // mg/dL per minute at that moment
	SensorNo int     `json:"sensor_no"`     // 0 before the first known start, then 1, 2, …
	Used     bool    `json:"used"`          // false: glucose was changing too fast to compare
	Src      string  `json:"src,omitempty"` // see Source; for a check entered in DiaLife, who entered it
}

type band struct {
	From, To int     // sensor mg/dL; To 0: open
	Unit     string  // "mgdl" below 100, "pct" above
	Bias     float64 // sensor minus meter, mean
	N        int
}

type Model struct {
	A      float64 `json:"a"`      // mg/dL
	B      float64 `json:"b"`      // relative slope: 0.07 = meter 7% above the sensor
	Offset float64 `json:"offset"` // the current sensor's own shift, relative (times max(s,100))
	SD     float64 `json:"sd"`     // typical spread around the estimate, relative
}

type Result struct {
	Ready   bool      `json:"ready"`
	Need    int       `json:"need"`
	Used    int       `json:"used"`
	Total   int       `json:"total"`
	Sensors int       `json:"sensors"`  // distinct sensors among the used pairs
	BiasPct float64   `json:"bias_pct"` // sensor vs meter, mean percent; negative: the sensor reads lower
	MARD    float64   `json:"mard"`     // mean absolute relative difference, percent
	Current *Current  `json:"current,omitempty"`
	Bands   []BandOut `json:"bands"`
	Model   *Model    `json:"model,omitempty"` // only when ready
	Pairs   []Pair    `json:"pairs"`
	Starts  []int64   `json:"starts"` // sensor starts, oldest first
}

type Current struct {
	Since   int64   `json:"since"`
	Day     int     `json:"day"` // 1 on the start day
	N       int     `json:"n"`
	BiasPct float64 `json:"bias_pct"`
}

type BandOut struct {
	From int     `json:"from"`
	To   int     `json:"to,omitempty"`
	Unit string  `json:"unit"`
	Bias float64 `json:"bias"`
	N    int     `json:"n"`
}

// Dedup drops repeats of one check: the same value (±1) within 15 minutes of
// one already kept. Input in any order; output oldest first.
func Dedup(ms []Point) []Point {
	ms = slices.Clone(ms)
	slices.SortFunc(ms, func(a, b Point) int { return cmp64(a.T, b.T) })
	var out []Point
	for _, m := range ms {
		dup := false
		for i := len(out) - 1; i >= 0 && m.T-out[i].T <= sameReading.Milliseconds(); i-- {
			if abs(m.V-out[i].V) <= 1 {
				dup = true
				break
			}
		}
		if !dup {
			out = append(out, m)
		}
	}
	return out
}

// Pairs matches checks with the sensor (sgv oldest first) and numbers each by
// sensor: 0 before the first known start, then 1, 2, …
func Pairs(meters, sgv []Point, starts []int64) []Pair {
	var out []Pair
	reach := pairReach.Milliseconds()
	for _, m := range meters {
		i, _ := slices.BinarySearchFunc(sgv, m.T, func(p Point, t int64) int { return cmp64(p.T, t) })
		var p Pair
		switch {
		case i < len(sgv) && sgv[i].T == m.T:
			p = Pair{Sensor: float64(sgv[i].V)}
			if i > 0 && m.T-sgv[i-1].T <= reach {
				p.Rate = rate(sgv[i-1], sgv[i])
			} else if i+1 < len(sgv) && sgv[i+1].T-m.T <= reach {
				p.Rate = rate(sgv[i], sgv[i+1])
			}
		case i > 0 && i < len(sgv) && m.T-sgv[i-1].T <= reach && sgv[i].T-m.T <= reach:
			a, b := sgv[i-1], sgv[i]
			f := float64(m.T-a.T) / float64(b.T-a.T)
			p = Pair{Sensor: float64(a.V) + f*float64(b.V-a.V), Rate: rate(a, b)}
		default:
			continue // no sensor around it: a gap, warm-up, a check before the sensor
		}
		p.T, p.Meter, p.Src = m.T, m.V, m.Src
		p.Sensor = math.Round(p.Sensor*10) / 10
		p.Rate = math.Round(p.Rate*100) / 100
		p.SensorNo = sensorNo(starts, m.T)
		p.Used = math.Abs(p.Rate) <= maxRate
		out = append(out, p)
	}
	return out
}

// Fit builds the summary and, with enough pairs, the model.
func Fit(pairs []Pair, starts []int64, now time.Time) Result {
	r := Result{Need: MinPairs, Total: len(pairs), Pairs: pairs, Starts: starts, Bands: []BandOut{}}
	if r.Pairs == nil {
		r.Pairs = []Pair{}
	}
	if r.Starts == nil {
		r.Starts = []int64{}
	}
	var used []Pair
	for _, p := range pairs {
		if p.Used {
			used = append(used, p)
		}
	}
	r.Used = len(used)
	cur := sensorNo(starts, now.UnixMilli())
	if cur > 0 {
		since := starts[cur-1]
		r.Current = &Current{Since: since, Day: int(now.UnixMilli()-since)/int(24*time.Hour.Milliseconds()) + 1}
	}
	if len(used) == 0 {
		return r
	}

	sensors := map[int]bool{}
	var bias, mard float64
	var curBias float64
	bands := []band{{From: 0, To: 100, Unit: "mgdl"}, {From: 100, To: 200, Unit: "pct"}, {From: 200, Unit: "pct"}}
	for _, p := range used {
		sensors[p.SensorNo] = true
		rel := (p.Sensor - float64(p.Meter)) / float64(p.Meter)
		bias += rel
		mard += math.Abs(rel)
		if cur > 0 && p.SensorNo == cur {
			r.Current.N++
			curBias += rel
		}
		for k := range bands {
			b := &bands[k]
			if p.Sensor >= float64(b.From) && (b.To == 0 || p.Sensor < float64(b.To)) {
				b.N++
				if b.Unit == "mgdl" {
					b.Bias += p.Sensor - float64(p.Meter)
				} else {
					b.Bias += rel * 100
				}
			}
		}
	}
	n := float64(len(used))
	r.Sensors = len(sensors)
	r.BiasPct = round1(bias / n * 100)
	r.MARD = round1(mard / n * 100)
	if r.Current != nil && r.Current.N > 0 {
		r.Current.BiasPct = round1(curBias / float64(r.Current.N) * 100)
	}
	for _, b := range bands {
		if b.N > 0 {
			r.Bands = append(r.Bands, BandOut{From: b.From, To: b.To, Unit: b.Unit, Bias: round1(b.Bias / float64(b.N)), N: b.N})
		}
	}
	if len(used) >= MinPairs {
		m := model(used, cur, now)
		r.Model = &m
		r.Ready = true
	}
	return r
}

// Estimate is what the glucometer would most likely show, and the typical spread.
func (m Model) Estimate(sensor float64) (v, spread float64) {
	sc := math.Max(sensor, relFloor)
	return sensor*(1+m.B) + m.A + m.Offset*sc, m.SD * sc
}

func model(ps []Pair, cur int, now time.Time) Model {
	// weighted least squares of (meter − sensor) on sensor, errors relative to max(s, 100)
	var sw, swx, swy, swxx, swxy float64
	for _, p := range ps {
		age := float64(now.UnixMilli()-p.T) / float64(halfLife.Milliseconds())
		sc := math.Max(p.Sensor, relFloor)
		w := math.Pow(0.5, math.Max(0, age)) / (sc * sc)
		x, y := p.Sensor, float64(p.Meter)-p.Sensor
		sw += w
		swx += w * x
		swy += w * y
		swxx += w * x * x
		swxy += w * x * y
	}
	// a slope needs evidence: with few pairs, or all at one level, it stays near 0
	// (as if two more pairs 40 mg/dL either side of the middle said "no slope")
	lambda := 2 * sw / float64(len(ps)) * 40 * 40
	det := sw*(swxx+lambda) - swx*swx
	var a, b float64
	if det > 0 {
		b = (sw*swxy - swx*swy) / det
	}
	b = math.Max(-0.3, math.Min(0.3, b))
	a = (swy - swx*b) / sw

	// each sensor's own shift, pulled towards the common model
	res := make([]float64, len(ps))
	sums, counts := map[int]float64{}, map[int]float64{}
	for i, p := range ps {
		sc := math.Max(p.Sensor, relFloor)
		res[i] = (float64(p.Meter) - (p.Sensor*(1+b) + a)) / sc
		sums[p.SensorNo] += res[i]
		counts[p.SensorNo]++
	}
	offset := func(k int) float64 { return sums[k] / (counts[k] + shrinkPairs) }
	var ss float64
	for i, p := range ps {
		e := res[i] - offset(p.SensorNo)
		ss += e * e
	}
	sd := math.Sqrt(ss / math.Max(1, float64(len(ps)-2)))
	return Model{
		A:      round2(a),
		B:      round4(b),
		Offset: round4(offset(cur)), // 0 for a sensor without pairs yet
		SD:     round4(math.Max(0.04, sd)),
	}
}

func sensorNo(starts []int64, t int64) int {
	n := 0
	for _, s := range starts {
		if s <= t {
			n++
		}
	}
	return n
}

func rate(a, b Point) float64 {
	dt := float64(b.T-a.T) / 60000
	if dt <= 0 {
		return 0
	}
	return float64(b.V-a.V) / dt
}

func cmp64(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }
func round2(v float64) float64 { return math.Round(v*100) / 100 }
func round4(v float64) float64 { return math.Round(v*10000) / 10000 }
