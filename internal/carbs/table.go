package carbs

import (
	"math"
	"slices"
	"strings"

	"dialife/internal/store"
)

// maxTable: at most this many table portions per typed food ("рис" has four).
const maxTable = 4

// TableHit is one portion from an imported carbohydrate table that matches a
// typed food: Carbs for that portion, or for the typed one when it could be
// scaled (Scaled).
type TableHit struct {
	ID       string      `json:"id"`
	Carbs    int         `json:"carbs"`
	Base     int         `json:"base"` // grams for the table's own portion
	Scaled   bool        `json:"scaled,omitempty"`
	Names    store.Langs `json:"names"`
	Portions store.Langs `json:"portions"`
	Source   string      `json:"source"`
	Score    float32     `json:"score,omitempty"` // a look-alike: how close the names are, 0–1
}

type tableEntry struct {
	ref    store.FoodRef
	source string
	keys   []string // normalized names in every language, and the aliases
}

func tableIndex(tables []store.FoodTable) []tableEntry {
	var out []tableEntry
	for _, t := range tables {
		for _, f := range t.Items {
			e := tableEntry{ref: f, source: t.Source}
			for _, n := range f.Names {
				e.keys = append(e.keys, norm(n))
			}
			for _, a := range f.Aliases {
				e.keys = append(e.keys, norm(a))
			}
			out = append(out, e)
		}
	}
	return out
}

// lookup finds a typed food in the tables: an exact name first; then a name
// holding every typed word ("сир" is "Сир (твердий)"); then one whose words
// begin with every typed word ("сік апельс" finds the orange juice). Words
// shorter than three letters never match by their beginning.
func lookup(index []tableEntry, it Item) []TableHit {
	q := norm(it.Name)
	if q == "" {
		return nil
	}
	words := strings.Fields(q)
	var exact, whole, loose []tableEntry
	for _, e := range index {
		switch {
		case hasKey(e.keys, q):
			exact = append(exact, e)
		case wordsMatch(e.keys, words, false):
			whole = append(whole, e)
		case wordsMatch(e.keys, words, true):
			loose = append(loose, e)
		}
	}
	found := exact
	if len(found) == 0 {
		found = whole
	}
	if len(found) == 0 {
		found = loose
	}
	if len(found) > maxTable {
		found = found[:maxTable]
	}
	out := make([]TableHit, 0, len(found))
	for _, e := range found {
		c, scaled := scale(e.ref, it.Portion)
		out = append(out, TableHit{ID: e.ref.ID, Carbs: c, Base: e.ref.Carbs, Scaled: scaled, Names: e.ref.Names, Portions: e.ref.Portions, Source: e.source})
	}
	return out
}

func hasKey(keys []string, q string) bool {
	for _, k := range keys {
		if k == q {
			return true
		}
	}
	return false
}

// wordsMatch: some key has every word, whole or (prefix) at a word's start.
func wordsMatch(keys []string, words []string, prefix bool) bool {
	if prefix {
		for _, w := range words {
			if len([]rune(w)) < 3 {
				return false
			}
		}
	}
	for _, k := range keys {
		kw := strings.Fields(k)
		all := true
		for _, w := range words {
			hit := false
			for _, x := range kw {
				if x == w || prefix && strings.HasPrefix(x, w) {
					hit = true
					break
				}
			}
			if !hit {
				all = false
				break
			}
		}
		if all {
			return true
		}
	}
	return false
}

// scale turns the table's portion into the typed one when both are counted
// the same way: grams by the portion's weight, millilitres, pieces, or the
// portion's own words ("2 чашки" of "1 чашка (250 мл)"). Anything else (a
// plate of what the table counts in cups) keeps the table's own number.
func scale(f store.FoodRef, typed string) (int, bool) {
	if strings.TrimSpace(typed) == "" || f.Carbs == 0 {
		return f.Carbs, false
	}
	var have []portion
	if f.Grams != nil && *f.Grams > 0 {
		have = append(have, portion{*f.Grams, "g"})
	}
	if f.Qty > 0 && (f.Unit == "ml" || f.Unit == "pc") {
		have = append(have, portion{f.Qty, map[string]string{"ml": "ml", "pc": ""}[f.Unit]})
	}
	for _, l := range []string{"uk", "en", "es"} {
		if t := strings.TrimSpace(f.Portions[l]); t != "" {
			if c := countOf(t); c.unit != "" && c.unit != "g" && c.unit != "ml" {
				have = append(have, c)
			}
		}
	}
	r, ok := scaleBy(typed, have)
	if !ok {
		return f.Carbs, false
	}
	return int(math.Round(float64(f.Carbs) * r)), true
}

// pieces: "2", "2 шт", "2 штуки", "2 pcs", "2 unidades".
func pieces(unit string) bool {
	switch unit {
	case "", "шт", "штук", "штуки", "штука", "pc", "pcs", "piec", "unid", "u":
		return true
	}
	return false
}

// Frequent is a food the family logs often, for the quick buttons.
type Frequent struct {
	Name    string `json:"name"`
	Portion string `json:"portion,omitempty"`
	Carbs   int    `json:"carbs"` // as last logged
	Times   int    `json:"times"`
}

// frequent: the most logged foods, newest spelling and portion of each.
func frequent(items []store.FoodItem, n int) []Frequent {
	var order []string
	by := map[string]*Frequent{}
	for _, f := range items { // newest first
		k := norm(f.Name)
		if k == "" {
			continue
		}
		if e, ok := by[k]; ok {
			e.Times++
			continue
		}
		by[k] = &Frequent{Name: strings.TrimSpace(f.Name), Portion: strings.TrimSpace(f.Portion), Carbs: int(math.Round(f.Carbs)), Times: 1}
		order = append(order, k)
	}
	out := make([]Frequent, 0, len(order))
	for _, k := range order {
		out = append(out, *by[k])
	}
	// most logged first; a tie keeps the newer one first
	slices.SortStableFunc(out, func(a, b Frequent) int { return b.Times - a.Times })
	if len(out) > n {
		out = out[:n]
	}
	return out
}
