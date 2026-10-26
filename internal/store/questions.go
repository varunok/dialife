package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type Question struct {
	ID          int64
	Kind        string // "rise" | "fall"
	From, To    int64  // unix ms
	SGVFrom     int
	SGVTo       int
	State       string // "open" | "answered" | "unknown" | "dismissed"
	Round       int
	Outcome     string
	TreatmentID string
	ClosedAt    *int64
	ClosedBy    string
	RemindedAt  *int64
}

type QuestionAnswer struct {
	QuestionID  int64
	Round       int
	Person      string
	DeviceID    int64
	Answer      string // "food" | "food_unknown" | "insulin" | "nothing"
	TreatmentID string
	At          int64
}

const questionCols = `id, kind, from_at, to_at, sgv_from, sgv_to, state, round, outcome, treatment_id, closed_at, closed_by, reminded_at`

func scanQuestion(sc scanner) (Question, error) {
	var (
		q                     Question
		outcome, tr, closedBy sql.NullString
		closedAt, remindedAt  sql.NullInt64
	)
	err := sc.Scan(&q.ID, &q.Kind, &q.From, &q.To, &q.SGVFrom, &q.SGVTo, &q.State, &q.Round, &outcome, &tr, &closedAt, &closedBy, &remindedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Question{}, ErrNotFound
	}
	if err != nil {
		return Question{}, err
	}
	q.Outcome, q.TreatmentID, q.ClosedBy = outcome.String, tr.String, closedBy.String
	if closedAt.Valid {
		q.ClosedAt = &closedAt.Int64
	}
	if remindedAt.Valid {
		q.RemindedAt = &remindedAt.Int64
	}
	return q, nil
}

func (s *Store) InsertQuestion(ctx context.Context, q Question, now time.Time) (Question, error) {
	row := s.w.QueryRowContext(ctx,
		`INSERT INTO questions (kind, from_at, to_at, sgv_from, sgv_to, reminded_at) VALUES (?, ?, ?, ?, ?, ?)
		 RETURNING `+questionCols,
		q.Kind, q.From, q.To, q.SGVFrom, q.SGVTo, now.UnixMilli())
	return scanQuestion(row)
}

// UpdateQuestion writes the mutable part: state, round, outcome, entry, closing, reminder.
func (s *Store) UpdateQuestion(ctx context.Context, q Question) error {
	_, err := s.w.ExecContext(ctx,
		`UPDATE questions SET state = ?, round = ?, outcome = nullif(?, ''), treatment_id = nullif(?, ''),
		   closed_at = ?, closed_by = nullif(?, ''), reminded_at = ? WHERE id = ?`,
		q.State, q.Round, q.Outcome, q.TreatmentID, q.ClosedAt, q.ClosedBy, q.RemindedAt, q.ID)
	return err
}

func (s *Store) Question(ctx context.Context, id int64) (Question, error) {
	return scanQuestion(s.r.QueryRowContext(ctx, `SELECT `+questionCols+` FROM questions WHERE id = ?`, id))
}

// QuestionsSince returns questions that began at or after since, plus every
// still open one, oldest first.
func (s *Store) QuestionsSince(ctx context.Context, since int64) ([]Question, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT `+questionCols+` FROM questions WHERE from_at >= ? OR state = 'open' ORDER BY from_at`, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Question
	for rows.Next() {
		q, err := scanQuestion(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, q)
	}
	return out, rows.Err()
}

func (s *Store) InsertQuestionAnswer(ctx context.Context, a QuestionAnswer) error {
	_, err := s.w.ExecContext(ctx,
		`INSERT INTO question_answers (question_id, round, person, device_id, answer, treatment_id, at)
		 VALUES (?, ?, ?, ?, ?, nullif(?, ''), ?)`,
		a.QuestionID, a.Round, a.Person, a.DeviceID, a.Answer, a.TreatmentID, a.At)
	return err
}

// QuestionAnswers returns every answer to a question, all rounds, oldest first.
func (s *Store) QuestionAnswers(ctx context.Context, id int64) ([]QuestionAnswer, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT question_id, round, person, device_id, answer, coalesce(treatment_id, ''), at
		 FROM question_answers WHERE question_id = ? ORDER BY at, id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []QuestionAnswer
	for rows.Next() {
		var a QuestionAnswer
		if err := rows.Scan(&a.QuestionID, &a.Round, &a.Person, &a.DeviceID, &a.Answer, &a.TreatmentID, &a.At); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
