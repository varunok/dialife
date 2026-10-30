// Package planned is a rapid dose worked out for a meal and not given yet: it
// counts for nothing (no double-dose guard, no stats, no wait) until a parent
// confirms the injection ("given"), says it was not given ("skipped"), or
// Expire passes. Every phone sees it.
package planned

import (
	"strings"
	"time"
)

const (
	Expire = 3 * time.Hour // closed as "expired" after this; the questions engine asks before
	ahead  = time.Hour     // a phone's clock may run this far ahead of the server's
)

// Dose is a planned dose as the phones see and send it.
type Dose struct {
	ID        string  `json:"id"`
	MealID    string  `json:"meal_id"` // the food entry it is for
	Dose      float64 `json:"dose"`
	Food      float64 `json:"food"` // the meal's part, before rounding
	Corr      float64 `json:"corr"` // the correction's part
	Carbs     int     `json:"carbs"`
	Meter     *int    `json:"meter"`    // the glucometer value the correction used
	MeterAt   *int64  `json:"meter_at"` // and when it was measured
	CreatedAt int64   `json:"created_at"`
	By        string  `json:"by"` // who worked it out
	DeviceID  int64   `json:"device_id"`
	State     string  `json:"state"` // "planned" | "given" | "skipped" | "expired"
}

// Normalize trims what a phone sent; false when it cannot be a planned dose.
func (d *Dose) Normalize(now time.Time) bool {
	d.ID, d.MealID = strings.TrimSpace(d.ID), strings.TrimSpace(d.MealID)
	return d.ID != "" && len(d.ID) <= 64 && d.MealID != "" && len(d.MealID) <= 64 &&
		d.Dose >= 0 && d.Dose <= 100 && d.Carbs >= 0 && d.Carbs <= 1000 &&
		d.CreatedAt > 0 && d.CreatedAt <= now.Add(ahead).UnixMilli()
}

// Open are the doses still to be given or skipped: past Expire they are as
// good as closed (the questions engine closes them within a minute).
func Open(ds []Dose, now time.Time) []Dose {
	out := []Dose{}
	for _, d := range ds {
		if d.CreatedAt >= now.Add(-Expire).UnixMilli() {
			out = append(out, d)
		}
	}
	return out
}
