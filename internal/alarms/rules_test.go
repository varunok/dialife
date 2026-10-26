package alarms

import (
	"testing"
	"time"

	"dialife/internal/glucose"
)

const now = int64(1_792_886_400_000)

func fresh(sgv int) Input {
	return Input{Now: now, HasData: true, LastMills: now - 2*60_000, SGV: sgv}
}

func ago(d time.Duration, sgv int) Input {
	return Input{Now: now, HasData: true, LastMills: now - d.Milliseconds(), SGV: sgv}
}

func levels(c map[Kind]Condition) map[Kind]Level {
	out := map[Kind]Level{}
	for k, v := range c {
		out[k] = v.Level
	}
	return out
}

func TestConditions(t *testing.T) {
	r := DefaultRules
	low := &glucose.Forecast{Level: glucose.LevelUrgent, Event: "low"}
	highWarn := &glucose.Forecast{Level: glucose.LevelWarn, Event: "high"}
	withForecast := func(in Input, f *glucose.Forecast) Input { in.Forecast = f; return in }

	cases := []struct {
		name   string
		in     Input
		active map[Kind]Level
		want   map[Kind]Level
	}{
		{"no data at all", Input{Now: now}, nil, map[Kind]Level{}},
		{"in range", fresh(120), nil, map[Kind]Level{}},
		{"exactly at low threshold is fine", fresh(70), nil, map[Kind]Level{}},
		{"low", fresh(69), nil, map[Kind]Level{KindLow: Warn}},
		{"urgent low", fresh(54), nil, map[Kind]Level{KindLow: Urgent}},
		{"high", fresh(181), nil, map[Kind]Level{KindHigh: Warn}},
		{"urgent high", fresh(251), nil, map[Kind]Level{KindHigh: Urgent}},

		{"low recovering inside hysteresis stays", fresh(74), map[Kind]Level{KindLow: Warn}, map[Kind]Level{KindLow: Warn}},
		{"low recovered past hysteresis clears", fresh(75), map[Kind]Level{KindLow: Warn}, map[Kind]Level{}},
		{"urgent low just above 55 stays urgent", fresh(58), map[Kind]Level{KindLow: Urgent}, map[Kind]Level{KindLow: Urgent}},
		{"urgent low at 60 drops to warn", fresh(60), map[Kind]Level{KindLow: Urgent}, map[Kind]Level{KindLow: Warn}},
		{"high falling inside hysteresis stays", fresh(176), map[Kind]Level{KindHigh: Warn}, map[Kind]Level{KindHigh: Warn}},
		{"high fell past hysteresis clears", fresh(175), map[Kind]Level{KindHigh: Warn}, map[Kind]Level{}},
		{"74 without an active low is fine", fresh(74), nil, map[Kind]Level{}},

		{"predicted low", withForecast(fresh(80), low), nil, map[Kind]Level{KindPredictedLow: Urgent}},
		{"predicted high", withForecast(fresh(170), highWarn), nil, map[Kind]Level{KindPredictedHigh: Warn}},
		{"forecast low muted while already low", withForecast(fresh(65), low), nil, map[Kind]Level{KindLow: Warn}},

		{"14 min without data: nothing yet", ago(14*time.Minute, 120), nil, map[Kind]Level{}},
		{"15 min without data", ago(15*time.Minute, 120), nil, map[Kind]Level{KindStale: Warn}},
		{"30 min without data", ago(30*time.Minute, 120), nil, map[Kind]Level{KindStale: Urgent}},
		{"stale data keeps a live low", ago(20*time.Minute, 120), map[Kind]Level{KindLow: Urgent},
			map[Kind]Level{KindStale: Warn, KindLow: Urgent}},
		{"stale data never raises a new low", ago(12*time.Minute, 50), nil, map[Kind]Level{}},
		{"stale data drops a forecast", withForecast(ago(12*time.Minute, 80), low), map[Kind]Level{KindPredictedLow: Urgent},
			map[Kind]Level{}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := levels(r.Conditions(c.in, c.active))
			if len(got) != len(c.want) {
				t.Fatalf("got %v, want %v", got, c.want)
			}
			for k, v := range c.want {
				if got[k] != v {
					t.Fatalf("got %v, want %v", got, c.want)
				}
			}
		})
	}
}
