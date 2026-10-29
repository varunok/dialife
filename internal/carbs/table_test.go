package carbs

import (
	"context"
	"testing"
	"time"

	"dialife/internal/store"
)

func grams(g float64) *float64 { return &g }

var sampleTable = store.FoodTable{
	FoodSource: store.FoodSource{Source: "sample", Title: "Sample table"},
	Categories: []store.FoodCategory{{ID: "fruit", Names: store.Langs{"uk": "Фрукти", "en": "Fruit", "es": "Frutas"}}},
	Items: []store.FoodRef{
		{ID: "banana", Cat: "fruit", Carbs: 15, Qty: 1, Unit: "pc", Names: store.Langs{"uk": "Банан", "en": "Banana", "es": "Banana"}, Portions: store.Langs{"uk": "1 маленький"}},
		{ID: "peach", Cat: "fruit", Carbs: 15, Qty: 1, Unit: "pc", Grams: grams(150), Names: store.Langs{"uk": "Персик", "en": "Peach", "es": "Duraznos"}, Aliases: []string{"персики"}},
		{ID: "juice", Cat: "fruit", Carbs: 15, Qty: 100, Unit: "ml", Names: store.Langs{"uk": "Сік апельсиновий свіжий", "en": "Fresh orange juice", "es": "Jugo de naranja"}},
		{ID: "pasta-half", Cat: "fruit", Carbs: 30, Qty: 1, Unit: "", Names: store.Langs{"uk": "Макарони варені", "en": "Pasta, cooked"}, Aliases: []string{"макарони"}},
		{ID: "pasta-plate", Cat: "fruit", Carbs: 75, Qty: 1, Unit: "", Names: store.Langs{"uk": "Макарони варені", "en": "Pasta, cooked"}, Aliases: []string{"макарони"}},
		{ID: "tomato", Cat: "fruit", Carbs: 0, Names: store.Langs{"uk": "Помідор", "en": "Tomato", "es": "Tomate"}},
		{ID: "rice-raw", Cat: "fruit", Carbs: 45, Names: store.Langs{"uk": "Рис сирий", "en": "Rice, raw"}},
		{ID: "cheese", Cat: "fruit", Carbs: 0, Names: store.Langs{"uk": "Сир (твердий)", "en": "Cheese"}},
	},
}

func TestLookup(t *testing.T) {
	index := tableIndex([]store.FoodTable{sampleTable})
	cases := []struct {
		it     Item
		ids    []string
		carbs  int
		scaled bool
	}{
		{Item{"банан", ""}, []string{"banana"}, 15, false},
		{Item{"Banana", "2"}, []string{"banana"}, 30, true},
		{Item{"банан", "3 шт"}, []string{"banana"}, 45, true},
		{Item{"банан", "тарілка"}, []string{"banana"}, 15, false},   // not counted the same way: the table's own portion
		{Item{"банан", "150 г"}, []string{"banana"}, 15, false},     // the table gives no weight for it
		{Item{"Duraznos", "300 г"}, []string{"peach"}, 30, true},    // by the portion's weight
		{Item{"персики", ""}, []string{"peach"}, 15, false},         // an alias
		{Item{"сік апельс", "200 мл"}, []string{"juice"}, 30, true}, // every word begins a word of the name
		{Item{"макарони", ""}, []string{"pasta-half", "pasta-plate"}, 30, false},
		{Item{"помідор", "2"}, []string{"tomato"}, 0, false},
		{Item{"сир", ""}, []string{"cheese"}, 0, false},        // a whole word beats "сирий"
		{Item{"рис сир", ""}, []string{"rice-raw"}, 45, false}, // by the words' beginnings
		{Item{"сі", ""}, nil, 0, false},                        // too short to match by its beginning
		{Item{"борщ", ""}, nil, 0, false},
	}
	for _, c := range cases {
		got := lookup(index, c.it)
		if len(got) != len(c.ids) {
			t.Errorf("%+v: got %d hits %+v, want %v", c.it, len(got), got, c.ids)
			continue
		}
		for i, id := range c.ids {
			if got[i].ID != id {
				t.Errorf("%+v: hit %d is %s, want %s", c.it, i, got[i].ID, id)
			}
		}
		if len(got) > 0 && (got[0].Carbs != c.carbs || got[0].Scaled != c.scaled) {
			t.Errorf("%+v: %d g scaled=%v, want %d g scaled=%v", c.it, got[0].Carbs, got[0].Scaled, c.carbs, c.scaled)
		}
	}
}

func TestServiceAsksModelsOnlyWhatTheTableDoesNotKnow(t *testing.T) {
	st := openStore(t)
	if err := st.ReplaceFoodTable(context.Background(), sampleTable); err != nil {
		t.Fatal(err)
	}
	m := &fake{name: "m", carbs: 40}
	s := &Service{Store: st, Models: []Model{m}, Now: time.Now}
	out, err := s.Suggest(context.Background(), 1, []Item{{Name: "Банан"}, {Name: "Борщ"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(out[0].Table) != 1 || out[0].Estimate != nil {
		t.Fatalf("banana: the table, no model: %+v", out[0])
	}
	if len(out[1].Table) != 0 || out[1].Estimate == nil || out[1].Estimate.Carbs != 40 {
		t.Fatalf("borscht: the model: %+v", out[1])
	}
	if m.calls != 1 {
		t.Fatalf("model asked %d times", m.calls)
	}
}

func TestReplaceFoodTable(t *testing.T) {
	st := openStore(t)
	ctx := context.Background()
	if err := st.ReplaceFoodTable(ctx, sampleTable); err != nil {
		t.Fatal(err)
	}
	smaller := sampleTable
	smaller.Items = smaller.Items[:1]
	if err := st.ReplaceFoodTable(ctx, smaller); err != nil {
		t.Fatal(err)
	}
	ts, err := st.FoodTables(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(ts) != 1 || len(ts[0].Items) != 1 || ts[0].Items[0].Names["en"] != "Banana" || len(ts[0].Categories) != 1 {
		t.Fatalf("a new import replaces the old one: %+v", ts)
	}
}

func TestFrequent(t *testing.T) {
	items := []store.FoodItem{ // newest first
		{Name: "Хліб", Portion: "1 скибка", Carbs: 12},
		{Name: "Борщ", Portion: "тарілка", Carbs: 18},
		{Name: "хліб", Portion: "2 скибки", Carbs: 24},
		{Name: "Яблуко", Carbs: 15},
		{Name: "борщ ", Carbs: 18},
	}
	got := frequent(items, 2)
	if len(got) != 2 || got[0] != (Frequent{"Хліб", "1 скибка", 12, 2}) || got[1] != (Frequent{"Борщ", "тарілка", 18, 2}) {
		t.Fatalf("%+v", got)
	}
}

func TestScaleByVolumeAndTheTablesOwnWords(t *testing.T) {
	milk := store.FoodRef{Carbs: 15, Qty: 250, Unit: "ml", Portions: store.Langs{"uk": "1 чашка (250 мл)", "en": "1 cup (250 ml)", "es": "1 taza (250 cc)"}}
	cases := []struct {
		portion string
		carbs   int
		scaled  bool
	}{
		{"200 мл", 12, true},
		{"200", 12, true},            // a bare number this large is millilitres
		{"2", 30, true},              // a small one counts cups
		{"0,5 л", 30, true},          // litres
		{"300 cc", 18, true},         // the Spanish millilitre
		{"2 чашки", 30, true},        // the table's own word
		{"1 taza", 15, true},         // in another language
		{"склянка 200 мл", 12, true}, // a volume anywhere in the text
		{"тарілка", 15, false},       // counted another way: the table's own number
	}
	for _, c := range cases {
		if got, scaled := scale(milk, c.portion); got != c.carbs || scaled != c.scaled {
			t.Errorf("%q: %d scaled=%v, want %d scaled=%v", c.portion, got, scaled, c.carbs, c.scaled)
		}
	}
}
