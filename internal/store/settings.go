package store

import (
	"context"
	"time"
)

// Setting returns the raw JSON stored under key, or ErrNotFound.
func (s *Store) Setting(ctx context.Context, key string) ([]byte, error) {
	var v string
	err := s.r.QueryRowContext(ctx, `SELECT value FROM settings WHERE key = ?`, key).Scan(&v)
	if isNoRows(err) {
		return nil, ErrNotFound
	}
	return []byte(v), err
}

func (s *Store) SetSetting(ctx context.Context, key string, value []byte, now time.Time) error {
	_, err := s.w.ExecContext(ctx,
		`INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
		 ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
		key, string(value), now.UnixMilli())
	return err
}
