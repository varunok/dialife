package alarms

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"dialife/internal/bus"
	"dialife/internal/store"
)

// An alarm is an EPISODE:
//
//	firing ──"I'm on it"──► handled ──recovers (2 readings)──► cleared
//	   ▲                       │
//	   └── worse by WorseBy, level up, urgent past Ceiling,
//	       check-in unanswered, quiet over ────┘ (rings everyone again)
//
// While someone handles it nobody is woken on a timer. The handler alone
// gets a quiet check-in when the value is not recovering; everyone is woken
// only when things get worse, the handler stops answering or asks for help.

// SSE event types for alarms.
const (
	EventAlarm     = "alarm"      // ring: {alarm, reason, was_handled_by}
	EventHandled   = "handled"    // someone took it: silence everywhere
	EventCheckin   = "checkin"    // quiet question for the handler only (alarm.handled_by_id)
	EventHelp      = "help"       // the handler asks the others for help
	EventComing    = "coming"     // someone answers the help request: "I'm coming" (the episode stays the handler's)
	EventCheckinOK = "checkin_ok" // the handler answered "all under control": the question can go
	EventClear     = "clear"      // episode over: {alarm, summary}
)

// Why an alarm rang.
const (
	ReasonNew        = "new"
	ReasonRepeat     = "repeat"      // nobody took it yet
	ReasonEscalated  = "escalated"   // level went up while nobody had it
	ReasonWorse      = "worse"       // worse than when it was taken
	ReasonCeiling    = "ceiling"     // urgent and taken too long
	ReasonNoResponse = "no_response" // the handler did not answer a check-in
	ReasonQuietOver  = "quiet_over"  // a "quiet for 2 h" ended and it is still on
)

var (
	ErrNotActive    = errors.New("alarm not active")
	ErrQuietRefused = errors.New("no quiet for an urgent low: take it with \"I'm on it\" instead")
)

type Timing struct {
	WatchdogEvery time.Duration
}

var DefaultTiming = Timing{WatchdogEvery: time.Minute}

// View is the client-facing shape of an episode.
type View struct {
	ID             int64  `json:"id"`
	Kind           Kind   `json:"kind"`
	Level          Level  `json:"level"`
	State          string `json:"state"` // firing | handled | cleared
	StartedAt      int64  `json:"started_at"`
	SGV            *int   `json:"sgv,omitempty"` // mg/dL at the latest evaluation
	Reason         string `json:"reason,omitempty"`
	HandledBy      string `json:"handled_by,omitempty"`
	HandledByID    int64  `json:"handled_by_id,omitempty"`
	HandledAt      *int64 `json:"handled_at,omitempty"`
	HandledSGV     *int   `json:"handled_sgv,omitempty"`
	QuietUntil     *int64 `json:"quiet_until,omitempty"`
	NextCheckinAt  *int64 `json:"next_checkin_at,omitempty"`
	CheckinPending bool   `json:"checkin_pending,omitempty"`
	CheckinSentAt  *int64 `json:"checkin_sent_at,omitempty"` // when the pending question was asked
	HelpAt         *int64 `json:"help_at,omitempty"`
	ComingBy       string `json:"coming_by,omitempty"`
	ComingByID     int64  `json:"coming_by_id,omitempty"`
	ComingAt       *int64 `json:"coming_at,omitempty"`
}

type Summary struct {
	DurationMin int    `json:"duration_min"`
	HandledBy   string `json:"handled_by,omitempty"`
	Manual      bool   `json:"manual,omitempty"`
	ResolvedBy  string `json:"resolved_by,omitempty"`
}

type Event struct {
	Alarm        View     `json:"alarm"`
	Reason       string   `json:"reason,omitempty"`
	WasHandledBy string   `json:"was_handled_by,omitempty"` // for no_response/worse: who had it
	By           string   `json:"by,omitempty"`             // handled/help/coming: who
	Summary      *Summary `json:"summary,omitempty"`
}

func ToView(a store.Alarm) View {
	v := View{ID: a.ID, Kind: Kind(a.Kind), Level: Level(a.Level), StartedAt: a.StartedAt, SGV: a.LastSGV, Reason: a.Reason,
		HandledBy: a.HandledByName, HandledAt: a.HandledAt, HandledSGV: a.HandledSGV, QuietUntil: a.QuietUntil,
		NextCheckinAt: a.NextCheckinAt, CheckinPending: a.CheckinSentAt != nil, CheckinSentAt: a.CheckinSentAt, HelpAt: a.HelpAt, State: "firing",
		ComingBy: a.ComingByName, ComingAt: a.ComingAt}
	if a.ComingBy != nil {
		v.ComingByID = *a.ComingBy
	}
	if a.HandledBy != nil {
		v.State, v.HandledByID = "handled", *a.HandledBy
	}
	if a.ClearedAt != nil {
		v.State = "cleared"
	}
	return v
}

type InputFunc func(ctx context.Context, now time.Time) (Input, error)

type Engine struct {
	Store  *store.Store
	Bus    *bus.Bus
	Now    func() time.Time
	Input  InputFunc
	Rules  Rules // defaults; household settings in the store override them
	Timing Timing

	mu sync.Mutex // evaluations and actions run one at a time
}

// CurrentRules are the defaults with the stored household settings applied.
// Read on every evaluation, so `dialife alarms set` takes effect within a minute.
func (e *Engine) CurrentRules(ctx context.Context) Rules {
	r, err := LoadRules(ctx, e.Store, e.Rules)
	if err != nil {
		slog.Error("alarm rules, using defaults", "err", err)
		return e.Rules
	}
	return r
}

// Evaluate reconciles live episodes with the current data.
func (e *Engine) Evaluate(ctx context.Context) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	now := e.Now()
	in, err := e.Input(ctx, now)
	if err != nil {
		return err
	}
	live, err := e.Store.ActiveAlarms(ctx)
	if err != nil {
		return err
	}
	byKind := map[Kind]store.Alarm{}
	levels := map[Kind]Level{}
	for _, a := range live {
		byKind[Kind(a.Kind)] = a
		levels[Kind(a.Kind)] = Level(a.Level)
	}

	rules := e.CurrentRules(ctx)
	want := rules.Conditions(in, levels)
	for _, k := range allKinds {
		cond, wanted := want[k]
		a, exists := byKind[k]
		switch {
		case wanted && !exists:
			err = e.maybeFire(ctx, k, cond, rules, now)
		case wanted && exists:
			err = e.step(ctx, a, cond, rules, now)
		case !wanted && exists:
			err = e.clear(ctx, a, in, now, nil)
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// maybeFire opens an episode unless someone just resolved this kind by hand
// and it is not worse than what they saw.
func (e *Engine) maybeFire(ctx context.Context, k Kind, c Condition, r Rules, now time.Time) error {
	prev, err := e.Store.LastManualResolve(ctx, string(k), now.UnixMilli())
	switch {
	case err == nil:
		worse := c.Level.rank() > Level(prev.Level).rank() || worseBy(k, c.SGV, prev.LastSGV, r.Episode.WorseBy)
		if !worse {
			return nil
		}
	case !errors.Is(err, store.ErrNotFound):
		return err
	}

	a := store.Alarm{Kind: string(k), Level: string(c.Level), StartedAt: now.UnixMilli(),
		TriggerSGV: c.SGV, LastSGV: c.SGV, LastNotifiedAt: now.UnixMilli(), Reason: ReasonNew}
	id, err := e.Store.InsertAlarm(ctx, a)
	if err != nil {
		return err
	}
	a.ID = id
	slog.Warn("alarm fired", "id", id, "kind", k, "level", c.Level, "sgv", deref(c.SGV))
	e.publish(ctx, EventAlarm, Event{Alarm: ToView(a), Reason: ReasonNew}, now)
	return nil
}

// step advances a live episode by one evaluation.
func (e *Engine) step(ctx context.Context, a store.Alarm, c Condition, r Rules, now time.Time) error {
	ms := now.UnixMilli()
	ep := r.Episode
	k := Kind(a.Kind)
	escalated := c.Level.rank() > Level(a.Level).rank()
	a.Level, a.LastSGV = string(c.Level), c.SGV

	if a.HandledBy == nil { // firing: nobody has it
		switch {
		case escalated:
			return e.ring(ctx, a, ReasonEscalated, now)
		case now.Sub(time.UnixMilli(a.LastNotifiedAt)) >= repeatEvery(ep, c.Level):
			return e.ring(ctx, a, ReasonRepeat, now)
		}
		return e.Store.UpdateAlarm(ctx, a)
	}

	quiet := a.QuietUntil != nil && *a.QuietUntil > ms
	worse := worseBy(k, c.SGV, a.HandledSGV, ep.WorseBy) || (escalated && side(k) != 0)
	switch {
	case worse: // breaks a quiet too
		return e.ring(ctx, a, ReasonWorse, now)
	case a.QuietUntil != nil && !quiet:
		return e.ring(ctx, a, ReasonQuietOver, now)
	case quiet:
		return e.Store.UpdateAlarm(ctx, a)
	case escalated: // stale 15 → 30 min while taken without a quiet
		return e.ring(ctx, a, ReasonWorse, now)
	case c.Level == Urgent && a.HandledAt != nil && now.Sub(time.UnixMilli(*a.HandledAt)) >= ep.Ceiling:
		return e.ring(ctx, a, ReasonCeiling, now)
	case a.CheckinSentAt != nil && now.Sub(time.UnixMilli(*a.CheckinSentAt)) >= ep.CheckinTimeout:
		return e.ring(ctx, a, ReasonNoResponse, now)
	case a.CheckinSentAt == nil && a.NextCheckinAt != nil && ms >= *a.NextCheckinAt && side(k) != 0:
		if improvedBy(k, c.SGV, a.HandledSGV, ep.Improve) {
			next := ms + checkinEvery(ep, c.Level).Milliseconds()
			a.NextCheckinAt = &next // recovering: no need to ask
			return e.Store.UpdateAlarm(ctx, a)
		}
		a.CheckinSentAt = &ms
		if err := e.Store.UpdateAlarm(ctx, a); err != nil {
			return err
		}
		slog.Info("alarm check-in", "id", a.ID, "handler", a.HandledByName, "sgv", deref(c.SGV))
		e.publish(ctx, EventCheckin, Event{Alarm: ToView(a)}, now)
		return nil
	}
	return e.Store.UpdateAlarm(ctx, a)
}

// ring wakes everyone: the episode goes back to firing.
func (e *Engine) ring(ctx context.Context, a store.Alarm, reason string, now time.Time) error {
	was := a.HandledByName
	a.HandledBy, a.HandledByName, a.HandledAt, a.HandledSGV = nil, "", nil, nil
	a.QuietUntil, a.NextCheckinAt, a.CheckinSentAt, a.HelpAt = nil, nil, nil, nil
	a.ComingBy, a.ComingByName, a.ComingAt = nil, "", nil
	a.LastNotifiedAt, a.Reason = now.UnixMilli(), reason
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return err
	}
	slog.Warn("alarm rings", "id", a.ID, "kind", a.Kind, "level", a.Level, "reason", reason, "was_handled_by", was, "sgv", deref(a.LastSGV))
	e.publish(ctx, EventAlarm, Event{Alarm: ToView(a), Reason: reason, WasHandledBy: was}, now)
	return nil
}

// clear ends an episode. by is set for a manual resolve.
func (e *Engine) clear(ctx context.Context, a store.Alarm, in Input, now time.Time, by *store.Device) error {
	ms := now.UnixMilli()
	sum := &Summary{DurationMin: int(now.Sub(time.UnixMilli(a.StartedAt)).Minutes()), HandledBy: a.HandledByName}
	a.ClearedAt = &ms
	if in.HasData {
		sgv := in.SGV // the value that ended it, for "all clear" messages
		a.LastSGV = &sgv
	}
	if by != nil {
		until := ms + e.CurrentRules(ctx).Episode.SuppressAfter.Milliseconds()
		a.ResolvedBy, a.ResolvedByName, a.ResolvedManually, a.SuppressUntil = &by.ID, by.Name, true, &until
		sum.Manual, sum.ResolvedBy = true, by.Name
	}
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return err
	}
	slog.Info("alarm cleared", "id", a.ID, "kind", a.Kind, "minutes", sum.DurationMin, "manual", sum.Manual)
	e.publish(ctx, EventClear, Event{Alarm: ToView(a), Summary: sum}, now)
	return nil
}

// Handle: "I'm on it". quiet > 0 additionally silences check-ins and level
// changes for that long ("quiet 2 h"); refused for an urgent low.
func (e *Engine) Handle(ctx context.Context, id int64, by store.Device, quiet time.Duration) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	a, err := e.liveAlarm(ctx, id)
	if err != nil {
		return View{}, err
	}
	if quiet > 0 && Kind(a.Kind) == KindLow && Level(a.Level) == Urgent {
		return View{}, ErrQuietRefused
	}
	ep := e.CurrentRules(ctx).Episode
	now := e.Now()
	ms := now.UnixMilli()
	next := ms + checkinFirst(ep, Level(a.Level)).Milliseconds()
	a.HandledBy, a.HandledByName, a.HandledAt, a.HandledSGV = &by.ID, by.Name, &ms, a.LastSGV
	a.NextCheckinAt, a.CheckinSentAt, a.HelpAt, a.QuietUntil = &next, nil, nil, nil
	if quiet > 0 {
		until := ms + min(quiet, ep.MaxQuiet).Milliseconds()
		a.QuietUntil = &until
	}
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return View{}, err
	}
	v := ToView(a)
	slog.Info("alarm handled", "id", id, "kind", a.Kind, "by", by.Name, "quiet_min", quiet.Minutes())
	e.publish(ctx, EventHandled, Event{Alarm: v, By: by.Name}, now)
	return v, nil
}

// CheckinOK answers "all under control": the responder becomes (or stays)
// the handler and the next check-in moves on.
func (e *Engine) CheckinOK(ctx context.Context, id int64, by store.Device) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	a, err := e.liveAlarm(ctx, id)
	if err != nil {
		return View{}, err
	}
	ep := e.CurrentRules(ctx).Episode
	now := e.Now()
	ms := now.UnixMilli()
	next := ms + checkinEvery(ep, Level(a.Level)).Milliseconds()
	if a.HandledBy == nil || *a.HandledBy != by.ID {
		a.HandledBy, a.HandledByName, a.HandledAt, a.HandledSGV = &by.ID, by.Name, &ms, a.LastSGV
	}
	a.NextCheckinAt, a.CheckinSentAt = &next, nil
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return View{}, err
	}
	slog.Info("alarm check-in ok", "id", id, "by", by.Name)
	v := ToView(a)
	e.publish(ctx, EventCheckinOK, Event{Alarm: v, By: by.Name}, now)
	return v, nil
}

// Help: the handler asks the others to come. The episode stays theirs.
func (e *Engine) Help(ctx context.Context, id int64, by store.Device) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	a, err := e.liveAlarm(ctx, id)
	if err != nil {
		return View{}, err
	}
	now := e.Now()
	ms := now.UnixMilli()
	if a.HandledBy == nil {
		a.HandledBy, a.HandledByName, a.HandledAt, a.HandledSGV = &by.ID, by.Name, &ms, a.LastSGV
	}
	a.HelpAt, a.CheckinSentAt = &ms, nil
	a.ComingBy, a.ComingByName, a.ComingAt = nil, "", nil // a new request: whoever said "coming" earlier is asked again
	next := ms + checkinEvery(e.CurrentRules(ctx).Episode, Level(a.Level)).Milliseconds()
	a.NextCheckinAt = &next
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return View{}, err
	}
	v := ToView(a)
	slog.Warn("alarm help requested", "id", id, "by", by.Name)
	e.publish(ctx, EventHelp, Event{Alarm: v, By: by.Name}, now)
	return v, nil
}

// Coming answers a help request: "I'm coming". The episode stays with its
// handler, who still gets the check-ins; the others just learn who is on the way.
func (e *Engine) Coming(ctx context.Context, id int64, by store.Device) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	a, err := e.liveAlarm(ctx, id)
	if err != nil {
		return View{}, err
	}
	now := e.Now()
	ms := now.UnixMilli()
	a.ComingBy, a.ComingByName, a.ComingAt = &by.ID, by.Name, &ms
	if err := e.Store.UpdateAlarm(ctx, a); err != nil {
		return View{}, err
	}
	v := ToView(a)
	slog.Info("alarm coming", "id", id, "by", by.Name, "handler", a.HandledByName)
	e.publish(ctx, EventComing, Event{Alarm: v, By: by.Name}, now)
	return v, nil
}

// Resolve ends an episode by hand (e.g. a finger stick says the sensor is
// wrong). The same kind will not ring again soon unless it gets worse.
// stillUrgentLow tells the caller to warn: the sensor still reads urgent low.
func (e *Engine) Resolve(ctx context.Context, id int64, by store.Device) (v View, stillUrgentLow bool, err error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	a, err := e.liveAlarm(ctx, id)
	if err != nil {
		return View{}, false, err
	}
	now := e.Now()
	in, err := e.Input(ctx, now)
	if err != nil {
		return View{}, false, err
	}
	if err := e.clear(ctx, a, in, now, &by); err != nil {
		return View{}, false, err
	}
	th := e.CurrentRules(ctx).Thresholds
	a, err = e.Store.AlarmByID(ctx, id)
	if err != nil {
		return View{}, false, err
	}
	return ToView(a), in.HasData && in.SGV < th.UrgentLow, nil
}

func (e *Engine) liveAlarm(ctx context.Context, id int64) (store.Alarm, error) {
	a, err := e.Store.AlarmByID(ctx, id)
	if errors.Is(err, store.ErrNotFound) || (err == nil && a.ClearedAt != nil) {
		return store.Alarm{}, ErrNotActive
	}
	return a, err
}

// EpisodeView is what a handler's screen needs to say "we wake everyone if…".
type EpisodeView struct {
	WorseBy           int `json:"worse_by"`
	CheckinTimeoutMin int `json:"checkin_timeout_min"`
	CeilingMin        int `json:"ceiling_min"`
}

func (e *Engine) EpisodeRules(ctx context.Context) EpisodeView {
	ep := e.CurrentRules(ctx).Episode
	return EpisodeView{WorseBy: ep.WorseBy, CheckinTimeoutMin: int(ep.CheckinTimeout.Minutes()), CeilingMin: int(ep.Ceiling.Minutes())}
}

// Live returns current episodes for the state snapshot.
func (e *Engine) Live(ctx context.Context) ([]View, error) {
	live, err := e.Store.ActiveAlarms(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]View, 0, len(live))
	for _, a := range live {
		out = append(out, ToView(a))
	}
	return out, nil
}

// Run is the watchdog: without it, silence (no uploads) would never alarm,
// and repeats, check-ins and their timeouts would never happen.
func (e *Engine) Run(ctx context.Context) {
	t := time.NewTicker(e.Timing.WatchdogEvery)
	defer t.Stop()
	for {
		if err := e.Evaluate(ctx); err != nil && ctx.Err() == nil {
			slog.Error("alarm watchdog", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func repeatEvery(ep Episode, l Level) time.Duration {
	if l == Urgent {
		return ep.RepeatUrgent
	}
	return ep.RepeatWarn
}

func checkinFirst(ep Episode, l Level) time.Duration {
	if l == Urgent {
		return ep.CheckinFirst
	}
	return 2 * ep.CheckinFirst
}

func checkinEvery(ep Episode, l Level) time.Duration {
	if l == Urgent {
		return ep.CheckinEvery
	}
	return 2 * ep.CheckinEvery
}

// worseBy: now is at least by mg/dL past ref in the bad direction for k.
func worseBy(k Kind, now, ref *int, by int) bool {
	if now == nil || ref == nil || side(k) == 0 {
		return false
	}
	return side(k)*(*now-*ref) >= by
}

// improvedBy: now is at least by mg/dL past ref in the good direction.
func improvedBy(k Kind, now, ref *int, by int) bool {
	if now == nil || ref == nil || side(k) == 0 {
		return false
	}
	return -side(k)*(*now-*ref) >= by
}

func deref(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

// publish tells everyone (SSE, ntfy) and keeps the step for the alarm log.
// A failed log write never stops the alarm itself.
func (e *Engine) publish(ctx context.Context, typ string, ev Event, now time.Time) {
	rec := store.AlarmEvent{AlarmID: ev.Alarm.ID, At: now.UnixMilli(), Type: typ, Reason: ev.Reason, By: ev.By, SGV: ev.Alarm.SGV}
	if typ == EventAlarm && ev.WasHandledBy != "" {
		rec.By = ev.WasHandledBy
	}
	if err := e.Store.InsertAlarmEvent(ctx, rec); err != nil {
		slog.Error("alarm log write", "id", ev.Alarm.ID, "type", typ, "err", err)
	}
	e.Bus.Publish(typ, ev, now)
}
