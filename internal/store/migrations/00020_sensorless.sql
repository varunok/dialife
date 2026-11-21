-- +goose Up
-- "No sensor" mode: while the family waits for sensors there is nothing to
-- alarm about. One row per period; ended_at NULL while it is on (at most one).
-- remind_open is the due time of the "measure with the glucometer" reminder
-- pushed and still showing, so a restart neither repeats nor loses it.
CREATE TABLE sensorless (
    id              INTEGER PRIMARY KEY,
    started_at      INTEGER NOT NULL,
    started_by      INTEGER REFERENCES devices (id),
    started_by_name TEXT    NOT NULL DEFAULT '',
    ended_at        INTEGER,
    ended_by        INTEGER REFERENCES devices (id),
    ended_by_name   TEXT    NOT NULL DEFAULT '',
    end_reason      TEXT    CHECK (end_reason IN ('manual', 'sensor')),
    remind_open     INTEGER
);
CREATE UNIQUE INDEX sensorless_one_open ON sensorless ((ended_at IS NULL)) WHERE ended_at IS NULL;
CREATE INDEX sensorless_started ON sensorless (started_at);

-- The card for a glucometer check out of range in the mode, closed once for
-- every phone. check_t is the check's time; mg its value (one check often
-- arrives twice, as a BG check and as an xDrip+ meter entry).
CREATE TABLE sensorless_check_closed (
    check_t   INTEGER PRIMARY KEY,
    mg        INTEGER NOT NULL,
    closed_at INTEGER NOT NULL,
    closed_by INTEGER REFERENCES devices (id)
);

-- +goose Down
DROP TABLE sensorless_check_closed;
DROP TABLE sensorless;
