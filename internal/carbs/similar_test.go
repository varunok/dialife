package carbs

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"dialife/internal/store"
)

// fakeEmbed puts each text on an axis by the meaning it was told about;
// anything else lands on its own axis, far from all of them.
type fakeEmbed struct {
	axes  map[string]int
	calls int
}

const fakeDims = 512

func (f *fakeEmbed) Name() string { return "fake" }

func (f *fakeEmbed) Embed(_ context.Context, texts []string) ([][]float32, error) {
	f.calls++
	out := make([][]float32, len(texts))
	for i, t := range texts {
		v := make([]float32, fakeDims)
		if f.axes == nil {
			f.axes = map[string]int{}
		}
		a, ok := f.axes[t]
		if !ok { // a meaning of its own
			a = 100 + len(f.axes)
			f.axes[t] = a
		}
		v[a] = 1
		out[i] = unit(v)
	}
	return out, nil
}

func TestSimilarFindsTableAndFamilyFoodsByMeaning(t *testing.T) {
	st := openStore(t)
	ctx := context.Background()
	if err := st.ReplaceFoodTable(ctx, sampleTable); err != nil {
		t.Fatal(err)
	}
	// the family logged "Хліб чорний" before
	dev, err := st.CreateDevice(ctx, "Мама", "caregiver", "h", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	err = st.UpsertTreatments(ctx, []store.Treatment{{ID: "t1", EventType: "Carb Correction", CreatedAt: time.Now().Add(-time.Hour).UnixMilli(),
		Raw: []byte(`{"foodItems":[{"name":"Хліб чорний","portion":"1 скибка","carbs":12}]}`)}}, dev.ID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	e := &fakeEmbed{axes: map[string]int{
		"банан": 1, "banana": 1, "бананчик": 1,
		"хліб чорний": 2, "хліб житній": 2,
	}}
	s := &Service{Store: st, Embed: e, Now: time.Now}
	out, err := s.Suggest(ctx, 1, []Item{{Name: "Бананчик", Portion: "2"}, {Name: "хліб житній", Portion: "2 скибки"}, {Name: "борщ"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(out[0].Similar) != 1 || out[0].Similar[0].ID != "banana" || out[0].Similar[0].Carbs != 30 || out[0].Similar[0].Score < 0.99 {
		t.Fatalf("banana by meaning, scaled to two: %+v", out[0].Similar)
	}
	if m := out[1].Mine; m == nil || m.Like != "Хліб чорний" || m.Carbs != 24 {
		t.Fatalf("the family's bread by meaning: %+v", out[1].Mine)
	}
	if len(out[2].Similar) != 0 || out[2].Mine != nil {
		t.Fatalf("nothing close: %+v", out[2])
	}
	// asked once; the vectors are kept, also across a restart
	calls := e.calls
	s.Suggest(ctx, 1, []Item{{Name: "бананчик"}})
	s2 := &Service{Store: st, Embed: e, Now: time.Now}
	s2.Suggest(ctx, 1, []Item{{Name: "бананчик"}})
	if e.calls != calls {
		t.Fatalf("embedded again: %d calls after %d", e.calls, calls)
	}
}

func TestSimilarOnlyWhenNothingMatchesByName(t *testing.T) {
	st := openStore(t)
	if err := st.ReplaceFoodTable(context.Background(), sampleTable); err != nil {
		t.Fatal(err)
	}
	e := &fakeEmbed{}
	s := &Service{Store: st, Embed: e, Now: time.Now}
	out, _ := s.Suggest(context.Background(), 1, []Item{{Name: "банан"}})
	if len(out[0].Table) != 1 || len(out[0].Similar) != 0 || e.calls != 0 {
		t.Fatalf("a name match needs no vectors: %+v, %d calls", out[0], e.calls)
	}
}

func TestGeminiEmbedRequest(t *testing.T) {
	var got struct {
		Requests []struct {
			Model   string
			Content struct {
				Parts []struct{ Text string }
			}
			TaskType             string
			OutputDimensionality int
		}
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1beta/models/gemini-embedding-001:batchEmbedContents" || r.Header.Get("x-goog-api-key") != "k" {
			t.Errorf("%s %s", r.URL.Path, r.Header.Get("x-goog-api-key"))
		}
		b, _ := io.ReadAll(r.Body)
		json.Unmarshal(b, &got)
		io.WriteString(w, `{"embeddings":[{"values":[3,4]},{"values":[0,2]}]}`)
	}))
	defer srv.Close()
	g := &GeminiEmbed{Key: "k", Model: "gemini-embedding-001", URL: srv.URL}
	vs, err := g.Embed(context.Background(), []string{"банан", "рис"})
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Requests) != 2 || got.Requests[0].Model != "models/gemini-embedding-001" || got.Requests[0].TaskType != "SEMANTIC_SIMILARITY" ||
		got.Requests[0].OutputDimensionality != embedDims || got.Requests[1].Content.Parts[0].Text != "рис" {
		t.Fatalf("%+v", got)
	}
	if vs[0][0] != 0.6 || vs[0][1] != 0.8 || vs[1][1] != 1 {
		t.Fatalf("not unit length: %v", vs)
	}
	// the newer models: a task prefix instead of a task type
	g.Model = "gemini-embedding-2"
	got.Requests = nil
	srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		json.Unmarshal(b, &got)
		io.WriteString(w, `{"embeddings":[{"values":[1]}]}`)
	})
	if _, err := g.Embed(context.Background(), []string{"банан"}); err != nil {
		t.Fatal(err)
	}
	if got.Requests[0].TaskType != "" || !strings.HasSuffix(got.Requests[0].Content.Parts[0].Text, "query: банан") {
		t.Fatalf("%+v", got)
	}
}
