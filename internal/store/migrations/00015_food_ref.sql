-- +goose Up
-- A carbohydrate table printed for families (a glucometer leaflet, a clinic's
-- handout), imported with `dialife foods import`. Names and portions are JSON
-- objects keyed by language: {"uk": "...", "en": "...", "es": "..."}.
CREATE TABLE food_sources (
    source TEXT    PRIMARY KEY,
    title  TEXT    NOT NULL,
    note   TEXT    NOT NULL DEFAULT '{}',
    at     INTEGER NOT NULL
);

CREATE TABLE food_categories (
    source TEXT    NOT NULL REFERENCES food_sources (source) ON DELETE CASCADE,
    id     TEXT    NOT NULL,
    names  TEXT    NOT NULL,
    sort   INTEGER NOT NULL,
    PRIMARY KEY (source, id)
);

-- One portion of one food. qty/unit say what the portion is so a typed one can
-- be scaled: unit "pc" (pieces), "g", "ml", or "" (a cup, a spoon: not scaled);
-- grams is the portion's weight when the table gives it.
CREATE TABLE food_ref (
    source   TEXT    NOT NULL REFERENCES food_sources (source) ON DELETE CASCADE,
    id       TEXT    NOT NULL,
    cat      TEXT    NOT NULL,
    carbs    INTEGER NOT NULL,
    qty      REAL    NOT NULL,
    unit     TEXT    NOT NULL,
    grams    REAL,
    names    TEXT    NOT NULL,
    portions TEXT    NOT NULL,
    aliases  TEXT    NOT NULL DEFAULT '[]',
    sort     INTEGER NOT NULL,
    PRIMARY KEY (source, id)
);

-- +goose Down
DROP TABLE food_ref;
DROP TABLE food_categories;
DROP TABLE food_sources;
