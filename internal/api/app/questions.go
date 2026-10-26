package app

import (
	"errors"
	"net/http"
	"strconv"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/questions"
)

type answerRequest struct {
	Answer      string `json:"answer"`                 // "food" | "food_unknown" | "insulin" | "nothing"
	TreatmentID string `json:"treatment_id,omitempty"` // the entry made for it
}

// AnswerQuestion serves POST /api/questions/{id}/answer.
func (h *Handlers) AnswerQuestion(w http.ResponseWriter, r *http.Request) {
	id, ok := questionID(w, r)
	if !ok {
		return
	}
	var req answerRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Questions.Answer(r.Context(), id, dev, req.Answer, req.TreatmentID)
	h.questionResult(w, v, err)
}

// DismissQuestion serves POST /api/questions/{id}/dismiss: clears a suspect mark.
func (h *Handlers) DismissQuestion(w http.ResponseWriter, r *http.Request) {
	id, ok := questionID(w, r)
	if !ok {
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	v, err := h.Questions.Dismiss(r.Context(), id, dev)
	h.questionResult(w, v, err)
}

func (h *Handlers) questionResult(w http.ResponseWriter, v questions.View, err error) {
	switch {
	case errors.Is(err, questions.ErrNotFound):
		httpx.Error(w, http.StatusNotFound)
	case errors.Is(err, questions.ErrClosed):
		httpx.Error(w, http.StatusConflict)
	case errors.Is(err, questions.ErrAnswer):
		httpx.Error(w, http.StatusBadRequest)
	case err != nil:
		fail(w, "question", err)
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
