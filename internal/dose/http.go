package dose

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the dose settings' part of the API (reading them comes with the state).
type HTTP struct{ Service *Service }

// Routes adds PUT /api/dose; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("PUT /api/dose", caregiver(http.HandlerFunc(h.put)))
}

// put serves PUT /api/dose: a parent changed the calculation; every phone
// gets it with the next state.
func (h HTTP) put(w http.ResponseWriter, r *http.Request) {
	var d Settings
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	d, err := h.Service.Put(r.Context(), d, dev.Person)
	switch {
	case errors.Is(err, ErrRange):
		httpx.Error(w, http.StatusBadRequest)
	case err != nil:
		slog.Error("dose save", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		httpx.WriteJSON(w, http.StatusOK, d)
	}
}
