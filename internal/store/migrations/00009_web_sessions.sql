-- +goose Up
-- Logging into the web viewer: the browser shows a QR code, a parent scans it
-- in the app and allows it for a chosen time. Codes live two minutes.
CREATE TABLE web_logins (
    id           TEXT    PRIMARY KEY, -- random, in the QR link and the browser's polling URL
    code_sha     TEXT    NOT NULL UNIQUE, -- the secret in the QR code
    browser_sha  TEXT    NOT NULL, -- the cookie of the browser that asked: only it gets the session
    user_agent   TEXT,
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    state        TEXT    NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'approved', 'rejected', 'used')),
    approved_by  INTEGER REFERENCES devices (id),
    minutes      INTEGER -- how long the session lasts once approved
);

-- A browser allowed in: read-only, until it expires or anyone revokes it.
CREATE TABLE web_sessions (
    id           INTEGER PRIMARY KEY,
    token_sha    TEXT    NOT NULL UNIQUE, -- the session cookie, hashed
    user_agent   TEXT,
    approved_by  INTEGER NOT NULL REFERENCES devices (id),
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    last_seen_at INTEGER,
    revoked_at   INTEGER,
    revoked_by   TEXT
);

-- +goose Down
DROP TABLE web_sessions;
DROP TABLE web_logins;
