-- +goose Up
-- "Already ate" on the wait after rapid insulin and food, and "close" on the
-- "more insulin than food" warning beside it: each closed once, for every
-- phone. Nothing else reads it; dose_id is the insulin entry it was shown for.
CREATE TABLE wait_closed (
    dose_id    TEXT    NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('wait', 'more')),
    closed_at  INTEGER NOT NULL,
    closed_by  INTEGER REFERENCES devices (id),
    PRIMARY KEY (dose_id, kind)
);

-- +goose Down
DROP TABLE wait_closed;
