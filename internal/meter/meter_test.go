package meter

import (
	"math"
	"slices"
	"testing"
	"time"
)

var now = time.Date(2026, 10, 25, 22, 0, 0, 0, time.UTC)

func ms(d time.Duration) int64 { return now.Add(-d).UnixMilli() }

// a steady sensor at v around t, readings every 5 minutes
func flatAround(t int64, v int) []Point {
	return []Point{{T: t - 4*60000, V: v}, {T: t + 60000, V: v}}
}

func TestDedup(t *testing.T) {
	got := Dedup([]Point{{T: ms(time.Hour), V: 229}, {T: ms(57 * time.Minute), V: 229}, {T: ms(50 * time.Minute), V: 180}, {T: ms(10 * time.Minute), V: 229}})
	if len(got) != 3 || got[1].V != 180 || got[2].V != 229 {
		t.Fatalf("%v", got)
	}
}

func TestPairsInterpolateAndSkipFastChanges(t *testing.T) {
	sgv := []Point{{T: ms(65 * time.Minute), V: 100}, {T: ms(60 * time.Minute), V: 110}, {T: ms(30 * time.Minute), V: 100}, {T: ms(25 * time.Minute), V: 150}}
	starts := []int64{ms(48 * time.Hour)}
	meters := []Point{
		{T: ms(62 * time.Minute), V: 120}, // between 100 and 110: 106, 2/min → used
		{T: ms(27 * time.Minute), V: 140}, // 10/min: kept, not used
		{T: ms(3 * time.Hour), V: 99},     // no sensor around: dropped
	}
	ps := Pairs(meters, sgv, starts, 0)
	if len(ps) != 2 || ps[0].Sensor != 106 || !ps[0].Used || ps[1].Used || ps[0].SensorNo != 1 {
		t.Fatalf("%+v", ps)
	}
}

// pairs at the given days ago (sensor, meter), flat around each check
func pairsAt(starts []int64, at ...[3]int) []Pair {
	var meters, sgv []Point
	for _, a := range at {
		t := ms(time.Duration(a[0]) * time.Hour)
		sgv = append(sgv, flatAround(t, a[1])...)
		meters = append(meters, Point{T: t, V: a[2]})
	}
	slices.SortFunc(sgv, func(a, b Point) int { return cmp64(a.T, b.T) })
	slices.SortFunc(meters, func(a, b Point) int { return cmp64(a.T, b.T) })
	return Pairs(meters, sgv, starts, 0)
}

func TestFitIsTheCurrentSensorOnly(t *testing.T) {
	// two old sensors read 20% high; the current one reads low: meter 137 at sensor 100
	starts := []int64{ms(30 * 24 * time.Hour), ms(20 * 24 * time.Hour), ms(5 * 24 * time.Hour)}
	var at [][3]int
	for i := 0; i < 10; i++ {
		at = append(at, [3]int{(28 - i*2) * 24, 120, 100})
	}
	at = append(at, [3]int{72, 100, 137}, [3]int{48, 150, 205}, [3]int{24, 80, 117})
	r := Fit(pairsAt(starts, at...), starts, nil, now)
	if !r.Ready || r.Used != 3 || len(r.Pairs) != 3 || r.Current == nil || r.Current.N != 3 || r.Sensors != 1 {
		t.Fatalf("summary %+v", r)
	}
	if r.BiasPct > -25 || r.BiasPct < -32 {
		t.Fatalf("bias of this sensor only: %v", r.BiasPct)
	}
	// the old sensors do not pull it: the meter is ~37% above the sensor here
	if v, _ := r.Model.Estimate(100); math.Abs(v-137) > 3 {
		t.Fatalf("estimate at 100: %v (model %+v)", v, r.Model)
	}
	if r.Model.A != 0 || r.Model.B != 0 || r.Model.Since != starts[2] {
		t.Fatalf("model %+v", r.Model)
	}
	// the band comes from how much checks wander within a sensor, the old ones included
	if r.Model.SD < minSpread || r.Model.SD > 0.1 {
		t.Fatalf("spread %v", r.Model.SD)
	}
}

func TestFitStartsAgainAtACalibration(t *testing.T) {
	starts := []int64{ms(5 * 24 * time.Hour)}
	cal := ms(30 * time.Hour)
	ps := pairsAt(starts, [3]int{60, 60, 100}, [3]int{40, 70, 110}, [3]int{20, 110, 115}, [3]int{10, 100, 106})
	r := Fit(ps, starts, []int64{cal}, now)
	if len(r.Pairs) != 4 || !r.Pairs[0].BeforeCal || !r.Pairs[1].BeforeCal || r.Pairs[2].BeforeCal {
		t.Fatalf("pairs %+v", r.Pairs)
	}
	if r.Used != 2 || r.Current.CalibratedAt != cal || len(r.Calibrations) != 1 || r.Model.Since != cal {
		t.Fatalf("summary %+v model %+v", r, r.Model)
	}
	// only after the calibration: the meter ~5% above, not the 50% of before
	if v, _ := r.Model.Estimate(100); v < 103 || v > 108 {
		t.Fatalf("estimate %v", v)
	}
}

func TestFitNeedsTwoPairsOfThisStretch(t *testing.T) {
	starts := []int64{ms(5 * 24 * time.Hour)}
	r := Fit(pairsAt(starts, [3]int{10, 100, 120}), starts, nil, now)
	if r.Ready || r.Model != nil || r.Used != 1 || r.Need != 2 {
		t.Fatalf("%+v", r)
	}
}

func TestNewerChecksWeighMore(t *testing.T) {
	// the offset drifts: 2 days ago the meter read 10% above, today 40%
	starts := []int64{ms(5 * 24 * time.Hour)}
	r := Fit(pairsAt(starts, [3]int{48, 100, 110}, [3]int{1, 100, 140}), starts, nil, now)
	if v, _ := r.Model.Estimate(100); v < 132 {
		t.Fatalf("the newer check should count far more: %v", v)
	}
}

func TestSource(t *testing.T) {
	for in, want := range map[string]string{
		"Bluetooth Glucose Meter:\nRoche   00:00:5E:00:53:01": "Roche 00:00:5E:00:53:01",
		"Manual Entry": "manual",
		"":             "",
	} {
		if got := Source(in); got != want {
			t.Errorf("%q: %q", in, got)
		}
	}
}
