-- +goose Up
CREATE TABLE devices (
    id            INTEGER PRIMARY KEY,
    name          TEXT    NOT NULL,
    role          TEXT    NOT NULL CHECK (role IN ('uploader', 'caregiver', 'reader')),
    display_units TEXT    NOT NULL DEFAULT 'mg/dl' CHECK (display_units IN ('mg/dl', 'mmol')),
    token_sha1    TEXT    NOT NULL UNIQUE,
    created_at    INTEGER NOT NULL, -- unix ms
    last_seen_at  INTEGER,
    revoked_at    INTEGER
);

-- a name can be reused once the old device is revoked
CREATE UNIQUE INDEX devices_active_name ON devices (name) WHERE revoked_at IS NULL;

-- +goose Down
DROP TABLE devices;
