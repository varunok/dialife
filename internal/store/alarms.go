package store

import (
	"context"
	"database/sql"
)

// Alarm is one alarm episode: it fires, someone may take it, and it ends
// on its own (recovery) or by hand.
type Alarm struct {
	ID             int64
	Kind           string
	Level          string
	StartedAt      int64
	TriggerSGV     *int
	LastSGV        *int
	LastNotifiedAt int64
	Reason         string

	HandledBy     *int64
	HandledByName string // joined from devices, read-only
	HandledAt     *int64
	HandledSGV    *int
	QuietUntil    *int64
	NextCheckinAt *int64
	CheckinSentAt *int64
	HelpAt        *int64
	ComingBy      *int64 // answered a help request with "I'm coming"
	ComingByName  string // joined, read-only
	ComingAt      *int64

	ClearedAt        *int64
	ResolvedBy       *int64
	ResolvedByName   string // joined, read-only
	ResolvedManually bool
	SuppressUntil    *int64
}

const alarmCols = `a.id, a.kind, a.level, a.started_at, a.trigger_sgv, a.last_sgv, a.last_notified_at, COALESCE(a.reason, ''),
	a.handled_by, COALESCE(h.name, ''), a.handled_at, a.handled_sgv, a.quiet_until, a.next_checkin_at, a.checkin_sent_at, a.help_at,
	a.cleared_at, a.resolved_by, COALESCE(r.name, ''), a.resolved_manually, a.suppress_until,
	a.coming_by, COALESCE(c.name, ''), a.coming_at`

const alarmFrom = ` FROM alarms a LEFT JOIN devices h ON h.id = a.handled_by LEFT JOIN devices r ON r.id = a.resolved_by LEFT JOIN devices c ON c.id = a.coming_by`

// ActiveAlarms returns live episodes, oldest first.
func (s *Store) ActiveAlarms(ctx context.Context) ([]Alarm, error) {
	rows, err := s.r.QueryContext(ctx, `SELECT `+alarmCols+alarmFrom+` WHERE a.cleared_at IS NULL ORDER BY a.started_at, a.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Alarm
	for rows.Next() {
		a, err := scanAlarm(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) AlarmByID(ctx context.Context, id int64) (Alarm, error) {
	return scanAlarm(s.r.QueryRowContext(ctx, `SELECT `+alarmCols+alarmFrom+` WHERE a.id = ?`, id))
}

// LastManualResolve is the newest hand-resolved episode of kind whose
// suppression is still running, or ErrNotFound.
func (s *Store) LastManualResolve(ctx context.Context, kind string, now int64) (Alarm, error) {
	return scanAlarm(s.r.QueryRowContext(ctx,
		`SELECT `+alarmCols+alarmFrom+` WHERE a.kind = ? AND a.resolved_manually = 1 AND a.suppress_until > ?
		 ORDER BY a.cleared_at DESC LIMIT 1`, kind, now))
}

func (s *Store) InsertAlarm(ctx context.Context, a Alarm) (int64, error) {
	res, err := s.w.ExecContext(ctx,
		`INSERT INTO alarms (kind, level, started_at, trigger_sgv, last_sgv, last_notified_at, reason) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		a.Kind, a.Level, a.StartedAt, a.TriggerSGV, a.LastSGV, a.LastNotifiedAt, nullStr(a.Reason))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateAlarm writes every mutable field of a.
func (s *Store) UpdateAlarm(ctx context.Context, a Alarm) error {
	_, err := s.w.ExecContext(ctx,
		`UPDATE alarms SET level = ?, last_sgv = ?, last_notified_at = ?, reason = ?,
		   handled_by = ?, handled_at = ?, handled_sgv = ?, quiet_until = ?, next_checkin_at = ?, checkin_sent_at = ?, help_at = ?,
		   cleared_at = ?, resolved_by = ?, resolved_manually = ?, suppress_until = ?, coming_by = ?, coming_at = ?
		 WHERE id = ?`,
		a.Level, a.LastSGV, a.LastNotifiedAt, nullStr(a.Reason),
		a.HandledBy, a.HandledAt, a.HandledSGV, a.QuietUntil, a.NextCheckinAt, a.CheckinSentAt, a.HelpAt,
		a.ClearedAt, a.ResolvedBy, a.ResolvedManually, a.SuppressUntil, a.ComingBy, a.ComingAt, a.ID)
	return err
}

// LatestValidSGV is the newest sgv above the sensor error codes.
func (s *Store) LatestValidSGV(ctx context.Context, maxErrorCode int) (Entry, error) {
	var e Entry
	err := s.r.QueryRowContext(ctx,
		`SELECT id, date, sgv FROM entries WHERE type = 'sgv' AND sgv > ? ORDER BY date DESC LIMIT 1`, maxErrorCode).
		Scan(&e.ID, &e.Date, &e.SGV)
	if isNoRows(err) {
		return Entry{}, ErrNotFound
	}
	e.Type = "sgv"
	return e, err
}

func scanAlarm(sc scanner) (Alarm, error) {
	var (
		a                              Alarm
		trigger, last, handledSGV      sql.NullInt64
		handledBy, handledAt, quiet    sql.NullInt64
		nextCheckin, checkinSent, help sql.NullInt64
		cleared, resolvedBy, suppress  sql.NullInt64
		comingBy, comingAt             sql.NullInt64
	)
	err := sc.Scan(&a.ID, &a.Kind, &a.Level, &a.StartedAt, &trigger, &last, &a.LastNotifiedAt, &a.Reason,
		&handledBy, &a.HandledByName, &handledAt, &handledSGV, &quiet, &nextCheckin, &checkinSent, &help,
		&cleared, &resolvedBy, &a.ResolvedByName, &a.ResolvedManually, &suppress,
		&comingBy, &a.ComingByName, &comingAt)
	if isNoRows(err) {
		return Alarm{}, ErrNotFound
	}
	if err != nil {
		return Alarm{}, err
	}
	a.TriggerSGV, a.LastSGV, a.HandledSGV = intPtr(trigger), intPtr(last), intPtr(handledSGV)
	a.HandledBy, a.HandledAt, a.QuietUntil = int64Ptr(handledBy), int64Ptr(handledAt), int64Ptr(quiet)
	a.NextCheckinAt, a.CheckinSentAt, a.HelpAt = int64Ptr(nextCheckin), int64Ptr(checkinSent), int64Ptr(help)
	a.ClearedAt, a.ResolvedBy, a.SuppressUntil = int64Ptr(cleared), int64Ptr(resolvedBy), int64Ptr(suppress)
	a.ComingBy, a.ComingAt = int64Ptr(comingBy), int64Ptr(comingAt)
	return a, nil
}

func int64Ptr(v sql.NullInt64) *int64 {
	if !v.Valid {
		return nil
	}
	return &v.Int64
}

// AlarmEvent is one step of an episode (see alarm_events).
type AlarmEvent struct {
	AlarmID int64
	At      int64
	Type    string
	Reason  string
	By      string
	SGV     *int
}

func (s *Store) InsertAlarmEvent(ctx context.Context, e AlarmEvent) error {
	_, err := s.w.ExecContext(ctx,
		`INSERT INTO alarm_events (alarm_id, at, type, reason, by_name, sgv) VALUES (?, ?, ?, ?, ?, ?)`,
		e.AlarmID, e.At, e.Type, nullStr(e.Reason), nullStr(e.By), e.SGV)
	return err
}

// AlarmsSince returns episodes started at or after from, newest first, live ones included.
func (s *Store) AlarmsSince(ctx context.Context, from int64, limit int) ([]Alarm, error) {
	rows, err := s.r.QueryContext(ctx, `SELECT `+alarmCols+alarmFrom+` WHERE a.started_at >= ? ORDER BY a.started_at DESC, a.id DESC LIMIT ?`, from, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Alarm
	for rows.Next() {
		a, err := scanAlarm(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// AlarmEventsSince returns the steps of episodes started at or after from, oldest first.
func (s *Store) AlarmEventsSince(ctx context.Context, from int64) ([]AlarmEvent, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT e.alarm_id, e.at, e.type, COALESCE(e.reason, ''), COALESCE(e.by_name, ''), e.sgv
		 FROM alarm_events e JOIN alarms a ON a.id = e.alarm_id
		 WHERE a.started_at >= ? ORDER BY e.at, e.id`, from)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AlarmEvent
	for rows.Next() {
		var (
			e   AlarmEvent
			sgv sql.NullInt64
		)
		if err := rows.Scan(&e.AlarmID, &e.At, &e.Type, &e.Reason, &e.By, &sgv); err != nil {
			return nil, err
		}
		e.SGV = intPtr(sgv)
		out = append(out, e)
	}
	return out, rows.Err()
}
