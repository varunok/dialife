package alarms

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"dialife/internal/store"
)

const settingsKey = "alarm_rules"

// Settings is the editable part of Rules, as stored (JSON) and shown by the CLI.
type Settings struct {
	UrgentLow     int  `json:"urgent_low"`
	Low           int  `json:"low"`
	High          int  `json:"high"`
	UrgentHigh    int  `json:"urgent_high"`
	HighForMin    int  `json:"high_for_min"`
	PredictedLow  bool `json:"predicted_low"`
	PredictedHigh bool `json:"predicted_high"`

	// episodes (urgent values; warn repeats 3×, check-ins 2× as slow)
	RepeatMin         int `json:"repeat_min"`
	WorseBy           int `json:"worse_by"`
	CheckinFirstMin   int `json:"checkin_first_min"`
	CheckinEveryMin   int `json:"checkin_every_min"`
	CheckinTimeoutMin int `json:"checkin_timeout_min"`
	CeilingMin        int `json:"ceiling_min"`
}

func SettingsOf(r Rules) Settings {
	e := r.Episode
	return Settings{
		UrgentLow: r.Thresholds.UrgentLow, Low: r.Thresholds.Low, High: r.Thresholds.High, UrgentHigh: r.Thresholds.UrgentHigh,
		HighForMin: int(r.HighFor / time.Minute), PredictedLow: r.PredictedLow, PredictedHigh: r.PredictedHigh,
		RepeatMin: mins(e.RepeatUrgent), WorseBy: e.WorseBy, CheckinFirstMin: mins(e.CheckinFirst),
		CheckinEveryMin: mins(e.CheckinEvery), CheckinTimeoutMin: mins(e.CheckinTimeout), CeilingMin: mins(e.Ceiling),
	}
}

func mins(d time.Duration) int { return int(d / time.Minute) }

// Apply returns base with the editable fields replaced.
func (s Settings) Apply(base Rules) Rules {
	m := func(n int) time.Duration { return time.Duration(n) * time.Minute }
	base.Thresholds.UrgentLow, base.Thresholds.Low = s.UrgentLow, s.Low
	base.Thresholds.High, base.Thresholds.UrgentHigh = s.High, s.UrgentHigh
	base.HighFor = m(s.HighForMin)
	base.PredictedLow, base.PredictedHigh = s.PredictedLow, s.PredictedHigh
	base.Episode.RepeatUrgent, base.Episode.RepeatWarn = m(s.RepeatMin), 3*m(s.RepeatMin)
	base.Episode.WorseBy = s.WorseBy
	base.Episode.CheckinFirst, base.Episode.CheckinEvery = m(s.CheckinFirstMin), m(s.CheckinEveryMin)
	base.Episode.CheckinTimeout, base.Episode.Ceiling = m(s.CheckinTimeoutMin), m(s.CeilingMin)
	return base
}

func (s Settings) Validate() error {
	for name, v := range map[string]int{"urgent_low": s.UrgentLow, "low": s.Low, "high": s.High, "urgent_high": s.UrgentHigh} {
		if v < 40 || v > 400 {
			return fmt.Errorf("%s=%d: must be 40..400 mg/dL", name, v)
		}
	}
	if !(s.UrgentLow < s.Low && s.Low < s.High && s.High < s.UrgentHigh) {
		return fmt.Errorf("need urgent_low < low < high < urgent_high, got %d < %d < %d < %d", s.UrgentLow, s.Low, s.High, s.UrgentHigh)
	}
	ranges := []struct {
		name      string
		v, lo, hi int
	}{
		{"high_for_min", s.HighForMin, 0, 240},
		{"repeat_min", s.RepeatMin, 1, 60},
		{"worse_by", s.WorseBy, 1, 100},
		{"checkin_first_min", s.CheckinFirstMin, 5, 240},
		{"checkin_every_min", s.CheckinEveryMin, 5, 240},
		{"checkin_timeout_min", s.CheckinTimeoutMin, 1, 60},
		{"ceiling_min", s.CeilingMin, 10, 480},
	}
	for _, r := range ranges {
		if r.v < r.lo || r.v > r.hi {
			return fmt.Errorf("%s=%d: must be %d..%d", r.name, r.v, r.lo, r.hi)
		}
	}
	return nil
}

// LoadRules returns the stored household rules, or base when none are stored.
func LoadRules(ctx context.Context, s *store.Store, base Rules) (Rules, error) {
	raw, err := s.Setting(ctx, settingsKey)
	if errors.Is(err, store.ErrNotFound) {
		return base, nil
	}
	if err != nil {
		return base, err
	}
	st := SettingsOf(base)
	if err := json.Unmarshal(raw, &st); err != nil {
		return base, fmt.Errorf("stored alarm rules: %w", err)
	}
	if err := st.Validate(); err != nil {
		return base, fmt.Errorf("stored alarm rules: %w", err)
	}
	return st.Apply(base), nil
}

func SaveSettings(ctx context.Context, s *store.Store, st Settings, now time.Time) error {
	if err := st.Validate(); err != nil {
		return err
	}
	raw, _ := json.Marshal(st)
	return s.SetSetting(ctx, settingsKey, raw, now)
}

// ParseAssignments applies "key=value" pairs (CLI syntax) onto st.
func ParseAssignments(st Settings, args []string) (Settings, error) {
	ints := map[string]*int{"urgent_low": &st.UrgentLow, "low": &st.Low, "high": &st.High, "urgent_high": &st.UrgentHigh,
		"high_for_min": &st.HighForMin, "repeat_min": &st.RepeatMin, "worse_by": &st.WorseBy,
		"checkin_first_min": &st.CheckinFirstMin, "checkin_every_min": &st.CheckinEveryMin,
		"checkin_timeout_min": &st.CheckinTimeoutMin, "ceiling_min": &st.CeilingMin}
	bools := map[string]*bool{"predicted_low": &st.PredictedLow, "predicted_high": &st.PredictedHigh}
	for _, a := range args {
		k, v, ok := strings.Cut(a, "=")
		if !ok {
			return st, fmt.Errorf("%q: want key=value", a)
		}
		if p, ok := ints[k]; ok {
			n, err := strconv.Atoi(v)
			if err != nil {
				return st, fmt.Errorf("%s: %q is not a number", k, v)
			}
			*p = n
			continue
		}
		if p, ok := bools[k]; ok {
			switch strings.ToLower(v) {
			case "on", "true", "yes", "1":
				*p = true
			case "off", "false", "no", "0":
				*p = false
			default:
				return st, fmt.Errorf("%s: %q is not on/off", k, v)
			}
			continue
		}
		keys := make([]string, 0, len(ints)+len(bools))
		for k := range ints {
			keys = append(keys, k)
		}
		for k := range bools {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		return st, fmt.Errorf("unknown setting %q (known: %s)", k, strings.Join(keys, ", "))
	}
	return st, st.Validate()
}
