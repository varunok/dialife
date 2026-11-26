package meter

import (
	"log/slog"
	"net/http"
	"time"

	"dialife/internal/httpx"
	"dialife/internal/store"
)

// all of it: the comparison gets better with every check, old sensors included
const meterRows = 100000

// HTTP is the glucometer comparison's part of the API.
type HTTP struct {
	Store *store.Store
	Now   func() time.Time
}

// Routes adds GET /api/meter for every role.
func (h HTTP) Routes(mux *http.ServeMux, anyRole func(http.Handler) http.Handler) {
	mux.Handle("GET /api/meter", anyRole(http.HandlerFunc(h.get)))
}

// get serves GET /api/meter: sensor vs glucometer over all checks ever made,
// and, with enough of them, the model the app draws "≈ meter" with.
func (h HTTP) get(w http.ResponseWriter, r *http.Request) {
	d, err := Load(r.Context(), h.Store)
	if err != nil {
		fail(w, "meter", err)
		return
	}
	lag := MeasureLag(d.Checks, d.SGV, d.Bounds())
	res := Fit(Pairs(d.Checks, d.SGV, d.Starts, lag.Duration()), d.Starts, d.Cals, h.Now())
	res.Lag = lag
	httpx.WriteJSON(w, http.StatusOK, res)
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}
