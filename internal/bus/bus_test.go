package bus

import (
	"errors"
	"testing"
	"time"
)

var t0 = time.UnixMilli(1_792_886_400_000)

func TestPublishFansOut(t *testing.T) {
	b := New(10)
	a, cancelA, _ := b.Subscribe()
	c, cancelC, _ := b.Subscribe()
	defer cancelA()
	defer cancelC()

	id := b.Publish("bg", map[string]int{"sgv": 120}, t0)
	for _, ch := range []<-chan Event{a, c} {
		ev := <-ch
		if ev.ID != id || ev.Type != "bg" || string(ev.Data) != `{"sgv":120}` {
			t.Fatalf("event %+v", ev)
		}
	}
}

func TestIDsStrictlyIncrease(t *testing.T) {
	b := New(1)
	first := b.Publish("x", 1, t0)
	second := b.Publish("x", 2, t0)                  // same millisecond
	third := b.Publish("x", 3, t0.Add(-time.Second)) // clock stepped back
	if first != t0.UnixMilli() || second != first+1 || third != second+1 {
		t.Fatalf("ids %d %d %d", first, second, third)
	}
}

func TestSlowSubscriberDropped(t *testing.T) {
	b := New(10)
	slow, _, _ := b.Subscribe()
	fast, cancel, _ := b.Subscribe()
	defer cancel()

	for i := range bufferSize + 1 {
		b.Publish("x", i, t0)
		<-fast
	}
	if b.Subscribers() != 1 {
		t.Fatalf("slow subscriber kept: %d subscribers", b.Subscribers())
	}
	n := 0
	for range slow { // drains the buffer, then sees the channel closed
		n++
	}
	if n != bufferSize {
		t.Fatalf("slow got %d buffered events before close", n)
	}
}

func TestLimitsAndClose(t *testing.T) {
	b := New(1)
	ch, cancel, err := b.Subscribe()
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := b.Subscribe(); !errors.Is(err, ErrFull) {
		t.Fatalf("second subscribe: %v", err)
	}
	cancel()
	cancel() // idempotent
	if _, ok := <-ch; ok {
		t.Fatal("cancelled channel still open")
	}

	ch, _, _ = b.Subscribe()
	b.Close()
	if _, ok := <-ch; ok {
		t.Fatal("channel open after Close")
	}
	if _, _, err := b.Subscribe(); !errors.Is(err, ErrClosed) {
		t.Fatalf("subscribe after close: %v", err)
	}
	if id := b.Publish("x", 1, t0); id != 0 {
		t.Fatalf("publish after close returned id %d", id)
	}
}
