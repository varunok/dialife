// Package recheck is "measure again in 15 minutes": when carbs are logged
// while glucose is low, every phone is asked 15 minutes later to check with
// the glucometer, as the ADA (Standards of Care 2026, 6.15), ISPAD (2022,
// hypoglycemia) and NICE (NG18, 1.2.88) recommend after treating a low. A
// check back at 70 or above ends it; one still below 70 says so and asks to
// treat again, by the family's own plan. DiaLife never says how much to give.
// The alarms are untouched. See docs/METHODS.md, "Recheck after a low".
package recheck

import (
	"errors"
	"time"
)

const (
	// EventType is the SSE event: a recheck is due, still low, or over.
	EventType = "recheck"

	// After is when to measure again after the carbs (ADA, ISPAD, NICE).
	After = 15 * time.Minute
	// Below is hypoglycaemia in the guidelines: < 70 mg/dL (3.9 mmol/L).
	Below = 70

	// our own, from our observations:
	// a sensor reading or a glucometer check this recent tells the level at the carbs
	sensorFresh = 10 * time.Minute
	meterFresh  = 15 * time.Minute
	// a check this soon after the carbs is the low itself entered late, not a recheck
	notBefore = 5 * time.Minute
	// carbs logged with a time further back than this are history, not a treatment now
	carbsWithin = 30 * time.Minute
	// nobody measured or closed it: it ends this long after it was due
	Expire = 2 * time.Hour
)

var ErrNotFound = errors.New("no such recheck")

// Low is what made the carbs a treatment: a live low alarm, or a value below 70.
type Low struct {
	Kind string `json:"kind"`         // "alarm" | "sensor" | "meter"
	MG   int    `json:"mg,omitempty"` // the low value, when there was one
}

// Point is a reading or a check: unix ms, mg/dL.
type Point struct {
	T  int64
	MG int
}

// Window is a time a low alarm was live: [From, Until); Until 0 while it lasts.
type Window struct{ From, Until int64 }

// LowAt reports whether glucose was low at t: a low alarm live then, else the
// newest sensor reading of the 10 minutes before (valid ones only), else the
// newest glucometer check of the 15 minutes before, below 70. Points oldest first.
func LowAt(t int64, alarms []Window, sgv, checks []Point) (Low, bool) {
	for _, w := range alarms {
		if w.From <= t && (w.Until == 0 || w.Until > t) {
			return Low{Kind: "alarm"}, true
		}
	}
	if p, ok := newest(sgv, t-sensorFresh.Milliseconds(), t); ok && p.MG < Below {
		return Low{Kind: "sensor", MG: p.MG}, true
	}
	if p, ok := newest(checks, t-meterFresh.Milliseconds(), t); ok && p.MG < Below {
		return Low{Kind: "meter", MG: p.MG}, true
	}
	return Low{}, false
}

// Recheck is what the phones show: the open one, newest carbs first.
type Recheck struct {
	ID      int64   `json:"id"`
	CarbsT  int64   `json:"carbs_t"`
	Carbs   float64 `json:"carbs"`
	Low     Low     `json:"low"`
	DueAt   int64   `json:"due_at"`
	State   string  `json:"state"`              // "waiting" | "due" | "still_low"
	CheckT  int64   `json:"check_t,omitempty"`  // still_low: the check
	CheckMG int     `json:"check_mg,omitempty"` //
}

// LatestCheck is the newest glucometer check that counts as a recheck: at
// least 5 minutes after the carbs (earlier is the low itself, entered late),
// at or before now. Checks oldest first.
func LatestCheck(carbsT, now int64, checks []Point) (Point, bool) {
	return newest(checks, carbsT+notBefore.Milliseconds(), now)
}

func newest(ps []Point, from, to int64) (Point, bool) {
	for i := len(ps) - 1; i >= 0; i-- {
		if ps[i].T <= to && ps[i].T >= from {
			return ps[i], true
		}
	}
	return Point{}, false
}
