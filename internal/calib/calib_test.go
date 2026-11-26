package calib

import (
	"slices"
	"testing"
	"time"
)

const minute = int64(60_000)

// a day on one sensor, started at the start: the sensor flat at each check's level
// (sensor, meter) at the given minutes; readings every 5 minutes around them
func day(at ...[3]int) Input {
	in := Input{SensorStart: 1, Since: 1, Lag: 4 * time.Minute, AbsorbFor: 3 * time.Hour} // started at the first ms
	for _, a := range at {
		t := int64(a[0]) * minute
		for k := int64(-20); k <= 20; k += 5 {
			in.SGV = append(in.SGV, Point{T: t + k*minute, MG: a[1]})
		}
		in.Checks = append(in.Checks, Point{T: t, MG: a[2]})
	}
	slices.SortFunc(in.SGV, func(a, b Point) int { return int(a.T - b.T) })
	in.SGV = slices.CompactFunc(in.SGV, func(a, b Point) bool { return a.T == b.T })
	last := in.Checks[len(in.Checks)-1]
	in.Now = last.T + 2*minute
	return in
}

func TestThreeInARowOutsideTwentyTwenty(t *testing.T) {
	// sensor 75 against the meter 105 (−30, beyond 21) three times, hours apart, after 12 h
	in := day([3]int{800, 75, 105}, [3]int{1000, 80, 112}, [3]int{1200, 78, 108})
	a := Decide(in)
	if a == nil || a.Side != "low" || len(a.Groups) != 3 || a.DiffMg != -31 {
		t.Fatalf("%+v", a)
	}
	// calibrate now: the check is fresh, 90–150, steady, no food or insulin
	if a.Now == nil || a.Now.MG != 108 || a.Now.Until != in.Checks[2].T+5*minute || len(a.Wait) != 0 {
		t.Fatalf("now %+v wait %v", a.Now, a.Wait)
	}
}

func TestNoAdviceWhenOneIsWithinOrOnTheOtherSide(t *testing.T) {
	// the middle one within 20%
	if a := Decide(day([3]int{800, 75, 105}, [3]int{1000, 100, 112}, [3]int{1200, 78, 108})); a != nil {
		t.Fatalf("within: %+v", a)
	}
	// one reads high
	if a := Decide(day([3]int{800, 75, 105}, [3]int{1000, 150, 112}, [3]int{1200, 78, 108})); a != nil {
		t.Fatalf("both sides: %+v", a)
	}
	// below 70 the limit is 20 mg/dL: 48 against 66 is within, though 27% off
	if a := Decide(day([3]int{800, 48, 66}, [3]int{1000, 48, 66}, [3]int{1200, 48, 66})); a != nil {
		t.Fatalf("20 mg/dL below 70: %+v", a)
	}
}

func TestChecksWithinAnHourAreOne(t *testing.T) {
	// three checks in 20 minutes are one moment, not three in a row
	if a := Decide(day([3]int{800, 75, 105}, [3]int{810, 75, 104}, [3]int{820, 75, 106})); a != nil {
		t.Fatalf("one moment: %+v", a)
	}
	if gs := Groups(day([3]int{800, 75, 105}, [3]int{810, 75, 104}, [3]int{1200, 75, 106})); len(gs) != 2 || gs[0].Meter != 105 {
		t.Fatalf("groups %+v", gs)
	}
}

func TestFirstTwelveHours(t *testing.T) {
	// 30 below in the first hours: not yet
	if a := Decide(day([3]int{100, 75, 105}, [3]int{300, 80, 112}, [3]int{500, 78, 108})); a != nil {
		t.Fatalf("settling: %+v", a)
	}
	// 50 below on average: at once
	if a := Decide(day([3]int{100, 60, 110}, [3]int{300, 62, 112}, [3]int{500, 58, 108})); a == nil {
		t.Fatal("far off: advice even in the first hours")
	}
	// LOW at a check of 90: at once, on its own
	in := day([3]int{300, 40, 90})
	in.SGV = nil
	in.Lows = []int64{300*minute + 3*minute}
	a := Decide(in)
	if a == nil || !a.LowRead || a.Side != "low" || len(a.Groups) != 1 {
		t.Fatalf("LOW: %+v", a)
	}
	if a.Now != nil || !slices.Contains(a.Wait, "no_trend") {
		t.Fatalf("no readings to see it steady: %+v %v", a.Now, a.Wait)
	}
	// LOW at a check of 60: the sensor was right
	in.Checks[0].MG = 60
	if a := Decide(in); a != nil {
		t.Fatalf("a real low: %+v", a)
	}
}

func TestWhenNotToCalibrateNow(t *testing.T) {
	base := day([3]int{800, 75, 105}, [3]int{1000, 80, 112}, [3]int{1200, 78, 108})
	cases := map[string]func(in *Input){
		"old":     func(in *Input) { in.Now += 10 * minute },
		"range":   func(in *Input) { in.Checks[2].MG = 160 },
		"food":    func(in *Input) { in.Carbs = []int64{in.Checks[2].T - 2*time.Hour.Milliseconds()} },
		"insulin": func(in *Input) { in.Rapid = []int64{in.Checks[2].T - 30*minute} },
		"shower":  func(in *Input) { in.Untrusted = true },
		"moving": func(in *Input) {
			for i := range in.SGV {
				if in.SGV[i].T == in.Checks[2].T-15*minute {
					in.SGV[i].MG += 20
				}
			}
		},
	}
	for want, change := range cases {
		in := base
		in.SGV = slices.Clone(base.SGV)
		in.Checks = slices.Clone(base.Checks)
		change(&in)
		a := Decide(in)
		if a == nil || a.Now != nil || !slices.Contains(a.Wait, want) {
			t.Errorf("%s: %+v", want, a)
		}
	}
	// food longer ago than the absorption time is fine
	in := base
	in.Carbs = []int64{in.Checks[2].T - 4*time.Hour.Milliseconds()}
	if a := Decide(in); a == nil || a.Now == nil {
		t.Fatalf("old food: %+v", a)
	}
	// the no-sensor mode: nothing at all
	in.Sensorless = true
	if a := Decide(in); a != nil {
		t.Fatalf("no sensor: %+v", a)
	}
}

func TestOnlyTheCurrentStretch(t *testing.T) {
	in := day([3]int{800, 75, 105}, [3]int{1000, 80, 112}, [3]int{1200, 78, 108})
	in.Since = 900 * minute // a calibration after the first
	if a := Decide(in); a != nil {
		t.Fatalf("two since the calibration: %+v", a)
	}
}
