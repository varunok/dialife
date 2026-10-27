package store

import (
	"context"
	"database/sql"
	"strings"
	"time"
)

type Treatment struct {
	ID        string
	UUID      string
	EventType string
	CreatedAt int64 // unix ms
	Insulin   *float64
	Carbs     *float64
	Notes     string
	EnteredBy string
	Raw       []byte
	DeviceID  int64 // who uploaded it; read only
}

// UpsertTreatments inserts or replaces treatments by ID. POST and PUT both
// land here, so repeated uploads of the same treatment are harmless.
// Re-uploading a deleted treatment brings it back.
func (s *Store) UpsertTreatments(ctx context.Context, ts []Treatment, deviceID int64, now time.Time) error {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	st, err := tx.PrepareContext(ctx,
		`INSERT INTO treatments (id, uuid, event_type, created_at, insulin, carbs, notes, entered_by, raw, device_id, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO UPDATE SET
		   uuid = excluded.uuid, event_type = excluded.event_type, created_at = excluded.created_at,
		   insulin = excluded.insulin, carbs = excluded.carbs, notes = excluded.notes,
		   entered_by = excluded.entered_by, raw = excluded.raw, device_id = excluded.device_id,
		   updated_at = excluded.updated_at, deleted_at = NULL`)
	if err != nil {
		return err
	}
	defer st.Close()

	for _, t := range ts {
		if _, err := st.ExecContext(ctx, t.ID, nullStr(t.UUID), nullStr(t.EventType), t.CreatedAt, t.Insulin, t.Carbs,
			nullStr(t.Notes), nullStr(t.EnteredBy), string(t.Raw), deviceID, now.UnixMilli()); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// TreatmentQuery filters on created_at (unix ms); nil bounds are open.
type TreatmentQuery struct {
	ID      string
	UUID    string
	Gte, Gt *int64
	Lte, Lt *int64
	Count   int
}

// ListTreatments returns live (not deleted) treatments, newest first.
func (s *Store) ListTreatments(ctx context.Context, q TreatmentQuery) ([]Treatment, error) {
	where := []string{"deleted_at IS NULL"}
	var args []any
	add := func(cond string, v any) {
		where = append(where, cond)
		args = append(args, v)
	}
	if q.ID != "" {
		add("id = ?", q.ID)
	}
	if q.UUID != "" {
		add("uuid = ?", q.UUID)
	}
	if q.Gte != nil {
		add("created_at >= ?", *q.Gte)
	}
	if q.Gt != nil {
		add("created_at > ?", *q.Gt)
	}
	if q.Lte != nil {
		add("created_at <= ?", *q.Lte)
	}
	if q.Lt != nil {
		add("created_at < ?", *q.Lt)
	}
	args = append(args, q.Count)

	rows, err := s.r.QueryContext(ctx,
		`SELECT id, uuid, event_type, created_at, insulin, carbs, notes, entered_by, raw, coalesce(device_id, 0)
		 FROM treatments WHERE `+strings.Join(where, " AND ")+`
		 ORDER BY created_at DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Treatment
	for rows.Next() {
		var (
			t                   Treatment
			uuid, et, notes, by sql.NullString
			insulin, carbs      sql.NullFloat64
			raw                 string
		)
		if err := rows.Scan(&t.ID, &uuid, &et, &t.CreatedAt, &insulin, &carbs, &notes, &by, &raw, &t.DeviceID); err != nil {
			return nil, err
		}
		t.UUID, t.EventType, t.Notes, t.EnteredBy, t.Raw = uuid.String, et.String, notes.String, by.String, []byte(raw)
		if insulin.Valid {
			t.Insulin = &insulin.Float64
		}
		if carbs.Valid {
			t.Carbs = &carbs.Float64
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// DeleteTreatment soft-deletes a treatment; false if there was nothing live to delete.
func (s *Store) DeleteTreatment(ctx context.Context, id string, now time.Time) (bool, error) {
	res, err := s.w.ExecContext(ctx,
		`UPDATE treatments SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL`,
		now.UnixMilli(), now.UnixMilli(), id)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// TreatmentsLastModified is the newest change (insert, update or delete), unix ms; 0 if none.
func (s *Store) TreatmentsLastModified(ctx context.Context) (int64, error) {
	var ms sql.NullInt64
	err := s.r.QueryRowContext(ctx, `SELECT MAX(updated_at) FROM treatments`).Scan(&ms)
	if err != nil && !isNoRows(err) {
		return 0, err
	}
	return ms.Int64, nil
}
