// Package alarms decides when the family must be alerted and drives each
// alarm through fire → repeat → ack/snooze → clear. Rules are pure
// (Conditions); Engine applies them to stored state and publishes events.
package alarms

import (
	"time"

	"dialife/internal/config"
	"dialife/internal/glucose"
)

type Kind string

const (
	KindLow           Kind = "low"
	KindHigh          Kind = "high"
	KindPredictedLow  Kind = "predicted_low"
	KindPredictedHigh Kind = "predicted_high"
	KindStale         Kind = "stale"
)

var allKinds = []Kind{KindLow, KindHigh, KindPredictedLow, KindPredictedHigh, KindStale}

type Level string

const (
	Warn   Level = "warn"
	Urgent Level = "urgent"
)

func (l Level) rank() int {
	switch l {
	case Warn:
		return 1
	case Urgent:
		return 2
	}
	return 0
}

// Rules are the tunables. DefaultRules are the generic standard values;
// a household's own values live in the settings table (see settings.go).
type Rules struct {
	Thresholds config.Thresholds
	// HighFor: a high must hold this long before it alarms, so short
	// post-meal peaks stay quiet. Lows never wait.
	HighFor       time.Duration
	PredictedLow  bool
	PredictedHigh bool
	Hysteresis    int           // mg/dL a value must recover past a threshold to clear
	FreshFor      time.Duration // thresholds and forecasts only judge readings this recent
	StaleWarn     time.Duration
	StaleUrgent   time.Duration

	Episode Episode
}

// Episode tunes how a taken alarm behaves (see engine.go).
type Episode struct {
	RepeatUrgent   time.Duration // nobody took it: ring everyone again
	RepeatWarn     time.Duration
	WorseBy        int           // mg/dL past the value at take-over that wakes everyone
	Improve        int           // mg/dL of recovery that skips a check-in
	CheckinFirst   time.Duration // urgent; warn waits twice as long
	CheckinEvery   time.Duration
	CheckinTimeout time.Duration // unanswered check-in: ring everyone
	Ceiling        time.Duration // urgent and still taken this long: ring everyone
	SuppressAfter  time.Duration // after a manual resolve, no new episode unless worse
	MaxQuiet       time.Duration
}

var DefaultEpisode = Episode{
	RepeatUrgent:   5 * time.Minute,
	RepeatWarn:     15 * time.Minute,
	WorseBy:        10,
	Improve:        10,
	CheckinFirst:   20 * time.Minute,
	CheckinEvery:   15 * time.Minute,
	CheckinTimeout: 5 * time.Minute,
	Ceiling:        45 * time.Minute,
	SuppressAfter:  30 * time.Minute,
	MaxQuiet:       4 * time.Hour,
}

var DefaultRules = Rules{
	Thresholds:    config.DefaultThresholds,
	PredictedLow:  true,
	PredictedHigh: true,
	Hysteresis:    5,
	FreshFor:      10 * time.Minute,
	StaleWarn:     15 * time.Minute,
	StaleUrgent:   30 * time.Minute,
	Episode:       DefaultEpisode,
}

// side tells which way is worse for a kind: -1 lower, +1 higher, 0 no value.
func side(k Kind) int {
	switch k {
	case KindLow, KindPredictedLow:
		return -1
	case KindHigh, KindPredictedHigh:
		return 1
	}
	return 0
}

// coverageSlack: a sustained-high window counts as covered when its first
// reading is at most this far after the window start (one missed reading).
const coverageSlack = 10 * time.Minute

type Reading struct {
	Date int64
	SGV  int
}

// Input is what the rules look at, built from the newest data.
type Input struct {
	Now       int64
	HasData   bool              // any valid reading ever
	LastMills int64             // newest valid reading (sensor error codes skipped)
	SGV       int               // its value, mg/dL
	Recent    []Reading         // valid readings covering at least HighFor before LastMills
	Forecast  *glucose.Forecast // nil when there is no usable AR2
}

type Condition struct {
	Level Level
	SGV   *int
}

// Conditions returns the alarms that should be live now. active holds the
// levels of currently live alarms; it gives hysteresis and lets alarms
// persist while data is missing (an unknown value is not a recovery).
func (r Rules) Conditions(in Input, active map[Kind]Level) map[Kind]Condition {
	out := map[Kind]Condition{}
	if !in.HasData {
		return out
	}
	sgv := in.SGV
	age := time.Duration(in.Now-in.LastMills) * time.Millisecond

	switch {
	case age >= r.StaleUrgent:
		out[KindStale] = Condition{Level: Urgent, SGV: &sgv}
	case age >= r.StaleWarn:
		out[KindStale] = Condition{Level: Warn, SGV: &sgv}
	}

	if age > r.FreshFor {
		// no fresh reading: keep threshold alarms as they are, drop forecasts
		for _, k := range []Kind{KindLow, KindHigh} {
			if lvl, ok := active[k]; ok {
				out[k] = Condition{Level: lvl, SGV: &sgv}
			}
		}
		return out
	}

	th, h := r.Thresholds, r.Hysteresis
	prev, hasPrev := previous(in)
	if lvl := lowLevel(sgv, prev, hasPrev, th, h, active[KindLow]); lvl != "" {
		out[KindLow] = Condition{Level: lvl, SGV: &sgv}
	}
	if lvl := r.highLevel(in, prev, hasPrev, active[KindHigh]); lvl != "" {
		out[KindHigh] = Condition{Level: lvl, SGV: &sgv}
	}

	if f := in.Forecast; f != nil && f.Level != glucose.LevelNone {
		lvl := Warn
		if f.Level == glucose.LevelUrgent {
			lvl = Urgent
		}
		// a forecast adds nothing while the same side is already alarming
		if _, low := out[KindLow]; r.PredictedLow && f.Event == "low" && !low {
			out[KindPredictedLow] = Condition{Level: lvl, SGV: &sgv}
		}
		if _, high := out[KindHigh]; r.PredictedHigh && f.Event == "high" && !high {
			out[KindPredictedHigh] = Condition{Level: lvl, SGV: &sgv}
		}
	}
	return out
}

// previous is the valid reading just before the newest one.
func previous(in Input) (int, bool) {
	var best *Reading
	for i := range in.Recent {
		r := &in.Recent[i]
		if r.Date < in.LastMills && (best == nil || r.Date > best.Date) {
			best = r
		}
	}
	if best == nil {
		return 0, false
	}
	return best.SGV, true
}

// A live alarm ends only after two readings in a row are past hysteresis,
// so one lucky value does not call off an episode.
func lowLevel(sgv, prev int, hasPrev bool, th config.Thresholds, h int, active Level) Level {
	switch {
	case sgv < th.UrgentLow:
		return Urgent
	case active == Urgent && sgv < th.UrgentLow+h:
		return Urgent
	case sgv < th.Low:
		return Warn
	case active != "" && sgv < th.Low+h:
		return Warn
	case active != "" && hasPrev && prev < th.Low+h:
		return Warn
	}
	return ""
}

// highLevel: a level is reached once readings stayed above it for HighFor;
// a live alarm keeps its level while the value is within hysteresis.
func (r Rules) highLevel(in Input, prev int, hasPrev bool, active Level) Level {
	th, h, sgv := r.Thresholds, r.Hysteresis, in.SGV
	switch {
	case r.sustainedAbove(in, th.UrgentHigh):
		return Urgent
	case active == Urgent && sgv > th.UrgentHigh-h:
		return Urgent
	case r.sustainedAbove(in, th.High):
		return Warn
	case active != "" && sgv > th.High-h:
		return Warn
	case active != "" && hasPrev && prev > th.High-h:
		return Warn
	}
	return ""
}

func (r Rules) sustainedAbove(in Input, threshold int) bool {
	if in.SGV <= threshold {
		return false
	}
	if r.HighFor <= 0 {
		return true
	}
	start := in.LastMills - r.HighFor.Milliseconds()
	covered := false
	for _, rd := range in.Recent {
		if rd.Date < start || rd.Date > in.LastMills {
			continue
		}
		if rd.SGV <= threshold {
			return false
		}
		if rd.Date <= start+coverageSlack.Milliseconds() {
			covered = true
		}
	}
	return covered
}
