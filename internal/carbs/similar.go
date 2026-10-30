package carbs

import (
	"cmp"
	"context"
	"log/slog"
	"slices"
	"time"

	"dialife/internal/store"
)

// defaultSimilar: the least cosine for "this is the same food" between two
// short names. Lower than this the names are only about food in general.
const defaultSimilar = 0.80

// maxSimilar: at most this many look-alike table portions per typed food.
const maxSimilar = 3

// vectors returns a vector for each text, from memory, the database, or the
// embedding model (once, then kept); a text the model could not do is missing.
func (s *Service) vectors(ctx context.Context, texts []string) map[string][]float32 {
	model := s.Embed.Name()
	out := map[string][]float32{}
	var unknown []string
	s.mu.Lock()
	if s.vecs == nil {
		s.vecs = map[string][]float32{}
	}
	for _, t := range texts {
		if v, ok := s.vecs[t]; ok {
			out[t] = v
		} else {
			unknown = append(unknown, t)
		}
	}
	s.mu.Unlock()
	if len(unknown) == 0 {
		return out
	}
	stored, err := s.Store.FoodVectors(ctx, model, unknown)
	if err != nil {
		slog.Error("food vectors", "err", err)
	}
	var ask []string
	for _, t := range unknown {
		if v, ok := stored[t]; ok {
			out[t] = v
		} else {
			ask = append(ask, t)
		}
	}
	if len(ask) > 0 {
		c, cancel := context.WithTimeout(ctx, s.timeout())
		vs, err := s.Embed.Embed(c, ask)
		cancel()
		if err != nil {
			slog.Warn("food embed", "model", model, "texts", len(ask), "err", err)
		} else {
			made := map[string][]float32{}
			for i, t := range ask {
				out[t], made[t] = vs[i], vs[i]
			}
			if err := s.Store.SaveFoodVectors(ctx, model, made); err != nil {
				slog.Error("food vectors save", "err", err)
			}
		}
	}
	s.mu.Lock()
	for t, v := range out {
		s.vecs[t] = v
	}
	s.mu.Unlock()
	return out
}

func (s *Service) timeout() time.Duration {
	if s.Timeout > 0 {
		return s.Timeout
	}
	return defaultTimeout
}

func (s *Service) similarMin() float32 {
	if s.Similar > 0 {
		return s.Similar
	}
	return defaultSimilar
}

// similar fills in, for the items nothing matched by name, the table portions
// and the family's own food that mean the same thing in other words.
func (s *Service) similar(ctx context.Context, index []tableEntry, foods []store.FoodItem, items []Item, out []Suggestion) {
	need := unmatched(items, out)
	if s.Embed == nil || len(need) == 0 {
		return
	}
	family := familyNames(foods)
	vecs := s.vectors(ctx, textsToEmbed(index, family, items, need))
	least := s.similarMin()
	for _, i := range need {
		q, ok := vecs[norm(items[i].Name)]
		if !ok {
			continue
		}
		for _, h := range closestTable(q, index, vecs, least) {
			th := hitFor(h.e, items[i].Portion)
			th.Score = h.score
			out[i].Similar = append(out[i].Similar, th)
		}
		bestName, bestScore := closestFamily(q, family, vecs, least)
		if bestName != "" {
			if m := recall(foods, Item{Name: bestName, Portion: items[i].Portion}); m != nil {
				m.Like, m.Score = bestName, bestScore
				out[i].Mine = m
			}
		}
		slog.Info("food similar", "table", len(out[i].Similar), "mine", bestName != "")
	}
}

// unmatched: the items with a name that nothing matched by name.
func unmatched(items []Item, out []Suggestion) []int {
	var need []int
	for i := range items {
		if out[i].Mine == nil && len(out[i].Table) == 0 && norm(items[i].Name) != "" {
			need = append(need, i)
		}
	}
	return need
}

// familyNames: the family's foods, normalized → as logged (the newest spelling).
func familyNames(foods []store.FoodItem) map[string]string {
	family := map[string]string{}
	for _, f := range foods {
		if k := norm(f.Name); k != "" {
			if _, ok := family[k]; !ok {
				family[k] = f.Name
			}
		}
	}
	return family
}

// textsToEmbed: every table name, every family food and every typed name looked for, once each.
func textsToEmbed(index []tableEntry, family map[string]string, items []Item, need []int) []string {
	texts := map[string]bool{}
	for _, e := range index {
		for _, k := range e.keys {
			texts[k] = true
		}
	}
	for k := range family {
		texts[k] = true
	}
	for _, i := range need {
		texts[norm(items[i].Name)] = true
	}
	return mapKeys(texts)
}

type scoredEntry struct {
	e     tableEntry
	score float32
}

// closestTable: the table portions whose best name is at least least alike, the closest first, at most maxSimilar.
func closestTable(q []float32, index []tableEntry, vecs map[string][]float32, least float32) []scoredEntry {
	var hits []scoredEntry
	for _, e := range index {
		best := float32(-1)
		for _, k := range e.keys {
			if v, ok := vecs[k]; ok {
				best = max(best, dot(q, v))
			}
		}
		if best >= least {
			hits = append(hits, scoredEntry{e, best})
		}
	}
	slices.SortStableFunc(hits, func(a, b scoredEntry) int { return cmp.Compare(b.score, a.score) })
	return hits[:min(len(hits), maxSimilar)]
}

// closestFamily: the family's food most alike, if at least least alike.
func closestFamily(q []float32, family map[string]string, vecs map[string][]float32, least float32) (string, float32) {
	bestName, bestScore := "", least
	for k, name := range family {
		if v, ok := vecs[k]; ok {
			if d := dot(q, v); d >= bestScore {
				bestName, bestScore = name, d
			}
		}
	}
	return bestName, bestScore
}

func mapKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	slices.Sort(out)
	return out
}
