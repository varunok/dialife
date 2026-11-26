package recheck

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"dialife/internal/bus"
	"dialife/internal/glucose"
	"dialife/internal/meter"
	"dialife/internal/store"
)

// Service opens a recheck for carbs given for a low, reminds when it is due
// and settles it by the next glucometer check.
type Service struct {
	Store *store.Store
	Bus   *bus.Bus
	Now   func() time.Time
	// something changed: every phone gets the new state; nil: nothing
	OnChange func(context.Context)

	mu sync.Mutex
}

// Event goes to the bus (and so to ntfy): a recheck is due, still low, or over.
type Event struct {
	Kind    string  `json:"kind"` // "due" | "still_low" | "over"
	Recheck Recheck `json:"recheck"`
}

// TreatmentsSaved looks at saved treatments for carbs given while low.
func (s *Service) TreatmentsSaved(ctx context.Context, ids []string) {
	if s == nil || len(ids) == 0 {
		return
	}
	s.mu.Lock()
	opened, err := s.open(ctx, ids)
	s.mu.Unlock()
	if err != nil {
		slog.Error("recheck open", "err", err)
		return
	}
	if opened {
		s.changed(ctx)
	}
	s.Tick(ctx) // carbs logged late may be due at once
}

func (s *Service) open(ctx context.Context, ids []string) (bool, error) {
	now := s.Now()
	opened := false
	for _, id := range ids {
		ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{ID: id, Count: 1})
		if err != nil {
			return false, err
		}
		if len(ts) == 0 || ts[0].Carbs == nil || *ts[0].Carbs <= 0 {
			continue
		}
		t := ts[0]
		if t.CreatedAt < now.Add(-carbsWithin).UnixMilli() || t.CreatedAt > now.Add(5*time.Minute).UnixMilli() {
			continue
		}
		if exists, err := s.exists(ctx, t.ID); err != nil || exists {
			if err != nil {
				return false, err
			}
			continue
		}
		low, isLow, err := s.lowAt(ctx, t.CreatedAt)
		if err != nil {
			return false, err
		}
		if !isLow {
			continue
		}
		if err := s.insert(ctx, t, low); err != nil {
			return false, err
		}
		slog.Info("recheck opened: carbs for a low", "carbs", *t.Carbs, "low", low.Kind, "mg", low.MG)
		opened = true
	}
	return opened, nil
}

// lowAt reads what LowAt needs around t.
func (s *Service) lowAt(ctx context.Context, t int64) (Low, bool, error) {
	alarms, err := s.lowAlarms(ctx, t)
	if err != nil {
		return Low{}, false, err
	}
	from := t - sensorFresh.Milliseconds()
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &t, Count: 10, Asc: true})
	if err != nil {
		return Low{}, false, err
	}
	var sgv []Point
	for _, e := range es {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			sgv = append(sgv, Point{T: e.Date, MG: *e.SGV})
		}
	}
	checks, err := s.checks(ctx, t-meterFresh.Milliseconds(), t)
	if err != nil {
		return Low{}, false, err
	}
	low, ok := LowAt(t, alarms, sgv, checks)
	return low, ok, nil
}

// checks are the glucometer checks in [from, to], oldest first: xDrip+ meter
// entries (not its calibrations) and checks logged in the apps.
func (s *Service) checks(ctx context.Context, from, to int64) ([]Point, error) {
	es, err := s.Store.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Gte: &from, Lte: &to, Count: 200, Asc: true})
	if err != nil {
		return nil, err
	}
	var out []Point
	for _, e := range es {
		if e.MBG != nil && *e.MBG > 0 && !meter.IsCalibration(e.Device) {
			out = append(out, Point{T: e.Date, MG: *e.MBG})
		}
	}
	ts, err := s.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: 200})
	if err != nil {
		return nil, err
	}
	for i := len(ts) - 1; i >= 0; i-- { // newest first from the store
		if mg := meter.CheckOf(ts[i].Raw); mg != nil {
			out = append(out, Point{T: ts[i].CreatedAt, MG: *mg})
		}
	}
	sortPoints(out)
	return out, nil
}

// State is the open recheck the phones show; nil when there is none.
func (s *Service) State(ctx context.Context) (*Recheck, error) {
	if s == nil {
		return nil, nil
	}
	r, err := s.current(ctx)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &r, nil
}

// Close ends the recheck for every phone.
func (s *Service) Close(ctx context.Context, id int64, by store.Device) error {
	s.mu.Lock()
	r, err := s.current(ctx)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && r.ID != id) {
		err = ErrNotFound
	}
	if err == nil {
		err = s.close(ctx, id, "closed", s.Now().UnixMilli(), by)
	}
	s.mu.Unlock()
	if err != nil {
		return err
	}
	slog.Info("recheck closed", "id", id, "by", by.Person)
	s.publish(Event{Kind: "over", Recheck: r})
	s.changed(ctx)
	return nil
}

// Tick settles the open recheck by the newest check, reminds when it is due,
// and lets it run out. Run calls it every minute; new data calls it at once.
func (s *Service) Tick(ctx context.Context) {
	if s == nil {
		return
	}
	s.mu.Lock()
	evs, err := s.tick(ctx)
	s.mu.Unlock()
	if err != nil {
		slog.Error("recheck tick", "err", err)
		return
	}
	for _, ev := range evs {
		s.publish(ev)
	}
	if len(evs) > 0 {
		s.changed(ctx)
	}
}

func (s *Service) tick(ctx context.Context) ([]Event, error) {
	rows, err := s.openRows(ctx)
	if err != nil {
		return nil, err
	}
	now := s.Now().UnixMilli()
	var evs []Event
	for i, r := range rows { // newest carbs first
		if i > 0 { // newer carbs: this low was treated again
			if err := s.close(ctx, r.ID, "again", now, store.Device{}); err != nil {
				return nil, err
			}
			evs = append(evs, Event{Kind: "over", Recheck: r.Recheck})
			continue
		}
		gone, err := s.treatmentGone(ctx, r.treatmentID)
		if err != nil {
			return nil, err
		}
		checks, err := s.checks(ctx, r.CarbsT, now)
		if err != nil {
			return nil, err
		}
		c, checked := LatestCheck(r.CarbsT, now, checks)
		switch {
		case gone:
			err = s.close(ctx, r.ID, "removed", now, store.Device{})
			evs = append(evs, Event{Kind: "over", Recheck: r.Recheck})
		case checked && c.MG >= Below:
			err = s.close(ctx, r.ID, "in_range", now, store.Device{})
			r.CheckT, r.CheckMG = c.T, c.MG
			evs = append(evs, Event{Kind: "over", Recheck: r.Recheck})
			slog.Info("recheck: back in range", "mg", c.MG)
		case checked && (r.CheckT != c.T || !r.stillTold):
			err = s.setCheck(ctx, r.ID, c)
			r.CheckT, r.CheckMG, r.State = c.T, c.MG, "still_low"
			evs = append(evs, Event{Kind: "still_low", Recheck: r.Recheck})
			slog.Warn("recheck: still low", "mg", c.MG)
		case now >= r.DueAt+Expire.Milliseconds():
			err = s.close(ctx, r.ID, "expired", now, store.Device{})
			evs = append(evs, Event{Kind: "over", Recheck: r.Recheck})
		case !checked && now >= r.DueAt && !r.reminded:
			err = s.setReminded(ctx, r.ID)
			r.State = "due"
			evs = append(evs, Event{Kind: "due", Recheck: r.Recheck})
			slog.Info("recheck due: measure with the glucometer")
		}
		if err != nil {
			return nil, err
		}
	}
	return evs, nil
}

// Run ticks once a minute until ctx ends: the reminder comes without new data.
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		s.Tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Service) publish(ev Event) {
	if s.Bus != nil {
		s.Bus.Publish(EventType, ev, s.Now())
	}
}

func (s *Service) changed(ctx context.Context) {
	if s.OnChange != nil {
		s.OnChange(ctx)
	}
}
