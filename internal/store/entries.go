package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

type Entry struct {
	ID        string
	Type      string
	Date      int64 // unix ms
	SGV       *int
	MBG       *int
	Direction string
	Delta     *float64
	Noise     *int
	Device    string
	Raw       []byte
	New       bool // set by SaveEntries: false when (type, date) was already stored
}

// SaveEntries inserts entries in one transaction. An entry whose (type, date)
// already exists is kept as is; its stored ID is written back into the slice.
// Returns how many entries were new.
func (s *Store) SaveEntries(ctx context.Context, es []Entry, deviceID int64, now time.Time) (int, error) {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	ins, err := tx.PrepareContext(ctx,
		`INSERT INTO entries (id, type, date, sgv, mbg, direction, delta, noise, device, raw, device_id, received_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (type, date) DO NOTHING`)
	if err != nil {
		return 0, err
	}
	defer ins.Close()

	added := 0
	for i := range es {
		e := &es[i]
		res, err := ins.ExecContext(ctx, e.ID, e.Type, e.Date, e.SGV, e.MBG, nullStr(e.Direction), e.Delta, e.Noise,
			nullStr(e.Device), string(e.Raw), deviceID, now.UnixMilli())
		if err != nil {
			return 0, err
		}
		if n, _ := res.RowsAffected(); n == 1 {
			e.New = true
			added++
			continue
		}
		if err := tx.QueryRowContext(ctx, `SELECT id FROM entries WHERE type = ? AND date = ?`, e.Type, e.Date).Scan(&e.ID); err != nil {
			return 0, err
		}
	}
	return added, tx.Commit()
}

// EntryQuery filters on type and date (unix ms); empty/nil fields are open.
type EntryQuery struct {
	Type    string
	Gte, Gt *int64
	Lte, Lt *int64
	Count   int
	Asc     bool // oldest first; default is newest first
}

// RecentEntries returns the newest entries of a type, newest first.
func (s *Store) RecentEntries(ctx context.Context, typ string, limit int) ([]Entry, error) {
	return s.QueryEntries(ctx, EntryQuery{Type: typ, Count: limit})
}

func (s *Store) QueryEntries(ctx context.Context, q EntryQuery) ([]Entry, error) {
	var (
		where []string
		args  []any
	)
	add := func(cond string, v any) {
		where = append(where, cond)
		args = append(args, v)
	}
	if q.Type != "" {
		add("type = ?", q.Type)
	}
	if q.Gte != nil {
		add("date >= ?", *q.Gte)
	}
	if q.Gt != nil {
		add("date > ?", *q.Gt)
	}
	if q.Lte != nil {
		add("date <= ?", *q.Lte)
	}
	if q.Lt != nil {
		add("date < ?", *q.Lt)
	}
	query := `SELECT id, type, date, sgv, mbg, direction, delta, noise, device, raw FROM entries`
	if len(where) > 0 {
		query += ` WHERE ` + strings.Join(where, " AND ")
	}
	if q.Asc {
		query += ` ORDER BY date ASC`
	} else {
		query += ` ORDER BY date DESC`
	}
	query += ` LIMIT ?`
	args = append(args, q.Count)

	rows, err := s.r.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Entry
	for rows.Next() {
		var (
			e              Entry
			sgv, mbg, nois sql.NullInt64
			delta          sql.NullFloat64
			dir, dev       sql.NullString
			raw            string
		)
		if err := rows.Scan(&e.ID, &e.Type, &e.Date, &sgv, &mbg, &dir, &delta, &nois, &dev, &raw); err != nil {
			return nil, err
		}
		e.SGV, e.MBG, e.Noise = intPtr(sgv), intPtr(mbg), intPtr(nois)
		if delta.Valid {
			e.Delta = &delta.Float64
		}
		e.Direction, e.Device, e.Raw = dir.String, dev.String, []byte(raw)
		out = append(out, e)
	}
	return out, rows.Err()
}

func nullStr(s string) sql.NullString { return sql.NullString{String: s, Valid: s != ""} }

func intPtr(v sql.NullInt64) *int {
	if !v.Valid {
		return nil
	}
	i := int(v.Int64)
	return &i
}

func isNoRows(err error) bool { return errors.Is(err, sql.ErrNoRows) }

// EntriesReceivedSince reports the reading-time span of entries stored after
// ms (by received_at). ok is false when nothing arrived.
func (s *Store) EntriesReceivedSince(ctx context.Context, ms int64) (from, to int64, ok bool, err error) {
	var lo, hi sql.NullInt64
	err = s.r.QueryRowContext(ctx,
		`SELECT MIN(date), MAX(date) FROM entries WHERE received_at > ?`, ms).Scan(&lo, &hi)
	if err != nil {
		return 0, 0, false, err
	}
	return lo.Int64, hi.Int64, lo.Valid, nil
}
