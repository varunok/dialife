package carbs

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/store"
)

// HTTP is the carb suggestions' part of the API: suggestions for a food,
// and the tables with the family's most logged foods.
type HTTP struct{ Service *Service }

// Routes adds the requests; caregiver lets only the parents' phones in.
func (h HTTP) Routes(mux *http.ServeMux, caregiver func(http.Handler) http.Handler) {
	mux.Handle("POST /api/carbs/suggest", caregiver(http.HandlerFunc(h.suggest)))
	mux.Handle("GET /api/foods", caregiver(http.HandlerFunc(h.foods)))
}

type suggestRequest struct {
	Items []Item `json:"items"`
}

type suggestResponse struct {
	Models bool         `json:"models"` // estimates are on: a missing one means the models did not know or did not answer
	Items  []Suggestion `json:"items"`
}

// suggest serves POST /api/carbs/suggest: for each food typed in the
// form, the family's own earlier number and the models' estimate. Only ever
// suggestions; the app shows them for a parent to pick or override.
func (h HTTP) suggest(w http.ResponseWriter, r *http.Request) {
	var req suggestRequest
	if !decode(w, r, &req) {
		return
	}
	if len(req.Items) == 0 || len(req.Items) > MaxItems {
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
	out, err := h.Service.Suggest(r.Context(), dev.ID, req.Items)
	if err != nil {
		fail(w, "carbs suggest", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, suggestResponse{Models: h.Service.Enabled(), Items: out})
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

type foodsResponse struct {
	Tables   []foodTable `json:"tables"`
	Frequent []Frequent  `json:"frequent"`
}

type foodTable struct {
	Source     string         `json:"source"`
	Title      string         `json:"title"`
	Note       store.Langs    `json:"note,omitempty"`
	Categories []foodCategory `json:"categories"`
	Items      []tableFood    `json:"items"`
}

type foodCategory struct {
	ID    string      `json:"id"`
	Names store.Langs `json:"names"`
}

type tableFood struct {
	ID       string      `json:"id"`
	Cat      string      `json:"cat"`
	Carbs    int         `json:"carbs"`
	Names    store.Langs `json:"names"`
	Portions store.Langs `json:"portions"`
}

// foods serves GET /api/foods: the carbohydrate tables to pick a food from,
// and the foods the family logs most, for the form's quick buttons.
func (h HTTP) foods(w http.ResponseWriter, r *http.Request) {
	c, err := h.Service.Catalog(r.Context())
	if err != nil {
		fail(w, "foods", err)
		return
	}
	out := foodsResponse{Tables: []foodTable{}, Frequent: c.Frequent}
	if out.Frequent == nil {
		out.Frequent = []Frequent{}
	}
	for _, t := range c.Tables {
		ft := foodTable{Source: t.Source, Title: t.Title, Note: t.Note, Categories: []foodCategory{}, Items: []tableFood{}}
		for _, cat := range t.Categories {
			ft.Categories = append(ft.Categories, foodCategory{ID: cat.ID, Names: cat.Names})
		}
		for _, f := range t.Items {
			ft.Items = append(ft.Items, tableFood{ID: f.ID, Cat: f.Cat, Carbs: f.Carbs, Names: f.Names, Portions: f.Portions})
		}
		out.Tables = append(out.Tables, ft)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}

// decode accepts an empty body; false means it already answered 400.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil && !errors.Is(err, io.EOF) {
		httpx.Error(w, http.StatusBadRequest)
		return false
	}
	return true
}
