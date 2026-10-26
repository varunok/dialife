-- +goose Up
CREATE TABLE settings (
    key        TEXT    PRIMARY KEY,
    value      TEXT    NOT NULL, -- JSON
    updated_at INTEGER NOT NULL
);

-- +goose Down
DROP TABLE settings;
