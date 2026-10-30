package planned

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

// HTTP is the planned doses' part of the phones' API.
type HTTP struct{ Service *Service }

// Routes adds the planned doses' requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/planned", caregiver(http.HandlerFunc(h.save)))
	mux.Handle("POST /api/planned/{id}/given", caregiver(http.HandlerFunc(h.given)))
	mux.Handle("POST /api/planned/{id}/skip", caregiver(http.HandlerFunc(h.skip)))
}

// save serves POST /api/planned: a dose worked out for a meal, not given yet.
// The same id changes it while it is still planned (409 after).
func (h HTTP) save(w http.ResponseWriter, r *http.Request) {
	var d Dose
	if !decode(w, r, &d) {
		return
	}
	if !d.Normalize(h.Service.Now()) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	err := h.Service.Save(r.Context(), d, dev.ID)
	if errors.Is(err, ErrClosed) {
		httpx.Error(w, http.StatusConflict)
		return
	}
	if err != nil {
		fail(w, "planned save", err)
		return
	}
	slog.Info("planned dose", "id", d.ID, "dose", d.Dose, "by", dev.Person)
	w.WriteHeader(http.StatusNoContent)
}

// given serves POST /api/planned/{id}/given {"insulin_id"}: it was injected
// (the insulin entry itself comes as a treatment, as always).
func (h HTTP) given(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InsulinID string `json:"insulin_id"`
	}
	if !decode(w, r, &req) {
		return
	}
	h.close(w, r, "given", strings.TrimSpace(req.InsulinID))
}

// skip serves POST /api/planned/{id}/skip: it was not injected.
func (h HTTP) skip(w http.ResponseWriter, r *http.Request) {
	h.close(w, r, "skipped", "")
}

func (h HTTP) close(w http.ResponseWriter, r *http.Request, state, insulinID string) {
	id := r.PathValue("id")
	dev, _ := auth.DeviceFrom(r.Context())
	d, closed, err := h.Service.Close(r.Context(), id, state, insulinID, dev.ID)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound)
		return
	}
	if err != nil {
		fail(w, "planned close", err)
		return
	}
	if closed {
		slog.Info("planned dose closed", "id", id, "state", state, "by", dev.Person)
	}
	httpx.WriteJSON(w, http.StatusOK, d)
}

// decode reads an optional JSON body; on failure it has already answered.
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
