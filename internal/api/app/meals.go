package app

import (
	"context"
	"log/slog"
	"slices"
	"sync"
	"time"

	"dialife/internal/store"
)

const (
	mealGapDefault = 30 // minutes, until there is history
	mealGapMin     = 15
	mealGapMax     = 30
	mealGapOver    = 90 * 24 * time.Hour
	mealGapSeen    = 60 // food entries further apart are separate meals, not a habit to learn
)

// the gap is the same for every phone, worked out hourly from the history
type mealGapCache struct {
	mu   sync.Mutex
	at   time.Time
	last int
}

// mealGap is how far apart, in minutes, two food entries of one meal may be:
// 30 until there are 10 short gaps between food entries, then the family's
// own habit (three in four of those gaps, plus 10), never more than 30.
func mealGap(foods []int64) int {
	foods = slices.Sorted(slices.Values(foods))
	var gaps []int
	for i := 1; i < len(foods); i++ {
		if m := int((foods[i] - foods[i-1]) / 60_000); m >= 1 && m < mealGapSeen {
			gaps = append(gaps, m)
		}
	}
	if len(gaps) < 10 {
		return mealGapDefault
	}
	slices.Sort(gaps)
	return min(max(gaps[len(gaps)*3/4]+10, mealGapMin), mealGapMax)
}

func (h *Handlers) mealGap(ctx context.Context, now time.Time) int {
	c := &h.mealGapQ
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && now.Sub(c.at) < qualityEvery {
		return c.last
	}
	from, to := now.Add(-mealGapOver).UnixMilli(), now.UnixMilli()
	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: maxHistoryRows})
	if err != nil {
		slog.Error("meal gap", "err", err)
		if c.last == 0 {
			return mealGapDefault
		}
		return c.last
	}
	var foods []int64
	for _, t := range ts {
		if t.Carbs != nil && *t.Carbs > 0 {
			foods = append(foods, t.CreatedAt)
		}
	}
	c.last, c.at = mealGap(foods), now
	return c.last
}
