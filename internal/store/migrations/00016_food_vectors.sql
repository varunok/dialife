-- +goose Up
-- Embedding vectors of food names, by model: the table's names and aliases,
-- the family's own foods and what was typed. Little-endian float32, unit length.
CREATE TABLE food_vectors (
    model TEXT NOT NULL,
    text  TEXT NOT NULL, -- normalized
    vec   BLOB NOT NULL,
    PRIMARY KEY (model, text)
);

-- +goose Down
DROP TABLE food_vectors;
