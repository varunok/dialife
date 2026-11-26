package alarms

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/store"
)

// HTTP is the alarms' part of the phones' API: the episode log and what a
// parent does with an episode, from the app or from a notification button.
type HTTP struct {
	Engine *Engine
	Store  *store.Store
	Links  *acklink.Signer // nil: notification buttons answer 404
	Now    func() time.Time
}

// Routes adds the alarms' requests: anyRole reads, caregiver acts; the
// signed notification link carries its own credential.
func (h HTTP) Routes(mux *http.ServeMux, anyRole, caregiver func(http.Handler) http.Handler) {
	mux.Handle("GET /api/alarms/log", anyRole(http.HandlerFunc(h.log)))
	mux.Handle("POST /api/alarms/{id}/handle", caregiver(http.HandlerFunc(h.handle)))
	mux.Handle("POST /api/alarms/{id}/ack", caregiver(http.HandlerFunc(h.handle))) // older name
	mux.Handle("POST /api/alarms/{id}/checkin", caregiver(http.HandlerFunc(h.checkin)))
	mux.Handle("POST /api/alarms/{id}/coming", caregiver(http.HandlerFunc(h.coming)))
	mux.Handle("POST /api/alarms/{id}/resolve", caregiver(http.HandlerFunc(h.resolve)))
	// no token: the signed link is the credential (see acklink)
	mux.Handle("POST /api/alarms/{id}/ack-link", http.HandlerFunc(h.ackLink))
}

type handleRequest struct {
	QuietMin  int `json:"quiet_min"`  // optional: "quiet for N min" (not for an urgent low)
	SnoozeMin int `json:"snooze_min"` // older name of quiet_min
}

type checkinRequest struct {
	Answer string `json:"answer"` // "ok" | "help"
}

type resolveResponse struct {
	Alarm   View   `json:"alarm"`
	Warning string `json:"warning,omitempty"`
}

// handle serves POST /api/alarms/{id}/handle (and the older /ack): "I'm on it".
func (h HTTP) handle(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	var req handleRequest
	if !decode(w, r, &req) {
		return
	}
	quiet := req.QuietMin
	if quiet == 0 {
		quiet = req.SnoozeMin
	}
	if quiet < 0 {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Engine.Handle(r.Context(), id, dev, time.Duration(quiet)*time.Minute)
	answer(w, v, err)
}

// checkin serves POST /api/alarms/{id}/checkin {"answer":"ok"|"help"}.
func (h HTTP) checkin(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	var req checkinRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	var (
		v   View
		err error
	)
	switch req.Answer {
	case acklink.ActOK:
		v, err = h.Engine.CheckinOK(r.Context(), id, dev)
	case acklink.ActHelp:
		v, err = h.Engine.Help(r.Context(), id, dev)
	default:
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	answer(w, v, err)
}

// coming serves POST /api/alarms/{id}/coming: "I'm coming" to a help request.
func (h HTTP) coming(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Engine.Coming(r.Context(), id, dev)
	answer(w, v, err)
}

// resolve serves POST /api/alarms/{id}/resolve: end an episode by hand.
func (h HTTP) resolve(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, stillLow, err := h.Engine.Resolve(r.Context(), id, dev)
	switch {
	case errors.Is(err, ErrNotActive):
		httpx.Error(w, http.StatusNotFound)
	case err != nil:
		fail(w, "resolve", err)
	default:
		resp := resolveResponse{Alarm: v}
		if stillLow {
			resp.Warning = "sensor still reads urgent low"
		}
		httpx.WriteJSON(w, http.StatusOK, resp)
	}
}

// ackLink serves POST /api/alarms/{id}/ack-link?d=&exp=&a=&s=&sig=, the
// target of notification buttons. It takes no token: the signature names one
// alarm, one caregiver and one action, and expires.
func (h HTTP) ackLink(w http.ResponseWriter, r *http.Request) {
	if h.Links == nil {
		httpx.Error(w, http.StatusNotFound)
		return
	}
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	devID, action, minutes, err := h.Links.Verify(id, r.URL.Query(), h.Now())
	if err != nil {
		slog.Warn("ack link rejected", "ip", httpx.ClientIP(r), "alarm", id)
		httpx.Error(w, http.StatusForbidden)
		return
	}
	dev, err := h.Store.ActiveDeviceByID(r.Context(), devID)
	if err != nil || auth.Role(dev.Role) != auth.RoleCaregiver {
		slog.Warn("ack link for unusable device", "device", devID, "err", err)
		httpx.Error(w, http.StatusForbidden)
		return
	}

	var v View
	switch action {
	case acklink.ActOK:
		v, err = h.Engine.CheckinOK(r.Context(), id, dev)
	case acklink.ActHelp:
		v, err = h.Engine.Help(r.Context(), id, dev)
	case acklink.ActComing:
		v, err = h.Engine.Coming(r.Context(), id, dev)
	case acklink.ActClose:
		v, _, err = h.Engine.Resolve(r.Context(), id, dev)
	default:
		v, err = h.Engine.Handle(r.Context(), id, dev, time.Duration(minutes)*time.Minute)
		if errors.Is(err, ErrQuietRefused) {
			v, err = h.Engine.Handle(r.Context(), id, dev, 0) // an old "quiet" button on an urgent low: still take it
		}
	}
	if errors.Is(err, ErrNotActive) {
		// already over: answer 2xx so the phone just dismisses the notification
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "not active"})
		return
	}
	answer(w, v, err)
}

func answer(w http.ResponseWriter, v View, err error) {
	switch {
	case errors.Is(err, ErrNotActive):
		httpx.Error(w, http.StatusNotFound)
	case errors.Is(err, ErrQuietRefused):
		httpx.WriteJSON(w, http.StatusConflict, map[string]any{"status": http.StatusConflict, "message": err.Error()})
	case err != nil:
		fail(w, "alarm action", err)
	default:
		httpx.WriteJSON(w, http.StatusOK, v)
	}
}

func alarmID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.Error(w, http.StatusBadRequest)
		return 0, false
	}
	return id, true
}

// decode accepts an empty body; false means it already answered 400.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return false
	}
	return true
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}
