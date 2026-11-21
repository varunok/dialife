package sensorless

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"time"

	"dialife/internal/bus"
	"dialife/internal/config"
	"dialife/internal/glucose"
	"dialife/internal/store"
)

const settingKey = "sensorless" // in the settings table

// Service keeps the mode, its reminder and the out-of-range card.
type Service struct {
	Store      *store.Store
	Bus        *bus.Bus
	Now        func() time.Time
	Thresholds func(context.Context) config.Thresholds // the alarms' low and high: what a check is out of range by
	// the mode went on or off, a reminder came or went, a card closed: the
	// alarms look again and every phone gets the new state; nil: nothing
	OnChange func(context.Context)

	mu sync.Mutex // changes run one at a time
}

// On reports whether the mode is on. A failure to read says no: the alarms
// rather ring than stay silent by mistake.
func (s *Service) On(ctx context.Context) bool {
	if s == nil {
		return false
	}
	_, err := s.open(ctx)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		slog.Error("sensorless", "err", err)
	}
	return err == nil
}

// Between is every period overlapping [from, to], oldest first; a nil service has none.
func (s *Service) Between(ctx context.Context, from, to int64) ([]Period, error) {
	if s == nil {
		return []Period{}, nil
	}
	return s.between(ctx, from, to)
}

// Settings are the family's, or the defaults when none were saved or they are unreadable.
func (s *Service) Settings(ctx context.Context) Settings {
	if s == nil {
		return Defaults
	}
	raw, err := s.Store.Setting(ctx, settingKey)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			slog.Error("sensorless settings", "err", err)
		}
		return Defaults
	}
	var st Settings
	if json.Unmarshal(raw, &st) != nil || st.Valid() != nil {
		slog.Error("sensorless settings: stored value unreadable, using defaults")
		return Defaults
	}
	return st
}

// PutSettings saves what a parent changed.
func (s *Service) PutSettings(ctx context.Context, st Settings, by store.Device) (Settings, error) {
	if err := st.Valid(); err != nil {
		return st, err
	}
	now := s.Now()
	st.UpdatedAt, st.UpdatedBy = now.UnixMilli(), name(by)
	raw, err := json.Marshal(st)
	if err != nil {
		return st, err
	}
	if err := s.Store.SetSetting(ctx, settingKey, raw, now); err != nil {
		return st, err
	}
	slog.Info("sensorless settings", "remind_min", st.RemindMin, "by", st.UpdatedBy)
	s.Tick(ctx) // a shorter reminder may be due at once
	s.changed(ctx)
	return st, nil
}

// State is what the phones show; nil while the mode is off.
func (s *Service) State(ctx context.Context) (*State, error) {
	if s == nil {
		return nil, nil
	}
	r, err := s.open(ctx)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	now := s.Now()
	last, err := s.lastCheck(ctx, now)
	if err != nil {
		return nil, err
	}
	st := &State{Since: r.Period.From, By: r.Period.By, LastCheck: last}
	st.RemindAt = RemindAt(r.Period, last, s.Settings(ctx).RemindMin)
	st.RemindDue = st.RemindAt != 0 && now.UnixMilli() >= st.RemindAt
	th := s.Thresholds(ctx)
	if c := CardFor(last, r.Period, th.Low, th.High); c != nil {
		closed, err := s.checkClosed(ctx, c.Check)
		if err != nil {
			return nil, err
		}
		if !closed {
			st.Card = c
		}
	}
	return st, nil
}

// Start turns the mode on for every phone. Refused while a sensor is
// sending: there is something to alarm about. On already: nothing changes.
func (s *Service) Start(ctx context.Context, by store.Device) error {
	s.mu.Lock()
	now := s.Now()
	if _, err := s.open(ctx); err == nil {
		s.mu.Unlock()
		return nil
	} else if !errors.Is(err, store.ErrNotFound) {
		s.mu.Unlock()
		return err
	}
	from := now.Add(-backWithin * time.Millisecond).UnixMilli()
	back, err := s.sensorBack(ctx, from)
	if err == nil && back {
		err = ErrSensorSending
	}
	if err == nil {
		err = s.insert(ctx, now.UnixMilli(), by)
	}
	s.mu.Unlock()
	if err != nil {
		return err
	}
	slog.Warn("sensorless on: no alarms", "by", name(by))
	s.publish(Event{Kind: "on", Period: Period{From: now.UnixMilli(), By: name(by)}})
	s.changed(ctx)
	return nil
}

// Stop turns the mode off by hand; off already: nothing changes.
func (s *Service) Stop(ctx context.Context, by store.Device) error {
	s.mu.Lock()
	r, err := s.open(ctx)
	if errors.Is(err, store.ErrNotFound) {
		s.mu.Unlock()
		return nil
	}
	now := s.Now().UnixMilli()
	if err == nil {
		err = s.end(ctx, r.ID, now, by, "manual")
	}
	s.mu.Unlock()
	if err != nil {
		return err
	}
	r.Period.Until, r.Period.EndedBy, r.Period.End = now, name(by), "manual"
	slog.Warn("sensorless off: alarms back", "by", name(by))
	s.publish(Event{Kind: "off", Period: r.Period})
	s.changed(ctx)
	return nil
}

// CloseCheck closes the card of the check taken at t for every phone.
func (s *Service) CloseCheck(ctx context.Context, t int64, by store.Device) error {
	s.mu.Lock()
	err := s.closeCard(ctx, t, by)
	s.mu.Unlock()
	if err != nil {
		return err
	}
	slog.Info("sensorless card closed", "t", t, "by", name(by))
	s.changed(ctx)
	return nil
}

func (s *Service) closeCard(ctx context.Context, t int64, by store.Device) error {
	if _, err := s.open(ctx); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return ErrNoCheck
		}
		return err
	}
	last, err := s.lastCheck(ctx, s.Now())
	if err != nil {
		return err
	}
	if last == nil || last.T != t {
		return ErrNoCheck
	}
	return s.closeCheck(ctx, *last, s.Now().UnixMilli(), by)
}

// Tick ends the mode when a sensor sends again, and brings or takes away the
// reminder to measure. Run calls it every minute; new data calls it at once.
func (s *Service) Tick(ctx context.Context) {
	if s == nil {
		return
	}
	s.mu.Lock()
	ev, err := s.tick(ctx)
	s.mu.Unlock()
	if err != nil {
		slog.Error("sensorless tick", "err", err)
		return
	}
	if ev == nil {
		return
	}
	s.publish(*ev)
	s.changed(ctx)
}

func (s *Service) tick(ctx context.Context) (*Event, error) {
	r, err := s.open(ctx)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	now := s.Now()
	ms := now.UnixMilli()
	back, err := s.sensorBack(ctx, r.Period.From)
	if err != nil {
		return nil, err
	}
	if back {
		if err := s.end(ctx, r.ID, ms, store.Device{}, "sensor"); err != nil {
			return nil, err
		}
		r.Period.Until, r.Period.End = ms, "sensor"
		slog.Warn("sensorless off: the sensor sends again, alarms back")
		return &Event{Kind: "off", Period: r.Period}, nil
	}

	last, err := s.lastCheck(ctx, now)
	if err != nil {
		return nil, err
	}
	remind := s.Settings(ctx).RemindMin
	due := RemindAt(r.Period, last, remind)
	switch overdue := due != 0 && ms >= due; {
	case overdue && (r.RemindOpen == nil || *r.RemindOpen != due):
		if err := s.setRemindOpen(ctx, r.ID, &due); err != nil {
			return nil, err
		}
		slog.Info("sensorless reminder: measure with the glucometer", "remind_min", remind)
		return &Event{Kind: "remind", Period: r.Period, LastCheck: last, RemindMin: remind}, nil
	case !overdue && r.RemindOpen != nil:
		if err := s.setRemindOpen(ctx, r.ID, nil); err != nil {
			return nil, err
		}
		return &Event{Kind: "remind_over", Period: r.Period, LastCheck: last}, nil
	}
	return nil, nil
}

// Run ticks once a minute until ctx ends: the reminder comes without new data.
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		s.Tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// sensorBack: two valid readings close together, taken from from on.
func (s *Service) sensorBack(ctx context.Context, from int64) (bool, error) {
	to := s.Now().Add(5 * time.Minute).UnixMilli() // a phone's clock a little ahead
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: 50, Asc: true})
	if err != nil {
		return false, err
	}
	return SensorBack(es, from, glucose.MaxErrorCode), nil
}

func (s *Service) publish(ev Event) {
	if s.Bus != nil {
		s.Bus.Publish(EventType, ev, s.Now())
	}
}

func (s *Service) changed(ctx context.Context) {
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
}

func name(d store.Device) string {
	if d.Person != "" {
		return d.Person
	}
	return d.Name
}
