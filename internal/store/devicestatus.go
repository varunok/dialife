package store

import (
	"context"
	"database/sql"
	"time"
)

type DeviceStatus struct {
	ID              string
	CreatedAt       int64 // unix ms
	Device          string
	UploaderBattery *int // percent
	Raw             []byte
}

func (s *Store) SaveDeviceStatuses(ctx context.Context, ds []DeviceStatus, deviceID int64, now time.Time) error {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, d := range ds {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO devicestatus (id, created_at, device, uploader_battery, raw, device_id, received_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?)`,
			d.ID, d.CreatedAt, nullStr(d.Device), d.UploaderBattery, string(d.Raw), deviceID, now.UnixMilli()); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// LatestDeviceStatus returns the newest status report, or ErrNotFound.
func (s *Store) LatestDeviceStatus(ctx context.Context) (DeviceStatus, error) {
	var (
		d       DeviceStatus
		dev     sql.NullString
		battery sql.NullInt64
		raw     string
	)
	err := s.r.QueryRowContext(ctx,
		`SELECT id, created_at, device, uploader_battery, raw FROM devicestatus ORDER BY created_at DESC LIMIT 1`).
		Scan(&d.ID, &d.CreatedAt, &dev, &battery, &raw)
	if isNoRows(err) {
		return DeviceStatus{}, ErrNotFound
	}
	if err != nil {
		return DeviceStatus{}, err
	}
	d.Device, d.UploaderBattery, d.Raw = dev.String, intPtr(battery), []byte(raw)
	return d, nil
}

type Activity struct {
	ID        string
	CreatedAt int64
	Raw       []byte
}

func (s *Store) SaveActivities(ctx context.Context, as []Activity, deviceID int64, now time.Time) error {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, a := range as {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO activity (id, created_at, raw, device_id, received_at) VALUES (?, ?, ?, ?, ?)
			 ON CONFLICT (id) DO NOTHING`,
			a.ID, a.CreatedAt, string(a.Raw), deviceID, now.UnixMilli()); err != nil {
			return err
		}
	}
	return tx.Commit()
}
