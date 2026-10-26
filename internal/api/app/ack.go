package app

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/auth"
	"dialife/internal/httpx"
)

type handleRequest struct {
	QuietMin  int `json:"quiet_min"`  // optional: "quiet for N min" (not for an urgent low)
	SnoozeMin int `json:"snooze_min"` // older name of quiet_min
}

type checkinRequest struct {
	Answer string `json:"answer"` // "ok" | "help"
}

type resolveResponse struct {
	Alarm   alarms.View `json:"alarm"`
	Warning string      `json:"warning,omitempty"`
}

// Handle serves POST /api/alarms/{id}/handle (and the older /ack): "I'm on it".
func (h *Handlers) Handle(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	var req handleRequest
	if !decodeOptional(w, r, &req) {
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
	v, err := h.Alarms.Handle(r.Context(), id, dev, time.Duration(quiet)*time.Minute)
	h.answer(w, v, err)
}

// Checkin serves POST /api/alarms/{id}/checkin {"answer":"ok"|"help"}.
func (h *Handlers) Checkin(w http.ResponseWriter, r *http.Request) {
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
		v   alarms.View
		err error
	)
	switch req.Answer {
	case acklink.ActOK:
		v, err = h.Alarms.CheckinOK(r.Context(), id, dev)
	case acklink.ActHelp:
		v, err = h.Alarms.Help(r.Context(), id, dev)
	default:
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	h.answer(w, v, err)
}

// Coming serves POST /api/alarms/{id}/coming: "I'm coming" to a help request.
func (h *Handlers) Coming(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Alarms.Coming(r.Context(), id, dev)
	h.answer(w, v, err)
}

// Resolve serves POST /api/alarms/{id}/resolve: end an episode by hand.
func (h *Handlers) Resolve(w http.ResponseWriter, r *http.Request) {
	id, ok := alarmID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, stillLow, err := h.Alarms.Resolve(r.Context(), id, dev)
	switch {
	case errors.Is(err, alarms.ErrNotActive):
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

// AckLink serves POST /api/alarms/{id}/ack-link?d=&exp=&a=&s=&sig=, the
// target of notification buttons. It takes no token: the signature names one
// alarm, one caregiver and one action, and expires.
func (h *Handlers) AckLink(w http.ResponseWriter, r *http.Request) {
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

	var v alarms.View
	switch action {
	case acklink.ActOK:
		v, err = h.Alarms.CheckinOK(r.Context(), id, dev)
	case acklink.ActHelp:
		v, err = h.Alarms.Help(r.Context(), id, dev)
	case acklink.ActComing:
		v, err = h.Alarms.Coming(r.Context(), id, dev)
	default:
		v, err = h.Alarms.Handle(r.Context(), id, dev, time.Duration(minutes)*time.Minute)
		if errors.Is(err, alarms.ErrQuietRefused) {
			v, err = h.Alarms.Handle(r.Context(), id, dev, 0) // an old "quiet" button on an urgent low: still take it
		}
	}
	if errors.Is(err, alarms.ErrNotActive) {
		// already over: answer 2xx so the phone just dismisses the notification
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "not active"})
		return
	}
	h.answer(w, v, err)
}

func (h *Handlers) answer(w http.ResponseWriter, v alarms.View, err error) {
	switch {
	case errors.Is(err, alarms.ErrNotActive):
		httpx.Error(w, http.StatusNotFound)
	case errors.Is(err, alarms.ErrQuietRefused):
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

// decodeOptional accepts an empty body; false means it already answered 400.
func decodeOptional(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return false
	}
	return true
}
