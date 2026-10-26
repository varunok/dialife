package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/store"
)

// testNow sits just after the timestamps in testdata/xdrip.
var testNow = time.Date(2026, 10, 25, 1, 0, 0, 0, time.UTC)

// testSecret signs ack links in tests.
const testSecret = "test-secret-0123456789abcdef0123456789"

type fixture struct {
	srv   *httptest.Server
	store *store.Store
	bus   *bus.Bus
	links *acklink.Signer
	clock time.Time
	mu    sync.Mutex
	app   *App
}

// now is the server's clock; tests move it with advance.
func (f *fixture) now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.clock
}

func (f *fixture) advance(d time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.clock = f.clock.Add(d)
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if _, err := s.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	b := bus.New(4)
	links, _ := acklink.NewSigner(testSecret)
	f := &fixture{store: s, bus: b, links: links, clock: testNow}
	f.app = New(Deps{Store: s, Auth: auth.NewAuthenticator(s, f.now), Bus: b, Version: "test", Now: f.now,
		PingEvery: 50 * time.Millisecond, Links: links})
	f.srv = httptest.NewServer(f.app.Handler)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fixture) addDevice(t *testing.T, name string, role auth.Role) (id int64, token string) {
	t.Helper()
	token, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	d, err := f.store.CreateDevice(context.Background(), name, string(role), auth.Hash(token), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return d.ID, token
}

func (f *fixture) get(t *testing.T, path string, hdr map[string]string) *http.Response {
	t.Helper()
	return f.do(t, http.MethodGet, path, nil, hdr)
}

func (f *fixture) do(t *testing.T, method, path string, body io.Reader, hdr map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(method, f.srv.URL+path, body)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func TestStatusAuth(t *testing.T) {
	f := newFixture(t)
	id, token := f.addDevice(t, "xDrip", auth.RoleUploader)

	cases := []struct {
		name string
		hdr  map[string]string
		want int
	}{
		{"no credentials", nil, 401},
		{"garbage bearer", map[string]string{"Authorization": "Bearer nope"}, 401},
		{"not bearer scheme", map[string]string{"Authorization": "Basic " + token}, 401},
		{"bearer", map[string]string{"Authorization": "Bearer " + token}, 200},
		{"xdrip hashed api-secret", map[string]string{"api-secret": auth.Hash(token)}, 200},
		{"plain api-secret", map[string]string{"api-secret": token}, 200},
		{"wrong api-secret", map[string]string{"api-secret": auth.Hash("x")}, 401},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := f.get(t, "/api/v1/status.json", c.hdr).StatusCode; got != c.want {
				t.Fatalf("status %d, want %d", got, c.want)
			}
		})
	}

	if err := f.store.RevokeDevice(context.Background(), id, time.Now()); err != nil {
		t.Fatal(err)
	}
	if got := f.get(t, "/api/v1/status.json", map[string]string{"Authorization": "Bearer " + token}).StatusCode; got != 401 {
		t.Fatalf("revoked token: status %d, want 401", got)
	}
}

func TestStatusBody(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)

	resp := f.get(t, "/api/v1/status.json", map[string]string{"api-secret": auth.Hash(token)})
	var body struct {
		Status          string `json:"status"`
		ServerTimeEpoch int64  `json:"serverTimeEpoch"`
		Settings        struct {
			Units      string         `json:"units"`
			Thresholds map[string]int `json:"thresholds"`
		} `json:"settings"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Status != "ok" || body.ServerTimeEpoch != testNow.UnixMilli() || body.Settings.Units != "mg/dl" {
		t.Fatalf("unexpected body %+v", body)
	}
	want := map[string]int{"bgLow": 55, "bgTargetBottom": 70, "bgTargetTop": 180, "bgHigh": 250}
	for k, v := range want {
		if body.Settings.Thresholds[k] != v {
			t.Errorf("threshold %s = %d, want %d", k, body.Settings.Thresholds[k], v)
		}
	}
}

func TestHealthzAndUnknownPaths(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "panel", auth.RoleReader)
	bearer := map[string]string{"Authorization": "Bearer " + token}

	if got := f.get(t, "/api/healthz", bearer).StatusCode; got != 200 {
		t.Fatalf("healthz %d", got)
	}
	if got := f.get(t, "/api/healthz", nil).StatusCode; got != 401 {
		t.Fatalf("healthz without token %d", got)
	}
	if got := f.get(t, "/wp-admin", nil).StatusCode; got != 404 {
		t.Fatalf("unknown path %d", got)
	}
}

func TestLastSeenIsTracked(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	f.get(t, "/api/healthz", map[string]string{"Authorization": "Bearer " + token})

	devs, err := f.store.ListDevices(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if devs[0].LastSeenAt == nil || !devs[0].LastSeenAt.Equal(testNow) {
		t.Fatalf("last_seen_at = %v", devs[0].LastSeenAt)
	}
}

func TestFailedAuthBlocksIP(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	scanner := map[string]string{"X-Forwarded-For": "203.0.113.66", "Authorization": "Bearer guess"}

	for i := range 20 {
		if got := f.get(t, "/api/healthz", scanner).StatusCode; got != 401 {
			t.Fatalf("attempt %d: status %d", i+1, got)
		}
	}
	resp := f.get(t, "/api/healthz", scanner)
	if resp.StatusCode != 429 || resp.Header.Get("Retry-After") != "601" { // 10 min block on a frozen clock
		t.Fatalf("21st attempt: status %d, Retry-After %q", resp.StatusCode, resp.Header.Get("Retry-After"))
	}
	// the block is per IP: the xDrip+ phone elsewhere is unaffected
	ok := map[string]string{"X-Forwarded-For": "198.51.100.7", "Authorization": "Bearer " + token}
	if got := f.get(t, "/api/healthz", ok).StatusCode; got != 200 {
		t.Fatalf("other IP: status %d", got)
	}
	// wrong role is not a failed login and does not count
	_, reader := f.addDevice(t, "panel", auth.RoleReader)
	for range 25 {
		f.do(t, "POST", "/api/v1/entries", strings.NewReader("[]"), map[string]string{"X-Forwarded-For": "198.51.100.8", "Authorization": "Bearer " + reader})
	}
	if got := f.get(t, "/api/healthz", map[string]string{"X-Forwarded-For": "198.51.100.8", "Authorization": "Bearer " + reader}).StatusCode; got != 200 {
		t.Fatalf("403s counted as failures: status %d", got)
	}
}

func TestMe(t *testing.T) {
	f := newFixture(t)
	id, token := f.addDevice(t, "Тато", auth.RoleCaregiver)
	resp := f.get(t, "/api/me", map[string]string{"Authorization": "Bearer " + token})
	var me struct {
		ID           int64
		Name, Role   string
		DisplayUnits string `json:"display_units"`
	}
	json.NewDecoder(resp.Body).Decode(&me)
	if resp.StatusCode != 200 || me.ID != id || me.Name != "Тато" || me.Role != "caregiver" || me.DisplayUnits != "mg/dl" {
		t.Fatalf("me: %d %+v", resp.StatusCode, me)
	}
	if got := f.get(t, "/api/me", map[string]string{"Authorization": "Bearer dl_nope"}).StatusCode; got != 401 {
		t.Fatalf("bad token: %d", got)
	}
}
