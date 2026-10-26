package meter

import (
	"math"
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
	ps := Pairs(meters, sgv, starts)
	if len(ps) != 2 || ps[0].Sensor != 106 || !ps[0].Used || ps[1].Used || ps[0].SensorNo != 1 {
		t.Fatalf("%+v", ps)
	}
}

func TestFitFindsGrowingGap(t *testing.T) {
	// the meter reads 10% above the sensor, a new sensor (no pairs yet) in place
	starts := []int64{ms(25 * 24 * time.Hour), ms(15 * 24 * time.Hour), ms(2 * time.Hour)}
	var meters, sgv []Point
	levels := []int{80, 120, 150, 180, 220, 260, 300, 100, 140, 200, 240, 170}
	for i, v := range levels {
		at := ms(time.Duration(20-i) * 24 * time.Hour)
		sgv = append(sgv, flatAround(at, v)...)
		meters = append(meters, Point{T: at, V: int(math.Round(float64(v) * 1.1))})
	}
	ps := Pairs(meters, sgv, starts)
	r := Fit(ps, starts, now)
	if !r.Ready || r.Used != 12 || r.Sensors != 2 || r.Current == nil || r.Current.N != 0 || r.Current.Day != 1 {
		t.Fatalf("summary %+v", r)
	}
	if r.BiasPct > -8 || r.BiasPct < -10 { // sensor 1/1.1 of the meter: −9.1%
		t.Fatalf("bias %v", r.BiasPct)
	}
	v, spread := r.Model.Estimate(250)
	if math.Abs(v-275) > 6 || spread <= 0 || spread > 15 {
		t.Fatalf("estimate at 250: %v ±%v (model %+v)", v, spread, r.Model)
	}
	hi := r.Bands[len(r.Bands)-1]
	if hi.From != 200 || hi.Unit != "pct" || hi.N != 5 {
		t.Fatalf("bands %+v", r.Bands)
	}
}

func TestFitNotReadyWithFewPairs(t *testing.T) {
	var meters, sgv []Point
	for i := 0; i < 5; i++ {
		at := ms(time.Duration(5-i) * time.Hour) // oldest first, as the store returns them
		sgv = append(sgv, flatAround(at, 150)...)
		meters = append(meters, Point{T: at, V: 160})
	}
	r := Fit(Pairs(meters, sgv, nil), nil, now)
	if r.Ready || r.Model != nil || r.Used != 5 || r.Need != 8 || r.Current != nil {
		t.Fatalf("%+v", r)
	}
}

func TestSensorOffsetPulledTowardsCommon(t *testing.T) {
	// two old sensors agree with the meter; the current one reads 20% low, with two pairs only
	starts := []int64{ms(30 * 24 * time.Hour), ms(20 * 24 * time.Hour), ms(5 * 24 * time.Hour)}
	var meters, sgv []Point
	add := func(d time.Duration, s, m int) {
		at := ms(d)
		sgv = append(sgv, flatAround(at, s)...)
		meters = append(meters, Point{T: at, V: m})
	}
	for i := 0; i < 8; i++ {
		add(time.Duration(28-i*2)*24*time.Hour, 120+i*15, 120+i*15)
	}
	add(4*24*time.Hour, 150, 188)
	add(3*24*time.Hour, 200, 250)
	r := Fit(Pairs(meters, sgv, starts), starts, now)
	off := r.Model.Offset
	if off <= 0.03 || off >= 0.2 { // towards +25%, but not all the way with two pairs
		t.Fatalf("offset %v", off)
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
