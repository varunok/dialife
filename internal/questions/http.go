package questions

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

// HTTP is the questions' part of the phones' API.
type HTTP struct{ Engine *Engine }

// Routes adds the questions' requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/questions/{id}/answer", caregiver(http.HandlerFunc(h.answer)))
	mux.Handle("POST /api/questions/{id}/dismiss", caregiver(http.HandlerFunc(h.dismiss)))
}

// answer serves POST /api/questions/{id}/answer {"answer", "treatment_id"}:
// "food" | "food_unknown" | "insulin" | "nothing", a rise also "asleep";
// treatment_id is the entry made for it.
func (h HTTP) answer(w http.ResponseWriter, r *http.Request) {
	id, ok := questionID(w, r)
	if !ok {
		return
	}
	var req struct {
		Answer      string `json:"answer"`
		TreatmentID string `json:"treatment_id,omitempty"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Engine.Answer(r.Context(), id, dev, req.Answer, req.TreatmentID)
	result(w, v, err)
}

// dismiss serves POST /api/questions/{id}/dismiss: clears a suspect mark.
func (h HTTP) dismiss(w http.ResponseWriter, r *http.Request) {
	id, ok := questionID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Engine.Dismiss(r.Context(), id, dev)
	result(w, v, err)
}

func result(w http.ResponseWriter, v View, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		httpx.Error(w, http.StatusNotFound)
	case errors.Is(err, ErrClosed):
		httpx.Error(w, http.StatusConflict)
	case errors.Is(err, ErrAnswer):
		httpx.Error(w, http.StatusBadRequest)
	case err != nil:
		slog.Error("question", "err", err)
		httpx.Error(w, http.StatusInternalServerError)
	default:
		httpx.WriteJSON(w, http.StatusOK, v)
	}
}

func questionID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.Error(w, http.StatusBadRequest)
		return 0, false
	}
	return id, true
}
