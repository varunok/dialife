// Package trust knows when the sensor is not to be believed: during a shower
// and for a while after it, warm water moves a CGM's numbers either way. In
// such a window the phones say "trust only the glucometer", glucometer checks
// stay out of the sensor-vs-meter model, the sensor's readings stay out of the
// forecasts' scores and of "did you forget to log?". The alarms are untouched.
package trust

import (
	"context"
	"encoding/json"
	"math"
	"time"

	"dialife/internal/store"
)

const (
	// Shower is the event type of a shower entry; its duration is in minutes.
	Shower = "Shower"
	// After is how long after a shower the sensor stays untrusted.
	After = 30 * time.Minute
	// MaxShower bounds a shower's duration, so a typo cannot blind the sensor for a day.
	MaxShower = 120 * time.Minute
	// DefaultShower is assumed when an entry carries no duration.
	DefaultShower = 10 * time.Minute
	maxRows       = 1000
)

// Window is a time the sensor is not trusted: [From, Until), unix ms.
type Window struct {
	From   int64  `json:"from"`
	Until  int64  `json:"until"`
	Reason string `json:"reason"` // "shower"
}

// Of is the window a shower entry opens; false for any other entry.
func Of(t store.Treatment) (Window, bool) {
	if t.EventType != Shower {
		return Window{}, false
	}
	d := DefaultShower
	var doc struct {
		Duration *float64 `json:"duration"`
	}
	if json.Unmarshal(t.Raw, &doc) == nil && doc.Duration != nil && *doc.Duration > 0 {
		d = time.Duration(math.Round(*doc.Duration)) * time.Minute
	}
	d = min(d, MaxShower)
	return Window{From: t.CreatedAt, Until: t.CreatedAt + (d + After).Milliseconds(), Reason: "shower"}, true
}

// Inside reports whether t (unix ms) falls in any of ws.
func Inside(ws []Window, t int64) bool {
	for _, w := range ws {
		if t >= w.From && t < w.Until {
			return true
		}
	}
	return false
}

// Drop is ps without the ones whose time falls in a window.
func Drop[P any](ps []P, at func(P) int64, ws []Window) []P {
	if len(ws) == 0 {
		return ps
	}
	out := make([]P, 0, len(ps))
	for _, p := range ps {
		if !Inside(ws, at(p)) {
			out = append(out, p)
		}
	}
	return out
}

// Service reads the windows from the logged entries.
type Service struct {
	Store *store.Store
}

// Between is every window that overlaps [from, to], oldest first. A nil
// service has none.
func (s *Service) Between(ctx context.Context, from, to int64) ([]Window, error) {
	if s == nil || s.Store == nil {
		return nil, nil
	}
	gte := from - (MaxShower + After).Milliseconds()
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{EventType: Shower, Gte: &gte, Lte: &to, Count: maxRows})
	if err != nil {
		return nil, err
	}
	out := []Window{}
	for i := len(ts) - 1; i >= 0; i-- { // newest first from the store
		if w, ok := Of(ts[i]); ok && w.Until > from {
			out = append(out, w)
		}
	}
	return out, nil
}

// Active is the window now falls in, the one lasting longest when they overlap; nil: the sensor is trusted.
func (s *Service) Active(ctx context.Context, now time.Time) (*Window, error) {
	ws, err := s.Between(ctx, now.UnixMilli(), now.UnixMilli())
	if err != nil {
		return nil, err
	}
	var best *Window
	for i := range ws {
		if Inside(ws[i:i+1], now.UnixMilli()) && (best == nil || ws[i].Until > best.Until) {
			best = &ws[i]
		}
	}
	return best, nil
}
