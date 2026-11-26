-- +goose Up
-- "Measure again in 15 minutes": carbs logged while glucose was low. One row
-- per such carbs entry; closed_at set once the recheck came back in range,
-- someone closed it, newer carbs replaced it or it ran out.
CREATE TABLE recheck (
    id             INTEGER PRIMARY KEY,
    treatment_id   TEXT    NOT NULL UNIQUE,
    carbs_t        INTEGER NOT NULL,         -- unix ms, the carbs entry's time
    carbs          REAL    NOT NULL,         -- grams
    low_kind       TEXT    NOT NULL CHECK (low_kind IN ('alarm', 'sensor', 'meter')),
    low_mg         INTEGER,                  -- the low value seen, when there was one
    due_at         INTEGER NOT NULL,         -- carbs_t + 15 min
    reminded       INTEGER NOT NULL DEFAULT 0, -- the push "measure now" went out
    check_t        INTEGER,                  -- the recheck: a glucometer check after the carbs
    check_mg       INTEGER,
    still_told     INTEGER NOT NULL DEFAULT 0, -- the push "still low" went out for check_t
    closed_at      INTEGER,
    outcome        TEXT    CHECK (outcome IN ('in_range', 'closed', 'again', 'expired', 'removed')),
    closed_by      INTEGER REFERENCES devices (id),
    closed_by_name TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX recheck_open ON recheck (closed_at, carbs_t);

-- +goose Down
DROP TABLE recheck;
