package wait

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the wait's part of the phones' API.
type HTTP struct{ Service *Service }

// Routes adds the wait's requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/wait/{dose_id}/close", caregiver(http.HandlerFunc(h.close)))
}

// close serves POST /api/wait/{dose_id}/close {"kind": "wait" | "more"}: gone
// on every phone. Closing it twice (two phones at once) is fine.
func (h HTTP) close(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Kind string `json:"kind"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	id := strings.TrimSpace(r.PathValue("dose_id"))
	if id == "" || len(id) > 64 || (req.Kind != "wait" && req.Kind != "more") {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	closed, err := h.Service.Close(r.Context(), id, req.Kind, dev.ID)
	if err != nil {
		slog.Error("wait close", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
		return
	}
	if closed {
		slog.Info("wait closed", "dose", id, "kind", req.Kind, "by", dev.Person)
	}
	w.WriteHeader(http.StatusNoContent)
}
