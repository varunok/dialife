package alarms

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestParseAssignments(t *testing.T) {
	st, err := ParseAssignments(SettingsOf(DefaultRules), []string{"low=80", "high=250", "urgent_high=300", "high_for_min=30", "predicted_high=off"})
	if err != nil {
		t.Fatal(err)
	}
	want := Settings{UrgentLow: 55, Low: 80, High: 250, UrgentHigh: 300, HighForMin: 30, PredictedLow: true, PredictedHigh: false,
		RepeatMin: 5, WorseBy: 10, CheckinFirstMin: 20, CheckinEveryMin: 15, CheckinTimeoutMin: 5, CeilingMin: 45}
	if st != want {
		t.Fatalf("got %+v, want %+v", st, want)
	}
	for args, msg := range map[string]string{
		"low=50":            "urgent_low < low",
		"high=abc":          "not a number",
		"high_for_min=500":  "0..240",
		"predicted_low=may": "on/off",
		"colour=red":        "unknown setting",
		"low":               "key=value",
		"urgent_high=999":   "40..400",
		"ceiling_min=5":     "10..480",
		"repeat_min=0":      "1..60",
	} {
		if _, err := ParseAssignments(SettingsOf(DefaultRules), strings.Fields(args)); err == nil || !strings.Contains(err.Error(), msg) {
			t.Errorf("%s: err %v, want it to mention %q", args, err, msg)
		}
	}
}

func TestSettingsRoundTripAndHotReload(t *testing.T) {
	r := newRig(t)
	ctx := context.Background()
	if got := r.eng.CurrentRules(ctx); got.Thresholds != DefaultRules.Thresholds {
		t.Fatalf("defaults expected without stored settings: %+v", got.Thresholds)
	}

	r.reading(200) // above the default 180
	r.expect(EventAlarm)

	st := SettingsOf(family)
	if err := SaveSettings(ctx, r.store, st, r.clock); err != nil {
		t.Fatal(err)
	}
	got := r.eng.CurrentRules(ctx)
	if got.Thresholds != family.Thresholds || got.HighFor != 30*time.Minute || got.PredictedHigh {
		t.Fatalf("stored rules not applied: %+v", got)
	}

	// the very next evaluation uses them: 200 is fine now, the high clears
	r.advance(time.Minute)
	r.reading(200)
	r.expect(EventClear)

	if err := SaveSettings(ctx, r.store, Settings{UrgentLow: 90, Low: 80, High: 250, UrgentHigh: 300}, r.clock); err == nil {
		t.Fatal("invalid settings saved")
	}
}
