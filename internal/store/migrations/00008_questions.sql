-- +goose Up
-- Who holds a phone: phones of one person answer a question as one voice.
-- NULL: the device name.
ALTER TABLE devices ADD COLUMN person TEXT;

-- "Did you forget to log something?": a rise without food or a fall without
-- insulin. Every parent answers; it closes once all agree.
CREATE TABLE questions (
    id           INTEGER PRIMARY KEY,
    kind         TEXT    NOT NULL CHECK (kind IN ('rise', 'fall')),
    from_at      INTEGER NOT NULL, -- unix ms: where the rise/fall began
    to_at        INTEGER NOT NULL, -- unix ms: the reading that made it a question
    sgv_from     INTEGER NOT NULL,
    sgv_to       INTEGER NOT NULL,
    state        TEXT    NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'answered', 'unknown', 'dismissed')),
    round        INTEGER NOT NULL DEFAULT 1, -- +1 every time answers disagree
    outcome      TEXT,    -- answered: the agreed answer
    treatment_id TEXT,    -- the entry made for it, if any
    closed_at    INTEGER,
    closed_by    TEXT,    -- who dismissed an unknown one; "log" when an entry explained it
    reminded_at  INTEGER  -- last re-ask of whoever has not answered
);
CREATE INDEX questions_state ON questions (state, from_at);

CREATE TABLE question_answers (
    id           INTEGER PRIMARY KEY,
    question_id  INTEGER NOT NULL REFERENCES questions (id),
    round        INTEGER NOT NULL,
    person       TEXT    NOT NULL,
    device_id    INTEGER NOT NULL REFERENCES devices (id),
    answer       TEXT    NOT NULL CHECK (answer IN ('food', 'food_unknown', 'insulin', 'nothing')),
    treatment_id TEXT,
    at           INTEGER NOT NULL
);
CREATE INDEX question_answers_q ON question_answers (question_id, round);

-- +goose Down
DROP TABLE question_answers;
DROP TABLE questions;
ALTER TABLE devices DROP COLUMN person;
