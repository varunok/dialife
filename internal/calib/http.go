package calib

import (
	"errors"
	"log/slog"
	"net/http"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the advice's part of the phones' API (it comes with /api/state).
type HTTP struct{ Service *Service }

// Routes adds POST /api/calibration/close for the parents' phones.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/calibration/close", caregiver(http.HandlerFunc(h.close)))
}

// close serves POST /api/calibration/close: the advice is gone on every phone
// until a newer check is off again.
func (h HTTP) close(w http.ResponseWriter, r *http.Request) {
	dev, _ := auth.DeviceFrom(r.Context())
	switch err := h.Service.Close(r.Context(), dev); {
	case errors.Is(err, ErrNothing):
		httpx.Error(w, http.StatusNotFound)
	case err != nil:
		slog.Error("calibration close", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}
