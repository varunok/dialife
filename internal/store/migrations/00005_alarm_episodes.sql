-- +goose Up
-- Alarms become episodes: someone takes them ("I'm on it") and the server
-- only wakes people again when things get worse, instead of on a timer.
ALTER TABLE alarms RENAME COLUMN acked_by TO handled_by;
ALTER TABLE alarms RENAME COLUMN acked_at TO handled_at;
ALTER TABLE alarms RENAME COLUMN snooze_until TO quiet_until;
ALTER TABLE alarms DROP COLUMN acked_level;
ALTER TABLE alarms ADD COLUMN handled_sgv INTEGER;     -- value when taken; "worse" is measured from it
ALTER TABLE alarms ADD COLUMN next_checkin_at INTEGER; -- when to quietly ask the handler "all under control?"
ALTER TABLE alarms ADD COLUMN checkin_sent_at INTEGER; -- pending check-in, unanswered
ALTER TABLE alarms ADD COLUMN help_at INTEGER;         -- handler asked for help
ALTER TABLE alarms ADD COLUMN reason TEXT;             -- why it last rang
ALTER TABLE alarms ADD COLUMN resolved_by INTEGER REFERENCES devices (id);
ALTER TABLE alarms ADD COLUMN resolved_manually INTEGER NOT NULL DEFAULT 0;
ALTER TABLE alarms ADD COLUMN suppress_until INTEGER;  -- after a manual resolve: no new episode of this kind unless worse

CREATE INDEX alarms_kind_cleared ON alarms (kind, cleared_at);

-- +goose Down
DROP INDEX alarms_kind_cleared;
ALTER TABLE alarms DROP COLUMN suppress_until;
ALTER TABLE alarms DROP COLUMN resolved_manually;
ALTER TABLE alarms DROP COLUMN resolved_by;
ALTER TABLE alarms DROP COLUMN reason;
ALTER TABLE alarms DROP COLUMN help_at;
ALTER TABLE alarms DROP COLUMN checkin_sent_at;
ALTER TABLE alarms DROP COLUMN next_checkin_at;
ALTER TABLE alarms DROP COLUMN handled_sgv;
ALTER TABLE alarms ADD COLUMN acked_level TEXT;
ALTER TABLE alarms RENAME COLUMN quiet_until TO snooze_until;
ALTER TABLE alarms RENAME COLUMN handled_at TO acked_at;
ALTER TABLE alarms RENAME COLUMN handled_by TO acked_by;
