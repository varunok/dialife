package app

import (
	"testing"
	"time"

	"dialife/internal/store"
)

func TestWaitFor(t *testing.T) {
	now := time.Date(2026, 10, 29, 12, 15, 0, 0, time.UTC)
	at := func(m int) int64 { return now.Add(time.Duration(m) * time.Minute).UnixMilli() }
	f := func(v float64) *float64 { return &v }
	i := func(v int) *int { return &v }
	rapid := store.Treatment{ID: "d", CreatedAt: at(-10), Insulin: f(3), Raw: []byte(`{"insulinType":"rapid"}`)}
	food := func(m int, g float64) store.Treatment {
		return store.Treatment{CreatedAt: at(m), Carbs: f(g), Raw: []byte(`{}`)}
	}
	meal := []store.Treatment{rapid, food(-12, 40)}
	cases := []struct {
		name    string
		ts      []store.Treatment
		sgv     *int
		in15    *int
		reason  string
		eatFrom int64
	}{
		{"in range", meal, i(142), i(140), "range", at(5)},
		{"high", meal, i(214), i(220), "high", at(10)},
		{"low", meal, i(78), i(70), "low", at(0)},
		{"falling", meal, i(95), i(76), "falling", at(0)},
		{"no reading", meal, nil, nil, "unseen", at(5)},
	}
	for _, c := range cases {
		w := waitFor(c.ts, now, c.sgv, c.in15, defaultDose)
		if w == nil || w.Reason != c.reason || w.EatFrom != c.eatFrom || w.Covers != 60 {
			t.Errorf("%s: %+v", c.name, w)
		}
	}
	none := map[string][]store.Treatment{
		"a dose alone":           {rapid},
		"a correction with food": {{CreatedAt: at(-10), Insulin: f(1), Raw: []byte(`{"insulinRole":"corr"}`)}, food(-10, 40)},
		"basal with food":        {{CreatedAt: at(-10), Insulin: f(18), Raw: []byte(`{"insulinType":"basal"}`)}, food(-10, 40)},
		"over an hour ago":       {{CreatedAt: at(-61), Insulin: f(3), Raw: []byte(`{}`)}, food(-61, 40)},
		"food 6 min before":      {rapid, food(-16, 40)},
		"food 6 min after":       {rapid, food(-4, 40)},
	}
	for name, ts := range none {
		if w := waitFor(ts, now, i(140), nil, defaultDose); w != nil {
			t.Errorf("%s: %+v, want none", name, w)
		}
	}
	// food before or after, the eating time from the dose
	for name, ts := range map[string][]store.Treatment{
		"food 5 min before": {rapid, food(-15, 40)},
		"food 5 min after":  {rapid, food(-5, 40)},
		"together":          {rapid, food(-10, 40)},
	} {
		if w := waitFor(ts, now, i(140), nil, defaultDose); w == nil || w.DoseID != "d" || w.EatFrom != at(5) || w.Food != 40 {
			t.Errorf("%s: %+v", name, w)
		}
	}
	if w := waitFor([]store.Treatment{rapid, food(-12, 20), food(-35, 15), food(-45, 50)}, now, i(140), nil, defaultDose); w == nil || w.Food != 35 {
		t.Errorf("the warning counts the food of half an hour before the dose on: %+v", w)
	}
	early := store.Treatment{ID: "e", CreatedAt: at(-50), Insulin: f(2), Raw: []byte(`{}`)}
	if w := waitFor([]store.Treatment{early, food(-50, 30), rapid}, now, i(140), nil, defaultDose); w == nil || w.DoseID != "e" {
		t.Errorf("a later dose with no food keeps the earlier meal: %+v", w)
	}
	if w := waitFor(append([]store.Treatment{early, food(-50, 30)}, meal...), now, i(140), nil, defaultDose); w == nil || w.DoseID != "d" {
		t.Errorf("the latest meal: %+v", w)
	}

	own := defaultDose
	own.WaitMin, own.WaitHighMin, own.WaitHighFrom, own.WaitLowBelow = 10, 30, 200, 70
	if w := waitFor(meal, now, i(190), nil, own); w == nil || w.Reason != "range" || w.EatFrom != at(0) {
		t.Errorf("the family's own minutes and bounds: %+v", w)
	}
	if w := waitFor(meal, now, i(75), nil, own); w == nil || w.Reason != "range" {
		t.Errorf("75 is above their own low bound: %+v", w)
	}
}
