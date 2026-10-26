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
	"dialife/internal/config"
	"dialife/internal/httpx"
	"dialife/internal/questions"
	"dialife/internal/store"
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
}

// App is the wired server: the HTTP handler plus the alarm engine, whose
// watchdog (Alarms.Run) the caller must start.
type App struct {
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
	api := &app.Handlers{Store: d.Store, Now: d.Now, Thresholds: config.DefaultThresholds, Bus: d.Bus, PingEvery: d.PingEvery, Links: d.Links, Loc: d.Loc, Uploader: d.Uploader}
	timing := d.Timing
	if timing == (alarms.Timing{}) {
		timing = alarms.DefaultTiming
	}
	api.Alarms = &alarms.Engine{Store: d.Store, Bus: d.Bus, Now: d.Now, Input: api.AlarmInput, Rules: alarms.DefaultRules, Timing: timing}
	api.Questions = &questions.Engine{Store: d.Store, Bus: d.Bus, Now: d.Now, Loc: d.Loc}
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
	mux.Handle("GET /api/stats", anyRole(http.HandlerFunc(api.Stats)))
	mux.Handle("GET /api/meter", anyRole(http.HandlerFunc(api.Meter)))
	mux.Handle("GET /api/alarms/log", anyRole(http.HandlerFunc(api.AlarmLog)))
	caregiver := d.Auth.Require(auth.RoleCaregiver)
	mux.Handle("POST /api/alarms/{id}/handle", caregiver(http.HandlerFunc(api.Handle)))
	mux.Handle("POST /api/alarms/{id}/ack", caregiver(http.HandlerFunc(api.Handle))) // older name
	mux.Handle("POST /api/alarms/{id}/checkin", caregiver(http.HandlerFunc(api.Checkin)))
	mux.Handle("POST /api/alarms/{id}/coming", caregiver(http.HandlerFunc(api.Coming)))
	mux.Handle("POST /api/alarms/{id}/resolve", caregiver(http.HandlerFunc(api.Resolve)))
	mux.Handle("POST /api/questions/{id}/answer", caregiver(http.HandlerFunc(api.AnswerQuestion)))
	mux.Handle("POST /api/questions/{id}/dismiss", caregiver(http.HandlerFunc(api.DismissQuestion)))
	// no token: the signed link is the credential (see acklink)
	mux.Handle("POST /api/alarms/{id}/ack-link", http.HandlerFunc(api.AckLink))
	mux.Handle("GET /api/healthz", anyRole(healthz(d.Store)))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		httpx.Error(w, http.StatusNotFound)
	})

	return &App{Handler: httpx.Recover(httpx.Log(httpx.Body(mux))), Alarms: api.Alarms, Questions: api.Questions}
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
