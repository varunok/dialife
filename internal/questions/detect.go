// Package questions asks the parents about a rise nobody logged food for, or
// a fall nobody logged insulin for. Every parent answers; the question closes
// only when they all agree, and asks again whenever they do not. Unanswered
// for 12 hours it becomes a "suspect" mark anyone may clear.
package questions

import (
	"math"
	"time"
)

const (
	trendSpan = 20 * time.Minute // the slope is fitted over this much
	lookBack  = 90 * time.Minute // where a rise or fall may have begun
	minRate   = 1.5              // mg/dL per minute, sustained over trendSpan
	// a slow meal (a dinner rising +73 in two hours never reached 1.5/min):
	// a gentler slope held longer, with the same rise from the low point
	slowSpan = 30 * time.Minute
	slowRate = 0.8
	// at night she sleeps: a slow rise is the basal or the dawn, plain on the
	// chart, and asking adds nothing; a fast one still asks (a snack, sweets after a low)
	slowNightFrom = 0 // local hours, [from, to)
	slowNightTo   = 6
	compression   = 4.0              // a steeper fall is the sensor pressed (lying on it), not insulin
	minRise       = 40               // mg/dL from the low point
	minFall       = 50               // mg/dL from the high point
	maxGap        = 15 * time.Minute // missing readings: no conclusion
	carbsBefore   = 90 * time.Minute // food this long before the rise still explains it
	insulinAct    = 4 * time.Hour    // rapid insulin this long before the fall explains it
	exerciseAct   = 3 * time.Hour
	warmUp        = 12 * time.Hour // a new sensor is noisy
)

type Point struct {
	T int64 // unix ms
	V int   // mg/dL
}

// Entry is what was logged, reduced to what explains a rise or a fall.
type Entry struct {
	T        int64
	Carbs    bool // any carbs
	Rapid    bool // rapid-acting insulin
	Exercise bool
	ID       string // the treatment
	MealID   string // rapid: the food entry it was given for, when the app said
	Corr     bool   // rapid marked a correction: not for a meal
}

type Suspect struct {
	Kind     string // "rise" | "fall"
	From, To int64
	SGVFrom  int
	SGVTo    int
}

// Detect looks at the newest readings (sgv oldest first) for a rise or a fall
// that nothing logged explains. now is in the family's time zone: it decides night.
func Detect(sgv []Point, logged []Entry, starts []int64, now time.Time) (Suspect, bool) {
	n := len(sgv)
	if n < 4 {
		return Suspect{}, false
	}
	last := sgv[n-1]
	if now.UnixMilli()-last.T > maxGap.Milliseconds() {
		return Suspect{}, false
	}
	for _, s := range starts {
		if s <= last.T && last.T-s < warmUp.Milliseconds() {
			return Suspect{}, false
		}
	}
	// the window back to lookBack, without holes
	i := n - 1
	for i > 0 && last.T-sgv[i-1].T <= lookBack.Milliseconds() {
		if sgv[i].T-sgv[i-1].T > maxGap.Milliseconds() {
			break
		}
		i--
	}
	win := sgv[i:]
	rate, ok := slope(win, last.T-trendSpan.Milliseconds())
	if !ok {
		return Suspect{}, false
	}
	slow, _ := slope(win, last.T-slowSpan.Milliseconds())
	lo, hi := win[0], win[0]
	for _, p := range win {
		if p.V < lo.V {
			lo = p
		}
		if p.V > hi.V {
			hi = p
		}
	}
	switch {
	case (rate >= minRate || (slow >= slowRate && !slowNight(now))) && last.V-lo.V >= minRise && lo.T < last.T:
		if explained(logged, lo.T-carbsBefore.Milliseconds(), now.UnixMilli(), func(e Entry) bool { return e.Carbs }) {
			return Suspect{}, false
		}
		return Suspect{Kind: "rise", From: lo.T, To: last.T, SGVFrom: lo.V, SGVTo: last.V}, true
	case rate <= -minRate && rate >= -compression && hi.V-last.V >= minFall && hi.T < last.T:
		if explained(logged, hi.T-insulinAct.Milliseconds(), now.UnixMilli(), func(e Entry) bool { return e.Rapid }) ||
			explained(logged, hi.T-exerciseAct.Milliseconds(), now.UnixMilli(), func(e Entry) bool { return e.Exercise }) {
			return Suspect{}, false
		}
		return Suspect{Kind: "fall", From: hi.T, To: last.T, SGVFrom: hi.V, SGVTo: last.V}, true
	}
	return Suspect{}, false
}

// Explains reports whether a logged entry accounts for the question: food for
// a rise, insulin or exercise for a fall, in the same window Detect uses.
func Explains(kind string, from int64, e Entry) bool {
	switch kind {
	case "rise":
		return e.Carbs && e.T >= from-carbsBefore.Milliseconds()
	case "fall":
		return (e.Rapid && e.T >= from-insulinAct.Milliseconds()) || (e.Exercise && e.T >= from-exerciseAct.Milliseconds())
	case "bolus":
		return e.Rapid && !e.Corr && e.T >= from-BolusBefore.Milliseconds() && e.T <= from+BolusAfter.Milliseconds()
	}
	return false
}

// A meal and its bolus: insulin up to half an hour before or an hour after
// counts as given for it (the app groups a meal's insulin by the same window);
// a dose linked to the meal counts wherever it is, one marked a correction
// never does. Asked once the hour has passed.
const (
	BolusBefore = 30 * time.Minute
	BolusAfter  = time.Hour
	bolusLookUp = 3 * time.Hour // meals this recent are checked
	// carbs at a low treat the low: no insulin is right then
	bolusLowBelow = 100
)

// MissingBolus finds the newest meal with carbs but no rapid insulin near it,
// old enough that the insulin would have been logged by now. sgvAt gives the
// sensor at the meal's time (0: unknown).
func MissingBolus(logged []Entry, now time.Time, sgvAt func(int64) int) (Entry, bool) {
	var found Entry
	ok := false
	for _, m := range logged {
		age := now.UnixMilli() - m.T
		if !m.Carbs || age < BolusAfter.Milliseconds() || age > bolusLookUp.Milliseconds() {
			continue
		}
		if explained(logged, m.T-BolusBefore.Milliseconds(), m.T+BolusAfter.Milliseconds(), func(e Entry) bool { return e.Rapid && !e.Corr }) ||
			linked(logged, m) {
			continue
		}
		if v := sgvAt(m.T); v > 0 && v < bolusLowBelow {
			continue // treating a low
		}
		if !ok || m.T > found.T {
			found, ok = m, true
		}
	}
	return found, ok
}

// linked: a rapid dose the app gave for this very meal.
func linked(logged []Entry, meal Entry) bool {
	for _, e := range logged {
		if e.Rapid && meal.ID != "" && e.MealID == meal.ID {
			return true
		}
	}
	return false
}

// MealAt is the food entry logged at t.
func MealAt(logged []Entry, t int64) Entry {
	for _, e := range logged {
		if e.Carbs && e.T == t {
			return e
		}
	}
	return Entry{}
}

func explained(logged []Entry, from, to int64, what func(Entry) bool) bool {
	for _, e := range logged {
		if e.T >= from && e.T <= to && what(e) {
			return true
		}
	}
	return false
}

// slope is the least-squares rate (mg/dL per minute) of the readings since
// from; at least 4 of them, or no answer.
func slope(ps []Point, from int64) (float64, bool) {
	var n, sx, sy, sxx, sxy float64
	for _, p := range ps {
		if p.T < from {
			continue
		}
		x := float64(p.T-from) / 60000
		y := float64(p.V)
		n++
		sx += x
		sy += y
		sxx += x * x
		sxy += x * y
	}
	d := n*sxx - sx*sx
	if n < 4 || d == 0 {
		return 0, false
	}
	r := (n*sxy - sx*sy) / d
	return r, !math.IsNaN(r)
}

func slowNight(now time.Time) bool {
	h := now.Hour()
	return h >= slowNightFrom && h < slowNightTo
}
