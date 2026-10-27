package app

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/httpx"
	"dialife/internal/questions"
)

const retryMs = 5000

// Stream serves GET /api/stream as Server-Sent Events.
//
// On connect the client always gets a "state" snapshot, so a reconnect needs
// no special handling. With Last-Event-ID it also learns what changed while
// it was away: a "history" range to refetch and a "treatment" change marker.
func (h *Handlers) Stream(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	dev, _ := auth.DeviceFrom(ctx)

	// subscribe before the snapshot so nothing published in between is lost
	events, cancel, err := h.Bus.Subscribe()
	if err != nil {
		if errors.Is(err, bus.ErrFull) {
			slog.Warn("sse refused", "device", dev.Name, "err", err)
		}
		httpx.Error(w, http.StatusServiceUnavailable)
		return
	}
	defer cancel()

	rc := http.NewResponseController(w)
	hdr := w.Header()
	hdr.Set("Content-Type", "text/event-stream")
	hdr.Set("Cache-Control", "no-cache")
	hdr.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	now := h.Now()
	snap, err := h.snapshot(ctx, now)
	if err != nil {
		slog.Error("sse snapshot", "err", err)
		return
	}
	snap.DisplayUnits = dev.DisplayUnits
	web := dev.WebSessionID != 0 // the web viewer gets no questions, in any form
	if web {
		snap.Questions = nil
	}

	fmt.Fprintf(w, "retry: %d\n\n", retryMs)
	if !h.send(w, bus.Event{ID: h.Bus.NextID(now), Type: EventState, Data: mustJSON(snap)}) {
		return
	}
	if !h.replay(w, r, lastEventID(r)) {
		return
	}
	if rc.Flush() != nil {
		return
	}
	slog.Info("sse open", "device", dev.Name, "subscribers", h.Bus.Subscribers())

	ping := time.NewTicker(h.PingEvery)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case ev, ok := <-events:
			if !ok { // too slow or server shutting down: client reconnects and resyncs
				return
			}
			if web {
				if ev.Type == questions.EventQuestion {
					continue
				}
				if ev.Type == EventBG || ev.Type == EventState {
					ev.Data = withoutQuestions(ev.Data)
				}
			}
			if !h.send(w, ev) || rc.Flush() != nil {
				return
			}
		case <-ping.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil || rc.Flush() != nil {
				return
			}
		}
	}
}

func (h *Handlers) replay(w http.ResponseWriter, r *http.Request, since int64) bool {
	if since <= 0 {
		return true
	}
	ctx := r.Context()
	now := h.Now()
	from, to, ok, err := h.Store.EntriesReceivedSince(ctx, since)
	if err != nil {
		slog.Error("sse replay entries", "err", err)
		return false
	}
	if ok && !h.send(w, bus.Event{ID: h.Bus.NextID(now), Type: EventHistory, Data: mustJSON(historyEvent{From: from, To: to})}) {
		return false
	}
	lastMod, err := h.Store.TreatmentsLastModified(ctx)
	if err != nil {
		slog.Error("sse replay treatments", "err", err)
		return false
	}
	if lastMod > since {
		return h.send(w, bus.Event{ID: h.Bus.NextID(now), Type: EventTreatment, Data: mustJSON(treatmentEvent{Op: "changed"})})
	}
	return true
}

func (h *Handlers) send(w http.ResponseWriter, ev bus.Event) bool {
	_, err := fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", ev.ID, ev.Type, ev.Data)
	return err == nil
}

func lastEventID(r *http.Request) int64 {
	s := strings.TrimSpace(r.Header.Get("Last-Event-ID"))
	if s == "" {
		s = r.URL.Query().Get("lastEventId") // for clients that cannot set headers on reconnect
	}
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil || id < 0 {
		return 0
	}
	return id
}

// withoutQuestions drops the questions from a shared snapshot event.
func withoutQuestions(data []byte) []byte {
	var m map[string]json.RawMessage
	if json.Unmarshal(data, &m) != nil {
		return data
	}
	delete(m, "questions")
	out, err := json.Marshal(m)
	if err != nil {
		return data
	}
	return out
}
