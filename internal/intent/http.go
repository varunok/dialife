package intent

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// HTTP is the insulin form's "open / closed" part of the API.
type HTTP struct{ Board *Board }

// Routes adds the requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/insulin/intent", caregiver(http.HandlerFunc(h.open)))
	mux.Handle("DELETE /api/insulin/intent", caregiver(http.HandlerFunc(h.end)))
}

// open serves POST /api/insulin/intent {"insulin": "rapid" | "basal"}: the form is open.
func (h HTTP) open(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Insulin string `json:"insulin"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	h.Board.Open(dev, req.Insulin)
	w.WriteHeader(http.StatusNoContent)
}

// end serves DELETE /api/insulin/intent: the form closed, saved or not.
func (h HTTP) end(w http.ResponseWriter, r *http.Request) {
	dev, _ := auth.DeviceFrom(r.Context())
	h.Board.End(dev.ID)
	w.WriteHeader(http.StatusNoContent)
}
