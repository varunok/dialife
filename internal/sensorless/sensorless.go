// Package sensorless is the "no sensor" mode: while the family waits for new
// sensors nothing sends readings, so nothing alarms. Food, insulin and
// glucometer checks are logged as always. The mode is the family's, on every
// phone: a period on the server, shown on the chart for good.
//
// While it is on: no alarm of any kind and no "did you forget to log?"; a
// glucometer check out of range shows a bright card everywhere (no sound);
// after so long without a check every phone is reminded once to measure. Two
// sensor readings close together end it by themselves: the alarms are back.
package sensorless

import (
	"errors"

	"dialife/internal/store"
)

const (
	// EventType is the SSE event: the mode went on or off, a reminder is due or over.
	EventType = "sensorless"

	// two readings at most this far apart end the mode: a new sensor is sending
	backWithin = 15 * 60 * 1000 // ms

	// one check often arrives twice (BG check + xDrip+'s meter entry)
	sameCheck = 15 * 60 * 1000 // ms
)

var (
	ErrRange         = errors.New("remind_min must be 0 (off) or 60..480 in steps of 30")
	ErrSensorSending = errors.New("the sensor is sending readings")
	ErrNoCheck       = errors.New("no such glucometer check")
)

// Period is one time the mode was on: [From, Until); Until 0 while it lasts.
type Period struct {
	From    int64  `json:"from"`
	Until   int64  `json:"until,omitempty"`
	By      string `json:"by,omitempty"`       // who turned it on
	EndedBy string `json:"ended_by,omitempty"` // who turned it off; empty when the sensor did
	End     string `json:"end,omitempty"`      // "manual" | "sensor"
}

// Settings are the family's: how long without a glucometer check before the reminder.
type Settings struct {
	RemindMin int    `json:"remind_min"` // 0: no reminder
	UpdatedAt int64  `json:"updated_at,omitempty"`
	UpdatedBy string `json:"updated_by,omitempty"`
}

var Defaults = Settings{RemindMin: 180}

func (s Settings) Valid() error {
	if s.RemindMin == 0 || (s.RemindMin >= 60 && s.RemindMin <= 480 && s.RemindMin%30 == 0) {
		return nil
	}
	return ErrRange
}

// Check is a glucometer reading, mg/dL.
type Check struct {
	T  int64 `json:"t"`
	MG int   `json:"mg"`
}

// Card is a check out of range while the mode is on: "low" or "high".
type Card struct {
	Check
	Kind string `json:"kind"`
}

// State is what the phones show while the mode is on.
type State struct {
	Since     int64  `json:"since"`
	By        string `json:"by,omitempty"`
	LastCheck *Check `json:"last_check,omitempty"` // the newest glucometer check, also from before the mode
	RemindAt  int64  `json:"remind_at,omitempty"`  // when the reminder is due; 0: reminder off
	RemindDue bool   `json:"remind_due,omitempty"`
	Card      *Card  `json:"card,omitempty"` // the newest check is out of range and nobody closed it
}

// Event is the SSE body of EventType.
type Event struct {
	Kind      string `json:"kind"` // "on" | "off" | "remind" | "remind_over"
	Period    Period `json:"period"`
	LastCheck *Check `json:"last_check,omitempty"`
	RemindMin int    `json:"remind_min,omitempty"`
}

// RemindAt is when the reminder is due: remindMin after the newest check, or
// after the mode began when there was none since; 0 when the reminder is off.
func RemindAt(p Period, last *Check, remindMin int) int64 {
	if remindMin <= 0 {
		return 0
	}
	base := p.From
	if last != nil && last.T > base {
		base = last.T
	}
	return base + int64(remindMin)*60*1000
}

// CardFor is the card for the newest check: only one taken in the mode, below
// low or above high.
func CardFor(last *Check, p Period, low, high int) *Card {
	if last == nil || last.T < p.From {
		return nil
	}
	switch {
	case last.MG < low:
		return &Card{Check: *last, Kind: "low"}
	case last.MG > high:
		return &Card{Check: *last, Kind: "high"}
	}
	return nil
}

// SensorBack reports two valid readings (oldest first) taken from on, at
// most backWithin apart: a sensor is sending again.
func SensorBack(es []store.Entry, from int64, maxError int) bool {
	prev := int64(-1)
	for _, e := range es {
		if e.Date < from || e.SGV == nil || *e.SGV <= maxError {
			continue
		}
		if prev >= 0 && e.Date-prev <= backWithin {
			return true
		}
		prev = e.Date
	}
	return false
}

// sameAs: two checks that are one reading arriving twice.
func sameAs(a, b Check) bool {
	dt, dv := a.T-b.T, a.MG-b.MG
	return dt <= sameCheck && dt >= -sameCheck && dv <= 1 && dv >= -1
}
