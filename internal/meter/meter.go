// Package meter compares the sensor with glucometer checks: how far apart the
// two are on the current sensor, and what the glucometer would most likely
// show for a sensor value. It never changes a reading and never feeds the alarms.
//
// Each sensor reads off by its own amount, and a calibration changes it, so
// everything here is about one stretch: the current sensor since its start,
// or since its last calibration. The estimate is the stretch's own offset,
// newer checks weighing more; older sensors only tell how much one sensor's
// offset wanders (the band around it). See docs/METHODS.md, "≈ meter".
package meter

import (
	"math"
	"slices"
	"strings"
	"time"
)

const (
	// pairs in the current stretch before the estimate is shown at all
	MinPairs = 2
	// a meter check pairs with sensor readings no further than this on each side
	pairReach = 10 * time.Minute
	// faster than this the sensor's lag (5–10 min) dominates the gap: kept, not used
	maxRate = 2.0 // mg/dL per minute
	// below this level errors are counted in mg/dL, above it in percent
	relFloor = 100.0
	// within a stretch the offset drifts: a check a day older weighs half
	dayWeight = 24 * time.Hour
	// the band's width, relative: never narrower than this; this wide until measured
	minSpread = 0.04
	noSpread  = 0.10
	// one check often arrives twice (BG check + calibration, or echoed back)
	sameReading = 15 * time.Minute
)

// IsCalibration reports an xDrip+ meter entry that is a calibration sent to the
// sensor, not a check of its own (the check it repeats arrives apart).
func IsCalibration(device string) bool {
	return strings.EqualFold(strings.TrimSpace(device), "Add Calibration")
}

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
	// current sensor only: before its last calibration, so not in the model
	BeforeCal bool `json:"before_cal,omitempty"`
}

type band struct {
	From, To int     // sensor mg/dL; To 0: open
	Unit     string  // "mgdl" below 100, "pct" above
	Bias     float64 // sensor minus meter, mean
	N        int
}

// Model is meter ≈ sensor + Offset·max(sensor, 100). A and B stay 0: kept so
// older apps read the same formula. Since: the stretch it holds for.
type Model struct {
	A      float64 `json:"a"`      // mg/dL; 0
	B      float64 `json:"b"`      // relative slope; 0
	Offset float64 `json:"offset"` // the stretch's own shift, relative (times max(s,100))
	SD     float64 `json:"sd"`     // typical spread around the estimate, relative
	Since  int64   `json:"since"`  // the stretch's start: the sensor's start or its last calibration
}

type Result struct {
	Ready   bool      `json:"ready"`
	Need    int       `json:"need"`
	Used    int       `json:"used"`
	Total   int       `json:"total"`
	Sensors int       `json:"sensors"`  // 1 with pairs: only the current sensor counts
	BiasPct float64   `json:"bias_pct"` // sensor vs meter, mean percent; negative: the sensor reads lower
	MARD    float64   `json:"mard"`     // mean absolute relative difference, percent
	Current *Current  `json:"current,omitempty"`
	Bands   []BandOut `json:"bands"`
	Model   *Model    `json:"model,omitempty"` // only when ready
	Pairs   []Pair    `json:"pairs"`           // the current sensor's, before its last calibration marked
	Starts  []int64   `json:"starts"`          // sensor starts, oldest first
	// the current sensor's calibrations, oldest first
	Calibrations []int64 `json:"calibrations"`
	Lag          Lag     `json:"lag"` // how far the sensor runs behind; the pairs use Lag.Used
}

type Current struct {
	Since        int64   `json:"since"`
	Day          int     `json:"day"` // 1 on the start day
	N            int     `json:"n"`   // used pairs since CalibratedAt, or since the start
	BiasPct      float64 `json:"bias_pct"`
	CalibratedAt int64   `json:"calibrated_at,omitempty"` // its last calibration, if any
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

// Pairs matches checks with the sensor (sgv oldest first) lag later, when it
// shows the blood the check measured, and numbers each by sensor: 0 before the
// first known start, then 1, 2, …
func Pairs(meters, sgv []Point, starts []int64, lag time.Duration) []Pair {
	var out []Pair
	reach := pairReach.Milliseconds()
	for _, m := range meters {
		at := m.T + lag.Milliseconds()
		i, _ := slices.BinarySearchFunc(sgv, at, func(p Point, t int64) int { return cmp64(p.T, t) })
		var p Pair
		switch {
		case i < len(sgv) && sgv[i].T == at:
			p = Pair{Sensor: float64(sgv[i].V)}
			if i > 0 && at-sgv[i-1].T <= reach {
				p.Rate = rate(sgv[i-1], sgv[i])
			} else if i+1 < len(sgv) && sgv[i+1].T-at <= reach {
				p.Rate = rate(sgv[i], sgv[i+1])
			}
		case i > 0 && i < len(sgv) && at-sgv[i-1].T <= reach && sgv[i].T-at <= reach:
			a, b := sgv[i-1], sgv[i]
			f := float64(at-a.T) / float64(b.T-a.T)
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

// Fit builds the summary of the current stretch and, with enough pairs in
// it, the model. starts and cals are sensor starts and calibrations, oldest
// first; pairs are every sensor's, so older stretches give the band's width.
func Fit(pairs []Pair, starts, cals []int64, now time.Time) Result {
	r := Result{Need: MinPairs, Pairs: []Pair{}, Starts: starts, Calibrations: []int64{}, Bands: []BandOut{}}
	if r.Starts == nil {
		r.Starts = []int64{}
	}
	bounds := slices.Sorted(slices.Values(append(slices.Clone(starts), cals...)))
	cur := sensorNo(starts, now.UnixMilli())
	from := int64(0) // the current sensor's start
	if cur > 0 {
		from = starts[cur-1]
		r.Current = &Current{Since: from, Day: int(now.UnixMilli()-from)/int(24*time.Hour.Milliseconds()) + 1}
	}
	seg := stretchOf(bounds, now.UnixMilli())
	segFrom := from
	for _, c := range cals {
		if c >= from && c <= now.UnixMilli() {
			r.Calibrations = append(r.Calibrations, c)
			segFrom = max(segFrom, c)
		}
	}
	if r.Current != nil && segFrom > from {
		r.Current.CalibratedAt = segFrom
	}

	var used []Pair // this stretch's
	byStretch := map[int][]Pair{}
	for _, p := range pairs {
		st := stretchOf(bounds, p.T)
		if p.Used {
			byStretch[st] = append(byStretch[st], p)
		}
		if p.SensorNo != cur {
			continue
		}
		p.BeforeCal = st != seg
		r.Pairs = append(r.Pairs, p)
		if st == seg {
			r.Total++
			if p.Used {
				used = append(used, p)
			}
		}
	}
	r.Used = len(used)
	if r.Current != nil {
		r.Current.N = len(used)
	}
	if len(used) == 0 {
		return r
	}
	r.Sensors = 1
	var bias, mard float64
	bands := []band{{From: 0, To: 100, Unit: "mgdl"}, {From: 100, To: 200, Unit: "pct"}, {From: 200, Unit: "pct"}}
	for _, p := range used {
		rel := (p.Sensor - float64(p.Meter)) / float64(p.Meter)
		bias += rel
		mard += math.Abs(rel)
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
	r.BiasPct = round1(bias / n * 100)
	r.MARD = round1(mard / n * 100)
	if r.Current != nil {
		r.Current.BiasPct = r.BiasPct
	}
	for _, b := range bands {
		if b.N > 0 {
			r.Bands = append(r.Bands, BandOut{From: b.From, To: b.To, Unit: b.Unit, Bias: round1(b.Bias / float64(b.N)), N: b.N})
		}
	}
	if len(used) >= MinPairs {
		m := Model{Offset: round4(offset(used, now)), SD: round4(spread(byStretch)), Since: segFrom}
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

// residual is how far the meter is from the sensor: relative to the sensor,
// in mg/dL below 100 (10% of a low value would be a few units only).
func residual(p Pair) float64 {
	return (float64(p.Meter) - p.Sensor) / math.Max(p.Sensor, relFloor)
}

// offset is the stretch's weighted mean residual: a check a day older counts half.
func offset(ps []Pair, now time.Time) float64 {
	var sw, s float64
	for _, p := range ps {
		age := float64(now.UnixMilli()-p.T) / float64(dayWeight.Milliseconds())
		w := math.Pow(0.5, math.Max(0, age))
		sw += w
		s += w * residual(p)
	}
	return s / sw
}

// spread is how far checks fall from their own stretch's mean, over every
// stretch with two or more: what one sensor's offset does from check to check.
func spread(byStretch map[int][]Pair) float64 {
	var ss float64
	var n, k int
	for _, ps := range byStretch {
		if len(ps) < 2 {
			continue
		}
		var mean float64
		for _, p := range ps {
			mean += residual(p)
		}
		mean /= float64(len(ps))
		for _, p := range ps {
			e := residual(p) - mean
			ss += e * e
		}
		n += len(ps)
		k++
	}
	if n-k < 2 {
		return noSpread
	}
	return math.Max(minSpread, math.Sqrt(ss/float64(n-k)))
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
func round4(v float64) float64 { return math.Round(v*10000) / 10000 }
