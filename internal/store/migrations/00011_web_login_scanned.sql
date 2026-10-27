-- +goose Up
-- A scanned code waits for the parent's answer: it lives on, and the browser stops renewing it.
ALTER TABLE web_logins ADD COLUMN scanned_at INTEGER;

-- +goose Down
ALTER TABLE web_logins DROP COLUMN scanned_at;
