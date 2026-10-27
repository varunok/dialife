package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type WebLogin struct {
	ID         string
	CodeSHA    string
	BrowserSHA string
	UserAgent  string
	CreatedAt  int64
	ExpiresAt  int64
	State      string // "pending" | "approved" | "rejected" | "used"
	ApprovedBy int64
	Minutes    int
}

type WebSession struct {
	ID           int64
	UserAgent    string
	ApprovedBy   int64
	ApproverName string // the approving device's person
	CreatedAt    int64
	ExpiresAt    int64
	LastSeenAt   *int64
}

const webLoginCols = `id, code_sha, browser_sha, coalesce(user_agent, ''), created_at, expires_at, state, coalesce(approved_by, 0), coalesce(minutes, 0)`

func scanWebLogin(sc scanner) (WebLogin, error) {
	var l WebLogin
	err := sc.Scan(&l.ID, &l.CodeSHA, &l.BrowserSHA, &l.UserAgent, &l.CreatedAt, &l.ExpiresAt, &l.State, &l.ApprovedBy, &l.Minutes)
	if errors.Is(err, sql.ErrNoRows) {
		return WebLogin{}, ErrNotFound
	}
	return l, err
}

// InsertWebLogin stores a new code, dropping long-expired ones on the way.
func (s *Store) InsertWebLogin(ctx context.Context, l WebLogin, now time.Time) error {
	if _, err := s.w.ExecContext(ctx, `DELETE FROM web_logins WHERE expires_at < ?`, now.Add(-time.Hour).UnixMilli()); err != nil {
		return err
	}
	_, err := s.w.ExecContext(ctx,
		`INSERT INTO web_logins (id, code_sha, browser_sha, user_agent, created_at, expires_at) VALUES (?, ?, ?, nullif(?, ''), ?, ?)`,
		l.ID, l.CodeSHA, l.BrowserSHA, l.UserAgent, l.CreatedAt, l.ExpiresAt)
	return err
}

// CountPendingWebLogins is how many unexpired codes are waiting: a cap against floods.
func (s *Store) CountPendingWebLogins(ctx context.Context, now time.Time) (int, error) {
	var n int
	err := s.r.QueryRowContext(ctx, `SELECT count(*) FROM web_logins WHERE state = 'pending' AND expires_at > ?`, now.UnixMilli()).Scan(&n)
	return n, err
}

func (s *Store) WebLoginByID(ctx context.Context, id string) (WebLogin, error) {
	return scanWebLogin(s.r.QueryRowContext(ctx, `SELECT `+webLoginCols+` FROM web_logins WHERE id = ?`, id))
}

func (s *Store) WebLoginByCode(ctx context.Context, codeSHA string) (WebLogin, error) {
	return scanWebLogin(s.r.QueryRowContext(ctx, `SELECT `+webLoginCols+` FROM web_logins WHERE code_sha = ?`, codeSHA))
}

// DecideWebLogin approves or rejects a pending code; false when it was no longer pending.
func (s *Store) DecideWebLogin(ctx context.Context, id string, approve bool, by int64, minutes int) (bool, error) {
	state := "rejected"
	if approve {
		state = "approved"
	}
	res, err := s.w.ExecContext(ctx,
		`UPDATE web_logins SET state = ?, approved_by = ?, minutes = ? WHERE id = ? AND state = 'pending'`,
		state, by, minutes, id)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// UseWebLogin turns an approved code into a session, once.
func (s *Store) UseWebLogin(ctx context.Context, l WebLogin, tokenSHA string, now time.Time) (int64, error) {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE web_logins SET state = 'used' WHERE id = ? AND state = 'approved'`, l.ID)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n != 1 {
		return 0, ErrNotFound
	}
	var id int64
	err = tx.QueryRowContext(ctx,
		`INSERT INTO web_sessions (token_sha, user_agent, approved_by, created_at, expires_at, last_seen_at)
		 VALUES (?, nullif(?, ''), ?, ?, ?, ?) RETURNING id`,
		tokenSHA, l.UserAgent, l.ApprovedBy, now.UnixMilli(), now.Add(time.Duration(l.Minutes)*time.Minute).UnixMilli(), now.UnixMilli()).Scan(&id)
	if err != nil {
		return 0, err
	}
	return id, tx.Commit()
}

// ActiveWebSession finds an unexpired, unrevoked session by its cookie.
func (s *Store) ActiveWebSession(ctx context.Context, tokenSHA string, now time.Time) (WebSession, error) {
	return scanWebSession(s.r.QueryRowContext(ctx,
		`SELECT `+webSessionCols+` FROM web_sessions w LEFT JOIN devices d ON d.id = w.approved_by
		 WHERE w.token_sha = ? AND w.revoked_at IS NULL AND w.expires_at > ?`, tokenSHA, now.UnixMilli()))
}

const webSessionCols = `w.id, coalesce(w.user_agent, ''), w.approved_by, coalesce(d.person, d.name, ''), w.created_at, w.expires_at, w.last_seen_at`

func scanWebSession(sc scanner) (WebSession, error) {
	var (
		w    WebSession
		seen sql.NullInt64
	)
	err := sc.Scan(&w.ID, &w.UserAgent, &w.ApprovedBy, &w.ApproverName, &w.CreatedAt, &w.ExpiresAt, &seen)
	if errors.Is(err, sql.ErrNoRows) {
		return WebSession{}, ErrNotFound
	}
	if seen.Valid {
		w.LastSeenAt = &seen.Int64
	}
	return w, err
}

// ActiveWebSessions lists the browsers allowed in right now, newest first.
func (s *Store) ActiveWebSessions(ctx context.Context, now time.Time) ([]WebSession, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT `+webSessionCols+` FROM web_sessions w LEFT JOIN devices d ON d.id = w.approved_by
		 WHERE w.revoked_at IS NULL AND w.expires_at > ? ORDER BY w.created_at DESC`, now.UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []WebSession
	for rows.Next() {
		w, err := scanWebSession(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, rows.Err()
}

func (s *Store) TouchWebSession(ctx context.Context, id int64, now time.Time) error {
	_, err := s.w.ExecContext(ctx, `UPDATE web_sessions SET last_seen_at = ? WHERE id = ?`, now.UnixMilli(), id)
	return err
}

// RevokeWebSession ends a session now; false when it was already over.
func (s *Store) RevokeWebSession(ctx context.Context, id int64, by string, now time.Time) (bool, error) {
	res, err := s.w.ExecContext(ctx,
		`UPDATE web_sessions SET revoked_at = ?, revoked_by = nullif(?, '') WHERE id = ? AND revoked_at IS NULL`, now.UnixMilli(), by, id)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}
