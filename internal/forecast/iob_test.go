package forecast

import (
	"math"
	"testing"
	"time"
)

var p = Params{ISF: 40, Ratio: 20, DIA: 4 * time.Hour, CarbFor: 3 * time.Hour}

func TestIOBFraction(t *testing.T) {
	// the exponential curve of LoopKit's ExponentialInsulinModel, peak 75 min, 4 h
	cases := []struct {
		at   time.Duration
		want float64
	}{{0, 1}, {60 * time.Minute, 0.75}, {120 * time.Minute, 0.34}, {240 * time.Minute, 0}}
	for _, c := range cases {
		if got := IOBFraction(c.at, p.DIA); math.Abs(got-c.want) > 0.05 {
			t.Errorf("at %v: %.3f, want ≈%.2f", c.at, got, c.want)
		}
	}
	// always going down
	prev := 1.0
	for m := 5; m <= 240; m += 5 {
		f := IOBFraction(time.Duration(m)*time.Minute, p.DIA)
		if f > prev+1e-9 || f < 0 {
			t.Fatalf("at %d min: %.3f after %.3f", m, f, prev)
		}
		prev = f
	}
}

func TestOnBoard(t *testing.T) {
	now := int64(10 * 3_600_000)
	iob, cob := OnBoard([]Dose{{now - 2*3_600_000, 4}, {now + 60_000, 9}}, []Meal{{now - 90*60_000, 60}}, p, now)
	if math.Abs(iob-4*IOBFraction(2*time.Hour, p.DIA)) > 1e-9 {
		t.Fatalf("iob %.2f: a dose in the future does not count", iob)
	}
	// 90 min after the meal, 75 of them absorbing over 180: 60 × (1 − 75/180)
	if math.Abs(cob-35) > 0.01 {
		t.Fatalf("cob %.2f", cob)
	}
}

func TestPhysiological(t *testing.T) {
	now := int64(10 * 3_600_000)
	// a meal alone rises
	up := Physiological(120, nil, []Meal{{now, 45}}, p, now, Horizons)
	if !(up[0].V >= 120 && up[1].V > up[0].V && up[2].V > up[1].V) {
		t.Fatalf("meal: %+v", up)
	}
	// a dose alone falls
	down := Physiological(200, []Dose{{now - 10*60_000, 2}}, nil, p, now, Horizons)
	if !(down[1].V < 200 && down[2].V < down[1].V) {
		t.Fatalf("dose: %+v", down)
	}
	// both, covered: 45 g at 1:20 is 2.25 u; after the whole action they meet again
	both := Physiological(150, []Dose{{now, 2.25}}, []Meal{{now, 45}}, p, now, []time.Duration{5 * time.Hour})
	if math.Abs(float64(both[0].V-150)) > 1 {
		t.Fatalf("covered meal ends where it began: %+v", both)
	}
	if Physiological(150, nil, nil, Params{}, now, Horizons) != nil {
		t.Fatal("no ISF: no forecast")
	}
}

func TestBacktestPhysiological(t *testing.T) {
	// flat readings, nothing logged: it says "no change" and comes true
	var rs []Point
	for i := range 48 {
		rs = append(rs, Point{T: int64(i) * 5 * 60_000, V: 110})
	}
	q := BacktestPhysiological(rs, nil, nil, p)
	if q.N == 0 || q.HitPct != 100 || q.Err80 != 0 {
		t.Fatalf("%+v", q)
	}
	// a dose given after a reading is not known to the forecast made at it
	q = BacktestPhysiological(rs, []Dose{{rs[47].T, 5}}, nil, p)
	if q.HitPct != 100 {
		t.Fatalf("a later dose leaked into the past: %+v", q)
	}
}
