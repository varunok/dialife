-- +goose Up
-- Carbohydrate per 100 g from a food composition table or label, and where it
-- comes from: a portion typed in grams is counted from it.
ALTER TABLE food_ref ADD COLUMN carbs100 REAL;
ALTER TABLE food_ref ADD COLUMN carbs100_src TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE food_ref DROP COLUMN carbs100_src;
ALTER TABLE food_ref DROP COLUMN carbs100;
