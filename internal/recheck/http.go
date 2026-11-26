package recheck

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the recheck's part of the phones' API (its state comes with /api/state).
type HTTP struct{ Service *Service }

// Routes adds POST /api/recheck/{id}/close for the parents' phones.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/recheck/{id}/close", caregiver(http.HandlerFunc(h.close)))
}

// close serves POST /api/recheck/{id}/close: the card is gone on every phone.
func (h HTTP) close(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	switch err := h.Service.Close(r.Context(), id, dev); {
	case errors.Is(err, ErrNotFound):
		httpx.Error(w, http.StatusNotFound)
	case err != nil:
		slog.Error("recheck close", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}
