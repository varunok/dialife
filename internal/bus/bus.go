// Package bus fans events out to live subscribers (SSE streams now, MQTT later).
// Publishing never blocks: a subscriber whose buffer is full is dropped and
// is expected to reconnect and resync.
package bus

import (
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"time"
)

var (
	ErrFull   = errors.New("too many subscribers")
	ErrClosed = errors.New("bus closed")
)

const bufferSize = 32

type Event struct {
	ID   int64 // unix ms, strictly increasing; doubles as SSE id / Last-Event-ID
	Type string
	Data []byte // JSON
}

type Bus struct {
	max int

	mu     sync.Mutex
	subs   map[chan Event]struct{}
	lastID int64
	closed bool
}

func New(maxSubscribers int) *Bus {
	return &Bus{max: maxSubscribers, subs: map[chan Event]struct{}{}}
}

// NextID returns a strictly increasing id based on wall time.
func (b *Bus) NextID(now time.Time) int64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.nextIDLocked(now)
}

func (b *Bus) nextIDLocked(now time.Time) int64 {
	b.lastID = max(b.lastID+1, now.UnixMilli())
	return b.lastID
}

// Subscribe returns a channel of events and a cancel func. The channel is
// closed when cancel is called, the subscriber falls behind, or the bus closes.
func (b *Bus) Subscribe() (<-chan Event, func(), error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return nil, nil, ErrClosed
	}
	if len(b.subs) >= b.max {
		return nil, nil, ErrFull
	}
	ch := make(chan Event, bufferSize)
	b.subs[ch] = struct{}{}
	var once sync.Once
	cancel := func() {
		once.Do(func() {
			b.mu.Lock()
			defer b.mu.Unlock()
			b.dropLocked(ch)
		})
	}
	return ch, cancel, nil
}

func (b *Bus) dropLocked(ch chan Event) {
	if _, ok := b.subs[ch]; ok {
		delete(b.subs, ch)
		close(ch)
	}
}

// Publish sends v as JSON to every subscriber and returns the event id.
func (b *Bus) Publish(typ string, v any, now time.Time) int64 {
	data, err := json.Marshal(v)
	if err != nil {
		slog.Error("bus marshal", "type", typ, "err", err)
		return 0
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return 0
	}
	ev := Event{ID: b.nextIDLocked(now), Type: typ, Data: data}
	for ch := range b.subs {
		select {
		case ch <- ev:
		default:
			slog.Warn("bus subscriber too slow, dropped", "type", typ)
			b.dropLocked(ch)
		}
	}
	return ev.ID
}

func (b *Bus) Subscribers() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.subs)
}

// Close ends every subscription; used on server shutdown so streams return.
func (b *Bus) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closed = true
	for ch := range b.subs {
		b.dropLocked(ch)
	}
}
