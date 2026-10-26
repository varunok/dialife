package auth

import (
	"sync"
	"time"
)

// failLimiter blocks an IP after too many failed authentications. Tokens are
// unguessable anyway; this keeps scanners from filling the logs and the DB.
// The threshold is generous so a misconfigured phone sharing the family's
// home IP does not lock out the xDrip+ uploader for long.
type failLimiter struct {
	max    int
	window time.Duration
	block  time.Duration

	mu sync.Mutex
	m  map[string]*ipFails
}

type ipFails struct {
	count        int
	windowStart  time.Time
	blockedUntil time.Time
}

const limiterMaxIPs = 10000

func newFailLimiter(max int, window, block time.Duration) *failLimiter {
	return &failLimiter{max: max, window: window, block: block, m: map[string]*ipFails{}}
}

// blocked reports whether ip is serving a block, and until when.
func (l *failLimiter) blocked(ip string, now time.Time) (bool, time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	f, ok := l.m[ip]
	if !ok || !now.Before(f.blockedUntil) {
		return false, time.Time{}
	}
	return true, f.blockedUntil
}

// fail records a failure; true when this one triggered a block.
func (l *failLimiter) fail(ip string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.m) >= limiterMaxIPs {
		l.sweep(now)
	}
	f := l.m[ip]
	if f == nil || now.Sub(f.windowStart) > l.window {
		f = &ipFails{windowStart: now}
		l.m[ip] = f
	}
	f.count++
	if f.count >= l.max && !now.Before(f.blockedUntil) {
		f.blockedUntil = now.Add(l.block)
		return true
	}
	return false
}

func (l *failLimiter) sweep(now time.Time) {
	for ip, f := range l.m {
		if now.Sub(f.windowStart) > l.window && !now.Before(f.blockedUntil) {
			delete(l.m, ip)
		}
	}
}
