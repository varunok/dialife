package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"dialife/internal/config"
	"dialife/internal/store"
)

// foodFile is the import format: one carbohydrate table, every name and
// portion in each language the app speaks.
type foodFile struct {
	Source     string      `json:"source"`
	Title      string      `json:"title"`
	Note       store.Langs `json:"note"`
	Categories []struct {
		ID    string      `json:"id"`
		Names store.Langs `json:"names"`
		Sort  int         `json:"sort"`
	} `json:"categories"`
	Items []struct {
		ID       string      `json:"id"`
		Cat      string      `json:"cat"`
		Carbs    int         `json:"carbs"`
		Qty      float64     `json:"qty"`
		Unit     string      `json:"unit"`
		Grams    *float64    `json:"grams"`
		Names    store.Langs `json:"names"`
		Portions store.Langs `json:"portions"`
		Aliases  []string    `json:"aliases"`
		Sort     int         `json:"sort"`
	} `json:"items"`
}

var foodLangs = []string{"uk", "en", "es"}

func foods(cfg config.Config, args []string) error {
	if len(args) == 0 || (args[0] != "import" && args[0] != "show") || (args[0] == "import" && len(args) != 2) {
		return errors.New("foods: want import FILE (- for stdin) or show")
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	ctx := context.Background()
	if args[0] == "show" {
		ts, err := s.FoodTables(ctx)
		if err != nil {
			return err
		}
		tw := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
		fmt.Fprintln(tw, "SOURCE\tCATEGORIES\tFOODS\tIMPORTED\tTITLE")
		for _, t := range ts {
			fmt.Fprintf(tw, "%s\t%d\t%d\t%s\t%s\n", t.Source, len(t.Categories), len(t.Items), time.UnixMilli(t.At).Format("2006-01-02 15:04"), t.Title)
		}
		return tw.Flush()
	}

	var r io.Reader = os.Stdin
	if args[1] != "-" {
		f, err := os.Open(args[1])
		if err != nil {
			return err
		}
		defer f.Close()
		r = f
	}
	t, err := readFoodFile(r)
	if err != nil {
		return err
	}
	t.At = time.Now().UnixMilli()
	if err := s.ReplaceFoodTable(ctx, t); err != nil {
		return err
	}
	fmt.Printf("%s: %d categories, %d foods\n", t.Source, len(t.Categories), len(t.Items))
	return nil
}

// readFoodFile checks a table before anything is written: known categories,
// unique ids, every name and portion in every language.
func readFoodFile(r io.Reader) (store.FoodTable, error) {
	var f foodFile
	if err := json.NewDecoder(r).Decode(&f); err != nil {
		return store.FoodTable{}, fmt.Errorf("foods file: %w", err)
	}
	if f.Source == "" || f.Title == "" || len(f.Items) == 0 {
		return store.FoodTable{}, errors.New("foods file: source, title and items are required")
	}
	t := store.FoodTable{FoodSource: store.FoodSource{Source: f.Source, Title: f.Title, Note: f.Note}}
	cats := map[string]bool{}
	for _, c := range f.Categories {
		if c.ID == "" || cats[c.ID] || missing(c.Names) != "" {
			return store.FoodTable{}, fmt.Errorf("foods file: category %q: empty, repeated or not in every language", c.ID)
		}
		cats[c.ID] = true
		t.Categories = append(t.Categories, store.FoodCategory{ID: c.ID, Names: c.Names, Sort: c.Sort})
	}
	ids := map[string]bool{}
	var probs []string
	for _, it := range f.Items {
		switch {
		case it.ID == "" || ids[it.ID]:
			probs = append(probs, fmt.Sprintf("%q: empty or repeated id", it.ID))
		case !cats[it.Cat]:
			probs = append(probs, fmt.Sprintf("%s: unknown category %q", it.ID, it.Cat))
		case it.Carbs < 0 || it.Carbs > 500 || it.Qty < 0:
			probs = append(probs, it.ID+": carbs or qty out of range")
		case it.Unit != "" && it.Unit != "pc" && it.Unit != "g" && it.Unit != "ml":
			probs = append(probs, fmt.Sprintf("%s: unit %q is not pc, g, ml or empty", it.ID, it.Unit))
		case missing(it.Names) != "":
			probs = append(probs, it.ID+": no name in "+missing(it.Names))
		case it.Carbs > 0 && missing(it.Portions) != "":
			probs = append(probs, it.ID+": no portion in "+missing(it.Portions))
		}
		ids[it.ID] = true
		t.Items = append(t.Items, store.FoodRef{ID: it.ID, Cat: it.Cat, Carbs: it.Carbs, Qty: it.Qty, Unit: it.Unit, Grams: it.Grams,
			Names: it.Names, Portions: it.Portions, Aliases: it.Aliases, Sort: it.Sort})
	}
	if len(probs) > 0 {
		return store.FoodTable{}, errors.New("foods file:\n  " + strings.Join(probs, "\n  "))
	}
	return t, nil
}

// missing names the first language with no text, or "".
func missing(l store.Langs) string {
	for _, lang := range foodLangs {
		if strings.TrimSpace(l[lang]) == "" {
			return lang
		}
	}
	return ""
}
