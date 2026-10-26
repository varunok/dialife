package alarms

import (
	"testing"
	"time"

	"dialife/internal/config"
	"dialife/internal/glucose"
)

// family is the household config agreed on 2026-10-25 (new onset, doses being titrated).
var family = func() Rules {
	r := DefaultRules
	r.Thresholds = config.Thresholds{UrgentLow: 55, Low: 80, High: 250, UrgentHigh: 300}
	r.HighFor = 30 * time.Minute
	r.PredictedHigh = false
	return r
}()

// series builds a fresh input from readings every 5 min, oldest first, the last one 2 min ago.
func series(vals ...int) Input {
	last := now - 2*60_000
	in := Input{Now: now, HasData: true, LastMills: last, SGV: vals[len(vals)-1]}
	for i, v := range vals {
		in.Recent = append(in.Recent, Reading{Date: last - int64(len(vals)-1-i)*5*60_000, SGV: v})
	}
	return in
}

func TestSustainedHigh(t *testing.T) {
	cases := []struct {
		name   string
		in     Input
		active map[Kind]Level
		want   Level
	}{
		{"post-meal peak, 20 min above 250: quiet", series(200, 230, 255, 270, 280, 275), nil, ""},
		{"35 min above 250: warn", series(240, 255, 262, 270, 268, 271, 266, 259), nil, Warn},
		{"exactly 30 min window above 250: warn", series(255, 260, 262, 264, 266, 268, 270), nil, Warn},
		{"one dip below inside the window: quiet", series(255, 260, 262, 248, 266, 268, 270), nil, ""},
		{"35 min above 300: urgent", series(305, 310, 320, 330, 325, 318, 312, 309), nil, Urgent},
		{"above 300 only 15 min but above 250 for 35: warn", series(260, 265, 270, 280, 290, 305, 310, 315), nil, Warn},
		{"live warn stays at 247 (hysteresis)", series(247), map[Kind]Level{KindHigh: Warn}, Warn},
		{"live warn clears at 245", series(245), map[Kind]Level{KindHigh: Warn}, ""},
		{"live urgent stays at 297", series(297), map[Kind]Level{KindHigh: Urgent}, Urgent},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := family.Conditions(c.in, c.active)[KindHigh].Level
			if got != c.want {
				t.Fatalf("high level %q, want %q", got, c.want)
			}
		})
	}
}

func TestSustainedHighNeedsCoverage(t *testing.T) {
	// only the last 15 minutes are known: we cannot say it held 30 minutes
	in := series(280, 285, 290, 295)
	if lvl := family.Conditions(in, nil)[KindHigh].Level; lvl != "" {
		t.Fatalf("alarmed without data covering the window: %q", lvl)
	}
	// one missed reading at the window start is tolerated
	in = series(270, 275, 280, 282, 284, 286, 288)
	in.Recent = append(in.Recent[:0:0], in.Recent[1:]...)
	if lvl := family.Conditions(in, nil)[KindHigh].Level; lvl != Warn {
		t.Fatalf("one gap at window start blocked the alarm: %q", lvl)
	}
}

func TestFamilyLowsAreImmediate(t *testing.T) {
	if lvl := family.Conditions(series(120, 100, 79), nil)[KindLow].Level; lvl != Warn {
		t.Fatalf("79 with low=80: %q", lvl)
	}
	if lvl := family.Conditions(series(54), nil)[KindLow].Level; lvl != Urgent {
		t.Fatalf("54: %q", lvl)
	}
	if lvl := family.Conditions(series(82), map[Kind]Level{KindLow: Warn})[KindLow].Level; lvl != Warn {
		t.Fatalf("82 inside hysteresis of 80: %q", lvl)
	}
}

func TestPredictedHighCanBeOff(t *testing.T) {
	in := series(240)
	in.Forecast = &glucose.Forecast{Level: glucose.LevelUrgent, Event: "high"}
	if _, ok := family.Conditions(in, nil)[KindPredictedHigh]; ok {
		t.Fatal("predicted_high fired while off")
	}
	in.Forecast = &glucose.Forecast{Level: glucose.LevelUrgent, Event: "low"}
	if _, ok := family.Conditions(in, nil)[KindPredictedLow]; !ok {
		t.Fatal("predicted_low must stay on")
	}
}
