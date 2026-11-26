package meter

import (
	"math"
	"math/rand/v2"
	"slices"
	"time"
)

// The sensor reads glucose in the fluid under the skin, which follows the
// blood by a few minutes; a glucometer reads the blood. How far behind, is
// measured the way CGM studies do it (docs/METHODS.md, "Sensor delay"): shift
// the sensor by 0, 1, … 20 minutes and keep the shift with the lowest mean
// absolute difference from the glucometer. Our own step: each sensor reads off
// by its own amount, and a calibration changes it, so that offset is taken out
// first: every stretch (a sensor, or a sensor since a calibration) compares
// with its own mean difference. How sure the answer is, is a bootstrap 95%
// percentile interval. Until it is narrow, comparisons use Dexcom's figure.
const (
	// PublishedLag is Dexcom's average delay of its CGM, in minutes.
	PublishedLag = 4
	// LagPrecise: the measured delay is used once its 95% interval is no wider than this, in minutes.
	LagPrecise = 4
	maxLag     = 20 // minutes tried
	bootstraps = 1000
	// below this many checks in stretches of two or more, nothing is measured
	minLagChecks = 10
)

// Lag is how far the sensor runs behind the glucometer.
type Lag struct {
	Min    *int   `json:"min,omitempty"` // the measured delay, minutes; nil: too few checks
	Lo     *int   `json:"lo,omitempty"`  // its 95% interval
	Hi     *int   `json:"hi,omitempty"`
	N      int    `json:"n"`      // checks it was measured on
	Used   int    `json:"used"`   // minutes the comparisons use
	Source string `json:"source"` // "measured" | "published"
}

// Duration is the delay the comparisons use.
func (l Lag) Duration() time.Duration { return time.Duration(l.Used) * time.Minute }

// MeasureLag measures the delay from glucometer checks (deduplicated, oldest
// first), the sensor (oldest first) and where the stretches begin: sensor
// starts and calibrations, unix ms.
func MeasureLag(checks, sgv []Point, bounds []int64) Lag {
	bounds = slices.Sorted(slices.Values(bounds))
	type row struct {
		seg int
		e   [maxLag + 1]float64 // meter − sensor shifted by k min, mg/dL
	}
	var rows []row
	for _, c := range checks {
		r := row{seg: stretchOf(bounds, c.T)}
		ok := true
		for k := 0; k <= maxLag && ok; k++ {
			var s float64
			s, ok = sensorAt(sgv, c.T+int64(k)*time.Minute.Milliseconds())
			r.e[k] = float64(c.V) - s
		}
		if ok {
			rows = append(rows, r)
		}
	}
	// a stretch with one check has no shift of its own to take out
	count := map[int]int{}
	for _, r := range rows {
		count[r.seg]++
	}
	rows = slices.DeleteFunc(rows, func(r row) bool { return count[r.seg] < 2 })

	out := Lag{N: len(rows), Used: PublishedLag, Source: "published"}
	if len(rows) < minLagChecks {
		return out
	}
	best := func(idx []int) int {
		// each stretch's mean per shift, then the mean distance from it
		type acc struct {
			sum [maxLag + 1]float64
			n   float64
		}
		means := map[int]*acc{}
		for _, i := range idx {
			a := means[rows[i].seg]
			if a == nil {
				a = &acc{}
				means[rows[i].seg] = a
			}
			for k := range a.sum {
				a.sum[k] += rows[i].e[k]
			}
			a.n++
		}
		bestK, bestScore := 0, math.Inf(1)
		for k := 0; k <= maxLag; k++ {
			var score float64
			for _, i := range idx {
				a := means[rows[i].seg]
				score += math.Abs(rows[i].e[k] - a.sum[k]/a.n)
			}
			if score < bestScore-1e-12 {
				bestK, bestScore = k, score
			}
		}
		return bestK
	}
	all := make([]int, len(rows))
	for i := range all {
		all[i] = i
	}
	est := best(all)

	// percentile bootstrap: resample the checks, measure again; the same seed, the same answer
	rng := rand.New(rand.NewPCG(1, 2))
	ks := make([]int, bootstraps)
	idx := make([]int, len(rows))
	for b := range ks {
		for i := range idx {
			idx[i] = rng.IntN(len(rows))
		}
		ks[b] = best(idx)
	}
	slices.Sort(ks)
	lo, hi := ks[int(0.025*bootstraps)], ks[int(0.975*bootstraps)-1]
	out.Min, out.Lo, out.Hi = &est, &lo, &hi
	if hi-lo <= LagPrecise {
		out.Used, out.Source = est, "measured"
	}
	return out
}

// stretchOf numbers t by the bounds at or before it: 0 before the first.
func stretchOf(bounds []int64, t int64) int {
	n, _ := slices.BinarySearch(bounds, t+1)
	return n
}

// sensorAt is the sensor at t, between the readings around it (no further than pairReach each side).
func sensorAt(sgv []Point, t int64) (float64, bool) {
	i, found := slices.BinarySearchFunc(sgv, t, func(p Point, t int64) int { return cmp64(p.T, t) })
	if found {
		return float64(sgv[i].V), true
	}
	reach := pairReach.Milliseconds()
	if i == 0 || i == len(sgv) || t-sgv[i-1].T > reach || sgv[i].T-t > reach {
		return 0, false
	}
	a, b := sgv[i-1], sgv[i]
	f := float64(t-a.T) / float64(b.T-a.T)
	return float64(a.V) + f*float64(b.V-a.V), true
}
