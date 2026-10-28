package app

import (
	"net/http"
	"strings"

	"dialife/internal/auth"
	"dialife/internal/carbs"
	"dialife/internal/httpx"
	"dialife/internal/store"
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

type foodsResponse struct {
	Tables   []foodTable      `json:"tables"`
	Frequent []carbs.Frequent `json:"frequent"`
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

// Foods serves GET /api/foods: the carbohydrate tables to pick a food from,
// and the foods the family logs most, for the form's quick buttons.
func (h *Handlers) Foods(w http.ResponseWriter, r *http.Request) {
	c, err := h.Carbs.Catalog(r.Context())
	if err != nil {
		fail(w, "foods", err)
		return
	}
	out := foodsResponse{Tables: []foodTable{}, Frequent: c.Frequent}
	if out.Frequent == nil {
		out.Frequent = []carbs.Frequent{}
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
