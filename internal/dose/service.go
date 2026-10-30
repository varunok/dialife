package dose

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"slices"
	"time"

	"dialife/internal/store"
)

const settingKey = "dose" // in the settings table

// Service keeps the dose settings.
type Service struct {
	Store    *store.Store
	Now      func() time.Time
	OnChange func(context.Context) // every phone gets the new state
}

// Get is the settings now; the defaults when none were saved or they are unreadable.
func (s *Service) Get(ctx context.Context) Settings {
	raw, err := s.Store.Setting(ctx, settingKey)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			slog.Error("dose settings", "err", err)
		}
		return Defaults
	}
	var d Settings
	err = json.Unmarshal(raw, &d)
	d = d.WithDefaults()
	if err != nil || d.Valid() != nil {
		slog.Error("dose settings: stored value unreadable, using defaults")
		return Defaults
	}
	return d
}

// Put saves settings a parent changed (by), with the table in order.
func (s *Service) Put(ctx context.Context, d Settings, by string) (Settings, error) {
	d = d.WithDefaults()
	if err := d.Valid(); err != nil {
		return d, err
	}
	slices.SortFunc(d.Table, func(a, b Step) int { return a.MG - b.MG })
	if d.Table == nil {
		d.Table = []Step{}
	}
	now := s.Now()
	d.UpdatedAt, d.UpdatedBy = now.UnixMilli(), by
	raw, err := json.Marshal(d)
	if err != nil {
		return d, err
	}
	if err := s.Store.SetSetting(ctx, settingKey, raw, now); err != nil {
		return d, err
	}
	slog.Info("dose settings", "by", by)
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
	return d, nil
}
