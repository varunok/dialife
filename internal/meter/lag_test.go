package meter

import (
	"math"
	"testing"
	"time"
)

// blood glucose that keeps moving: a slow wave of ±60 mg/dL around 140
func blood(t int64) float64 {
	return 140 + 60*math.Sin(float64(t)/float64((95*time.Minute).Milliseconds()))
}

// a sensor that shows the blood lag later, every 5 minutes, off by the
// stretch's own amount; checks every 3 h 17 min, so they land on every phase
func lagged(lag time.Duration, days int, offsets map[int]float64, bounds []int64) (checks, sgv []Point) {
	from := now.Add(-time.Duration(days) * 24 * time.Hour).UnixMilli()
	for t := from; t < now.UnixMilli(); t += (5 * time.Minute).Milliseconds() {
		v := blood(t-lag.Milliseconds()) + offsets[stretchOf(bounds, t)]
		sgv = append(sgv, Point{T: t, V: int(math.Round(v))})
	}
	for t := from + time.Hour.Milliseconds(); t < now.UnixMilli()-time.Hour.Milliseconds(); t += (197 * time.Minute).Milliseconds() {
		checks = append(checks, Point{T: t, V: int(math.Round(blood(t)))})
	}
	return checks, sgv
}

func TestMeasureLagFindsTheDelay(t *testing.T) {
	// three stretches reading off by −30, +10 and +25 mg/dL: their offsets must not hide the delay
	bounds := []int64{ms(20 * 24 * time.Hour), ms(10 * 24 * time.Hour)}
	checks, sgv := lagged(7*time.Minute, 30, map[int]float64{0: -30, 1: 10, 2: 25}, bounds)
	l := MeasureLag(checks, sgv, bounds)
	if l.Min == nil || *l.Min != 7 || *l.Lo > 7 || *l.Hi < 7 || l.Source != "measured" || l.Used != 7 {
		t.Fatalf("%+v (min %v, %v–%v)", l, deref(l.Min), deref(l.Lo), deref(l.Hi))
	}
}

func TestMeasureLagFallsBackWhenUnsure(t *testing.T) {
	// a few checks only: nothing measured, Dexcom's figure used
	checks, sgv := lagged(7*time.Minute, 1, nil, nil)
	l := MeasureLag(checks, sgv, nil)
	if l.Min != nil || l.Used != PublishedLag || l.Source != "published" || l.N >= minLagChecks {
		t.Fatalf("%+v", l)
	}
	// noisy checks: a delay is measured but too loosely to be used
	checks, sgv = lagged(7*time.Minute, 6, nil, nil)
	for i := range checks {
		checks[i].V += []int{-25, 18, -9, 30, -16, 4}[i%6]
	}
	l = MeasureLag(checks, sgv, nil)
	if l.Min == nil || *l.Hi-*l.Lo <= LagPrecise || l.Used != PublishedLag || l.Source != "published" {
		t.Fatalf("%+v (min %v, %v–%v)", l, deref(l.Min), deref(l.Lo), deref(l.Hi))
	}
}

func TestPairsUseTheDelay(t *testing.T) {
	// the sensor shows at +5 min what the meter saw: paired then, they agree
	sgv := []Point{{T: ms(30 * time.Minute), V: 100}, {T: ms(25 * time.Minute), V: 150}}
	ps := Pairs([]Point{{T: ms(30 * time.Minute), V: 150}}, sgv, nil, 5*time.Minute)
	if len(ps) != 1 || ps[0].Sensor != 150 || ps[0].T != ms(30*time.Minute) {
		t.Fatalf("%+v", ps)
	}
}

func deref(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}
