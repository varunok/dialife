package sensorless

import (
	"testing"

	"dialife/internal/store"
)

const min = 60 * 1000

func TestRemindAt(t *testing.T) {
	p := Period{From: 1000 * min}
	if got := RemindAt(p, nil, 180); got != 1180*min {
		t.Fatalf("no check: %d", got)
	}
	if got := RemindAt(p, &Check{T: 900 * min}, 180); got != 1180*min {
		t.Fatalf("a check before the mode counts from the mode: %d", got)
	}
	if got := RemindAt(p, &Check{T: 1100 * min}, 120); got != 1220*min {
		t.Fatalf("from the newest check: %d", got)
	}
	if got := RemindAt(p, nil, 0); got != 0 {
		t.Fatalf("off: %d", got)
	}
}

func TestCardFor(t *testing.T) {
	p := Period{From: 100}
	for _, c := range []struct {
		check *Check
		want  string
	}{
		{nil, ""},
		{&Check{T: 50, MG: 50}, ""}, // before the mode
		{&Check{T: 150, MG: 79}, "low"},
		{&Check{T: 150, MG: 80}, ""},
		{&Check{T: 150, MG: 200}, ""},
		{&Check{T: 150, MG: 201}, "high"},
	} {
		got := CardFor(c.check, p, 80, 200)
		if (got == nil && c.want != "") || (got != nil && got.Kind != c.want) {
			t.Errorf("%+v: %+v, want %q", c.check, got, c.want)
		}
	}
}

func TestSensorBack(t *testing.T) {
	e := func(at int64, v int) store.Entry { return store.Entry{Date: at * min, SGV: &v} }
	from := int64(100 * min)
	for _, c := range []struct {
		name string
		es   []store.Entry
		want bool
	}{
		{"none", nil, false},
		{"one", []store.Entry{e(105, 120)}, false},
		{"two close", []store.Entry{e(105, 120), e(110, 122)}, true},
		{"two far apart", []store.Entry{e(105, 120), e(125, 122)}, false},
		{"one before the mode", []store.Entry{e(95, 120), e(105, 122)}, false},
		{"an error code between", []store.Entry{e(105, 120), e(110, 5)}, false},
	} {
		if got := SensorBack(c.es, from, 39); got != c.want {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
		}
	}
}

func TestSettingsValid(t *testing.T) {
	for v, ok := range map[int]bool{0: true, 60: true, 90: true, 480: true, 30: false, 45: false, 510: false, -30: false} {
		if (Settings{RemindMin: v}.Valid() == nil) != ok {
			t.Errorf("%d min: valid %v", v, !ok)
		}
	}
}
