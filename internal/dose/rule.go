// Package dose is the family's dose calculation: one for every phone, kept
// in settings — the meal ratio, rounding, limits, the correction table, the
// on-board forecast's numbers and when to eat after rapid insulin.
package dose

import (
	"cmp"
	"errors"
	"slices"
)

type Settings struct {
	Ratio    float64 `json:"ratio"` // 1 unit per this many grams of carbohydrate
	Rule     string  `json:"rule"`  // "nearest" | "halfDown" | "down" | "up"
	Step     float64 `json:"step"`  // 1 or 0.5 unit
	FreshMin int     `json:"fresh_min"`
	MaxRapid float64 `json:"max_rapid"`
	MaxBasal float64 `json:"max_basal"`
	MaxCarbs int     `json:"max_carbs"`
	Table    []Step  `json:"table"`
	// the physiological forecast: mg/dL one unit lowers (0: from the table),
	// how long rapid acts, how long a meal takes to absorb
	ISF     float64 `json:"isf"`
	DIAMin  int     `json:"dia_min"`
	CarbMin int     `json:"carb_min"`
	// when to eat after a rapid dose (a suggestion): minutes to wait with the
	// sugar in range and above WaitHighFrom; below WaitLowBelow (now or by the
	// trend in 15 min) eat at once
	WaitMin      int    `json:"wait_min"`
	WaitHighMin  int    `json:"wait_high_min"`
	WaitHighFrom int    `json:"wait_high_from"`
	WaitLowBelow int    `json:"wait_low_below"`
	UpdatedAt    int64  `json:"updated_at"`
	UpdatedBy    string `json:"updated_by"`
}

// Step is a correction table row: from this glucometer value (mg/dL) up, add this many units.
type Step struct {
	MG int     `json:"mg"`
	U  float64 `json:"u"`
}

// Defaults are the settings until a parent saves the family's own.
var Defaults = Settings{
	Ratio: 20, Rule: "halfDown", Step: 1, FreshMin: 15, MaxRapid: 10, MaxBasal: 20, MaxCarbs: 150,
	Table:  []Step{{140, 1}, {180, 2}, {220, 3}, {260, 4}, {300, 5}},
	DIAMin: 240, CarbMin: 180,
	WaitMin: 15, WaitHighMin: 20, WaitHighFrom: 180, WaitLowBelow: 80,
}

// WithDefaults keeps the default for a field settings saved before it
// existed, or an older app, did not have.
func (d Settings) WithDefaults() Settings {
	if d.DIAMin == 0 {
		d.DIAMin = Defaults.DIAMin
	}
	if d.CarbMin == 0 {
		d.CarbMin = Defaults.CarbMin
	}
	if d.WaitMin == 0 && d.WaitHighMin == 0 && d.WaitHighFrom == 0 && d.WaitLowBelow == 0 {
		d.WaitMin, d.WaitHighMin, d.WaitHighFrom, d.WaitLowBelow = Defaults.WaitMin, Defaults.WaitHighMin, Defaults.WaitHighFrom, Defaults.WaitLowBelow
	}
	return d
}

// EffectiveISF is the one set, or the table's own: the typical mg/dL between its rows
// per unit added; 40 with no table to read it from.
func (d Settings) EffectiveISF() float64 {
	if d.ISF > 0 {
		return d.ISF
	}
	var steps []float64
	t := slices.Clone(d.Table)
	slices.SortFunc(t, func(a, b Step) int { return a.MG - b.MG })
	for i := 1; i < len(t); i++ {
		if du := t[i].U - t[i-1].U; du > 0 {
			steps = append(steps, float64(t[i].MG-t[i-1].MG)/du)
		}
	}
	if len(steps) == 0 {
		return 40
	}
	slices.Sort(steps)
	return steps[len(steps)/2]
}

// ErrRange means a value is out of what can be meant.
var ErrRange = errors.New("dose settings out of range")

// Valid checks every value is within reason.
func (d Settings) Valid() error {
	if !d.valuesValid() || !d.waitValid() || !d.tableValid() {
		return ErrRange
	}
	return nil
}

// Rules are the ways a dose can be rounded to the pen's step.
var Rules = []string{"nearest", "halfDown", "down", "up"}

func between[T cmp.Ordered](v, lo, hi T) bool { return v >= lo && v <= hi }

func (d Settings) valuesValid() bool {
	return !slices.Contains([]bool{
		between(d.Ratio, 1, 100),
		slices.Contains(Rules, d.Rule),
		d.Step == 1 || d.Step == 0.5,
		between(d.FreshMin, 1, 60),
		between(d.MaxRapid, 0.5, 100),
		between(d.MaxBasal, 0.5, 200),
		between(d.MaxCarbs, 20, 500),
		d.ISF == 0 || between(d.ISF, 5, 400), // 0: from the table
		between(d.DIAMin, 120, 480),
		between(d.CarbMin, 60, 360),
	}, false)
}

// waitValid: the minutes in order (high no shorter), the bounds in order.
func (d Settings) waitValid() bool {
	return between(d.WaitMin, 0, 45) && between(d.WaitHighMin, d.WaitMin, 60) &&
		between(d.WaitLowBelow, 60, 120) && between(d.WaitHighFrom, d.WaitLowBelow+1, 300)
}

// tableValid: at most 20 rows, each a plausible glucometer value and dose, no value twice.
func (d Settings) tableValid() bool {
	if len(d.Table) > 20 {
		return false
	}
	seen := map[int]bool{}
	for _, s := range d.Table {
		if !between(s.MG, 40, 600) || !between(s.U, 0, 50) || seen[s.MG] {
			return false
		}
		seen[s.MG] = true
	}
	return true
}
