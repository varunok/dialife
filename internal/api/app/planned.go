package app

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/questions"
	"dialife/internal/store"
)

// plannedDose is a planned dose as the phones see it.
type plannedDose struct {
	ID        string  `json:"id"`
	MealID    string  `json:"meal_id"`
	Dose      float64 `json:"dose"`
	Food      float64 `json:"food"`
	Corr      float64 `json:"corr"`
	Carbs     int     `json:"carbs"`
	Meter     *int    `json:"meter"`
	MeterAt   *int64  `json:"meter_at"`
	CreatedAt int64   `json:"created_at"`
	By        string  `json:"by"`
	DeviceID  int64   `json:"device_id"`
	State     string  `json:"state"`
}

// planned: the doses still waiting for "given" or "skipped", oldest first.
func (h *Handlers) planned(ctx context.Context, devs []store.Device) ([]plannedDose, error) {
	ps, err := h.Store.PlannedDoses(ctx)
	if err != nil {
		return nil, err
	}
	expireBefore := h.Now().Add(-questions.PlannedExpire).UnixMilli()
	out := []plannedDose{}
	for _, p := range ps {
		if p.CreatedAt < expireBefore {
			continue // past its three hours: the questions engine closes it within a minute
		}
		by := ""
		for _, d := range devs {
			if d.ID == p.DeviceID {
				by = d.Person
			}
		}
		out = append(out, plannedDose{ID: p.ID, MealID: p.MealID, Dose: p.Dose, Food: p.Food, Corr: p.Corr, Carbs: p.Carbs,
			Meter: p.Meter, MeterAt: p.MeterAt, CreatedAt: p.CreatedAt, By: by, DeviceID: p.DeviceID, State: p.State})
	}
	return out, nil
}

type plannedRequest struct {
	ID        string  `json:"id"`
	MealID    string  `json:"meal_id"`
	Dose      float64 `json:"dose"`
	Food      float64 `json:"food"`
	Corr      float64 `json:"corr"`
	Carbs     int     `json:"carbs"`
	Meter     *int    `json:"meter"`
	MeterAt   *int64  `json:"meter_at"`
	CreatedAt int64   `json:"created_at"`
}

// SavePlanned serves POST /api/planned: a dose worked out for a meal, not
// given yet. The same id changes it while it is still planned (409 after).
func (h *Handlers) SavePlanned(w http.ResponseWriter, r *http.Request) {
	var req plannedRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	now := h.Now()
	req.ID, req.MealID = strings.TrimSpace(req.ID), strings.TrimSpace(req.MealID)
	if req.ID == "" || len(req.ID) > 64 || req.MealID == "" || len(req.MealID) > 64 ||
		req.Dose < 0 || req.Dose > 100 || req.Carbs < 0 || req.Carbs > 1000 ||
		req.CreatedAt <= 0 || req.CreatedAt > now.Add(entryAhead).UnixMilli() {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	dev, _ := auth.DeviceFrom(r.Context())
	err := h.Store.SavePlannedDose(r.Context(), store.PlannedDose{ID: req.ID, MealID: req.MealID, Dose: req.Dose, Food: req.Food, Corr: req.Corr,
		Carbs: req.Carbs, Meter: req.Meter, MeterAt: req.MeterAt, CreatedAt: req.CreatedAt, DeviceID: dev.ID}, now.UnixMilli())
	if errors.Is(err, store.ErrClosedDose) {
		httpx.Error(w, http.StatusConflict)
		return
	}
	if err != nil {
		fail(w, "planned save", err)
		return
	}
	slog.Info("planned dose", "id", req.ID, "dose", req.Dose, "by", dev.Person)
	h.PublishState(r.Context())
	w.WriteHeader(http.StatusNoContent)
}

type givenRequest struct {
	InsulinID string `json:"insulin_id"`
}

// PlannedGiven serves POST /api/planned/{id}/given: it was injected (the
// insulin entry itself comes as a treatment, as always).
func (h *Handlers) PlannedGiven(w http.ResponseWriter, r *http.Request) {
	var req givenRequest
	if !decodeOptional(w, r, &req) {
		return
	}
	h.closePlanned(w, r, "given", strings.TrimSpace(req.InsulinID))
}

// PlannedSkip serves POST /api/planned/{id}/skip: it was not injected.
func (h *Handlers) PlannedSkip(w http.ResponseWriter, r *http.Request) {
	h.closePlanned(w, r, "skipped", "")
}

// closePlanned: an already closed dose answers 200 as it is (two phones may
// confirm at once); an unknown one 404.
func (h *Handlers) closePlanned(w http.ResponseWriter, r *http.Request, state, insulinID string) {
	ctx := r.Context()
	id := r.PathValue("id")
	dev, _ := auth.DeviceFrom(ctx)
	if _, err := h.Store.PlannedDose(ctx, id); errors.Is(err, store.ErrNotFound) {
		httpx.Error(w, http.StatusNotFound)
		return
	} else if err != nil {
		fail(w, "planned", err)
		return
	}
	closed, err := h.Store.ClosePlannedDose(ctx, id, state, insulinID, &dev.ID, h.Now().UnixMilli())
	if err != nil {
		fail(w, "planned close", err)
		return
	}
	if closed {
		slog.Info("planned dose closed", "id", id, "state", state, "by", dev.Person)
		if h.Questions != nil {
			if err := h.Questions.SettlePlanned(ctx, id); err != nil {
				slog.Error("planned question", "err", err)
			}
		}
		h.PublishState(ctx)
	}
	p, err := h.Store.PlannedDose(ctx, id)
	if err != nil {
		fail(w, "planned", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, plannedDose{ID: p.ID, MealID: p.MealID, Dose: p.Dose, Food: p.Food, Corr: p.Corr, Carbs: p.Carbs,
		Meter: p.Meter, MeterAt: p.MeterAt, CreatedAt: p.CreatedAt, DeviceID: p.DeviceID, State: p.State})
}
