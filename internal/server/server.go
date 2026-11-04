// Package server wires routes and middleware into one http.Handler.
package server

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/api/app"
	"dialife/internal/api/nsv1"
	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/carbs"
	"dialife/internal/config"
	dosecalc "dialife/internal/dose" // "dose" is a test helper here
	"dialife/internal/forecast"
	"dialife/internal/httpx"
	"dialife/internal/intent"
	"dialife/internal/meter"
	"dialife/internal/planned"
	"dialife/internal/questions"
	"dialife/internal/stats"
	"dialife/internal/store"
	"dialife/internal/trust"
	"dialife/internal/wait"
	"dialife/internal/web"
	"dialife/internal/weblogin"
)

type Deps struct {
	Store     *store.Store
	Auth      *auth.Authenticator
	Bus       *bus.Bus
	Version   string
	Now       func() time.Time
	PingEvery time.Duration
	Timing    alarms.Timing   // zero value: alarms.DefaultTiming
	Links     *acklink.Signer // nil disables notification ack links
	Loc       *time.Location  // the family's time zone (stats day); nil: UTC
	Uploader  string          // what the family calls the xDrip+ phone (DIALIFE_UPLOADER_LABEL)
	Carbs     *carbs.Service  // nil: the family's own numbers only, no models
}

// App is the wired server: the HTTP handler plus the alarm engine, whose
// watchdog (Alarms.Run) the caller must start.
type App struct {
	api       *app.Handlers
	webLogin  *weblogin.HTTP
	Handler   http.Handler
	Alarms    *alarms.Engine
	Questions *questions.Engine // its Run (timeouts, reminders) the caller starts too
}

func New(d Deps) *App {
	var (
		anyRole   = d.Auth.Require()
		upload    = d.Auth.Require(auth.RoleUploader)
		careWrite = d.Auth.Require(auth.RoleUploader, auth.RoleCaregiver)
	)
	api := &app.Handlers{Store: d.Store, Now: d.Now, Thresholds: config.DefaultThresholds, Bus: d.Bus, PingEvery: d.PingEvery, Links: d.Links, Loc: d.Loc, Uploader: d.Uploader, Carbs: d.Carbs}
	if api.Carbs == nil {
		api.Carbs = &carbs.Service{Store: d.Store, Now: d.Now}
	}
	timing := d.Timing
	if timing == (alarms.Timing{}) {
		timing = alarms.DefaultTiming
	}
	api.Alarms = &alarms.Engine{Store: d.Store, Bus: d.Bus, Now: d.Now, Input: api.AlarmInput, Rules: alarms.DefaultRules, Timing: timing}
	api.Questions = &questions.Engine{Store: d.Store, Bus: d.Bus, Now: d.Now, Loc: d.Loc, Uploader: d.Uploader}
	api.Questions.OnPlanned = func() { api.PublishState(context.Background()) }
	api.Wait = &wait.Service{Store: d.Store, Now: d.Now, OnChange: api.PublishState}
	api.Forecast = &forecast.Service{Store: d.Store}
	api.Trust = &trust.Service{Store: d.Store}
	api.Intents = &intent.Board{Bus: d.Bus, Now: d.Now}
	api.Dose = &dosecalc.Service{Store: d.Store, Now: d.Now, OnChange: api.PublishState}
	api.Planned = &planned.Service{Store: d.Store, Now: d.Now, OnChange: api.PublishState, Settle: api.Questions.SettlePlanned}
	status := nsv1.Status(d.Version, func(ctx context.Context) config.Thresholds { return api.Alarms.CurrentRules(ctx).Thresholds }, d.Now)
	ns := &nsv1.Handlers{Store: d.Store, Now: d.Now, Events: api}

	mux := http.NewServeMux()
	// Nightscout clients use both "/x" and "/x.json"
	both := func(method, path string, h http.Handler) {
		mux.Handle(method+" "+path, h)
		mux.Handle(method+" "+path+".json", h)
	}

	both("GET", "/api/v1/status", anyRole(status))
	both("POST", "/api/v1/entries", upload(http.HandlerFunc(ns.PostEntries)))
	both("POST", "/api/v1/devicestatus", upload(http.HandlerFunc(ns.PostDeviceStatus)))
	both("POST", "/api/v1/activity", upload(http.HandlerFunc(ns.PostActivity)))
	both("GET", "/api/v1/entries", anyRole(http.HandlerFunc(ns.GetEntries)))
	mux.Handle("GET /api/v1/entries/{spec}", anyRole(http.HandlerFunc(ns.GetEntriesSpec)))
	both("GET", "/api/v1/treatments", anyRole(http.HandlerFunc(ns.GetTreatments)))
	both("POST", "/api/v1/treatments", careWrite(http.HandlerFunc(ns.UpsertTreatments)))
	both("PUT", "/api/v1/treatments", careWrite(http.HandlerFunc(ns.UpsertTreatments)))
	mux.Handle("DELETE /api/v1/treatments/{id}", careWrite(http.HandlerFunc(ns.DeleteTreatment)))

	mux.Handle("GET /api/me", anyRole(http.HandlerFunc(api.Me)))
	mux.Handle("GET /api/state", anyRole(http.HandlerFunc(api.State)))
	mux.Handle("GET /api/history", anyRole(http.HandlerFunc(api.History)))
	mux.Handle("GET /api/stream", anyRole(http.HandlerFunc(api.Stream)))
	caregiver := d.Auth.Require(auth.RoleCaregiver)
	// the web viewer: logging in by a QR code a parent scans, then read-only like a reader
	planned.HTTP{Service: api.Planned}.Routes(mux, caregiver)
	wait.HTTP{Service: api.Wait}.Routes(mux, caregiver)
	questions.HTTP{Engine: api.Questions}.Routes(mux, caregiver)
	carbs.HTTP{Service: api.Carbs}.Routes(mux, caregiver)
	intent.HTTP{Board: api.Intents}.Routes(mux, caregiver)
	dosecalc.HTTP{Service: api.Dose}.Routes(mux, caregiver)
	webLogin := &weblogin.HTTP{Store: d.Store, Bus: d.Bus, Now: d.Now}
	webLogin.Routes(mux, anyRole, caregiver)
	stats.HTTP{Store: d.Store, Now: d.Now, Loc: d.Loc, Thresholds: api.CurrentThresholds}.Routes(mux, anyRole)
	meter.HTTP{Store: d.Store, Now: d.Now}.Routes(mux, anyRole)
	alarms.HTTP{Engine: api.Alarms, Store: d.Store, Links: d.Links, Now: d.Now}.Routes(mux, anyRole, caregiver)
	mux.Handle("GET /api/healthz", anyRole(healthz(d.Store)))
	// the read-only web viewer
	viewer := web.Handler()
	mux.Handle("GET /web/", viewer)
	mux.Handle("GET /{$}", viewer)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotFound)
	})

	return &App{Handler: httpx.Recover(httpx.Log(httpx.Body(mux))), Alarms: api.Alarms, Questions: api.Questions, api: api, webLogin: webLogin}
}

func healthz(s *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := s.Ping(r.Context()); err != nil {
			slog.Error("healthz db", "err", err)
			httpx.WriteJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "error", "db": "down"})
			return
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok", "db": "ok"})
	}
}
