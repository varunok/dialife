package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

// FoodItem is one line of a meal logged as a list in the app.
type FoodItem struct {
	T       int64
	Name    string
	Portion string
	Carbs   float64
}

// FoodItems returns the listed foods of live meals since then, newest first.
func (s *Store) FoodItems(ctx context.Context, since int64) ([]FoodItem, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT created_at, raw FROM treatments
		 WHERE deleted_at IS NULL AND created_at >= ? AND raw LIKE '%"foodItems"%'
		 ORDER BY created_at DESC LIMIT 5000`, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FoodItem
	for rows.Next() {
		var (
			t   int64
			raw string
		)
		if err := rows.Scan(&t, &raw); err != nil {
			return nil, err
		}
		var d struct {
			Items []struct {
				Name    string   `json:"name"`
				Portion string   `json:"portion"`
				Carbs   *float64 `json:"carbs"`
			} `json:"foodItems"`
		}
		if json.Unmarshal([]byte(raw), &d) != nil {
			continue
		}
		for _, it := range d.Items {
			if it.Name != "" && it.Carbs != nil {
				out = append(out, FoodItem{T: t, Name: it.Name, Portion: it.Portion, Carbs: *it.Carbs})
			}
		}
	}
	return out, rows.Err()
}

type CarbEstimate struct {
	Carbs, Lo, Hi, Models int
	At                    int64
}

// CarbEstimate is a cached model answer; ErrNotFound if none or older than maxAge.
func (s *Store) CarbEstimate(ctx context.Context, key string, maxAge time.Duration, now time.Time) (CarbEstimate, error) {
	var e CarbEstimate
	err := s.r.QueryRowContext(ctx, `SELECT carbs, lo, hi, models, at FROM carb_estimates WHERE key = ? AND at >= ?`,
		key, now.Add(-maxAge).UnixMilli()).Scan(&e.Carbs, &e.Lo, &e.Hi, &e.Models, &e.At)
	if errors.Is(err, sql.ErrNoRows) {
		return e, ErrNotFound
	}
	return e, err
}

func (s *Store) SaveCarbEstimate(ctx context.Context, key string, e CarbEstimate) error {
	_, err := s.w.ExecContext(ctx,
		`INSERT INTO carb_estimates (key, carbs, lo, hi, models, at) VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT (key) DO UPDATE SET carbs = excluded.carbs, lo = excluded.lo, hi = excluded.hi, models = excluded.models, at = excluded.at`,
		key, e.Carbs, e.Lo, e.Hi, e.Models, e.At)
	return err
}
