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
	cases := []struct {
		name    string
		ts      []store.Treatment
		sgv     *int
		in15    *int
		reason  string
		eatFrom int64
	}{
		{"in range", []store.Treatment{rapid}, i(142), i(140), "range", at(5)},
		{"high", []store.Treatment{rapid}, i(214), i(220), "high", at(10)},
		{"low", []store.Treatment{rapid}, i(78), i(70), "low", at(0)},
		{"falling", []store.Treatment{rapid}, i(95), i(76), "falling", at(0)},
		{"no reading", []store.Treatment{rapid}, nil, nil, "unseen", at(5)},
	}
	for _, c := range cases {
		w := waitFor(c.ts, now, c.sgv, c.in15, defaultDose)
		if w == nil || w.Reason != c.reason || w.EatFrom != c.eatFrom || w.Covers != 60 {
			t.Errorf("%s: %+v", c.name, w)
		}
	}
	none := map[string][]store.Treatment{
		"a correction":     {{CreatedAt: at(-10), Insulin: f(1), Raw: []byte(`{"insulinRole":"corr"}`)}},
		"basal":            {{CreatedAt: at(-10), Insulin: f(18), Raw: []byte(`{"insulinType":"basal"}`)}},
		"over an hour ago": {{CreatedAt: at(-61), Insulin: f(3), Raw: []byte(`{}`)}},
		"food came first":  {rapid, {CreatedAt: at(-20), Carbs: f(30), Raw: []byte(`{}`)}},
	}
	for name, ts := range none {
		if w := waitFor(ts, now, i(140), nil, defaultDose); w != nil {
			t.Errorf("%s: %+v, want none", name, w)
		}
	}
	w := waitFor([]store.Treatment{rapid, {CreatedAt: at(-2), Carbs: f(20), Raw: []byte(`{}`)}}, now, i(140), nil, defaultDose)
	if w == nil || w.Food != 20 {
		t.Errorf("food logged after the dose counts: %+v", w)
	}

	own := defaultDose
	own.WaitMin, own.WaitHighMin, own.WaitHighFrom, own.WaitLowBelow = 10, 30, 200, 70
	if w := waitFor([]store.Treatment{rapid}, now, i(190), nil, own); w == nil || w.Reason != "range" || w.EatFrom != at(0) {
		t.Errorf("the family's own minutes and bounds: %+v", w)
	}
	if w := waitFor([]store.Treatment{rapid}, now, i(75), nil, own); w == nil || w.Reason != "range" {
		t.Errorf("75 is above their own low bound: %+v", w)
	}
}
