package app

import (
	"net/http"
	"strings"

	"dialife/internal/auth"
	"dialife/internal/carbs"
	"dialife/internal/httpx"
)

type suggestRequest struct {
	Items []carbs.Item `json:"items"`
}

type suggestResponse struct {
	Models bool               `json:"models"` // estimates are on: a missing one means the models did not know or did not answer
	Items  []carbs.Suggestion `json:"items"`
}

// SuggestCarbs serves POST /api/carbs/suggest: for each food typed in the
// form, the family's own earlier number and the models' estimate. Only ever
// suggestions; the app shows them for a parent to pick or override.
func (h *Handlers) SuggestCarbs(w http.ResponseWriter, r *http.Request) {
	var req suggestRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	if len(req.Items) == 0 || len(req.Items) > carbs.MaxItems {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	for i, it := range req.Items {
		it.Name, it.Portion = clip(it.Name, 80), clip(it.Portion, 40)
		if it.Name == "" {
			httpx.Error(w, http.StatusBadRequest)
			return
		}
		req.Items[i] = it
	}
	dev, _ := auth.DeviceFrom(r.Context())
	out, err := h.Carbs.Suggest(r.Context(), dev.ID, req.Items)
	if err != nil {
		fail(w, "carbs suggest", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, suggestResponse{Models: h.Carbs.Enabled(), Items: out})
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}
