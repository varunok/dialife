package dose

import (
	"errors"
	"testing"
)

func TestDefaultsAreValid(t *testing.T) {
	if err := Defaults.Valid(); err != nil {
		t.Fatalf("defaults: %v", err)
	}
}

// Every value has its bounds; one step past either side is refused, the bound itself is not.
func TestValidBounds(t *testing.T) {
	cases := []struct {
		name   string
		ok, no func(*Settings)
	}{
		{"ratio low", func(d *Settings) { d.Ratio = 1 }, func(d *Settings) { d.Ratio = 0.9 }},
		{"ratio high", func(d *Settings) { d.Ratio = 100 }, func(d *Settings) { d.Ratio = 101 }},
		{"rule", func(d *Settings) { d.Rule = "up" }, func(d *Settings) { d.Rule = "half" }},
		{"step", func(d *Settings) { d.Step = 0.5 }, func(d *Settings) { d.Step = 2 }},
		{"fresh low", func(d *Settings) { d.FreshMin = 1 }, func(d *Settings) { d.FreshMin = 0 }},
		{"fresh high", func(d *Settings) { d.FreshMin = 60 }, func(d *Settings) { d.FreshMin = 61 }},
		{"max rapid low", func(d *Settings) { d.MaxRapid = 0.5; d.Table = nil }, func(d *Settings) { d.MaxRapid = 0.4 }},
		{"max rapid high", func(d *Settings) { d.MaxRapid = 100 }, func(d *Settings) { d.MaxRapid = 100.5 }},
		{"max basal low", func(d *Settings) { d.MaxBasal = 0.5 }, func(d *Settings) { d.MaxBasal = 0.4 }},
		{"max basal high", func(d *Settings) { d.MaxBasal = 200 }, func(d *Settings) { d.MaxBasal = 201 }},
		{"max carbs low", func(d *Settings) { d.MaxCarbs = 20 }, func(d *Settings) { d.MaxCarbs = 19 }},
		{"max carbs high", func(d *Settings) { d.MaxCarbs = 500 }, func(d *Settings) { d.MaxCarbs = 501 }},
		{"isf off", func(d *Settings) { d.ISF = 0 }, func(d *Settings) { d.ISF = 4 }},
		{"isf low", func(d *Settings) { d.ISF = 5 }, func(d *Settings) { d.ISF = 4.9 }},
		{"isf high", func(d *Settings) { d.ISF = 400 }, func(d *Settings) { d.ISF = 401 }},
		{"dia low", func(d *Settings) { d.DIAMin = 120 }, func(d *Settings) { d.DIAMin = 119 }},
		{"dia high", func(d *Settings) { d.DIAMin = 480 }, func(d *Settings) { d.DIAMin = 481 }},
		{"carb min low", func(d *Settings) { d.CarbMin = 60 }, func(d *Settings) { d.CarbMin = 59 }},
		{"carb min high", func(d *Settings) { d.CarbMin = 360 }, func(d *Settings) { d.CarbMin = 361 }},
		{"wait low", func(d *Settings) { d.WaitMin = 0 }, func(d *Settings) { d.WaitMin = -1 }},
		{"wait high", func(d *Settings) { d.WaitMin = 45; d.WaitHighMin = 45 }, func(d *Settings) { d.WaitMin = 46; d.WaitHighMin = 46 }},
		{"wait high not under wait", func(d *Settings) { d.WaitHighMin = d.WaitMin }, func(d *Settings) { d.WaitHighMin = d.WaitMin - 1 }},
		{"wait high max", func(d *Settings) { d.WaitHighMin = 60 }, func(d *Settings) { d.WaitHighMin = 61 }},
		{"low bound low", func(d *Settings) { d.WaitLowBelow = 60 }, func(d *Settings) { d.WaitLowBelow = 59 }},
		{"low bound high", func(d *Settings) { d.WaitLowBelow = 120 }, func(d *Settings) { d.WaitLowBelow = 121 }},
		{"high above low", func(d *Settings) { d.WaitHighFrom = d.WaitLowBelow + 1 }, func(d *Settings) { d.WaitHighFrom = d.WaitLowBelow }},
		{"high bound", func(d *Settings) { d.WaitHighFrom = 300 }, func(d *Settings) { d.WaitHighFrom = 301 }},
		{"table rows", func(d *Settings) { d.Table = rows(20) }, func(d *Settings) { d.Table = rows(21) }},
		{"table mg low", func(d *Settings) { d.Table = []Step{{40, 1}} }, func(d *Settings) { d.Table = []Step{{39, 1}} }},
		{"table mg high", func(d *Settings) { d.Table = []Step{{600, 1}} }, func(d *Settings) { d.Table = []Step{{601, 1}} }},
		{"table units", func(d *Settings) { d.Table = []Step{{140, 50}} }, func(d *Settings) { d.Table = []Step{{140, 50.5}} }},
		{"table units negative", func(d *Settings) { d.Table = []Step{{140, 0}} }, func(d *Settings) { d.Table = []Step{{140, -1}} }},
		{"table twice", func(d *Settings) { d.Table = []Step{{140, 1}, {180, 2}} }, func(d *Settings) { d.Table = []Step{{140, 1}, {140, 2}} }},
	}
	for _, c := range cases {
		ok, no := Defaults, Defaults
		ok.Table, no.Table = append([]Step(nil), Defaults.Table...), append([]Step(nil), Defaults.Table...)
		c.ok(&ok)
		c.no(&no)
		if err := ok.Valid(); err != nil {
			t.Errorf("%s: the bound itself refused: %v", c.name, err)
		}
		if err := no.Valid(); !errors.Is(err, ErrRange) {
			t.Errorf("%s: past the bound accepted", c.name)
		}
	}
}

func rows(n int) []Step {
	out := make([]Step, n)
	for i := range out {
		out[i] = Step{MG: 100 + i*10, U: 1}
	}
	return out
}

func TestEffectiveISF(t *testing.T) {
	if got := (Settings{ISF: 55}).EffectiveISF(); got != 55 {
		t.Errorf("set: %v", got)
	}
	// 40 mg/dL a unit between the default rows
	if got := Defaults.EffectiveISF(); got != 40 {
		t.Errorf("from the table: %v", got)
	}
	if got := (Settings{}).EffectiveISF(); got != 40 {
		t.Errorf("no table: %v", got)
	}
	// the typical step, not one odd row: 30, 40, 40 → 40
	d := Settings{Table: []Step{{180, 2}, {140, 1}, {210, 3}, {250, 4}}}
	if got := d.EffectiveISF(); got != 40 {
		t.Errorf("median: %v", got)
	}
}

func TestWithDefaultsKeepsWhatWasSaved(t *testing.T) {
	got := Settings{Ratio: 12, WaitMin: 10}.WithDefaults()
	if got.DIAMin != Defaults.DIAMin || got.CarbMin != Defaults.CarbMin {
		t.Errorf("missing fields not filled: %+v", got)
	}
	if got.WaitMin != 10 || got.WaitHighMin != 0 {
		t.Errorf("a saved wait is kept as it is: %+v", got)
	}
	if w := (Settings{}).WithDefaults(); w.WaitMin != Defaults.WaitMin || w.WaitLowBelow != Defaults.WaitLowBelow {
		t.Errorf("no wait saved: the defaults: %+v", w)
	}
}
