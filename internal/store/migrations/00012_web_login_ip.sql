-- +goose Up
-- Where the browser asked from: the app shows whether it is the phone's own network.
ALTER TABLE web_logins ADD COLUMN ip TEXT;

-- +goose Down
ALTER TABLE web_logins DROP COLUMN ip;
