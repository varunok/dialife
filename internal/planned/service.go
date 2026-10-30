package planned

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"dialife/internal/store"
)

var (
	ErrNotFound = errors.New("no such planned dose")
	ErrClosed   = errors.New("the planned dose was already given, skipped or expired")
)

// Service keeps the planned doses (the planned_doses table, shared with the
// questions engine, so its SQL stays in internal/store).
type Service struct {
	Store    *store.Store
	Now      func() time.Time
	OnChange func(context.Context)                      // every phone gets the new state
	Settle   func(ctx context.Context, id string) error // the "was it given?" question goes with the dose
}

// List is the open doses, oldest first, with who worked each out.
func (s *Service) List(ctx context.Context, devs []store.Device) ([]Dose, error) {
	ps, err := s.Store.PlannedDoses(ctx)
	if err != nil {
		return nil, err
	}
	names := make(map[int64]string, len(devs))
	for _, d := range devs {
		names[d.ID] = d.Person
	}
	ds := make([]Dose, 0, len(ps))
	for _, p := range ps {
		d := doseOf(p)
		d.By = names[p.DeviceID]
		ds = append(ds, d)
	}
	return Open(ds, s.Now()), nil
}

// Save makes a planned dose or changes it while it is still planned.
func (s *Service) Save(ctx context.Context, d Dose, deviceID int64) error {
	err := s.Store.SavePlannedDose(ctx, store.PlannedDose{
		ID: d.ID, MealID: d.MealID, Dose: d.Dose, Food: d.Food, Corr: d.Corr, Carbs: d.Carbs,
		Meter: d.Meter, MeterAt: d.MeterAt, CreatedAt: d.CreatedAt, DeviceID: deviceID,
	}, s.Now().UnixMilli())
	if errors.Is(err, store.ErrClosedDose) {
		return ErrClosed
	}
	if err != nil {
		return err
	}
	s.changed(ctx)
	return nil
}

// Close moves a dose to "given" (with its insulin entry) or "skipped". An
// already closed dose comes back as it is: two phones may confirm at once.
func (s *Service) Close(ctx context.Context, id, state, insulinID string, deviceID int64) (Dose, bool, error) {
	if _, err := s.Store.PlannedDose(ctx, id); errors.Is(err, store.ErrNotFound) {
		return Dose{}, false, ErrNotFound
	} else if err != nil {
		return Dose{}, false, err
	}
	closed, err := s.Store.ClosePlannedDose(ctx, id, state, insulinID, &deviceID, s.Now().UnixMilli())
	if err != nil {
		return Dose{}, false, err
	}
	if closed {
		if s.Settle != nil {
			if err := s.Settle(ctx, id); err != nil {
				slog.Error("planned question", "err", err)
			}
		}
		s.changed(ctx)
	}
	p, err := s.Store.PlannedDose(ctx, id)
	if err != nil {
		return Dose{}, false, err
	}
	return doseOf(p), closed, nil
}

func (s *Service) changed(ctx context.Context) {
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
}

func doseOf(p store.PlannedDose) Dose {
	return Dose{ID: p.ID, MealID: p.MealID, Dose: p.Dose, Food: p.Food, Corr: p.Corr, Carbs: p.Carbs,
		Meter: p.Meter, MeterAt: p.MeterAt, CreatedAt: p.CreatedAt, DeviceID: p.DeviceID, State: p.State}
}
