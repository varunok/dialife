package glucose

import (
	"math"
	"slices"
	"testing"
)

const T = int64(1_792_886_400_000)

func min5(n int64) int64 { return n * bucketMs }

func TestComputeSteadyFiveMinutes(t *testing.T) {
	st, ok := Compute([]Reading{
		{Date: T - min5(2), SGV: 140},
		{Date: T, SGV: 120, Direction: "FortyFiveDown"},
		{Date: T - min5(1), SGV: 130},
	}, T)
	if !ok {
		t.Fatal("no state")
	}
	if st.Last.SGV != 120 || st.Mean != 120 || st.Mills != T || st.Error {
		t.Fatalf("state %+v", st)
	}
	d := st.Delta
	if d == nil || d.MgDL != -10 || d.Exact != -10 || d.Interpolated || d.Mean5MinsAgo != 130 {
		t.Fatalf("delta %+v", d)
	}
	if st.Direction != "FortyFiveDown" || st.Arrow != "↘" {
		t.Fatalf("direction %q %q", st.Direction, st.Arrow)
	}
}

func TestComputeInterpolatesGap(t *testing.T) {
	// one reading 15 minutes ago, nothing in between: delta scaled back to 5 minutes
	st, _ := Compute([]Reading{{Date: T, SGV: 120}, {Date: T - min5(3), SGV: 150}}, T)
	d := st.Delta
	if d == nil || !d.Interpolated || d.ElapsedMins != 15 || d.Absolute != -30 || d.Mean5MinsAgo != 130 || d.MgDL != -10 {
		t.Fatalf("delta %+v", d)
	}
}

func TestComputeAveragesBucket(t *testing.T) {
	// xDrip+ backfill can put two readings in one bucket
	st, _ := Compute([]Reading{{Date: T, SGV: 120}, {Date: T - minuteMs, SGV: 124}, {Date: T - min5(1), SGV: 130}}, T)
	if st.Mean != 122 || st.Buckets[0].Valid != 2 || st.Buckets[0].Last != 120 {
		t.Fatalf("bucket %+v mean %v", st.Buckets[0], st.Mean)
	}
	if st.Delta.MgDL != -8 {
		t.Fatalf("delta %+v", st.Delta)
	}
}

func TestComputeSensorErrorCode(t *testing.T) {
	st, _ := Compute([]Reading{{Date: T, SGV: 10}, {Date: T - min5(1), SGV: 120}, {Date: T - min5(2), SGV: 125}}, T)
	if !st.Error || st.Last.SGV != 10 {
		t.Fatalf("newest error not flagged: %+v", st)
	}
	// the error bucket is skipped; mean and delta come from the valid ones
	if st.Mean != 120 || st.Mills != T-min5(1) || st.Delta == nil || st.Delta.MgDL != -5 {
		t.Fatalf("state %+v delta %+v", st, st.Delta)
	}
}

func TestComputeIgnoresFutureAndEmpty(t *testing.T) {
	if _, ok := Compute(nil, T); ok {
		t.Fatal("state from no readings")
	}
	if _, ok := Compute([]Reading{{Date: T + minuteMs, SGV: 100}}, T); ok {
		t.Fatal("state from a future reading")
	}
	st, _ := Compute([]Reading{{Date: T + min5(2), SGV: 300}, {Date: T, SGV: 100}}, T)
	if st.Last.SGV != 100 {
		t.Fatalf("future reading used: %+v", st.Last)
	}
	if st.Delta != nil {
		t.Fatalf("delta from a single reading: %+v", st.Delta)
	}
}

func TestDirectionNormalizeAndFallback(t *testing.T) {
	for in, want := range map[string]string{
		"slightUp": "FortyFiveUp", "SLIDE-DOWN": "FortyFiveDown", "up": "SingleUp", "Flat": "Flat",
		"NOT COMPUTABLE": "NOT COMPUTABLE", "not_computable": "NOT COMPUTABLE", "weird": "weird", "": "",
	} {
		if got := NormalizeDirection(in); got != want {
			t.Errorf("NormalizeDirection(%q) = %q, want %q", in, got, want)
		}
	}

	// uploader sent no direction: derived from +12 mg/dL per 5 min = 2.4/min
	st, _ := Compute([]Reading{{Date: T, SGV: 132}, {Date: T - min5(1), SGV: 120}}, T)
	if st.Direction != "SingleUp" || st.Arrow != "↑" {
		t.Fatalf("fallback direction %q %q", st.Direction, st.Arrow)
	}
	cases := map[float64]string{20: "DoubleUp", 12: "SingleUp", 7: "FortyFiveUp", 5: "Flat", -5: "Flat",
		-7: "FortyFiveDown", -12: "SingleDown", -20: "DoubleDown"}
	for per5, want := range cases {
		if got := directionFromDelta(per5); got != want {
			t.Errorf("directionFromDelta(%v) = %s, want %s", per5, got, want)
		}
	}
}

// Golden values come from an independent Python port of Nightscout's ar2.js.
func TestAR2MatchesNightscout(t *testing.T) {
	cases := []struct {
		mean, m5      float64
		pred          []int
		cone1, cone13 ConePoint
		loss          float64
		level         Level
		event         string
	}{
		{120, 130, []int{113, 109, 106, 104, 103, 103}, ConePoint{Lo: 109, Hi: 118}, ConePoint{Lo: 70, Hi: 157}, 0.003599, LevelNone, ""},
		{70, 80, []int{64, 60, 58, 57, 56, 56}, ConePoint{Lo: 61, Hi: 66}, ConePoint{Lo: 42, Hi: 93}, 0.117698, LevelUrgent, "low"},
		{250, 230, []int{264, 274, 280, 283, 284, 283}, ConePoint{Lo: 254, Hi: 275}, ConePoint{Lo: 174, Hi: 388}, 0.159776, LevelUrgent, "high"},
		{190, 193.07, []int{187, 185, 183, 181, 180, 178}, ConePoint{Lo: 180, Hi: 195}, ConePoint{Lo: 114, Hi: 255}, 0.039648, LevelNone, ""},
	}
	for _, c := range cases {
		f, ok := AR2(c.mean, c.m5, T, 70, 180, DefaultConeFactor)
		if !ok {
			t.Fatalf("%v/%v: no forecast", c.mean, c.m5)
		}
		got := make([]int, len(f.Predicted))
		for i, p := range f.Predicted {
			got[i] = p.SGV
			if p.T != T+int64(i+1)*bucketMs {
				t.Errorf("%v/%v: point %d at %d", c.mean, c.m5, i, p.T)
			}
		}
		if !slices.Equal(got, c.pred) {
			t.Errorf("%v/%v: predicted %v, want %v", c.mean, c.m5, got, c.pred)
		}
		if len(f.Cone) != 13 {
			t.Fatalf("cone has %d points", len(f.Cone))
		}
		if first, last := f.Cone[0], f.Cone[12]; first.Lo != c.cone1.Lo || first.Hi != c.cone1.Hi || last.Lo != c.cone13.Lo || last.Hi != c.cone13.Hi {
			t.Errorf("%v/%v: cone %+v .. %+v", c.mean, c.m5, first, last)
		}
		if math.Abs(f.AvgLoss-c.loss) > 1e-6 {
			t.Errorf("%v/%v: avgLoss %.6f, want %.6f", c.mean, c.m5, f.AvgLoss, c.loss)
		}
		if f.Level != c.level || f.Event != c.event {
			t.Errorf("%v/%v: level %q event %q, want %q %q", c.mean, c.m5, f.Level, f.Event, c.level, c.event)
		}
	}
}

func TestAR2Refuses(t *testing.T) {
	for _, c := range [][2]float64{{30, 40}, {120, 0}, {120, math.NaN()}} {
		if _, ok := AR2(c[0], c[1], T, 70, 180, DefaultConeFactor); ok {
			t.Errorf("AR2(%v, %v) forecast", c[0], c[1])
		}
	}
}

// xDrip+ (native G6/ONE) uploads "Flat" for everything; 2026-10-25 02:53 it
// said Flat at +12 mg/dL in 5 minutes. The arrow must come from the readings.
func TestDirectionIgnoresUploaderFlat(t *testing.T) {
	cases := []struct {
		name string
		vals []int // oldest first, 5 min apart, newest at T
		want string
	}{
		{"+12 in 5 min", []int{182, 194}, "SingleUp"},
		{"+30 in 15 min", []int{150, 160, 170, 180}, "FortyFiveUp"},
		{"−45 in 15 min", []int{200, 185, 170, 155}, "SingleDown"},
		{"wobble around flat", []int{180, 186, 178, 182}, "Flat"},
		{"one noisy jump does not make a double arrow", []int{180, 180, 180, 198}, "FortyFiveUp"},
	}
	for _, c := range cases {
		var rs []Reading
		for i, v := range c.vals {
			rs = append(rs, Reading{Date: T - int64(len(c.vals)-1-i)*bucketMs, SGV: v, Direction: "Flat"})
		}
		st, _ := Compute(rs, T)
		if st.Direction != c.want || st.UploaderDirection != "Flat" {
			t.Errorf("%s: direction %q (uploader %q), want %q", c.name, st.Direction, st.UploaderDirection, c.want)
		}
	}

	// a single reading cannot give a trend: fall back to what the uploader said
	st, _ := Compute([]Reading{{Date: T, SGV: 120, Direction: "SingleUp"}}, T)
	if st.Direction != "SingleUp" {
		t.Fatalf("fallback %q", st.Direction)
	}
}
