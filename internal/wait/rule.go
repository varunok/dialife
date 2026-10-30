// Package wait is "when to eat" after rapid insulin: a suggestion on home,
// never a rule and never recorded. Rapid analogues given 15–20 min before a
// meal give a lower peak after it; a higher sugar wants a little longer, a low
// or falling one wants the food now. No study ties the wait to the size of the
// dose or of the meal, so neither changes it: they only feed the "more insulin
// than food" warning, a card of its own. The minutes and bounds are the
// family's, in the dose settings (15 / 20 min, 80 / 180 mg/dL by default).
//
// Both cards open for a rapid dose, not a correction, with any food entry
// within PairGap of it, before or after. Each goes with its own button, on
// every phone, or an hour after the dose.
package wait

import (
	"time"

	"dialife/internal/glucose"
)

const (
	Within  = time.Hour        // a dose further back has no wait
	PairGap = 5 * time.Minute  // food this close to the dose is its meal
	foodFor = 30 * time.Minute // the warning counts food from this long before the dose
)

// Settings are the family's, from the dose settings.
type Settings struct {
	Ratio        float64 // grams of carbohydrate one unit covers
	WaitMin      int     // sugar in range, or no fresh reading
	WaitHighMin  int     // sugar above WaitHighFrom
	WaitHighFrom int     // mg/dL
	WaitLowBelow int     // mg/dL: below it, now or in 15 min, eat at once
}

// Entry is a treatment as the rule sees it.
type Entry struct {
	ID         string
	At         int64 // unix ms
	Insulin    float64
	Carbs      float64
	Basal      bool
	Correction bool
}

// Reading is the sugar the wait goes by; nil fields: not known.
type Reading struct {
	SGV  *int // the newest fresh reading
	In15 *int // the forecast 15 min after it
}

// State is what the phones get.
type State struct {
	DoseID  string  `json:"dose_id"`
	DoseAt  int64   `json:"dose_at"`
	Units   float64 `json:"units"`
	EatFrom int64   `json:"eat_from"` // unix ms; at or before now: eat now
	// "range" | "high": waiting by the sugar; "low" | "falling": eat now;
	// "unseen": no fresh reading, the usual wait
	Reason string  `json:"reason"`
	SGV    *int    `json:"sgv,omitempty"`
	Covers float64 `json:"covers_g"` // carbs the dose covers at the meal ratio
	Food   float64 `json:"food_g"`   // carbs logged since half an hour before it
	// "already ate" / the warning closed, on any phone
	Closed     bool `json:"closed"`
	MoreClosed bool `json:"more_closed"`
}

// For is the wait for the latest rapid dose of the last hour, not a
// correction, with food within PairGap of it; nil when there is none.
func For(es []Entry, now time.Time, r Reading, s Settings) *State {
	dose := latestMeal(es, now)
	if dose == nil {
		return nil
	}
	w := &State{DoseID: dose.ID, DoseAt: dose.At, Units: dose.Insulin, Covers: dose.Insulin * s.Ratio, SGV: r.SGV}
	for _, e := range es {
		if e.Carbs > 0 && e.At >= dose.At-foodFor.Milliseconds() {
			w.Food += e.Carbs
		}
	}
	wait := time.Duration(s.WaitMin) * time.Minute
	switch {
	case r.SGV == nil:
		w.Reason = "unseen"
	case *r.SGV < s.WaitLowBelow:
		w.Reason, wait = "low", 0
	case r.In15 != nil && *r.In15 < s.WaitLowBelow:
		w.Reason, wait = "falling", 0
	case *r.SGV > s.WaitHighFrom:
		w.Reason, wait = "high", time.Duration(s.WaitHighMin)*time.Minute
	default:
		w.Reason = "range"
	}
	w.EatFrom = dose.At + wait.Milliseconds()
	if w.Reason == "low" || w.Reason == "falling" {
		w.EatFrom = now.UnixMilli()
	}
	return w
}

// latestMeal is the latest rapid dose of the last hour, not a correction, with food beside it.
func latestMeal(es []Entry, now time.Time) *Entry {
	gap := PairGap.Milliseconds()
	withFood := func(at int64) bool {
		for _, e := range es {
			if e.Carbs > 0 && e.At >= at-gap && e.At <= at+gap {
				return true
			}
		}
		return false
	}
	var dose *Entry
	for i := range es {
		e := &es[i]
		if e.Insulin <= 0 || e.Basal || e.Correction || e.At > now.UnixMilli() || now.UnixMilli()-e.At > Within.Milliseconds() {
			continue
		}
		if (dose == nil || e.At > dose.At) && withFood(e.At) {
			dose = e
		}
	}
	return dose
}

// ReadingFrom is the sugar to go by: the newest reading while fresh and the
// forecast 15 min after it.
func ReadingFrom(st glucose.State, fresh bool, f *glucose.Forecast) Reading {
	if !fresh {
		return Reading{}
	}
	v := st.Last.SGV
	r := Reading{SGV: &v}
	if f != nil {
		for _, p := range f.Predicted {
			if p.T-st.Last.Date >= 15*time.Minute.Milliseconds() {
				v := p.SGV
				r.In15 = &v
				break
			}
		}
	}
	return r
}
