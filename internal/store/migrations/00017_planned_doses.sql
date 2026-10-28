-- +goose Up
-- A dose worked out for a meal and not yet given: it is no insulin until a
-- parent confirms the injection ("given"), says it was not given ("skipped"),
-- or three hours pass ("expired").
CREATE TABLE planned_doses (
    id          TEXT    PRIMARY KEY,
    meal_id     TEXT    NOT NULL,
    dose        REAL    NOT NULL,
    food        REAL    NOT NULL,
    corr        REAL    NOT NULL,
    carbs       INTEGER NOT NULL,
    meter       INTEGER,
    meter_at    INTEGER,
    created_at  INTEGER NOT NULL,
    device_id   INTEGER NOT NULL REFERENCES devices (id),
    state       TEXT    NOT NULL DEFAULT 'planned' CHECK (state IN ('planned', 'given', 'skipped', 'expired')),
    insulin_id  TEXT,
    closed_at   INTEGER,
    closed_by   INTEGER REFERENCES devices (id),
    updated_at  INTEGER NOT NULL
);
CREATE INDEX planned_doses_state ON planned_doses (state, created_at);

-- A fifth kind of question, "planned" (was the planned dose given? ref_id is
-- the planned dose), and the answer "asleep" to a rise. Both tables rebuilt.
CREATE TABLE questions_new (
    id           INTEGER PRIMARY KEY,
    kind         TEXT    NOT NULL CHECK (kind IN ('rise', 'fall', 'bolus', 'double', 'planned')),
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
    reminded_at  INTEGER,
    ref_id       TEXT
);
INSERT INTO questions_new SELECT id, kind, from_at, to_at, sgv_from, sgv_to, state, round, outcome, treatment_id, closed_at, closed_by, reminded_at, ref_id FROM questions;

CREATE TABLE question_answers_new (
    id           INTEGER PRIMARY KEY,
    question_id  INTEGER NOT NULL REFERENCES questions_new (id),
    round        INTEGER NOT NULL,
    person       TEXT    NOT NULL,
    device_id    INTEGER NOT NULL REFERENCES devices (id),
    answer       TEXT    NOT NULL CHECK (answer IN ('food', 'food_unknown', 'insulin', 'nothing', 'one', 'two', 'asleep')),
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
