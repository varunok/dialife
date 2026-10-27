package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type Device struct {
	ID           int64
	Name         string
	Person       string // who holds it: several phones of one person count once; default the name
	Role         string
	DisplayUnits string
	CreatedAt    time.Time
	LastSeenAt   *time.Time
	RevokedAt    *time.Time

	// a browser session, admitted as a reader; not a row in devices (ID 0)
	WebSessionID int64
}

const deviceCols = `id, name, coalesce(person, name), role, display_units, created_at, last_seen_at, revoked_at`

func (s *Store) CreateDevice(ctx context.Context, name, role, tokenSHA1 string, now time.Time) (Device, error) {
	row := s.w.QueryRowContext(ctx,
		`INSERT INTO devices (name, role, token_sha1, created_at) VALUES (?, ?, ?, ?)
		 RETURNING `+deviceCols,
		name, role, tokenSHA1, now.UnixMilli())
	return scanDevice(row)
}

// ActiveDeviceByTokenSHA1 returns the non-revoked device owning the token hash.
func (s *Store) ActiveDeviceByTokenSHA1(ctx context.Context, tokenSHA1 string) (Device, error) {
	row := s.r.QueryRowContext(ctx,
		`SELECT `+deviceCols+` FROM devices WHERE token_sha1 = ? AND revoked_at IS NULL`, tokenSHA1)
	return scanDevice(row)
}

func (s *Store) ListDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.r.QueryContext(ctx, `SELECT `+deviceCols+` FROM devices ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Device
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) RevokeDevice(ctx context.Context, id int64, now time.Time) error {
	res, err := s.w.ExecContext(ctx,
		`UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, now.UnixMilli(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) TouchDevice(ctx context.Context, id int64, now time.Time) error {
	_, err := s.w.ExecContext(ctx, `UPDATE devices SET last_seen_at = ? WHERE id = ?`, now.UnixMilli(), id)
	return err
}

type scanner interface{ Scan(dest ...any) error }

func scanDevice(sc scanner) (Device, error) {
	var (
		d                 Device
		created           int64
		lastSeen, revoked sql.NullInt64
	)
	err := sc.Scan(&d.ID, &d.Name, &d.Person, &d.Role, &d.DisplayUnits, &created, &lastSeen, &revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	if err != nil {
		return Device{}, err
	}
	d.CreatedAt = time.UnixMilli(created)
	d.LastSeenAt = msPtr(lastSeen)
	d.RevokedAt = msPtr(revoked)
	return d, nil
}

func msPtr(v sql.NullInt64) *time.Time {
	if !v.Valid {
		return nil
	}
	t := time.UnixMilli(v.Int64)
	return &t
}

// ActiveDeviceByID returns a non-revoked device, or ErrNotFound.
func (s *Store) ActiveDeviceByID(ctx context.Context, id int64) (Device, error) {
	row := s.r.QueryRowContext(ctx, `SELECT `+deviceCols+` FROM devices WHERE id = ? AND revoked_at IS NULL`, id)
	return scanDevice(row)
}

// RotateToken gives an active device a new token (the old one stops working
// at once). Its id, name and role stay, so alarm history and ntfy targets keep pointing at it.
func (s *Store) RotateToken(ctx context.Context, id int64, tokenSHA1 string) (Device, error) {
	row := s.w.QueryRowContext(ctx,
		`UPDATE devices SET token_sha1 = ? WHERE id = ? AND revoked_at IS NULL RETURNING `+deviceCols, tokenSHA1, id)
	return scanDevice(row)
}

// SetPerson says who holds a device; "" goes back to its name.
func (s *Store) SetPerson(ctx context.Context, id int64, person string) error {
	res, err := s.w.ExecContext(ctx, `UPDATE devices SET person = nullif(?, '') WHERE id = ?`, person, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}
