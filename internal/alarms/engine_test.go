package alarms

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"dialife/internal/bus"
	"dialife/internal/store"
)

type rig struct {
	t        *testing.T
	eng      *Engine
	store    *store.Store
	events   <-chan bus.Event
	clock    time.Time
	readings []Reading
	dad, mom store.Device
}

func newRig(t *testing.T) *rig {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if _, err := s.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	dad, _ := s.CreateDevice(ctx, "Тато", "caregiver", "h1", time.Now())
	mom, _ := s.CreateDevice(ctx, "Мама", "caregiver", "h2", time.Now())
	b := bus.New(4)
	events, _, _ := b.Subscribe()
	r := &rig{t: t, store: s, events: events, clock: time.UnixMilli(now), dad: dad, mom: mom}
	r.eng = r.newEngine(b)
	return r
}

func (r *rig) newEngine(b *bus.Bus) *Engine {
	return &Engine{
		Store: r.store, Bus: b, Rules: DefaultRules, Timing: DefaultTiming,
		Now: func() time.Time { return r.clock },
		Input: func(_ context.Context, now time.Time) (Input, error) {
			if len(r.readings) == 0 {
				return Input{Now: now.UnixMilli()}, nil
			}
			last := r.readings[len(r.readings)-1]
			return Input{Now: now.UnixMilli(), HasData: true, LastMills: last.Date, SGV: last.SGV, Recent: r.readings}, nil
		},
	}
}

// reading adds a fresh reading and evaluates, like an upload does.
func (r *rig) reading(sgv int) {
	r.t.Helper()
	r.readings = append(r.readings, Reading{Date: r.clock.UnixMilli(), SGV: sgv})
	r.eval()
}

// every5 advances 5 minutes per value, adding a reading each time.
func (r *rig) every5(vals ...int) {
	r.t.Helper()
	for _, v := range vals {
		r.advance(5 * time.Minute)
		r.reading(v)
	}
}

func (r *rig) eval() {
	r.t.Helper()
	if err := r.eng.Evaluate(context.Background()); err != nil {
		r.t.Fatal(err)
	}
}

func (r *rig) advance(d time.Duration) { r.clock = r.clock.Add(d) }

func (r *rig) expect(typ string) Event {
	r.t.Helper()
	select {
	case ev := <-r.events:
		if ev.Type != typ {
			r.t.Fatalf("got %s %s, want %s", ev.Type, ev.Data, typ)
		}
		var e Event
		json.Unmarshal(ev.Data, &e)
		return e
	default:
		r.t.Fatalf("no event, want %s", typ)
	}
	return Event{}
}

func (r *rig) expectNone() {
	r.t.Helper()
	select {
	case ev := <-r.events:
		r.t.Fatalf("unexpected %s %s", ev.Type, ev.Data)
	default:
	}
}

func (r *rig) handle(id int64, by store.Device) View {
	r.t.Helper()
	v, err := r.eng.Handle(context.Background(), id, by, 0)
	if err != nil {
		r.t.Fatal(err)
	}
	if ev := r.expect(EventHandled); ev.By != by.Name {
		r.t.Fatalf("handled by %q", ev.By)
	}
	return v
}

func TestUnhandledRepeats(t *testing.T) {
	r := newRig(t)
	r.reading(50) // urgent low
	ev := r.expect(EventAlarm)
	if ev.Reason != ReasonNew || ev.Alarm.State != "firing" || ev.Alarm.Kind != KindLow || ev.Alarm.Level != Urgent {
		t.Fatalf("fire %+v", ev)
	}
	r.advance(4 * time.Minute)
	r.eval()
	r.expectNone()
	r.advance(time.Minute)
	r.eval()
	if ev := r.expect(EventAlarm); ev.Reason != ReasonRepeat {
		t.Fatalf("unhandled urgent repeat %+v", ev)
	}

	w := newRig(t)
	w.reading(65) // warn low repeats every 15
	w.expect(EventAlarm)
	w.advance(10 * time.Minute)
	w.eval()
	w.expectNone()
	w.advance(5 * time.Minute)
	w.eval()
	w.expect(EventAlarm)
}

// The case from 2026-10-25: dad treats the hypo; nothing may scream at him
// while it is slowly getting better.
func TestHandledAndRecoveringStaysQuiet(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	v := r.handle(id, r.dad)
	if v.State != "handled" || *v.HandledSGV != 54 || *v.NextCheckinAt != r.clock.Add(20*time.Minute).UnixMilli() {
		t.Fatalf("handled view %+v", v)
	}

	r.every5(53, 56, 60, 65) // 20 min, up by 11: recovering, no check-in
	r.expectNone()
	r.every5(70, 76) // 76 ≥ 75, but one lucky reading does not end it…
	r.expectNone()
	r.every5(82) // …76 then 82: two in a row past 75, over
	ev := r.expect(EventClear)
	if ev.Summary == nil || ev.Summary.HandledBy != "Тато" || ev.Summary.DurationMin != 35 || ev.Summary.Manual {
		t.Fatalf("clear %+v", ev)
	}
}

func TestCheckinOnlyWhenNotRecovering(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)

	r.every5(53, 54, 55, 56) // 20 min, barely moving
	ev := r.expect(EventCheckin)
	if ev.Alarm.HandledByID != r.dad.ID || !ev.Alarm.CheckinPending {
		t.Fatalf("check-in %+v", ev)
	}
	if _, err := r.eng.CheckinOK(context.Background(), id, r.dad); err != nil {
		t.Fatal(err)
	}
	if ev := r.expect(EventCheckinOK); ev.By != "Тато" {
		t.Fatalf("checkin_ok %+v", ev)
	}
	r.every5(57, 58) // 10 min after "ok": quiet
	r.expectNone()
	r.every5(58) // 15 min after "ok": asks again
	r.expect(EventCheckin)
}

func TestUnansweredCheckinWakesEveryone(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)
	r.every5(54, 54, 55, 55)
	r.expect(EventCheckin)
	r.every5(56) // 5 min, no answer
	ev := r.expect(EventAlarm)
	if ev.Reason != ReasonNoResponse || ev.WasHandledBy != "Тато" || ev.Alarm.State != "firing" {
		t.Fatalf("no response %+v", ev)
	}
}

func TestWorseWakesEveryone(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)
	r.every5(50, 46) // 8 below: not yet
	r.expectNone()
	r.every5(44) // 10 below the value when taken
	if ev := r.expect(EventAlarm); ev.Reason != ReasonWorse || ev.WasHandledBy != "Тато" {
		t.Fatalf("worse %+v", ev)
	}
}

func TestEscalationWhileHandledIsWorse(t *testing.T) {
	r := newRig(t)
	r.reading(66) // warn low
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.mom)
	r.every5(60, 58) // still warn, 8 below
	r.expectNone()
	r.every5(54) // urgent now
	if ev := r.expect(EventAlarm); ev.Reason != ReasonWorse || ev.Alarm.Level != Urgent {
		t.Fatalf("escalation %+v", ev)
	}
}

func TestCeilingForLongUrgent(t *testing.T) {
	r := newRig(t)
	r.reading(52)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)
	vals := []int{52, 53, 54, 52, 53, 54, 53, 54, 53} // 45 min stuck
	for i, v := range vals {
		r.every5(v)
		if i == 3 {
			r.expect(EventCheckin) // at 20 min
			r.eng.CheckinOK(context.Background(), id, r.dad)
			r.expect(EventCheckinOK)
		}
		if i == 6 {
			r.expect(EventCheckin) // 15 min after ok
			r.eng.CheckinOK(context.Background(), id, r.dad)
			r.expect(EventCheckinOK)
		}
	}
	if ev := r.expect(EventAlarm); ev.Reason != ReasonCeiling {
		t.Fatalf("ceiling %+v", ev)
	}
}

func TestHelpGoesToOthers(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)
	v, err := r.eng.Help(context.Background(), id, r.dad)
	if err != nil || v.HelpAt == nil || v.State != "handled" {
		t.Fatalf("help %+v %v", v, err)
	}
	if ev := r.expect(EventHelp); ev.By != "Тато" || ev.Alarm.HandledByID != r.dad.ID {
		t.Fatalf("help event %+v", ev)
	}
}

func TestComingKeepsTheEpisodeWithTheHandler(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)
	if _, err := r.eng.Help(context.Background(), id, r.dad); err != nil {
		t.Fatal(err)
	}
	r.expect(EventHelp)
	v, err := r.eng.Coming(context.Background(), id, r.mom)
	if err != nil || v.HandledByID != r.dad.ID || v.ComingByID != r.mom.ID || v.ComingBy != "Мама" || v.ComingAt == nil {
		t.Fatalf("coming %+v %v", v, err)
	}
	if ev := r.expect(EventComing); ev.By != "Мама" || ev.Alarm.HandledBy != "Тато" {
		t.Fatalf("coming event %+v", ev)
	}
	// asking again resets it: the others are asked once more
	if v, err := r.eng.Help(context.Background(), id, r.dad); err != nil || v.ComingByID != 0 || v.ComingAt != nil {
		t.Fatalf("second help %+v %v", v, err)
	}
	r.expect(EventHelp)
	// ringing everyone again starts a new round: nobody is on the way yet
	r.every5(44)
	if ev := r.expect(EventAlarm); ev.Reason != ReasonWorse || ev.Alarm.ComingByID != 0 || ev.Alarm.ComingAt != nil {
		t.Fatalf("coming survived a re-ring %+v", ev)
	}
}

func TestQuietForStaleNotForUrgentLow(t *testing.T) {
	r := newRig(t)
	r.reading(52)
	id := r.expect(EventAlarm).Alarm.ID
	if _, err := r.eng.Handle(context.Background(), id, r.dad, 2*time.Hour); !errors.Is(err, ErrQuietRefused) {
		t.Fatalf("quiet on urgent low: %v", err)
	}

	s := newRig(t)
	s.reading(140)
	s.advance(16 * time.Minute)
	s.eval()
	id = s.expect(EventAlarm).Alarm.ID // stale warn
	v, err := s.eng.Handle(context.Background(), id, s.dad, 2*time.Hour)
	if err != nil || v.QuietUntil == nil {
		t.Fatalf("quiet stale %+v %v", v, err)
	}
	s.expect(EventHandled)
	s.advance(20 * time.Minute) // becomes urgent stale: quiet holds (receiver works)
	s.eval()
	s.expectNone()
	s.advance(100 * time.Minute) // 2 h since it was taken (at 16 min), still no data
	s.eval()
	if ev := s.expect(EventAlarm); ev.Reason != ReasonQuietOver {
		t.Fatalf("quiet over %+v", ev)
	}
}

func TestStaleTakenWithoutQuietEscalates(t *testing.T) {
	r := newRig(t)
	r.reading(140)
	r.advance(16 * time.Minute)
	r.eval()
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.mom)
	r.advance(15 * time.Minute) // 31 min without data: urgent
	r.eval()
	if ev := r.expect(EventAlarm); ev.Reason != ReasonWorse || ev.Alarm.Level != Urgent {
		t.Fatalf("stale escalation %+v", ev)
	}
}

func TestManualResolveSuppressesUnlessWorse(t *testing.T) {
	r := newRig(t)
	r.reading(52)
	id := r.expect(EventAlarm).Alarm.ID
	v, still, err := r.eng.Resolve(context.Background(), id, r.mom)
	if err != nil || !still || v.State != "cleared" {
		t.Fatalf("resolve %+v still=%v %v", v, still, err)
	}
	if ev := r.expect(EventClear); ev.Summary == nil || !ev.Summary.Manual || ev.Summary.ResolvedBy != "Мама" {
		t.Fatalf("clear %+v", ev)
	}

	r.every5(53, 51) // finger stick said fine; sensor still ~52: no new episode
	r.expectNone()
	r.every5(41) // 11 below what mom saw: ring again
	if ev := r.expect(EventAlarm); ev.Reason != ReasonNew || ev.Alarm.ID == id {
		t.Fatalf("worse after resolve %+v", ev)
	}

	q := newRig(t)
	q.reading(52)
	id = q.expect(EventAlarm).Alarm.ID
	q.eng.Resolve(context.Background(), id, q.mom)
	q.expect(EventClear)
	q.advance(31 * time.Minute) // suppression over
	q.reading(52)
	q.expect(EventAlarm)
}

func TestActionErrors(t *testing.T) {
	r := newRig(t)
	ctx := context.Background()
	if _, err := r.eng.Handle(ctx, 999, r.dad, 0); !errors.Is(err, ErrNotActive) {
		t.Fatalf("handle unknown: %v", err)
	}
	r.reading(65)
	id := r.expect(EventAlarm).Alarm.ID
	r.every5(90, 95)
	r.expect(EventClear)
	if _, err := r.eng.CheckinOK(ctx, id, r.dad); !errors.Is(err, ErrNotActive) {
		t.Fatalf("checkin on cleared: %v", err)
	}
	if _, _, err := r.eng.Resolve(ctx, id, r.dad); !errors.Is(err, ErrNotActive) {
		t.Fatalf("resolve cleared: %v", err)
	}
}

func TestEpisodeSurvivesRestart(t *testing.T) {
	r := newRig(t)
	r.reading(54)
	id := r.expect(EventAlarm).Alarm.ID
	r.handle(id, r.dad)

	b := bus.New(2)
	r.events, _, _ = b.Subscribe()
	r.eng = r.newEngine(b) // as after a deploy

	r.every5(54, 55, 55)
	r.expectNone() // still handled by dad, check-in not due yet
	r.every5(55)
	if ev := r.expect(EventCheckin); ev.Alarm.HandledBy != "Тато" {
		t.Fatalf("check-in after restart %+v", ev)
	}
}
