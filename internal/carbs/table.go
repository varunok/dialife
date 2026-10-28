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
// the same way: grams by the portion's weight, millilitres, or pieces.
// Anything else (a cup, a plate) keeps the table's own number.
func scale(f store.FoodRef, portion string) (int, bool) {
	if strings.TrimSpace(portion) == "" || f.Carbs == 0 {
		return f.Carbs, false
	}
	p := parsePortion(portion)
	if p.n <= 0 {
		return f.Carbs, false
	}
	var per float64 // the table's portion, in the typed unit
	switch {
	case p.unit == "g" && f.Grams != nil && *f.Grams > 0:
		per = *f.Grams
	case p.unit == "ml" && f.Unit == "ml" && f.Qty > 0:
		per = f.Qty
	case f.Unit == "pc" && f.Qty > 0 && pieces(p.unit):
		per = f.Qty
	default:
		return f.Carbs, false
	}
	return int(math.Round(float64(f.Carbs) * p.n / per)), true
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
