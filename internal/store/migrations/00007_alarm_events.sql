-- +goose Up
-- Every step of an episode, for the alarm log: rang, taken, asked, answered,
-- help, coming, over. The alarms row keeps only the current state.
CREATE TABLE alarm_events (
    id       INTEGER PRIMARY KEY,
    alarm_id INTEGER NOT NULL REFERENCES alarms (id),
    at       INTEGER NOT NULL, -- unix ms
    type     TEXT    NOT NULL, -- the SSE event type: alarm, handled, checkin, checkin_ok, help, coming, clear
    reason   TEXT,             -- for "alarm": why it rang
    by_name  TEXT,             -- who acted; for a re-ring: who had it
    sgv      INTEGER           -- mg/dL at that moment
);
CREATE INDEX alarm_events_alarm ON alarm_events (alarm_id, at);

-- +goose Down
DROP TABLE alarm_events;
