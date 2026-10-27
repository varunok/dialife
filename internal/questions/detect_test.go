package questions

import (
	"testing"
	"time"
)

var now = time.Date(2026, 10, 26, 13, 0, 0, 0, time.UTC)

// readings every 5 minutes ending now, from these values
func series(vs ...int) []Point {
	ps := make([]Point, len(vs))
	for i, v := range vs {
		ps[i] = Point{T: now.Add(-time.Duration(len(vs)-1-i) * 5 * time.Minute).UnixMilli(), V: v}
	}
	return ps
}

func TestRiseWithoutFood(t *testing.T) {
	sgv := series(120, 118, 119, 125, 136, 148, 160, 171)
	s, ok := Detect(sgv, nil, nil, now)
	if !ok || s.Kind != "rise" || s.SGVFrom != 118 || s.SGVTo != 171 {
		t.Fatalf("%+v %v", s, ok)
	}
	food := []Entry{{T: sgv[1].T - 20*60000, Carbs: true}}
	if _, ok := Detect(sgv, food, nil, now); ok {
		t.Fatal("food 20 min before the rise explains it")
	}
}

func TestFallWithoutInsulin(t *testing.T) {
	sgv := series(220, 222, 218, 208, 196, 184, 172, 162)
	s, ok := Detect(sgv, nil, nil, now)
	if !ok || s.Kind != "fall" || s.SGVFrom != 222 {
		t.Fatalf("%+v %v", s, ok)
	}
	for _, e := range []Entry{{T: sgv[0].T - 2*3600000, Rapid: true}, {T: sgv[0].T, Exercise: true}} {
		if _, ok := Detect(sgv, []Entry{e}, nil, now); ok {
			t.Fatalf("%+v explains the fall", e)
		}
	}
}

func TestNoQuestion(t *testing.T) {
	cases := map[string][]Point{
		"steady":      series(150, 152, 149, 151, 150, 153, 150, 151),
		"slow rise":   series(120, 124, 128, 132, 136, 140, 144, 148), // under 1.5/min
		"compression": series(180, 181, 179, 150, 110, 80, 60, 55),    // 6/min down: pressed sensor
		"too few":     series(120, 160, 200),
	}
	for name, sgv := range cases {
		if s, ok := Detect(sgv, nil, nil, now); ok {
			t.Errorf("%s: %+v", name, s)
		}
	}
	if _, ok := Detect(series(120, 118, 119, 125, 136, 148, 160, 171), nil, []int64{now.Add(-3 * time.Hour).UnixMilli()}, now); ok {
		t.Error("new sensor warming up")
	}
}

// the dinner of 26.10: +73 in two hours, never 1.5/min; asked by the slow rule
func TestSlowRiseWithoutFood(t *testing.T) {
	sgv := series(141, 137, 132, 132, 134, 135, 137, 138, 140, 140, 144, 146, 152, 157, 162, 169, 174)
	s, ok := Detect(sgv, nil, nil, now)
	if !ok || s.Kind != "rise" || s.SGVFrom != 132 || s.SGVTo != 174 {
		t.Fatalf("%+v %v", s, ok)
	}
}
