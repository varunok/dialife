package store

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

// Langs maps a language code to text: {"uk": "Банан", "en": "Banana"}.
type Langs map[string]string

// FoodSource is one imported carbohydrate table.
type FoodSource struct {
	Source string
	Title  string
	Note   Langs
	At     int64
}

type FoodCategory struct {
	ID    string
	Names Langs
	Sort  int
}

// FoodRef is one portion of one food in a table: Carbs grams for Portions.
type FoodRef struct {
	ID    string
	Cat   string
	Carbs int
	Qty   float64
	Unit  string   // "pc", "g", "ml" or "" (not scaled)
	Grams *float64 // the portion's weight, when the table gives it
	// carbohydrate per 100 g and its source (a composition table or a label):
	// a portion typed in grams is counted from it
	Carbs100    *float64
	Carbs100Src string
	// for information only, where the source gives them: fibre and available carbohydrate per 100 g
	Fiber100, Avail100 *float64
	Names              Langs
	Portions           Langs
	Aliases            []string
	Sort               int
}

// FoodTable is a whole source with what it holds.
type FoodTable struct {
	FoodSource
	Categories []FoodCategory
	Items      []FoodRef
}

// ReplaceFoodTable puts a table in place of the one with the same source.
func (s *Store) ReplaceFoodTable(ctx context.Context, t FoodTable) error {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{`DELETE FROM food_ref WHERE source = ?`, `DELETE FROM food_categories WHERE source = ?`, `DELETE FROM food_sources WHERE source = ?`} {
		if _, err := tx.ExecContext(ctx, q, t.Source); err != nil {
			return err
		}
	}
	note, _ := json.Marshal(t.Note)
	if _, err := tx.ExecContext(ctx, `INSERT INTO food_sources (source, title, note, at) VALUES (?, ?, ?, ?)`, t.Source, t.Title, string(note), t.At); err != nil {
		return err
	}
	for _, c := range t.Categories {
		names, _ := json.Marshal(c.Names)
		if _, err := tx.ExecContext(ctx, `INSERT INTO food_categories (source, id, names, sort) VALUES (?, ?, ?, ?)`, t.Source, c.ID, string(names), c.Sort); err != nil {
			return fmt.Errorf("category %s: %w", c.ID, err)
		}
	}
	for _, f := range t.Items {
		names, _ := json.Marshal(f.Names)
		portions, _ := json.Marshal(f.Portions)
		aliases, _ := json.Marshal(f.Aliases)
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO food_ref (source, id, cat, carbs, qty, unit, grams, carbs100, carbs100_src, fiber100, avail100, names, portions, aliases, sort)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			t.Source, f.ID, f.Cat, f.Carbs, f.Qty, f.Unit, f.Grams, f.Carbs100, f.Carbs100Src, f.Fiber100, f.Avail100, string(names), string(portions), string(aliases), f.Sort); err != nil {
			return fmt.Errorf("food %s: %w", f.ID, err)
		}
	}
	return tx.Commit()
}

// FoodTables returns every imported table, oldest import first.
func (s *Store) FoodTables(ctx context.Context) ([]FoodTable, error) {
	rows, err := s.r.QueryContext(ctx, `SELECT source, title, note, at FROM food_sources ORDER BY at, source`)
	if err != nil {
		return nil, err
	}
	var out []FoodTable
	for rows.Next() {
		var t FoodTable
		var note string
		if err := rows.Scan(&t.Source, &t.Title, &note, &t.At); err != nil {
			rows.Close()
			return nil, err
		}
		_ = json.Unmarshal([]byte(note), &t.Note)
		out = append(out, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for i := range out {
		if out[i].Categories, err = s.foodCategories(ctx, out[i].Source); err != nil {
			return nil, err
		}
		if out[i].Items, err = s.foodRefs(ctx, out[i].Source); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func (s *Store) foodCategories(ctx context.Context, source string) ([]FoodCategory, error) {
	rows, err := s.r.QueryContext(ctx, `SELECT id, names, sort FROM food_categories WHERE source = ? ORDER BY sort, id`, source)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FoodCategory
	for rows.Next() {
		var c FoodCategory
		var names string
		if err := rows.Scan(&c.ID, &names, &c.Sort); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(names), &c.Names)
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) foodRefs(ctx context.Context, source string) ([]FoodRef, error) {
	rows, err := s.r.QueryContext(ctx,
		`SELECT id, cat, carbs, qty, unit, grams, carbs100, carbs100_src, fiber100, avail100, names, portions, aliases, sort FROM food_ref WHERE source = ? ORDER BY sort, id`, source)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FoodRef
	for rows.Next() {
		var f FoodRef
		var names, portions, aliases string
		if err := rows.Scan(&f.ID, &f.Cat, &f.Carbs, &f.Qty, &f.Unit, &f.Grams, &f.Carbs100, &f.Carbs100Src, &f.Fiber100, &f.Avail100, &names, &portions, &aliases, &f.Sort); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(names), &f.Names)
		_ = json.Unmarshal([]byte(portions), &f.Portions)
		_ = json.Unmarshal([]byte(aliases), &f.Aliases)
		out = append(out, f)
	}
	return out, rows.Err()
}

// FoodVectors returns the stored vectors of these texts under a model; a text
// with none is left out.
func (s *Store) FoodVectors(ctx context.Context, model string, texts []string) (map[string][]float32, error) {
	out := map[string][]float32{}
	const chunk = 400 // SQLite's bound parameters
	for start := 0; start < len(texts); start += chunk {
		part := texts[start:min(start+chunk, len(texts))]
		args := []any{model}
		for _, t := range part {
			args = append(args, t)
		}
		rows, err := s.r.QueryContext(ctx,
			`SELECT text, vec FROM food_vectors WHERE model = ? AND text IN (?`+strings.Repeat(",?", len(part)-1)+`)`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var t string
			var b []byte
			if err := rows.Scan(&t, &b); err != nil {
				rows.Close()
				return nil, err
			}
			v := make([]float32, len(b)/4)
			for i := range v {
				v[i] = math.Float32frombits(binary.LittleEndian.Uint32(b[i*4:]))
			}
			out[t] = v
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func (s *Store) SaveFoodVectors(ctx context.Context, model string, vs map[string][]float32) error {
	tx, err := s.w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for t, v := range vs {
		b := make([]byte, 4*len(v))
		for i, x := range v {
			binary.LittleEndian.PutUint32(b[i*4:], math.Float32bits(x))
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO food_vectors (model, text, vec) VALUES (?, ?, ?)
			ON CONFLICT (model, text) DO UPDATE SET vec = excluded.vec`, model, t, b); err != nil {
			return err
		}
	}
	return tx.Commit()
}
