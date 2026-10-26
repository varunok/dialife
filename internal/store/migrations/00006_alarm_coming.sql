-- +goose Up
-- "I'm coming": someone answers a help request without taking the episode over.
ALTER TABLE alarms ADD COLUMN coming_by INTEGER REFERENCES devices (id);
ALTER TABLE alarms ADD COLUMN coming_at INTEGER;

-- +goose Down
ALTER TABLE alarms DROP COLUMN coming_at;
ALTER TABLE alarms DROP COLUMN coming_by;
