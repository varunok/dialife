-- +goose Up
-- A third kind of question: food was logged but no rapid insulin near it.
-- SQLite cannot change a CHECK, so both tables are rebuilt, answers and all.
CREATE TABLE questions_new (
    id           INTEGER PRIMARY KEY,
    kind         TEXT    NOT NULL CHECK (kind IN ('rise', 'fall', 'bolus')),
    from_at      INTEGER NOT NULL,
    to_at        INTEGER NOT NULL,
    sgv_from     INTEGER NOT NULL,
    sgv_to       INTEGER NOT NULL,
    state        TEXT    NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'answered', 'unknown', 'dismissed')),
    round        INTEGER NOT NULL DEFAULT 1,
    outcome      TEXT,
    treatment_id TEXT,
    closed_at    INTEGER,
    closed_by    TEXT,
    reminded_at  INTEGER
);
INSERT INTO questions_new SELECT id, kind, from_at, to_at, sgv_from, sgv_to, state, round, outcome, treatment_id, closed_at, closed_by, reminded_at FROM questions;

CREATE TABLE question_answers_new (
    id           INTEGER PRIMARY KEY,
    question_id  INTEGER NOT NULL REFERENCES questions_new (id),
    round        INTEGER NOT NULL,
    person       TEXT    NOT NULL,
    device_id    INTEGER NOT NULL REFERENCES devices (id),
    answer       TEXT    NOT NULL CHECK (answer IN ('food', 'food_unknown', 'insulin', 'nothing')),
    treatment_id TEXT,
    at           INTEGER NOT NULL
);
INSERT INTO question_answers_new SELECT id, question_id, round, person, device_id, answer, treatment_id, at FROM question_answers;

DROP TABLE question_answers;
DROP TABLE questions;
ALTER TABLE questions_new RENAME TO questions;
ALTER TABLE question_answers_new RENAME TO question_answers;
CREATE INDEX questions_state ON questions (state, from_at);
CREATE INDEX question_answers_q ON question_answers (question_id, round);

-- +goose Down
SELECT 1;
