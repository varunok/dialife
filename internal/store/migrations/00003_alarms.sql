-- +goose Up
CREATE TABLE alarms (
    id               INTEGER PRIMARY KEY,
    kind             TEXT    NOT NULL, -- low | high | predicted_low | predicted_high | stale
    level            TEXT    NOT NULL CHECK (level IN ('warn', 'urgent')),
    started_at       INTEGER NOT NULL, -- unix ms
    trigger_sgv      INTEGER,          -- mg/dL when it fired
    last_sgv         INTEGER,          -- mg/dL at the latest evaluation
    last_notified_at INTEGER NOT NULL,
    acked_by         INTEGER REFERENCES devices (id),
    acked_at         INTEGER,
    acked_level      TEXT,
    snooze_until     INTEGER,
    cleared_at       INTEGER
);

-- at most one live alarm per kind
CREATE UNIQUE INDEX alarms_active_kind ON alarms (kind) WHERE cleared_at IS NULL;

-- +goose Down
DROP TABLE alarms;
