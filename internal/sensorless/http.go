package sensorless

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the mode's part of the phones' API (its state comes with /api/state).
type HTTP struct{ Service *Service }

// Routes adds the mode's requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("PUT /api/sensorless", caregiver(http.HandlerFunc(h.put)))
	mux.Handle("PUT /api/sensorless/settings", caregiver(http.HandlerFunc(h.settings)))
	mux.Handle("POST /api/sensorless/checks/{t}/close", caregiver(http.HandlerFunc(h.closeCheck)))
}

// put serves PUT /api/sensorless {"on": true|false}: the mode for every phone.
// 409 while a sensor is sending: then there is no need for it.
func (h HTTP) put(w http.ResponseWriter, r *http.Request) {
	var req struct {
		On *bool `json:"on"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.On == nil {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	var err error
	if *req.On {
		err = h.Service.Start(r.Context(), dev)
	} else {
		err = h.Service.Stop(r.Context(), dev)
	}
	switch {
	case errors.Is(err, ErrSensorSending):
		httpx.WriteJSON(w, http.StatusConflict, map[string]any{"status": http.StatusConflict, "message": err.Error()})
	case err != nil:
		slog.Error("sensorless", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		httpx.WriteJSON(w, http.StatusOK, map[string]bool{"on": *req.On})
	}
}

// settings serves PUT /api/sensorless/settings {"remind_min": N}.
func (h HTTP) settings(w http.ResponseWriter, r *http.Request) {
	var st Settings
	if err := json.NewDecoder(r.Body).Decode(&st); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	st, err := h.Service.PutSettings(r.Context(), Settings{RemindMin: st.RemindMin}, dev)
	switch {
	case errors.Is(err, ErrRange):
		httpx.Error(w, http.StatusBadRequest)
	case err != nil:
		slog.Error("sensorless settings", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		httpx.WriteJSON(w, http.StatusOK, st)
	}
}

// closeCheck serves POST /api/sensorless/checks/{t}/close: the out-of-range
// card of the check taken at t is gone on every phone. Twice is fine.
func (h HTTP) closeCheck(w http.ResponseWriter, r *http.Request) {
	t, err := strconv.ParseInt(r.PathValue("t"), 10, 64)
	if err != nil || t <= 0 {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	switch err := h.Service.CloseCheck(r.Context(), t, dev); {
	case errors.Is(err, ErrNoCheck):
		httpx.Error(w, http.StatusNotFound)
	case err != nil:
		slog.Error("sensorless close card", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}
