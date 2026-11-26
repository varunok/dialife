package calib

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"slices"
	"sync"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/insulin"
	"dialife/internal/meter"
	"dialife/internal/store"
	"dialife/internal/trust"
)

const (
	settingKey = "calibration_closed" // in the settings table
	lagEvery   = time.Hour            // the delay is measured again this often
	startsBack = 30 * 24 * time.Hour  // sensor starts and calibrations are looked for this far back
)

// Service works the advice out for /api/state and keeps who closed it.
type Service struct {
	Store      *store.Store
	Now        func() time.Time
	Trust      *trust.Service                      // the sensor not believed (a shower); nil: always believed
	Sensorless func(context.Context) bool          // the "no sensor" mode; nil: never
	AbsorbFor  func(context.Context) time.Duration // how long food and rapid insulin keep glucose moving
	OnChange   func(context.Context)               // closed: every phone gets the new state

	mu    sync.Mutex
	lag   time.Duration
	lagAt time.Time
}

// closed is a parent's "close": for the stretch Since, until a group newer than At.
type closed struct {
	Since int64  `json:"since"`
	At    int64  `json:"at"`
	By    string `json:"by,omitempty"`
}

var ErrNothing = errors.New("no calibration advice to close")

// State is the advice the phones show; nil: none.
func (s *Service) State(ctx context.Context) (*Advice, error) {
	if s == nil {
		return nil, nil
	}
	in, err := s.input(ctx)
	if err != nil {
		return nil, err
	}
	a := Decide(in)
	if a == nil {
		return nil, nil
	}
	if c, ok := s.closed(ctx); ok && c.Since == a.Since && a.Groups[len(a.Groups)-1].T <= c.At {
		return nil, nil
	}
	return a, nil
}

// Close hides the advice on every phone until a newer check is off again.
func (s *Service) Close(ctx context.Context, by store.Device) error {
	a, err := s.State(ctx)
	if err != nil {
		return err
	}
	if a == nil {
		return ErrNothing
	}
	now := s.Now()
	raw, _ := json.Marshal(closed{Since: a.Since, At: now.UnixMilli(), By: by.Person})
	if err := s.Store.SetSetting(ctx, settingKey, raw, now); err != nil {
		return err
	}
	slog.Info("calibration advice closed", "by", by.Person)
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
	return nil
}

func (s *Service) closed(ctx context.Context) (closed, bool) {
	raw, err := s.Store.Setting(ctx, settingKey)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			slog.Error("calibration closed", "err", err)
		}
		return closed{}, false
	}
	var c closed
	return c, json.Unmarshal(raw, &c) == nil
}

// input reads the current stretch only: since the newest sensor start or calibration.
func (s *Service) input(ctx context.Context) (Input, error) {
	now := s.Now()
	ms := now.UnixMilli()
	in := Input{Now: ms, Lag: s.delay(ctx)}
	back := now.Add(-startsBack).UnixMilli()
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &back, Lte: &ms, Count: 5000})
	if err != nil {
		return in, err
	}
	for _, t := range ts {
		if t.EventType == "Sensor Start" || t.EventType == "Sensor Change" {
			in.SensorStart = max(in.SensorStart, t.CreatedAt)
		}
	}
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Gte: &back, Lte: &ms, Count: 5000, Asc: true})
	if err != nil {
		return in, err
	}
	in.Since = in.SensorStart
	for _, e := range es {
		if meter.IsCalibration(e.Device) && e.Date >= in.SensorStart {
			in.Since = max(in.Since, e.Date)
		}
	}
	if in.Since == 0 {
		in.Since = back
	}

	// the stretch's checks, without repeats or checks after a shower
	var checks []meter.Point
	var untrusted []trust.Window
	for _, e := range es {
		if e.Date >= in.Since && !meter.IsCalibration(e.Device) && e.MBG != nil && *e.MBG > 0 {
			checks = append(checks, meter.Point{T: e.Date, V: *e.MBG})
		}
	}
	absorb := 3 * time.Hour
	if s.AbsorbFor != nil {
		absorb = s.AbsorbFor(ctx)
	}
	in.AbsorbFor = absorb
	for _, t := range ts {
		if w, ok := trust.Of(t); ok {
			untrusted = append(untrusted, w)
		}
		if t.CreatedAt >= in.Since {
			if v := meter.CheckOf(t.Raw); v != nil {
				checks = append(checks, meter.Point{T: t.CreatedAt, V: *v})
			}
		}
		if t.CreatedAt < ms-absorb.Milliseconds()-time.Hour.Milliseconds() {
			continue
		}
		if t.Carbs != nil && *t.Carbs > 0 {
			in.Carbs = append(in.Carbs, t.CreatedAt)
		}
		if t.Insulin != nil && *t.Insulin > 0 {
			if typ, _ := insulin.Kind(t.Raw); typ != "basal" {
				in.Rapid = append(in.Rapid, t.CreatedAt)
			}
		}
	}
	for _, c := range trust.Drop(meter.Dedup(checks), func(p meter.Point) int64 { return p.T }, untrusted) {
		in.Checks = append(in.Checks, Point{T: c.T, MG: c.V})
	}

	// the sensor over the stretch, from 15 minutes before it
	from := in.Since - steadyIn.Milliseconds()
	ss, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &ms, Count: 20000, Asc: true})
	if err != nil {
		return in, err
	}
	for _, e := range ss {
		switch {
		case e.SGV == nil:
		case *e.SGV > glucose.MaxErrorCode:
			in.SGV = append(in.SGV, Point{T: e.Date, MG: *e.SGV})
		case *e.SGV == 39: // xDrip+'s Dexcom LOW
			in.Lows = append(in.Lows, e.Date)
		}
	}
	slices.SortFunc(in.Checks, func(a, b Point) int { return cmp.Compare(a.T, b.T) })

	if w, err := s.Trust.Active(ctx, now); err == nil && w != nil {
		in.Untrusted = true
	}
	if s.Sensorless != nil {
		in.Sensorless = s.Sensorless(ctx)
	}
	return in, nil
}

// delay is the sensor's delay the comparison uses, measured again once an hour.
func (s *Service) delay(ctx context.Context) time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.lagAt.IsZero() && s.Now().Sub(s.lagAt) < lagEvery {
		return s.lag
	}
	d, err := meter.Load(ctx, s.Store)
	if err != nil {
		slog.Error("calibration delay", "err", err)
		return time.Duration(meter.PublishedLag) * time.Minute
	}
	s.lag, s.lagAt = meter.MeasureLag(d.Checks, d.SGV, d.Bounds()).Duration(), s.Now()
	return s.lag
}
