package wait

import (
	"context"
	"log/slog"
	"time"

	"dialife/internal/insulin"
	"dialife/internal/store"
)

// Service reads the treatments behind the wait and keeps what was closed.
type Service struct {
	Store    *store.Store
	Now      func() time.Time
	OnChange func(context.Context) // every phone gets the new state
}

// State is the wait for now; nil when there is none or both cards were closed.
func (s *Service) State(ctx context.Context, r Reading, set Settings) *State {
	now := s.Now()
	from, to := now.Add(-Within-foodFor).UnixMilli(), now.UnixMilli()
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: 100})
	if err != nil {
		slog.Error("wait", "err", err)
		return nil
	}
	w := For(entries(ts), now, r, set)
	if w == nil {
		return nil
	}
	if w.Closed, w.MoreClosed, err = s.closed(ctx, w.DoseID); err != nil {
		slog.Error("wait closed", "err", err)
	}
	if w.Closed && w.MoreClosed {
		return nil
	}
	return w
}

// Close closes the wait ("wait": already ate) or the warning ("more") for
// every phone; false when it was closed already.
func (s *Service) Close(ctx context.Context, doseID, kind string, by int64) (bool, error) {
	closed, err := s.close(ctx, doseID, kind, by, s.Now().UnixMilli())
	if err != nil || !closed {
		return closed, err
	}
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
	return true, nil
}

func entries(ts []store.Treatment) []Entry {
	es := make([]Entry, 0, len(ts))
	for _, t := range ts {
		e := Entry{ID: t.ID, At: t.CreatedAt}
		if t.Insulin != nil {
			e.Insulin = *t.Insulin
			typ, _ := insulin.Kind(t.Raw)
			e.Basal = typ == "basal"
			e.Correction = insulin.Role(t.Raw) == "corr"
		}
		if t.Carbs != nil {
			e.Carbs = *t.Carbs
		}
		es = append(es, e)
	}
	return es
}
