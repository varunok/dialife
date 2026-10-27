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

// portion: "150 г" → 150 "g"; "2 скибки" → 2 "скиб"; "тарілка" → 1 "тарі"; "" → 1 "".
type portion struct {
	n    float64
	unit string
}

var numRe = regexp.MustCompile(`^(\d+(?:\.\d+)?)\s*(.*)$`)

func parsePortion(s string) portion {
	s = norm(s)
	p := portion{n: 1}
	if m := numRe.FindStringSubmatch(s); m != nil {
		p.n, _ = strconv.ParseFloat(m[1], 64)
		s = m[2]
	} else if strings.HasPrefix(s, "пів") || strings.HasPrefix(s, "половин") {
		p.n, s = 0.5, strings.TrimPrefix(strings.TrimPrefix(s, "пів"), " ")
	}
	switch s {
	case "г", "гр", "грам", "грамів", "g", "gr":
		p.unit = "g"
	case "мл", "ml":
		p.unit = "ml"
	default:
		// "скибка" and "скибки" are one unit: compare the stem
		r := []rune(s)
		if len(r) > 4 {
			r = r[:4]
		}
		p.unit = string(r)
	}
	return p
}

// recall finds the family's own number for a food: the newest entry of that
// name whose portion is in the same unit, scaled to the one asked about; with
// no portion asked, the newest entry as it was.
func recall(items []store.FoodItem, it Item) *Mine {
	name := norm(it.Name)
	want := parsePortion(it.Portion)
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
		have := parsePortion(f.Portion)
		if have.unit != want.unit || have.n <= 0 {
			continue
		}
		mine = &Mine{Carbs: int(math.Round(f.Carbs * want.n / have.n)), Portion: f.Portion}
	}
	if mine != nil {
		mine.Times = times
	}
	return mine
}
