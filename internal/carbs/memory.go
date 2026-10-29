package carbs

import (
	"math"
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"dialife/internal/store"
)

// Mine is what the family logged for this food before: the newest entry with
// the same name and a portion it can be scaled from.
type Mine struct {
	Carbs   int    `json:"carbs"`
	Portion string `json:"portion"` // of that entry: "тарілка", "150 г"
	Times   int    `json:"times"`   // how often this food was logged
	// found by meaning, not by name: the family's food it was taken from
	Like  string  `json:"like,omitempty"`
	Score float32 `json:"score,omitempty"`
}

// norm: lower case, letters and digits, single spaces.
func norm(s string) string {
	var b strings.Builder
	space := false
	for _, r := range strings.ToLower(strings.TrimSpace(s)) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '.' || r == ',':
			if space && b.Len() > 0 {
				b.WriteByte(' ')
			}
			b.WriteRune(r)
			space = false
		default:
			space = true
		}
	}
	return strings.ReplaceAll(b.String(), ",", ".")
}

// portion: "150 г" → 150 "g"; "0,5 л" → 500 "ml"; "1 чашка (250 мл)" → 250 "ml";
// "2 скибки" → 2 "скиб"; "2 шт" → 2 ""; "тарілка" → 1 "тарі".
type portion struct {
	n    float64
	unit string // "g", "ml", "" for pieces or a bare number, else the first word's stem
}

var (
	numRe     = regexp.MustCompile(`^(\d+(?:\.\d+)?)\s*(.*)$`)
	measureRe = regexp.MustCompile(`(\d+(?:\.\d+)?)\s*(мл|ml|cc|куб|л|l|літр\S*|литр\S*|litr\S*|кг|kg|г|гр|грам\S*|g|gr|gram\S*)(?:\s|$)`)
)

// parsePortion reads a weight or volume wherever it is in the text, else a count.
func parsePortion(s string) portion {
	if m := measureRe.FindStringSubmatch(norm(s)); m != nil {
		n, _ := strconv.ParseFloat(m[1], 64)
		switch u := m[2]; {
		case u == "кг" || u == "kg":
			return portion{n * 1000, "g"}
		case u == "л" || u == "l" || strings.HasPrefix(u, "літр") || strings.HasPrefix(u, "литр") || strings.HasPrefix(u, "litr"):
			return portion{n * 1000, "ml"}
		case u == "мл" || u == "ml" || u == "cc" || u == "куб":
			return portion{n, "ml"}
		default:
			return portion{n, "g"}
		}
	}
	return countOf(s)
}

// countOf reads only the count: "1 чашка (250 мл)" → 1 "чашк", "пів склянки" → 0.5 "скля".
func countOf(s string) portion {
	s = norm(s)
	p := portion{n: 1}
	if m := numRe.FindStringSubmatch(s); m != nil {
		p.n, _ = strconv.ParseFloat(m[1], 64)
		s = m[2]
	} else if strings.HasPrefix(s, "пів") || strings.HasPrefix(s, "половин") {
		p.n, s = 0.5, strings.TrimPrefix(strings.TrimPrefix(s, "пів"), " ")
	}
	if w := strings.Fields(s); len(w) > 0 {
		s = w[0]
	}
	if pieces(s) {
		return portion{p.n, ""}
	}
	// "скибка" and "скибки" are one unit: compare the stem
	r := []rune(s)
	if len(r) > 4 {
		r = r[:4]
	}
	p.unit = string(r)
	return p
}

// ratio is how many of [have] the [want] portion is. A bare number is the
// same unit when it is large enough to be grams or millilitres ("200" of
// milk), else a count of whole portions ("2" of milk is two of them).
func ratio(want, have portion) (float64, bool) {
	if want.n <= 0 || have.n <= 0 {
		return 0, false
	}
	switch {
	case want.unit == have.unit:
		return want.n / have.n, true
	case want.unit == "" && (have.unit == "g" || have.unit == "ml"):
		if want.n >= 20 {
			return want.n / have.n, true
		}
		return want.n, true
	}
	return 0, false
}

// scaleBy: the first way [want] can be counted in one of [have].
func scaleBy(want string, have []portion) (float64, bool) {
	for _, w := range []portion{parsePortion(want), countOf(want)} {
		for _, h := range have {
			if r, ok := ratio(w, h); ok {
				return r, true
			}
		}
	}
	return 0, false
}

// recall finds the family's own number for a food: the newest entry of that
// name whose portion is in the same unit, scaled to the one asked about; with
// no portion asked, the newest entry as it was.
func recall(items []store.FoodItem, it Item) *Mine {
	name := norm(it.Name)
	var mine *Mine
	times := 0
	for _, f := range items { // newest first
		if norm(f.Name) != name {
			continue
		}
		times++
		if mine != nil {
			continue
		}
		if strings.TrimSpace(it.Portion) == "" {
			// no portion typed: the family's usual one, as last logged
			mine = &Mine{Carbs: int(math.Round(f.Carbs)), Portion: f.Portion}
			continue
		}
		r, ok := scaleBy(it.Portion, []portion{parsePortion(f.Portion), countOf(f.Portion)})
		if !ok {
			continue
		}
		mine = &Mine{Carbs: int(math.Round(f.Carbs * r)), Portion: f.Portion}
	}
	if mine != nil {
		mine.Times = times
	}
	return mine
}
