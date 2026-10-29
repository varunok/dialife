-- +goose Up
-- Carbohydrate per 100 g from a food composition table or label, and where it
-- comes from: a portion typed in grams is counted from it.
ALTER TABLE food_ref ADD COLUMN carbs100 REAL;
ALTER TABLE food_ref ADD COLUMN carbs100_src TEXT NOT NULL DEFAULT '';
-- shown for information, never counted: fibre and available carbohydrate per 100 g
ALTER TABLE food_ref ADD COLUMN fiber100 REAL;
ALTER TABLE food_ref ADD COLUMN avail100 REAL;

-- +goose Down
ALTER TABLE food_ref DROP COLUMN avail100;
ALTER TABLE food_ref DROP COLUMN fiber100;
ALTER TABLE food_ref DROP COLUMN carbs100_src;
ALTER TABLE food_ref DROP COLUMN carbs100;
