package app

import (
	"net/http"
	"slices"
	"sync"
	"time"

	"dialife/internal/auth"
)

// "Dad is giving insulin": a parent's phone opened the insulin form, so the
// others see it before they reach for a pen too. Kept in memory only; it ends
// when the form closes, or by itself after intentFor if the phone goes quiet.
const (
	EventIntent = "intent" // SSE: {intents: [...]} whenever the list changes
	intentFor   = 10 * time.Minute
)

type insulinIntent struct {
	DeviceID int64  `json:"device_id"`
	Person   string `json:"person"`
	Insulin  string `json:"insulin"` // "rapid" | "basal"
	Since    int64  `json:"since"`
	Until    int64  `json:"until"`
}

type intentEvent struct {
	Intents []insulinIntent `json:"intents"`
}

type intentBoard struct {
	mu     sync.Mutex
	m      map[int64]insulinIntent
	timers map[int64]*time.Timer
}

// list: the live ones, oldest first.
func (b *intentBoard) list(now time.Time) []insulinIntent {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := []insulinIntent{}
	for _, in := range b.m {
		if in.Until > now.UnixMilli() {
			out = append(out, in)
		}
	}
	slices.SortFunc(out, func(a, c insulinIntent) int { return int(a.Since - c.Since) })
	return out
}

type intentRequest struct {
	Insulin string `json:"insulin"`
}

// InsulinIntent serves POST /api/insulin/intent: the form is open (again: the type changed).
func (h *Handlers) InsulinIntent(w http.ResponseWriter, r *http.Request) {
	var req intentRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	if req.Insulin != "basal" {
		req.Insulin = "rapid"
	}
	dev, _ := auth.DeviceFrom(r.Context())
	now := h.Now()
	b := &h.intents
	b.mu.Lock()
	if b.m == nil {
		b.m, b.timers = map[int64]insulinIntent{}, map[int64]*time.Timer{}
	}
	in, ok := b.m[dev.ID]
	if !ok || in.Until <= now.UnixMilli() {
		in = insulinIntent{DeviceID: dev.ID, Person: dev.Person, Since: now.UnixMilli()}
	}
	in.Insulin, in.Until = req.Insulin, now.Add(intentFor).UnixMilli()
	b.m[dev.ID] = in
	if t := b.timers[dev.ID]; t != nil {
		t.Stop()
	}
	b.timers[dev.ID] = time.AfterFunc(intentFor, func() { h.endIntent(dev.ID) })
	b.mu.Unlock()
	h.publishIntents()
	w.WriteHeader(http.StatusNoContent)
}

// InsulinIntentEnd serves DELETE /api/insulin/intent: the form closed, saved or not.
func (h *Handlers) InsulinIntentEnd(w http.ResponseWriter, r *http.Request) {
	dev, _ := auth.DeviceFrom(r.Context())
	h.endIntent(dev.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) endIntent(id int64) {
	b := &h.intents
	b.mu.Lock()
	_, ok := b.m[id]
	delete(b.m, id)
	if t := b.timers[id]; t != nil {
		t.Stop()
		delete(b.timers, id)
	}
	b.mu.Unlock()
	if ok {
		h.publishIntents()
	}
}

func (h *Handlers) publishIntents() {
	now := h.Now()
	h.Bus.Publish(EventIntent, intentEvent{Intents: h.intents.list(now)}, now)
}
