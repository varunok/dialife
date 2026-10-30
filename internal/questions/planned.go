package questions

import (
	"context"
	"log/slog"
	"slices"
	"time"

	"dialife/internal/store"
)

func (e *Engine) detectPlanned(ctx context.Context, qs []store.Question, now time.Time) error {
	gone, err := e.Store.ExpirePlannedDoses(ctx, now.Add(-PlannedExpire).UnixMilli(), now.UnixMilli())
	if err != nil {
		return err
	}
	for _, p := range gone {
		slog.Info("planned dose expired", "id", p.ID)
		if err := e.settlePlanned(ctx, p.ID, now); err != nil {
			return err
		}
	}
	if len(gone) > 0 && e.OnPlanned != nil {
		e.OnPlanned()
	}
	ps, err := e.Store.PlannedDoses(ctx)
	if err != nil {
		return err
	}
	for _, p := range ps {
		if now.UnixMilli()-p.CreatedAt < PlannedAsk.Milliseconds() || slices.ContainsFunc(qs, func(q store.Question) bool { return q.Kind == "planned" && q.RefID == p.ID }) {
			continue
		}
		at := p.CreatedAt
		if meal, ok, err := e.treatment(ctx, p.MealID); err != nil {
			return err
		} else if ok {
			at = meal.CreatedAt
		}
		q, err := e.Store.InsertQuestion(ctx, store.Question{Kind: "planned", From: at, To: now.UnixMilli(), RefID: p.ID}, now)
		if err != nil {
			return err
		}
		slog.Info("question", "id", q.ID, "kind", "planned", "dose", p.ID)
		if err := e.publish(ctx, q, now, false); err != nil {
			return err
		}
	}
	return nil
}

// SettlePlanned closes the question about a planned dose once it was given,
// skipped or expired; nothing when there is none.
func (e *Engine) SettlePlanned(ctx context.Context, plannedID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.settlePlanned(ctx, plannedID, e.Now())
}

func (e *Engine) settlePlanned(ctx context.Context, plannedID string, now time.Time) error {
	qs, err := e.Store.QuestionsSince(ctx, now.Add(-PlannedExpire-36*time.Hour).UnixMilli())
	if err != nil {
		return err
	}
	for _, q := range qs {
		if q.Kind == "planned" && q.RefID == plannedID && q.State == "open" {
			if err := e.step(ctx, q, nil, now); err != nil {
				return err
			}
		}
	}
	return nil
}

// detectDouble asks about two doses too close together when xDrip+ sent one of them.
