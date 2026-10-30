package wait

import "context"

// The wait's own table, wait_closed (migration 00019): what was closed, by whom.

func (s *Service) close(ctx context.Context, doseID, kind string, by int64, now int64) (bool, error) {
	var dev any // 0: no device known
	if by != 0 {
		dev = by
	}
	res, err := s.Store.Writer().ExecContext(ctx,
		`INSERT OR IGNORE INTO wait_closed (dose_id, kind, closed_at, closed_by) VALUES (?, ?, ?, ?)`, doseID, kind, now, dev)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

func (s *Service) closed(ctx context.Context, doseID string) (wait, more bool, err error) {
	rows, err := s.Store.Reader().QueryContext(ctx, `SELECT kind FROM wait_closed WHERE dose_id = ?`, doseID)
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
