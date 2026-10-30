package store

import "context"

// CloseWait closes the wait ("wait") or the warning beside it ("more") shown
// for a dose; false when it was closed already.
func (s *Store) CloseWait(ctx context.Context, doseID, kind string, by *int64, now int64) (bool, error) {
	res, err := s.w.ExecContext(ctx,
		`INSERT OR IGNORE INTO wait_closed (dose_id, kind, closed_at, closed_by) VALUES (?, ?, ?, ?)`, doseID, kind, now, by)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// WaitClosed tells which of the two were closed for a dose.
func (s *Store) WaitClosed(ctx context.Context, doseID string) (wait, more bool, err error) {
	rows, err := s.r.QueryContext(ctx, `SELECT kind FROM wait_closed WHERE dose_id = ?`, doseID)
	if err != nil {
		return false, false, err
	}
	defer rows.Close()
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return false, false, err
		}
		wait = wait || k == "wait"
		more = more || k == "more"
	}
	return wait, more, rows.Err()
}
