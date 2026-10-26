package auth

import (
	"testing"
	"time"
)

func TestFailLimiter(t *testing.T) {
	l := newFailLimiter(3, time.Minute, 10*time.Minute)
	t0 := time.Unix(1_792_886_400, 0)

	for i := range 2 {
		if l.fail("1.1.1.1", t0.Add(time.Duration(i)*time.Second)) {
			t.Fatalf("blocked after %d failures", i+1)
		}
	}
	if b, _ := l.blocked("1.1.1.1", t0.Add(3*time.Second)); b {
		t.Fatal("blocked before the limit")
	}
	if !l.fail("1.1.1.1", t0.Add(3*time.Second)) {
		t.Fatal("third failure did not block")
	}
	if b, until := l.blocked("1.1.1.1", t0.Add(5*time.Minute)); !b || !until.Equal(t0.Add(3*time.Second+10*time.Minute)) {
		t.Fatalf("not blocked mid-way: %v %v", b, until)
	}
	if b, _ := l.blocked("2.2.2.2", t0.Add(5*time.Minute)); b {
		t.Fatal("another IP blocked")
	}
	if b, _ := l.blocked("1.1.1.1", t0.Add(11*time.Minute)); b {
		t.Fatal("block never ends")
	}

	// failures spread wider than the window never add up
	slow := newFailLimiter(3, time.Minute, time.Minute)
	for i := range 10 {
		if slow.fail("3.3.3.3", t0.Add(time.Duration(i)*2*time.Minute)) {
			t.Fatal("slow failures blocked")
		}
	}
}

func TestFailLimiterSweeps(t *testing.T) {
	l := newFailLimiter(100, time.Minute, time.Minute)
	t0 := time.Unix(1_792_886_400, 0)
	for i := range limiterMaxIPs {
		l.fail(string(rune(i)), t0)
	}
	l.fail("late", t0.Add(2*time.Minute))
	if len(l.m) != 1 {
		t.Fatalf("stale entries kept: %d", len(l.m))
	}
}
