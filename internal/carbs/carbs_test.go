package carbs

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"dialife/internal/store"
)

func TestParse(t *testing.T) {
	gs, err := parse("```json\n{\"items\":[{\"carbs\":18,\"lo\":14,\"hi\":24},{\"carbs\":-1,\"lo\":0,\"hi\":0},{\"carbs\":9000,\"lo\":1,\"hi\":2}]}\n```", 3)
	if err != nil {
		t.Fatal(err)
	}
	if gs[0] == nil || *gs[0] != (Guess{18, 14, 24}) || gs[1] != nil || gs[2] != nil {
		t.Fatalf("%v %v %v", gs[0], gs[1], gs[2])
	}
	// a range that does not hold the number collapses to it
	gs, _ = parse(`{"items":[{"carbs":20,"lo":30,"hi":5}]}`, 1)
	if *gs[0] != (Guess{20, 20, 20}) {
		t.Fatalf("%v", gs[0])
	}
	if _, err := parse(`{"items":[]}`, 1); err == nil {
		t.Fatal("wrong count accepted")
	}
	if _, err := parse("about 20 grams", 1); err == nil {
		t.Fatal("prose accepted")
	}
}

func TestMerge(t *testing.T) {
	g := func(c, lo, hi int) *Guess { return &Guess{c, lo, hi} }
	es := merge([][]*Guess{{g(18, 14, 24), nil}, nil, {g(22, 20, 30), nil}, {g(30, 25, 35), nil}}, 2)
	if es[0] == nil || *es[0] != (Estimate{Carbs: 22, Lo: 14, Hi: 35, Models: 3}) || es[1] != nil {
		t.Fatalf("%+v %+v", es[0], es[1])
	}
	es = merge([][]*Guess{{g(18, 18, 18)}, {g(21, 21, 21)}}, 1)
	if es[0].Carbs != 20 || es[0].Models != 2 {
		t.Fatalf("two models: %+v", es[0])
	}
}

func TestRecall(t *testing.T) {
	foods := []store.FoodItem{ // newest first
		{Name: "Борщ", Portion: "тарілка", Carbs: 18},
		{Name: "хліб  чорний", Portion: "2 скибки", Carbs: 22},
		{Name: "Хліб чорний", Portion: "1 скибка", Carbs: 12},
		{Name: "гречка", Portion: "150 г", Carbs: 27},
	}
	cases := []struct {
		it   Item
		want *Mine
	}{
		{Item{"борщ", ""}, &Mine{Carbs: 18, Portion: "тарілка", Times: 1}},
		{Item{"Борщ", "2 тарілки"}, &Mine{Carbs: 36, Portion: "тарілка", Times: 1}},
		{Item{"хліб чорний", "3 скибки"}, &Mine{Carbs: 33, Portion: "2 скибки", Times: 2}},
		{Item{"Гречка", "200 г"}, &Mine{Carbs: 36, Portion: "150 г", Times: 1}},
		{Item{"гречка", "тарілка"}, nil}, // grams cannot become plates
		{Item{"банан", ""}, nil},
	}
	for _, c := range cases {
		got := recall(foods, c.it)
		if (got == nil) != (c.want == nil) || got != nil && *got != *c.want {
			t.Errorf("%+v: got %+v, want %+v", c.it, got, c.want)
		}
	}
}

type fake struct {
	name  string
	delay time.Duration
	err   error
	carbs int
	calls int
}

func (f *fake) Name() string { return f.name }
func (f *fake) Guess(ctx context.Context, items []Item) ([]*Guess, error) {
	f.calls++
	select {
	case <-time.After(f.delay):
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if f.err != nil {
		return nil, f.err
	}
	out := make([]*Guess, len(items))
	for i := range items {
		out[i] = &Guess{f.carbs, f.carbs - 2, f.carbs + 2}
	}
	return out, nil
}

func openStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/t.db")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestServiceSlowAndBrokenModels(t *testing.T) {
	ok := &fake{name: "ok", carbs: 20}
	slow := &fake{name: "slow", delay: time.Second, carbs: 90}
	broken := &fake{name: "broken", err: errors.New("HTTP 500")}
	s := &Service{Store: openStore(t), Models: []Model{ok, slow, broken}, Timeout: 50 * time.Millisecond, Now: time.Now}
	out, err := s.Suggest(context.Background(), 1, []Item{{Name: "Банан"}})
	if err != nil {
		t.Fatal(err)
	}
	if e := out[0].Estimate; e == nil || e.Carbs != 20 || e.Models != 1 {
		t.Fatalf("only the model that answered in time counts: %+v", e)
	}
	// asked once: the second time comes from the cache
	s.Suggest(context.Background(), 1, []Item{{Name: " банан "}})
	if ok.calls != 1 {
		t.Fatalf("asked %d times", ok.calls)
	}
}

func TestServiceWithoutModelsAsksNobody(t *testing.T) {
	s := &Service{Store: openStore(t), Now: time.Now}
	out, err := s.Suggest(context.Background(), 1, []Item{{Name: "борщ"}})
	if err != nil || out[0].Estimate != nil || s.Enabled() {
		t.Fatalf("%+v %v", out, err)
	}
}

func TestServiceLimitsAPhone(t *testing.T) {
	m := &fake{name: "m", carbs: 10}
	s := &Service{Store: openStore(t), Models: []Model{m}, Now: time.Now}
	items := make([]Item, perHour)
	for i := range items {
		items[i] = Item{Name: "food " + string(rune('a'+i%26)) + string(rune('a'+i/26))}
	}
	s.Suggest(context.Background(), 7, items)
	out, _ := s.Suggest(context.Background(), 7, []Item{{Name: "one more"}})
	if out[0].Estimate != nil || m.calls != 1 {
		t.Fatalf("past the hourly limit: %+v, %d calls", out[0].Estimate, m.calls)
	}
}

func TestGeminiRequest(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1beta/models/gemini-x:generateContent" || r.Header.Get("x-goog-api-key") != "k" {
			t.Errorf("%s %q", r.URL.Path, r.Header.Get("x-goog-api-key"))
		}
		b, _ := io.ReadAll(r.Body)
		json.Unmarshal(b, &got)
		io.WriteString(w, `{"candidates":[{"content":{"parts":[{"text":"thinking…","thought":true},{"text":"{\"items\":[{\"carbs\":23,\"lo\":18,\"hi\":28}]}"}]}}]}`)
	}))
	defer srv.Close()
	g := &Gemini{Key: "k", Model: "gemini-x", URL: srv.URL}
	gs, err := g.Guess(context.Background(), []Item{{Name: "банан", Portion: "1 шт"}})
	if err != nil || gs[0] == nil || gs[0].Carbs != 23 {
		t.Fatalf("%v %v", gs, err)
	}
	cfg := got["generationConfig"].(map[string]any)
	if cfg["responseMimeType"] != "application/json" || cfg["responseSchema"] == nil {
		t.Fatalf("no JSON schema asked: %v", cfg)
	}
	user := got["contents"].([]any)[0].(map[string]any)["parts"].([]any)[0].(map[string]any)["text"].(string)
	if !strings.Contains(user, `"name":"банан"`) {
		t.Fatalf("items go as JSON data: %s", user)
	}
}

func TestOpenAIErrorsAreReported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer k" {
			t.Errorf("%s %q", r.URL.Path, r.Header.Get("Authorization"))
		}
		http.Error(w, `{"error":"quota"}`, http.StatusTooManyRequests)
	}))
	defer srv.Close()
	o := &OpenAI{URL: srv.URL + "/v1/", Key: "k", Model: "m"}
	if _, err := o.Guess(context.Background(), []Item{{Name: "x"}}); err == nil || !strings.Contains(err.Error(), "429") {
		t.Fatalf("%v", err)
	}
}

func TestFromConfig(t *testing.T) {
	if ms := FromConfig("", "gemini-x", "", "", "gpt"); len(ms) != 0 {
		t.Fatalf("no keys, no models: %v", ms)
	}
	ms := FromConfig("g", "a, b", "http://localhost:11434/v1", "", "llama")
	if len(ms) != 3 || ms[1].Name() != "gemini:b" || ms[2].Name() != "openai:llama" {
		t.Fatalf("%v", ms)
	}
}

func TestRecallScalesAVolume(t *testing.T) {
	foods := []store.FoodItem{{Name: "Молоко", Portion: "250мл", Carbs: 12}, {Name: "Кефір", Portion: "1 склянка (200 мл)", Carbs: 8}}
	cases := []struct {
		it    Item
		carbs int
	}{
		{Item{"молоко", "200 мл"}, 10},
		{Item{"молоко", "200"}, 10},
		{Item{"молоко", "0.5 л"}, 24},
		{Item{"кефір", "2 склянки"}, 16},
		{Item{"кефір", "100 мл"}, 4},
	}
	for _, c := range cases {
		if m := recall(foods, c.it); m == nil || m.Carbs != c.carbs {
			t.Errorf("%+v: %+v, want %d g", c.it, m, c.carbs)
		}
	}
	if m := recall(foods, Item{"молоко", "тарілка"}); m != nil {
		t.Errorf("a plate of milk: %+v, want none", m)
	}
}
