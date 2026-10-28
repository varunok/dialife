package store

import (
	"context"
	"database/sql"
	"errors"
)

// PlannedDose is rapid insulin worked out for a meal and not yet confirmed as
// given; until then it counts for nothing (no double-dose guard, no stats).
type PlannedDose struct {
	ID        string
	MealID    string // the food entry it is for
	Dose      float64
	Food      float64 // the meal's part, before rounding
	Corr      float64 // the correction's part
	Carbs     int
	Meter     *int   // the glucometer value the correction used
	MeterAt   *int64 // and when it was measured
	CreatedAt int64
	DeviceID  int64
	State     string // "planned" | "given" | "skipped" | "expired"
	InsulinID string // "given": the insulin entry
	ClosedAt  *int64
	ClosedBy  *int64
}

const plannedCols = `id, meal_id, dose, food, corr, carbs, meter, meter_at, created_at, device_id, state, coalesce(insulin_id, ''), closed_at, closed_by`

func scanPlanned(sc scanner) (PlannedDose, error) {
	var (
		p                        PlannedDose
		meter, meterAt, closedAt sql.NullInt64
		closedBy                 sql.NullInt64
	)
	err := sc.Scan(&p.ID, &p.MealID, &p.Dose, &p.Food, &p.Corr, &p.Carbs, &meter, &meterAt, &p.CreatedAt, &p.DeviceID, &p.State, &p.InsulinID, &closedAt, &closedBy)
	if errors.Is(err, sql.ErrNoRows) {
		return PlannedDose{}, ErrNotFound
	}
	if err != nil {
		return PlannedDose{}, err
	}
	if meter.Valid {
		v := int(meter.Int64)
		p.Meter = &v
	}
	if meterAt.Valid {
		p.MeterAt = &meterAt.Int64
	}
	if closedAt.Valid {
		p.ClosedAt = &closedAt.Int64
	}
	if closedBy.Valid {
		p.ClosedBy = &closedBy.Int64
	}
	return p, nil
}

// ErrClosedDose: the dose was already given, skipped or expired.
var ErrClosedDose = errors.New("planned dose is closed")

// SavePlannedDose creates a planned dose or changes one still planned.
func (s *Store) SavePlannedDose(ctx context.Context, p PlannedDose, now int64) error {
	res, err := s.w.ExecContext(ctx,
		`INSERT INTO planned_doses (id, meal_id, dose, food, corr, carbs, meter, meter_at, created_at, device_id, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO UPDATE SET meal_id = excluded.meal_id, dose = excluded.dose, food = excluded.food, corr = excluded.corr,
		   carbs = excluded.carbs, meter = excluded.meter, meter_at = excluded.meter_at, updated_at = excluded.updated_at
		 WHERE planned_doses.state = 'planned'`,
		p.ID, p.MealID, p.Dose, p.Food, p.Corr, p.Carbs, p.Meter, p.MeterAt, p.CreatedAt, p.DeviceID, now)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrClosedDose
	}
	return nil
}

func (s *Store) PlannedDose(ctx context.Context, id string) (PlannedDose, error) {
	return scanPlanned(s.r.QueryRowContext(ctx, `SELECT `+plannedCols+` FROM planned_doses WHERE id = ?`, id))
}

// ClosePlannedDose moves a planned dose to "given", "skipped" or "expired";
// false when it was no longer planned (or does not exist).
func (s *Store) ClosePlannedDose(ctx context.Context, id, state, insulinID string, by *int64, now int64) (bool, error) {
	var ins any
	if insulinID != "" {
		ins = insulinID
	}
	res, err := s.w.ExecContext(ctx,
		`UPDATE planned_doses SET state = ?, insulin_id = ?, closed_at = ?, closed_by = ?, updated_at = ?
		 WHERE id = ? AND state = 'planned'`, state, ins, now, by, now, id)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// PlannedDoses returns the doses still planned, oldest first.
func (s *Store) PlannedDoses(ctx context.Context) ([]PlannedDose, error) {
	return s.queryPlanned(ctx, `SELECT `+plannedCols+` FROM planned_doses WHERE state = 'planned' ORDER BY created_at, id`)
}

// PlannedDosesSince returns every planned dose made since then, whatever became of it.
func (s *Store) PlannedDosesSince(ctx context.Context, since int64) ([]PlannedDose, error) {
	return s.queryPlanned(ctx, `SELECT `+plannedCols+` FROM planned_doses WHERE created_at >= ? ORDER BY created_at, id`, since)
}

// ExpirePlannedDoses closes the planned doses made before then; it returns them.
func (s *Store) ExpirePlannedDoses(ctx context.Context, before, now int64) ([]PlannedDose, error) {
	old, err := s.queryPlanned(ctx, `SELECT `+plannedCols+` FROM planned_doses WHERE state = 'planned' AND created_at < ? ORDER BY created_at`, before)
	if err != nil {
		return nil, err
	}
	var out []PlannedDose
	for _, p := range old {
		ok, err := s.ClosePlannedDose(ctx, p.ID, "expired", "", nil, now)
		if err != nil {
			return out, err
		}
		if ok {
			p.State = "expired"
			out = append(out, p)
		}
	}
	return out, nil
}

func (s *Store) queryPlanned(ctx context.Context, q string, args ...any) ([]PlannedDose, error) {
	rows, err := s.r.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PlannedDose
	for rows.Next() {
		p, err := scanPlanned(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
