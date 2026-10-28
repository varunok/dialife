package carbs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strings"
)

// Embedder turns short texts (food names) into vectors whose closeness is
// closeness of meaning, across languages: "картопля" lands near "Papa".
type Embedder interface {
	Name() string
	Embed(ctx context.Context, texts []string) ([][]float32, error)
}

// embedDims: enough for food names, a quarter of the full size.
const embedDims = 768

// GeminiEmbed calls Google's batchEmbedContents.
type GeminiEmbed struct {
	Key   string
	Model string // e.g. "gemini-embedding-001"
	HTTP  *http.Client
	URL   string // tests only; "" is Google's
}

func (g *GeminiEmbed) Name() string { return "gemini:" + g.Model }

// geminiBatch: Google takes at most this many texts per call.
const geminiBatch = 100

func (g *GeminiEmbed) Embed(ctx context.Context, texts []string) ([][]float32, error) {
	out := make([][]float32, 0, len(texts))
	for start := 0; start < len(texts); start += geminiBatch {
		part := texts[start:min(start+geminiBatch, len(texts))]
		vs, err := g.embed(ctx, part)
		if err != nil {
			return nil, err
		}
		out = append(out, vs...)
	}
	return out, nil
}

func (g *GeminiEmbed) embed(ctx context.Context, texts []string) ([][]float32, error) {
	base := g.URL
	if base == "" {
		base = "https://generativelanguage.googleapis.com"
	}
	reqs := make([]map[string]any, len(texts))
	for i, t := range texts {
		r := map[string]any{
			"model":                "models/" + g.Model,
			"content":              map[string]any{"parts": []map[string]string{{"text": t}}},
			"outputDimensionality": embedDims,
		}
		// the newer models take no task type: a prefix says what the text is
		if strings.Contains(g.Model, "embedding-0") || strings.Contains(g.Model, "text-embedding") {
			r["taskType"] = "SEMANTIC_SIMILARITY"
		} else {
			r["content"] = map[string]any{"parts": []map[string]string{{"text": "task: sentence similarity | query: " + t}}}
		}
		reqs[i] = r
	}
	data, err := post(ctx, client(g.HTTP), base+"/v1beta/models/"+g.Model+":batchEmbedContents", map[string]string{"x-goog-api-key": g.Key}, map[string]any{"requests": reqs})
	if err != nil {
		return nil, err
	}
	var r struct {
		Embeddings []struct {
			Values []float32 `json:"values"`
		} `json:"embeddings"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return nil, err
	}
	if len(r.Embeddings) != len(texts) {
		return nil, fmt.Errorf("%d vectors for %d texts", len(r.Embeddings), len(texts))
	}
	out := make([][]float32, len(texts))
	for i, e := range r.Embeddings {
		if len(e.Values) == 0 {
			return nil, errors.New("empty vector")
		}
		out[i] = unit(e.Values)
	}
	return out, nil
}

// unit scales a vector to length one: a shortened vector is not, and the
// cosine of two unit vectors is just their dot product.
func unit(v []float32) []float32 {
	var s float64
	for _, x := range v {
		s += float64(x) * float64(x)
	}
	n := float32(math.Sqrt(s))
	if n == 0 {
		return v
	}
	out := make([]float32, len(v))
	for i, x := range v {
		out[i] = x / n
	}
	return out
}

func dot(a, b []float32) float32 {
	var s float32
	for i := range min(len(a), len(b)) {
		s += a[i] * b[i]
	}
	return s
}
