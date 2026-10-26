package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"dialife/internal/httpx"
	"dialife/internal/store"
)

// touchEvery limits last_seen_at writes; xDrip+ uploads every 5 minutes anyway.
const touchEvery = time.Minute

// failed-auth limits per client IP
const (
	maxAuthFailures = 20
	authFailWindow  = time.Minute
	authBlockFor    = 10 * time.Minute
)

type Authenticator struct {
	store *store.Store
	now   func() time.Time

	mu      sync.Mutex
	touched map[int64]time.Time
	limiter *failLimiter
}

func NewAuthenticator(s *store.Store, now func() time.Time) *Authenticator {
	return &Authenticator{store: s, now: now, touched: map[int64]time.Time{},
		limiter: newFailLimiter(maxAuthFailures, authFailWindow, authBlockFor)}
}

type ctxKey struct{}

func DeviceFrom(ctx context.Context) (store.Device, bool) {
	d, ok := ctx.Value(ctxKey{}).(store.Device)
	return d, ok
}

// Require admits only requests carrying a valid, non-revoked token.
// With no roles given, any role is accepted.
func (a *Authenticator) Require(roles ...Role) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			now := a.now()
			if blocked, until := a.limiter.blocked(httpx.ClientIP(r), now); blocked {
				w.Header().Set("Retry-After", strconv.Itoa(int(until.Sub(now).Seconds())+1))
				httpx.Error(w, http.StatusTooManyRequests)
				return
			}
			hash, ok := credentialHash(r)
			if !ok {
				a.reject(w, r, "missing credentials")
				return
			}
			dev, err := a.store.ActiveDeviceByTokenSHA1(r.Context(), hash)
			if errors.Is(err, store.ErrNotFound) {
				a.reject(w, r, "unknown token")
				return
			}
			if err != nil {
				slog.Error("auth lookup", "err", err)
				httpx.Error(w, http.StatusInternalServerError)
				return
			}
			if !allowed(Role(dev.Role), roles) {
				slog.Warn("auth forbidden", "device", dev.Name, "role", dev.Role, "path", r.URL.Path)
				httpx.Error(w, http.StatusForbidden)
				return
			}
			a.touch(r.Context(), dev.ID)
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, dev)))
		})
	}
}

// credentialHash accepts "Authorization: Bearer <token>" (our apps) or
// "api-secret: <sha1 hex>" (xDrip+ and other Nightscout clients). A plain
// api-secret is hashed too, for clients that send the secret as-is.
func credentialHash(r *http.Request) (string, bool) {
	if h := r.Header.Get("Authorization"); h != "" {
		tok, ok := strings.CutPrefix(h, "Bearer ")
		if !ok || tok == "" {
			return "", false
		}
		return Hash(tok), true
	}
	if s := strings.TrimSpace(r.Header.Get("api-secret")); s != "" {
		if isSHA1Hex(s) {
			return strings.ToLower(s), true
		}
		return Hash(s), true
	}
	return "", false
}

func isSHA1Hex(s string) bool {
	if len(s) != 40 {
		return false
	}
	for _, c := range s {
		if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
			return false
		}
	}
	return true
}

func allowed(role Role, roles []Role) bool {
	if len(roles) == 0 {
		return true
	}
	for _, r := range roles {
		if r == role {
			return true
		}
	}
	return false
}

// reject answers every failure the same way so probes learn nothing.
func (a *Authenticator) reject(w http.ResponseWriter, r *http.Request, reason string) {
	ip := httpx.ClientIP(r)
	slog.Warn("auth failed", "ip", ip, "path", r.URL.Path, "reason", reason)
	if a.limiter.fail(ip, a.now()) {
		slog.Warn("auth ip blocked", "ip", ip, "for", authBlockFor.String())
	}
	httpx.Error(w, http.StatusUnauthorized)
}

func (a *Authenticator) touch(ctx context.Context, id int64) {
	now := a.now()
	a.mu.Lock()
	if now.Sub(a.touched[id]) < touchEvery {
		a.mu.Unlock()
		return
	}
	a.touched[id] = now
	a.mu.Unlock()

	if err := a.store.TouchDevice(ctx, id, now); err != nil {
		slog.Warn("touch device", "id", id, "err", err)
	}
}
