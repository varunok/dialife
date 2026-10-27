-- +goose Up
-- What the language models said for a food and portion: asked once, reused
-- (key: the normalized "name|portion").
CREATE TABLE carb_estimates (
    key    TEXT    PRIMARY KEY,
    carbs  INTEGER NOT NULL,
    lo     INTEGER NOT NULL,
    hi     INTEGER NOT NULL,
    models INTEGER NOT NULL,
    at     INTEGER NOT NULL
);

-- +goose Down
DROP TABLE carb_estimates;
