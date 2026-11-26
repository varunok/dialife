package recheck

import (
	"context"
	"database/sql"
	"slices"

	"dialife/internal/store"
)

// The package's own table (migration 00021): recheck.

const cols = `id, treatment_id, carbs_t, carbs, low_kind, COALESCE(low_mg, 0), due_at, reminded, COALESCE(check_t, 0), COALESCE(check_mg, 0), still_told`

type row struct {
	Recheck
	treatmentID string
	reminded    bool
	stillTold   bool
}

func (s *Service) scan(sc interface{ Scan(...any) error }) (row, error) {
	var r row
	err := sc.Scan(&r.ID, &r.treatmentID, &r.CarbsT, &r.Carbs, &r.Low.Kind, &r.Low.MG, &r.DueAt, &r.reminded, &r.CheckT, &r.CheckMG, &r.stillTold)
	switch {
	case r.CheckT != 0:
		r.State = "still_low"
	case s.Now().UnixMilli() >= r.DueAt:
		r.State = "due"
	default:
		r.State = "waiting"
	}
	return r, err
}

// openRows are the rechecks not closed yet, newest carbs first.
func (s *Service) openRows(ctx context.Context) ([]row, error) {
	rs, err := s.Store.Reader().QueryContext(ctx, `SELECT `+cols+` FROM recheck WHERE closed_at IS NULL ORDER BY carbs_t DESC, id DESC`)
	if err != nil {
		return nil, err
	}
	defer rs.Close()
	var out []row
	for rs.Next() {
		r, err := s.scan(rs)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rs.Err()
}

// current is the open recheck of the newest carbs, or sql.ErrNoRows.
func (s *Service) current(ctx context.Context) (Recheck, error) {
	r, err := s.scan(s.Store.Reader().QueryRowContext(ctx,
		`SELECT `+cols+` FROM recheck WHERE closed_at IS NULL ORDER BY carbs_t DESC, id DESC LIMIT 1`))
	return r.Recheck, err
}

func (s *Service) exists(ctx context.Context, treatmentID string) (bool, error) {
	var n int
	err := s.Store.Reader().QueryRowContext(ctx, `SELECT COUNT(*) FROM recheck WHERE treatment_id = ?`, treatmentID).Scan(&n)
	return n > 0, err
}

func (s *Service) insert(ctx context.Context, t store.Treatment, low Low) error {
	var mg any
	if low.MG > 0 {
		mg = low.MG
	}
	_, err := s.Store.Writer().ExecContext(ctx,
		`INSERT OR IGNORE INTO recheck (treatment_id, carbs_t, carbs, low_kind, low_mg, due_at) VALUES (?, ?, ?, ?, ?, ?)`,
		t.ID, t.CreatedAt, *t.Carbs, low.Kind, mg, t.CreatedAt+After.Milliseconds())
	return err
}

func (s *Service) close(ctx context.Context, id int64, outcome string, now int64, by store.Device) error {
	var dev any
	if by.ID != 0 {
		dev = by.ID
	}
	_, err := s.Store.Writer().ExecContext(ctx,
		`UPDATE recheck SET closed_at = ?, outcome = ?, closed_by = ?, closed_by_name = ? WHERE id = ? AND closed_at IS NULL`,
		now, outcome, dev, by.Person, id)
	return err
}

func (s *Service) setCheck(ctx context.Context, id int64, c Point) error {
	_, err := s.Store.Writer().ExecContext(ctx, `UPDATE recheck SET check_t = ?, check_mg = ?, still_told = 1 WHERE id = ?`, c.T, c.MG, id)
	return err
}

func (s *Service) setReminded(ctx context.Context, id int64) error {
	_, err := s.Store.Writer().ExecContext(ctx, `UPDATE recheck SET reminded = 1 WHERE id = ?`, id)
	return err
}

// treatmentGone: the carbs entry was deleted since.
func (s *Service) treatmentGone(ctx context.Context, id string) (bool, error) {
	var deleted sql.NullInt64
	err := s.Store.Reader().QueryRowContext(ctx, `SELECT deleted_at FROM treatments WHERE id = ?`, id).Scan(&deleted)
	if err == sql.ErrNoRows {
		return true, nil
	}
	return deleted.Valid, err
}

// lowAlarms are the low alarms live at t.
func (s *Service) lowAlarms(ctx context.Context, t int64) ([]Window, error) {
	rs, err := s.Store.Reader().QueryContext(ctx,
		`SELECT started_at, COALESCE(cleared_at, 0) FROM alarms WHERE kind = 'low' AND started_at <= ? AND (cleared_at IS NULL OR cleared_at > ?)`, t, t)
	if err != nil {
		return nil, err
	}
	defer rs.Close()
	var out []Window
	for rs.Next() {
		var w Window
		if err := rs.Scan(&w.From, &w.Until); err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, rs.Err()
}

func sortPoints(ps []Point) {
	slices.SortStableFunc(ps, func(a, b Point) int {
		switch {
		case a.T < b.T:
			return -1
		case a.T > b.T:
			return 1
		}
		return 0
	})
}
