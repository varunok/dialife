// Package intent is "Dad is giving insulin": a parent's phone opened the
// insulin form, so the others see it before they reach for a pen too. Kept in
// memory only; it ends when the form closes, or by itself after For if the
// phone goes quiet.
package intent

import (
	"slices"
	"sync"
	"time"

	"dialife/internal/bus"
	"dialife/internal/store"
)

const (
	Event = "intent" // SSE: {intents: [...]} whenever the list changes
	For   = 10 * time.Minute
)

// Intent is one parent with the insulin form open.
type Intent struct {
	DeviceID int64  `json:"device_id"`
	Person   string `json:"person"`
	Insulin  string `json:"insulin"` // "rapid" | "basal"
	Since    int64  `json:"since"`
	Until    int64  `json:"until"`
}

type event struct {
	Intents []Intent `json:"intents"`
}

// Board keeps who has the form open and tells every phone when that changes.
type Board struct {
	Bus *bus.Bus
	Now func() time.Time

	mu     sync.Mutex
	m      map[int64]Intent
	timers map[int64]*time.Timer
}

// List is the live ones, oldest first.
func (b *Board) List(now time.Time) []Intent {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := []Intent{}
	for _, in := range b.m {
		if in.Until > now.UnixMilli() {
			out = append(out, in)
		}
	}
	slices.SortFunc(out, func(a, c Intent) int { return int(a.Since - c.Since) })
	return out
}

// Open marks the form open on this device (again: the insulin type changed).
func (b *Board) Open(dev store.Device, insulin string) {
	if insulin != "basal" {
		insulin = "rapid"
	}
	now := b.Now()
	b.mu.Lock()
	if b.m == nil {
		b.m, b.timers = map[int64]Intent{}, map[int64]*time.Timer{}
	}
	in, ok := b.m[dev.ID]
	if !ok || in.Until <= now.UnixMilli() {
		in = Intent{DeviceID: dev.ID, Person: dev.Person, Since: now.UnixMilli()}
	}
	in.Insulin, in.Until = insulin, now.Add(For).UnixMilli()
	b.m[dev.ID] = in
	if t := b.timers[dev.ID]; t != nil {
		t.Stop()
	}
	b.timers[dev.ID] = time.AfterFunc(For, func() { b.End(dev.ID) })
	b.mu.Unlock()
	b.publish()
}

// End closes the form on this device, saved or not.
func (b *Board) End(id int64) {
	b.mu.Lock()
	_, ok := b.m[id]
	delete(b.m, id)
	if t := b.timers[id]; t != nil {
		t.Stop()
		delete(b.timers, id)
	}
	b.mu.Unlock()
	if ok {
		b.publish()
	}
}

func (b *Board) publish() {
	now := b.Now()
	b.Bus.Publish(Event, event{Intents: b.List(now)}, now)
}
