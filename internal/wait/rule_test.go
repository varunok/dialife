package wait

import (
	"testing"
	"time"
)

var family = Settings{Ratio: 20, WaitMin: 15, WaitHighMin: 20, WaitHighFrom: 180, WaitLowBelow: 80}

func TestFor(t *testing.T) {
	now := time.Date(2026, 10, 29, 12, 15, 0, 0, time.UTC)
	at := func(m int) int64 { return now.Add(time.Duration(m) * time.Minute).UnixMilli() }
	i := func(v int) *int { return &v }
	rapid := Entry{ID: "d", At: at(-10), Insulin: 3}
	food := func(m int, g float64) Entry { return Entry{At: at(m), Carbs: g} }
	meal := []Entry{rapid, food(-12, 40)}

	for _, c := range []struct {
		name    string
		r       Reading
		reason  string
		eatFrom int64
	}{
		{"in range", Reading{i(142), i(140)}, "range", at(5)},
		{"high", Reading{i(214), i(220)}, "high", at(10)},
		{"low", Reading{i(78), i(70)}, "low", at(0)},
		{"falling", Reading{i(95), i(76)}, "falling", at(0)},
		{"no reading", Reading{}, "unseen", at(5)},
	} {
		w := For(meal, now, c.r, family)
		if w == nil || w.Reason != c.reason || w.EatFrom != c.eatFrom || w.Covers != 60 {
			t.Errorf("%s: %+v", c.name, w)
		}
	}

	for name, es := range map[string][]Entry{
		"a dose alone":           {rapid},
		"a correction with food": {{At: at(-10), Insulin: 1, Correction: true}, food(-10, 40)},
		"basal with food":        {{At: at(-10), Insulin: 18, Basal: true}, food(-10, 40)},
		"over an hour ago":       {{At: at(-61), Insulin: 3}, food(-61, 40)},
		"food 6 min before":      {rapid, food(-16, 40)},
		"food 6 min after":       {rapid, food(-4, 40)},
	} {
		if w := For(es, now, Reading{SGV: i(140)}, family); w != nil {
			t.Errorf("%s: %+v, want none", name, w)
		}
	}

	// food before or after, the eating time from the dose
	for name, es := range map[string][]Entry{
		"food 5 min before": {rapid, food(-15, 40)},
		"food 5 min after":  {rapid, food(-5, 40)},
		"together":          {rapid, food(-10, 40)},
	} {
		if w := For(es, now, Reading{SGV: i(140)}, family); w == nil || w.DoseID != "d" || w.EatFrom != at(5) || w.Food != 40 {
			t.Errorf("%s: %+v", name, w)
		}
	}
	if w := For([]Entry{rapid, food(-12, 20), food(-35, 15), food(-45, 50)}, now, Reading{SGV: i(140)}, family); w == nil || w.Food != 35 {
		t.Errorf("the warning counts the food of half an hour before the dose on: %+v", w)
	}
	early := Entry{ID: "e", At: at(-50), Insulin: 2}
	if w := For([]Entry{early, food(-50, 30), rapid}, now, Reading{SGV: i(140)}, family); w == nil || w.DoseID != "e" {
		t.Errorf("a later dose with no food keeps the earlier meal: %+v", w)
	}
	if w := For(append([]Entry{early, food(-50, 30)}, meal...), now, Reading{SGV: i(140)}, family); w == nil || w.DoseID != "d" {
		t.Errorf("the latest meal: %+v", w)
	}

	own := Settings{Ratio: 20, WaitMin: 10, WaitHighMin: 30, WaitHighFrom: 200, WaitLowBelow: 70}
	if w := For(meal, now, Reading{SGV: i(190)}, own); w == nil || w.Reason != "range" || w.EatFrom != at(0) {
		t.Errorf("the family's own minutes and bounds: %+v", w)
	}
	if w := For(meal, now, Reading{SGV: i(75)}, own); w == nil || w.Reason != "range" {
		t.Errorf("75 is above their own low bound: %+v", w)
	}
}
