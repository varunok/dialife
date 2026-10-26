// Package stats summarises a period of CGM readings the way diabetes clinics
// read them (the international consensus on time in range, Battelino 2019):
// time in five zones, mean, GMI, variability and the AGP percentile day.
package stats

import (
	"math"
	"slices"
	"strings"
	"time"

	"dialife/internal/config"
)

// Reading is one valid sensor value (error codes already removed).
type Reading struct {
	Mills int64
	SGV   int // mg/dL
}

// Zones are percentages of readings; they add up to 100 (± rounding).
type Zones struct {
	VeryLow  float64 `json:"very_low"`  // < urgent low
	Low      float64 `json:"low"`       // urgent low … low-1
	InRange  float64 `json:"in_range"`  // low … high (the family's thresholds)
	High     float64 `json:"high"`      // high+1 … urgent high
	VeryHigh float64 `json:"very_high"` // > urgent high
}

// AGPPoint is one slot of the "typical day": percentiles of all readings in
// that slot across the period.
type AGPPoint struct {
	Minute int `json:"minute"` // slot start, minutes after local midnight
	P5     int `json:"p5"`
	P25    int `json:"p25"`
	P50    int `json:"p50"`
	P75    int `json:"p75"`
	P95    int `json:"p95"`
	N      int `json:"n"`
}

type Summary struct {
	From     int64             `json:"from"`
	To       int64             `json:"to"`
	Days     int               `json:"days"`
	Count    int               `json:"count"`
	First    int64             `json:"first,omitempty"` // oldest reading in the period: data may start later than "from"
	Coverage float64           `json:"coverage"`        // % of the readings a sensor would give (one per 5 min)
	Mean     float64           `json:"mean"`            // mg/dL
	SD       float64           `json:"sd"`
	CV       float64           `json:"cv"`  // %, ≤ 36 counts as stable
	GMI      float64           `json:"gmi"` // %, glucose management indicator (≈ HbA1c from CGM)
	Zones    Zones             `json:"zones"`
	TIR70    float64           `json:"tir_70_180"` // % in the standard 70–180, for the doctor
	Hypos    int               `json:"hypos"`      // episodes below low for ≥ 15 min
	AGP      []AGPPoint        `json:"agp"`
	Range    config.Thresholds `json:"thresholds"`
}

const (
	slot       = 30 * time.Minute // AGP resolution
	hypoMinLen = 15 * time.Minute
	gapBreak   = 15 * time.Minute // longer silence ends an episode
)

// Compute summarises readings (any order) between from and to.
func Compute(rs []Reading, from, to time.Time, th config.Thresholds, loc *time.Location) Summary {
	days := int(math.Round(to.Sub(from).Hours() / 24))
	s := Summary{From: from.UnixMilli(), To: to.UnixMilli(), Days: days, Range: th, AGP: []AGPPoint{}}
	rs = slices.Clone(rs)
	slices.SortFunc(rs, func(a, b Reading) int { return int(a.Mills - b.Mills) })
	s.Count = len(rs)
	if s.Count == 0 {
		return s
	}
	s.First = rs[0].Mills
	expected := to.Sub(from).Minutes() / 5
	s.Coverage = round1(min(100, float64(s.Count)/expected*100))

	var sum, sq float64
	var z [5]int
	var std int
	for _, r := range rs {
		v := float64(r.SGV)
		sum += v
		sq += v * v
		switch {
		case r.SGV < th.UrgentLow:
			z[0]++
		case r.SGV < th.Low:
			z[1]++
		case r.SGV <= th.High:
			z[2]++
		case r.SGV <= th.UrgentHigh:
			z[3]++
		default:
			z[4]++
		}
		if r.SGV >= 70 && r.SGV <= 180 {
			std++
		}
	}
	n := float64(s.Count)
	s.Mean = round1(sum / n)
	if s.Count > 1 {
		s.SD = round1(math.Sqrt(math.Max(0, (sq-sum*sum/n)/(n-1))))
	}
	if s.Mean > 0 {
		s.CV = round1(s.SD / s.Mean * 100)
	}
	s.GMI = round1(3.31 + 0.02392*s.Mean)
	pct := func(k int) float64 { return round1(float64(k) / n * 100) }
	s.Zones = Zones{VeryLow: pct(z[0]), Low: pct(z[1]), InRange: pct(z[2]), High: pct(z[3]), VeryHigh: pct(z[4])}
	s.TIR70 = pct(std)
	s.Hypos = hypos(rs, th.Low)
	s.AGP = agp(rs, loc)
	return s
}

// hypos counts runs below low lasting at least 15 minutes; a long gap in the
// data ends a run (we do not know what happened in between).
func hypos(rs []Reading, low int) int {
	count := 0
	var start, last int64 = -1, 0
	flush := func() {
		if start >= 0 && time.Duration(last-start)*time.Millisecond >= hypoMinLen {
			count++
		}
		start = -1
	}
	for _, r := range rs {
		if start >= 0 && time.Duration(r.Mills-last)*time.Millisecond > gapBreak {
			flush()
		}
		if r.SGV < low {
			if start < 0 {
				start = r.Mills
			}
			last = r.Mills
		} else {
			flush()
		}
	}
	flush()
	return count
}

func agp(rs []Reading, loc *time.Location) []AGPPoint {
	slots := int(24 * time.Hour / slot)
	buckets := make([][]int, slots)
	for _, r := range rs {
		t := time.UnixMilli(r.Mills).In(loc)
		m := t.Hour()*60 + t.Minute()
		i := m / int(slot.Minutes())
		buckets[i] = append(buckets[i], r.SGV)
	}
	out := make([]AGPPoint, 0, slots)
	for i, b := range buckets {
		if len(b) == 0 {
			continue
		}
		slices.Sort(b)
		out = append(out, AGPPoint{
			Minute: i * int(slot.Minutes()),
			P5:     percentile(b, 5), P25: percentile(b, 25), P50: percentile(b, 50),
			P75: percentile(b, 75), P95: percentile(b, 95), N: len(b),
		})
	}
	return out
}

// percentile of sorted values, linear between neighbours.
func percentile(sorted []int, p float64) int {
	if len(sorted) == 1 {
		return sorted[0]
	}
	pos := p / 100 * float64(len(sorted)-1)
	lo := int(math.Floor(pos))
	hi := min(lo+1, len(sorted)-1)
	f := pos - float64(lo)
	return int(math.Round(float64(sorted[lo])*(1-f) + float64(sorted[hi])*f))
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }

// Dose is one treatment for the daily totals.
type Dose struct {
	Mills   int64
	Insulin float64
	Basal   bool // long-acting; otherwise rapid
	Carbs   float64
}

// DayTotal is what the doctor asks when doses are being adjusted.
type DayTotal struct {
	Date  string  `json:"date"` // 2026-10-25, the family's local day
	Basal float64 `json:"basal"`
	Rapid float64 `json:"rapid"`
	Carbs float64 `json:"carbs"`
}

// Totals sums doses per local day, newest day first; days with nothing entered are left out.
func Totals(ds []Dose, loc *time.Location) []DayTotal {
	byDay := map[string]*DayTotal{}
	for _, d := range ds {
		key := time.UnixMilli(d.Mills).In(loc).Format("2006-01-02")
		t := byDay[key]
		if t == nil {
			t = &DayTotal{Date: key}
			byDay[key] = t
		}
		if d.Basal {
			t.Basal += d.Insulin
		} else {
			t.Rapid += d.Insulin
		}
		t.Carbs += d.Carbs
	}
	out := make([]DayTotal, 0, len(byDay))
	for _, t := range byDay {
		t.Basal, t.Rapid, t.Carbs = round1(t.Basal), round1(t.Rapid), round1(t.Carbs)
		out = append(out, *t)
	}
	slices.SortFunc(out, func(a, b DayTotal) int { return strings.Compare(b.Date, a.Date) })
	return out
}
