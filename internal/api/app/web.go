package app

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"rsc.io/qr"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/store"
)

// Logging into the web viewer. The browser asks for a code and shows it as a
// QR code; a parent scans it in the app and allows it for a chosen time; the
// browser, polling with the secret only it got, then receives a read-only
// session token. Nothing is ever stored in the browser by the server.
const (
	webCodeTTL        = 2 * time.Minute
	webScannedTTL     = 5 * time.Minute // once scanned, the parent has this long to choose and allow
	webMaxPending     = 50              // unanswered codes at once: a cap on floods
	webMaxSessionMins = 90 * 24 * 60
)

type webLoginStarted struct {
	ID        string `json:"id"`
	Secret    string `json:"secret"` // the browser keeps it in memory and polls with it
	ExpiresAt int64  `json:"expires_at"`
}

// WebLoginStart serves POST /api/web/login: a new code for this browser.
func (h *Handlers) WebLoginStart(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	now := h.Now()
	if n, err := h.Store.CountPendingWebLogins(ctx, now); err != nil {
		fail(w, "web login count", err)
		return
	} else if n >= webMaxPending {
		httpx.Error(w, http.StatusTooManyRequests)
		return
	}
	l := store.WebLogin{
		ID:        randomHex(16),
		UserAgent: truncate(r.UserAgent(), 300),
		IP:        httpx.ClientIP(r),
		CreatedAt: now.UnixMilli(),
		ExpiresAt: now.Add(webCodeTTL).UnixMilli(),
	}
	code, secret := randomHex(20), randomHex(20)
	l.CodeSHA, l.BrowserSHA = auth.Hash(code), auth.Hash(secret)
	if err := h.Store.InsertWebLogin(ctx, l, now); err != nil {
		fail(w, "web login insert", err)
		return
	}
	h.webCodes.put(l.ID, code, now.Add(webCodeTTL))
	httpx.WriteJSON(w, http.StatusOK, webLoginStarted{ID: l.ID, Secret: secret, ExpiresAt: l.ExpiresAt})
}

// WebLoginQR serves GET /api/web/login/{id}/qr.svg: the code to scan, while it lives.
// The code itself never reaches the browser's script, only this picture.
func (h *Handlers) WebLoginQR(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	code, ok := h.webCodes.get(id, h.Now())
	if !ok {
		httpx.Error(w, http.StatusNotFound)
		return
	}
	c, err := qr.Encode("dialife://web-login?id="+id+"&code="+code, qr.M)
	if err != nil {
		fail(w, "web login qr", err)
		return
	}
	w.Header().Set("Content-Type", "image/svg+xml")
	w.Header().Set("Cache-Control", "no-store")
	fmt.Fprint(w, qrSVG(c))
}

type webPollRequest struct {
	Secret string `json:"secret"`
}

type webPollResponse struct {
	State     string `json:"state"` // "pending" | "scanned" | "approved" | "rejected" | "expired"
	Token     string `json:"token,omitempty"`
	ExpiresAt int64  `json:"expires_at,omitempty"`
	By        string `json:"by,omitempty"` // who allowed it
}

// WebLoginPoll serves POST /api/web/login/{id}/poll: has a parent answered yet?
func (h *Handlers) WebLoginPoll(w http.ResponseWriter, r *http.Request) {
	var req webPollRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	ctx := r.Context()
	now := h.Now()
	l, err := h.Store.WebLoginByID(ctx, r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) || (err == nil && l.BrowserSHA != auth.Hash(req.Secret)) {
		httpx.Error(w, http.StatusNotFound) // another browser's code looks like no code at all
		return
	}
	if err != nil {
		fail(w, "web login poll", err)
		return
	}
	switch {
	case l.State == "rejected":
		httpx.WriteJSON(w, http.StatusOK, webPollResponse{State: "rejected"})
	case l.State == "approved":
		token, err := auth.NewWebToken()
		if err != nil {
			fail(w, "web token", err)
			return
		}
		if _, err := h.Store.UseWebLogin(ctx, l, auth.Hash(token), now); err != nil {
			fail(w, "web session", err)
			return
		}
		h.webCodes.drop(l.ID)
		by := ""
		if d, err := h.Store.ActiveDeviceByID(ctx, l.ApprovedBy); err == nil {
			by = d.Person
		}
		slog.Info("web session", "by", by, "minutes", l.Minutes, "agent", l.UserAgent)
		httpx.WriteJSON(w, http.StatusOK, webPollResponse{State: "approved", Token: token, By: by,
			ExpiresAt: now.Add(time.Duration(l.Minutes) * time.Minute).UnixMilli()})
	case l.State == "used" || now.UnixMilli() > l.ExpiresAt:
		httpx.WriteJSON(w, http.StatusOK, webPollResponse{State: "expired"})
	case l.ScannedAt > 0:
		// a parent has it on the phone: keep this code, it lives until they answer
		httpx.WriteJSON(w, http.StatusOK, webPollResponse{State: "scanned", ExpiresAt: l.ExpiresAt})
	default:
		httpx.WriteJSON(w, http.StatusOK, webPollResponse{State: "pending", ExpiresAt: l.ExpiresAt})
	}
}

type webCodeRequest struct {
	ID      string `json:"id"`
	Code    string `json:"code"`
	Minutes int    `json:"minutes,omitempty"`
}

type webLoginInfo struct {
	ID        string `json:"id"`
	Browser   string `json:"browser"`
	OS        string `json:"os"`
	ExpiresAt int64  `json:"expires_at"`
	MaxMin    int    `json:"max_minutes"`
	// whether the browser asked from the network the phone is on now: at home
	// both leave through one address; a code shown by someone else will not
	IP          string `json:"ip,omitempty"`
	SameNetwork bool   `json:"same_network"`
}

// WebLoginInspect serves POST /api/web/inspect {id, code}: which browser asks, before a parent decides.
func (h *Handlers) WebLoginInspect(w http.ResponseWriter, r *http.Request) {
	l, ok := h.pendingLogin(w, r, nil)
	if !ok {
		return
	}
	// scanned: the parent now needs time to choose, and the browser must not swap the code
	exp := h.Now().Add(webScannedTTL).UnixMilli()
	if err := h.Store.ScanWebLogin(r.Context(), l.ID, h.Now(), exp); err != nil {
		fail(w, "web login scan", err)
		return
	}
	h.webCodes.extend(l.ID, time.UnixMilli(exp))
	b, o := describeAgent(l.UserAgent)
	httpx.WriteJSON(w, http.StatusOK, webLoginInfo{ID: l.ID, Browser: b, OS: o, ExpiresAt: max(l.ExpiresAt, exp), MaxMin: webMaxSessionMins,
		IP: l.IP, SameNetwork: l.IP != "" && l.IP == httpx.ClientIP(r)})
}

// WebLoginApprove serves POST /api/web/approve {id, code, minutes}.
func (h *Handlers) WebLoginApprove(w http.ResponseWriter, r *http.Request) {
	var req webCodeRequest
	l, ok := h.pendingLogin(w, r, &req)
	if !ok {
		return
	}
	if req.Minutes < 1 || req.Minutes > webMaxSessionMins {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	h.decideLogin(w, r, l, true, dev, req.Minutes)
}

// WebLoginReject serves POST /api/web/reject {id, code}.
func (h *Handlers) WebLoginReject(w http.ResponseWriter, r *http.Request) {
	l, ok := h.pendingLogin(w, r, nil)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	h.decideLogin(w, r, l, false, dev, 0)
}

func (h *Handlers) decideLogin(w http.ResponseWriter, r *http.Request, l store.WebLogin, approve bool, dev store.Device, minutes int) {
	done, err := h.Store.DecideWebLogin(r.Context(), l.ID, approve, dev.ID, minutes)
	if err != nil {
		fail(w, "web login decide", err)
		return
	}
	if !done {
		httpx.Error(w, http.StatusConflict) // someone else answered first
		return
	}
	slog.Info("web login decided", "approve", approve, "by", dev.Person, "minutes", minutes)
	w.WriteHeader(http.StatusNoContent)
}

// pendingLogin finds the unexpired, unanswered code a scanned QR carries.
func (h *Handlers) pendingLogin(w http.ResponseWriter, r *http.Request, into *webCodeRequest) (store.WebLogin, bool) {
	var req webCodeRequest
	if into == nil {
		into = &req
	}
	if !decodeOptional(w, r, into) {
		return store.WebLogin{}, false
	}
	l, err := h.Store.WebLoginByCode(r.Context(), auth.Hash(into.Code))
	if errors.Is(err, store.ErrNotFound) || (err == nil && l.ID != into.ID) {
		httpx.Error(w, http.StatusNotFound)
		return l, false
	}
	if err != nil {
		fail(w, "web login lookup", err)
		return l, false
	}
	if l.State != "pending" || h.Now().UnixMilli() > l.ExpiresAt {
		httpx.Error(w, http.StatusGone)
		return l, false
	}
	return l, true
}

type webSessionView struct {
	ID         int64  `json:"id"`
	Browser    string `json:"browser"`
	OS         string `json:"os"`
	By         string `json:"by"`
	CreatedAt  int64  `json:"created_at"`
	ExpiresAt  int64  `json:"expires_at"`
	LastSeenAt *int64 `json:"last_seen_at,omitempty"`
}

// WebSessions serves GET /api/web/sessions: the browsers allowed in now.
func (h *Handlers) WebSessions(w http.ResponseWriter, r *http.Request) {
	ss, err := h.Store.ActiveWebSessions(r.Context(), h.Now())
	if err != nil {
		fail(w, "web sessions", err)
		return
	}
	out := []webSessionView{}
	for _, s := range ss {
		b, o := describeAgent(s.UserAgent)
		out = append(out, webSessionView{ID: s.ID, Browser: b, OS: o, By: s.ApproverName, CreatedAt: s.CreatedAt, ExpiresAt: s.ExpiresAt, LastSeenAt: s.LastSeenAt})
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

// WebSessionRevoke serves POST /api/web/sessions/{id}/revoke: any parent closes any browser.
func (h *Handlers) WebSessionRevoke(w http.ResponseWriter, r *http.Request) {
	id, ok := questionID(w, r) // a positive integer id, same parsing
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	if _, err := h.Store.RevokeWebSession(r.Context(), id, dev.Person, h.Now()); err != nil {
		fail(w, "web session revoke", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// WebLogout serves POST /api/web/logout: the browser ends its own session.
func (h *Handlers) WebLogout(w http.ResponseWriter, r *http.Request) {
	dev, _ := auth.DeviceFrom(r.Context())
	if dev.WebSessionID == 0 {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	if _, err := h.Store.RevokeWebSession(r.Context(), dev.WebSessionID, "web", h.Now()); err != nil {
		fail(w, "web logout", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// describeAgent turns a User-Agent into "Firefox" and "Linux", close enough for a parent to recognise.
func describeAgent(ua string) (browser, os string) {
	has := func(s string) bool { return strings.Contains(ua, s) }
	switch {
	case has("Edg/"):
		browser = "Edge"
	case has("OPR/"):
		browser = "Opera"
	case has("Firefox/"):
		browser = "Firefox"
	case has("Chrome/") || has("CriOS/"):
		browser = "Chrome"
	case has("Safari/"):
		browser = "Safari"
	default:
		browser = "?"
	}
	switch {
	case has("Android"):
		os = "Android"
	case has("iPhone") || has("iPad"):
		os = "iOS"
	case has("Windows"):
		os = "Windows"
	case has("Mac OS X") || has("Macintosh"):
		os = "macOS"
	case has("CrOS"):
		os = "ChromeOS"
	case has("Linux"):
		os = "Linux"
	default:
		os = "?"
	}
	return browser, os
}

func qrSVG(c *qr.Code) string {
	const quiet = 4
	n := c.Size + 2*quiet
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges"><rect width="%d" height="%d" fill="#fff"/><path fill="#000" d="`, n, n, n, n)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; x++ {
			if c.Black(x, y) {
				fmt.Fprintf(&b, "M%d %dh1v1h-1z", x+quiet, y+quiet)
			}
		}
	}
	b.WriteString(`"/></svg>`)
	return b.String()
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// webCodes keeps each code in memory only for drawing its QR picture; the
// database has just its hash. A restart forgets them: the page asks again.
type webCodes struct {
	mu sync.Mutex
	m  map[string]webCode
}

type webCode struct {
	code    string
	expires time.Time
}

func (c *webCodes) put(id, code string, expires time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string]webCode{}
	}
	for k, v := range c.m {
		if time.Now().After(v.expires.Add(time.Minute)) {
			delete(c.m, k)
		}
	}
	c.m[id] = webCode{code, expires}
}

func (c *webCodes) get(id string, now time.Time) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	v, ok := c.m[id]
	if !ok || now.After(v.expires) {
		return "", false
	}
	return v.code, true
}

func (c *webCodes) extend(id string, expires time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if v, ok := c.m[id]; ok && expires.After(v.expires) {
		v.expires = expires
		c.m[id] = v
	}
}

func (c *webCodes) drop(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.m, id)
}

// WebCode is the code behind a login id, for tests that play the scanning app.
func (h *Handlers) WebCode(id string) string {
	c, _ := h.webCodes.get(id, time.Time{})
	return c
}
