package sensorless

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"dialife/internal/meter"
	"dialife/internal/store"
)

// The mode's own tables (migration 00020): sensorless, sensorless_check_closed.

const (
	periodCols = `id, started_at, started_by_name, ended_at, ended_by_name, COALESCE(end_reason, ''), remind_open`
	// the newest check is looked for this far back
	checksBack = 7 * 24 * time.Hour
)

type row struct {
	ID         int64
	Period     Period
	RemindOpen *int64
}

func scanRow(sc interface{ Scan(...any) error }) (row, error) {
	var (
		r     row
		until sql.NullInt64
		open  sql.NullInt64
	)
	err := sc.Scan(&r.ID, &r.Period.From, &r.Period.By, &until, &r.Period.EndedBy, &r.Period.End, &open)
	r.Period.Until = until.Int64
	if open.Valid {
		r.RemindOpen = &open.Int64
	}
	return r, err
}

// open is the period on now, or ErrNotFound.
func (s *Service) open(ctx context.Context) (row, error) {
	r, err := scanRow(s.Store.Reader().QueryRowContext(ctx, `SELECT `+periodCols+` FROM sensorless WHERE ended_at IS NULL`))
	if errors.Is(err, sql.ErrNoRows) {
		return r, store.ErrNotFound
	}
	return r, err
}

func (s *Service) insert(ctx context.Context, now int64, by store.Device) error {
	_, err := s.Store.Writer().ExecContext(ctx,
		`INSERT INTO sensorless (started_at, started_by, started_by_name) VALUES (?, ?, ?)`, now, deviceID(by), by.Person)
	return err
}

func (s *Service) end(ctx context.Context, id, now int64, by store.Device, reason string) error {
	_, err := s.Store.Writer().ExecContext(ctx,
		`UPDATE sensorless SET ended_at = ?, ended_by = ?, ended_by_name = ?, end_reason = ?, remind_open = NULL WHERE id = ?`,
		now, deviceID(by), by.Person, reason, id)
	return err
}

func (s *Service) setRemindOpen(ctx context.Context, id int64, due *int64) error {
	_, err := s.Store.Writer().ExecContext(ctx, `UPDATE sensorless SET remind_open = ? WHERE id = ?`, due, id)
	return err
}

// between is every period overlapping [from, to], oldest first.
func (s *Service) between(ctx context.Context, from, to int64) ([]Period, error) {
	rows, err := s.Store.Reader().QueryContext(ctx,
		`SELECT `+periodCols+` FROM sensorless WHERE started_at <= ? AND (ended_at IS NULL OR ended_at > ?) ORDER BY started_at`, to, from)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Period{}
	for rows.Next() {
		r, err := scanRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r.Period)
	}
	return out, rows.Err()
}

// lastCheck is the newest glucometer check: an xDrip+ meter entry or one
// logged in the app (a treatment carrying glucose); nil when none in a week.
func (s *Service) lastCheck(ctx context.Context, now time.Time) (*Check, error) {
	var best *Check
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Lte: ptr(now.Add(time.Hour).UnixMilli()), Count: 1})
	if err != nil {
		return nil, err
	}
	if len(es) == 1 && es[0].MBG != nil && *es[0].MBG > 0 {
		best = &Check{T: es[0].Date, MG: *es[0].MBG}
	}
	from, to := now.Add(-checksBack).UnixMilli(), now.Add(time.Hour).UnixMilli()
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: 1000})
	if err != nil {
		return nil, err
	}
	for _, t := range ts { // newest first
		if mg := meter.CheckOf(t.Raw); mg != nil {
			if best == nil || t.CreatedAt > best.T {
				best = &Check{T: t.CreatedAt, MG: *mg}
			}
			break
		}
	}
	if best != nil && best.T < from {
		return nil, nil
	}
	return best, nil
}

func (s *Service) closeCheck(ctx context.Context, c Check, now int64, by store.Device) error {
	_, err := s.Store.Writer().ExecContext(ctx,
		`INSERT OR IGNORE INTO sensorless_check_closed (check_t, mg, closed_at, closed_by) VALUES (?, ?, ?, ?)`,
		c.T, c.MG, now, deviceID(by))
	return err
}

// checkClosed: this check (or the same reading arriving twice) was closed.
func (s *Service) checkClosed(ctx context.Context, c Check) (bool, error) {
	rows, err := s.Store.Reader().QueryContext(ctx,
		`SELECT check_t, mg FROM sensorless_check_closed WHERE check_t BETWEEN ? AND ?`, c.T-sameCheck, c.T+sameCheck)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var o Check
		if err := rows.Scan(&o.T, &o.MG); err != nil {
			return false, err
		}
		if sameAs(c, o) {
			return true, nil
		}
	}
	return false, rows.Err()
}

func deviceID(d store.Device) any {
	if d.ID == 0 {
		return nil
	}
	return d.ID
}

func ptr[T any](v T) *T { return &v }
