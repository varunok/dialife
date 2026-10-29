package carbs

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"

	"dialife/internal/store"
)

const (
	defaultTimeout = 10 * time.Second // per model; the app keeps waiting no longer
	cacheFor       = 90 * 24 * time.Hour
	memoryFor      = 365 * 24 * time.Hour
	MaxItems       = 20
	// a phone asking about more new foods than this in an hour gets no model
	// estimates for a while (the family's own numbers still come)
	perHour = 60
)

// Estimate is what the models said together: the middle of their numbers, and
// the widest range any of them gave.
type Estimate struct {
	Carbs  int `json:"carbs"`
	Lo     int `json:"lo"`
	Hi     int `json:"hi"`
	Models int `json:"models"` // how many answered
}

type Suggestion struct {
	Mine     *Mine      `json:"mine,omitempty"`
	Table    []TableHit `json:"table,omitempty"`   // from an imported carbohydrate table
	Similar  []TableHit `json:"similar,omitempty"` // table foods that mean the same in other words
	Estimate *Estimate  `json:"estimate,omitempty"`
}

type Service struct {
	Store   *store.Store
	Models  []Model  // none: only the family's own numbers, nothing leaves the server
	Embed   Embedder // nil: names match only as written
	Similar float32  // least cosine for a look-alike; 0: defaultSimilar
	Timeout time.Duration
	Now     func() time.Time

	mu    sync.Mutex
	asked map[int64][]time.Time // per device: model questions in the last hour
	vecs  map[string][]float32  // embedding vectors already read or made
}

// Enabled: some model is configured, so estimates may come.
func (s *Service) Enabled() bool { return len(s.Models) > 0 }

// Suggest answers for each item: the family's own number if there is one,
// the portions of an imported table that match, and the models' estimate when
// they are configured and answer in time. A food the table knows is not put to
// the models, unless its portion is typed and no table portion could be
// recounted to it: then both are shown.
func (s *Service) Suggest(ctx context.Context, device int64, items []Item) ([]Suggestion, error) {
	now := s.Now()
	foods, err := s.Store.FoodItems(ctx, now.Add(-memoryFor).UnixMilli())
	if err != nil {
		return nil, err
	}
	tables, err := s.Store.FoodTables(ctx)
	if err != nil {
		return nil, err
	}
	index := tableIndex(tables)
	out := make([]Suggestion, len(items))
	var ask []int // items with no cached estimate
	for i, it := range items {
		out[i].Mine = recall(foods, it)
		out[i].Table = lookup(index, it)
		if !s.Enabled() {
			continue
		}
		e, err := s.Store.CarbEstimate(ctx, key(it), cacheFor, now)
		switch {
		case err == nil:
			out[i].Estimate = &Estimate{Carbs: e.Carbs, Lo: e.Lo, Hi: e.Hi, Models: e.Models}
		case errors.Is(err, store.ErrNotFound):
			if !recounted(it, out[i].Table) {
				ask = append(ask, i)
			}
		default:
			return nil, err
		}
	}
	s.similar(ctx, index, foods, items, out)
	if len(ask) == 0 || !s.allow(device, len(ask), now) {
		return out, nil
	}
	batch := make([]Item, len(ask))
	for j, i := range ask {
		batch[j] = items[i]
	}
	for j, e := range s.ask(ctx, batch) {
		if e == nil {
			continue
		}
		i := ask[j]
		out[i].Estimate = e
		if err := s.Store.SaveCarbEstimate(ctx, key(items[i]), store.CarbEstimate{Carbs: e.Carbs, Lo: e.Lo, Hi: e.Hi, Models: e.Models, At: now.UnixMilli()}); err != nil {
			slog.Error("carbs cache", "err", err)
		}
	}
	return out, nil
}

const (
	frequentFor = 90 * 24 * time.Hour
	frequentMax = 12
)

// Catalog is what the food form offers to pick from: the imported tables and
// the foods the family logs most.
type Catalog struct {
	Tables   []store.FoodTable
	Frequent []Frequent
}

func (s *Service) Catalog(ctx context.Context) (Catalog, error) {
	tables, err := s.Store.FoodTables(ctx)
	if err != nil {
		return Catalog{}, err
	}
	foods, err := s.Store.FoodItems(ctx, s.Now().Add(-frequentFor).UnixMilli())
	if err != nil {
		return Catalog{}, err
	}
	return Catalog{Tables: tables, Frequent: frequent(foods, frequentMax)}, nil
}

// ask puts the items to every model at once, each with its own deadline; a
// model that fails or is late is left out, the others still count.
func (s *Service) ask(ctx context.Context, items []Item) []*Estimate {
	timeout := s.Timeout
	if timeout <= 0 {
		timeout = defaultTimeout
	}
	answers := make([][]*Guess, len(s.Models))
	var wg sync.WaitGroup
	for m, model := range s.Models {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			start := time.Now()
			gs, err := model.Guess(c, items)
			if err != nil {
				slog.Warn("carbs model", "model", model.Name(), "items", len(items), "ms", time.Since(start).Milliseconds(), "err", err)
				return
			}
			slog.Info("carbs model", "model", model.Name(), "items", len(items), "ms", time.Since(start).Milliseconds())
			answers[m] = gs
		}()
	}
	wg.Wait()
	return merge(answers, len(items))
}

// merge: per item the median of the models' numbers, the widest of their ranges.
func merge(answers [][]*Guess, n int) []*Estimate {
	out := make([]*Estimate, n)
	for i := range n {
		var cs []int
		lo, hi := 0, 0
		for _, gs := range answers {
			if gs == nil || gs[i] == nil {
				continue
			}
			g := gs[i]
			if len(cs) == 0 || g.Lo < lo {
				lo = g.Lo
			}
			hi = max(hi, g.Hi)
			cs = append(cs, g.Carbs)
		}
		if len(cs) == 0 {
			continue
		}
		slices.Sort(cs)
		mid := cs[len(cs)/2]
		if len(cs)%2 == 0 {
			mid = (cs[len(cs)/2-1] + cs[len(cs)/2] + 1) / 2
		}
		out[i] = &Estimate{Carbs: mid, Lo: min(lo, mid), Hi: max(hi, mid), Models: len(cs)}
	}
	return out
}

func (s *Service) allow(device int64, n int, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.asked == nil {
		s.asked = map[int64][]time.Time{}
	}
	recent := s.asked[device][:0]
	for _, t := range s.asked[device] {
		if now.Sub(t) < time.Hour {
			recent = append(recent, t)
		}
	}
	if len(recent)+n > perHour {
		s.asked[device] = recent
		slog.Warn("carbs: too many questions, no model estimate", "device", device)
		return false
	}
	for range n {
		recent = append(recent, now)
	}
	s.asked[device] = recent
	return true
}

// recounted: the table has the food, for the portion typed (or none was typed).
func recounted(it Item, hits []TableHit) bool {
	if len(hits) == 0 {
		return false
	}
	if strings.TrimSpace(it.Portion) == "" {
		return true
	}
	return slices.ContainsFunc(hits, func(h TableHit) bool { return h.Scaled || h.Carbs == 0 && h.Base == 0 })
}

func key(it Item) string { return norm(it.Name) + "|" + norm(it.Portion) }
