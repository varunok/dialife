-- +goose Up
CREATE TABLE entries (
    id          TEXT    PRIMARY KEY,  -- 24 hex, Nightscout-style _id
    type        TEXT    NOT NULL,     -- sgv | mbg | cal | ...
    date        INTEGER NOT NULL,     -- reading time, unix ms
    sgv         INTEGER,              -- mg/dL
    mbg         INTEGER,              -- mg/dL
    direction   TEXT,
    delta       REAL,
    noise       INTEGER,
    device      TEXT,
    raw         TEXT    NOT NULL,     -- original JSON document
    device_id   INTEGER REFERENCES devices (id),
    received_at INTEGER NOT NULL,
    UNIQUE (type, date)
);
CREATE INDEX entries_date ON entries (date);

CREATE TABLE treatments (
    id         TEXT    PRIMARY KEY,  -- 24 hex; xDrip+ derives it from uuid
    uuid       TEXT,
    event_type TEXT,
    created_at INTEGER NOT NULL,     -- unix ms
    insulin    REAL,
    carbs      REAL,
    notes      TEXT,
    entered_by TEXT,
    raw        TEXT    NOT NULL,
    device_id  INTEGER REFERENCES devices (id),
    updated_at INTEGER NOT NULL,     -- drives Last-Modified, deletes included
    deleted_at INTEGER
);
CREATE INDEX treatments_created ON treatments (created_at);
CREATE INDEX treatments_uuid ON treatments (uuid);

CREATE TABLE devicestatus (
    id               TEXT    PRIMARY KEY,
    created_at       INTEGER NOT NULL,
    device           TEXT,
    uploader_battery INTEGER,
    raw              TEXT    NOT NULL,
    device_id        INTEGER REFERENCES devices (id),
    received_at      INTEGER NOT NULL
);
CREATE INDEX devicestatus_created ON devicestatus (created_at);

-- steps / heart rate from xDrip+; kept raw, unused for now
CREATE TABLE activity (
    id          TEXT    PRIMARY KEY,
    created_at  INTEGER NOT NULL,
    raw         TEXT    NOT NULL,
    device_id   INTEGER REFERENCES devices (id),
    received_at INTEGER NOT NULL
);
CREATE INDEX activity_created ON activity (created_at);

-- +goose Down
DROP TABLE activity;
DROP TABLE devicestatus;
DROP TABLE treatments;
DROP TABLE entries;
