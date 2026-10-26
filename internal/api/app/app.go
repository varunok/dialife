// Package app serves DiaLife's own API for the Android app: the current state
// in one request and compact history for charts. All BG values are mg/dL;
// display_units tells the client whether to show mmol/L instead.
package app

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"strings"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/config"
	"dialife/internal/glucose"
	"dialife/internal/httpx"
	"dialife/internal/insulin"
	"dialife/internal/meter"
	"dialife/internal/nstime"
	"dialife/internal/questions"
	"dialife/internal/store"
)

const (
	// phones run a little ahead sometimes; readings this far in the future still count
	clockSkew = 5 * time.Minute
	// the 4 buckets span 20 minutes back from the newest reading
	stateWindow = 25 * time.Minute
	// an AR2 forecast from older data would be misleading
	forecastMaxAge = 15 * time.Minute
	// readings handed to the alarm rules: the 240 min max high_for_min plus slack
	alarmHistory = 250 * time.Minute

	defaultHistory = 6 * time.Hour
	maxHistory     = 31 * 24 * time.Hour
	maxHistoryRows = 20000 // 31 days at 5 min is ~9000; leaves room for a second uploader
)

type Handlers struct {
	Store      *store.Store
	Now        func() time.Time
	Thresholds config.Thresholds // fallback when no alarm engine is wired
	Bus        *bus.Bus
	PingEvery  time.Duration // SSE keep-alive; mobile NATs drop idle connections
	Alarms     *alarms.Engine
	Questions  *questions.Engine // "did you forget to log?"; nil: off
	Links      *acklink.Signer   // nil: ack links disabled
	Loc        *time.Location    // the family's time zone, for the AGP day; nil: UTC
	Uploader   string            // what the family calls the xDrip+ phone; sent to the app as is
}

type stateResponse struct {
	Now          int64               `json:"now"`
	DisplayUnits string              `json:"display_units,omitempty"` // absent in SSE bg events
	Thresholds   config.Thresholds   `json:"thresholds"`
	BG           *bgState            `json:"bg"`
	AR2          *glucose.Forecast   `json:"ar2"`
	Uploader     *uploaderState      `json:"uploader"`
	Alarms       []alarms.View       `json:"alarms"`                  // live alarms, oldest first
	Episode      *alarms.EpisodeView `json:"episode,omitempty"`       // rules behind "we wake everyone if…"
	Caregivers   []caregiver         `json:"caregivers"`              // who an alarm wakes, for "Notified: Dad, Mom"
	Questions    []questions.View    `json:"questions"`               // open ones and the last day's suspect marks
	UploaderName string              `json:"uploader_name,omitempty"` // what the family calls the xDrip+ phone
}

type caregiver struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type bgState struct {
	SGV          int      `json:"sgv"`   // newest reading; a value <= 39 is a sensor error code
	Mills        int64    `json:"mills"` // time of that reading
	AgeSec       int64    `json:"age_sec"`
	Error        bool     `json:"error"`
	Direction    string   `json:"direction,omitempty"`
	Arrow        string   `json:"arrow,omitempty"`
	Delta        *int     `json:"delta"` // mg/dL per 5 min, rounded
	DeltaExact   *float64 `json:"delta_exact"`
	Interpolated bool     `json:"interpolated"`
	Noise        *int     `json:"noise,omitempty"`
	Device       string   `json:"device,omitempty"`
}

type uploaderState struct {
	Battery *int   `json:"battery"`
	Mills   int64  `json:"mills"`
	Device  string `json:"device,omitempty"`
}

func (h *Handlers) State(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	dev, _ := auth.DeviceFrom(ctx)
	resp, err := h.snapshot(ctx, h.Now())
	if err != nil {
		fail(w, "state", err)
		return
	}
	resp.DisplayUnits = dev.DisplayUnits
	httpx.WriteJSON(w, http.StatusOK, resp)
}

// thresholds are the live household values (settings table), else defaults.
func (h *Handlers) thresholds(ctx context.Context) config.Thresholds {
	if h.Alarms == nil {
		return h.Thresholds
	}
	return h.Alarms.CurrentRules(ctx).Thresholds
}

// snapshot is the device-independent state; callers add display_units.
func (h *Handlers) snapshot(ctx context.Context, now time.Time) (stateResponse, error) {
	th := h.thresholds(ctx)
	resp := stateResponse{Now: now.UnixMilli(), Thresholds: th, UploaderName: h.Uploader}

	st, ok, err := h.computeState(ctx, now)
	if err != nil {
		return resp, err
	}
	if ok {
		resp.BG, resp.AR2 = h.describe(st, now, th)
	}

	ds, err := h.Store.LatestDeviceStatus(ctx)
	switch {
	case err == nil:
		resp.Uploader = uploaderFrom(ds)
	case !errors.Is(err, store.ErrNotFound):
		return resp, err
	}

	resp.Alarms = []alarms.View{}
	if h.Alarms != nil {
		if resp.Alarms, err = h.Alarms.Live(ctx); err != nil {
			return resp, err
		}
		ep := h.Alarms.EpisodeRules(ctx)
		resp.Episode = &ep
	}
	devs, err := h.Store.ListDevices(ctx)
	if err != nil {
		return resp, err
	}
	resp.Questions = []questions.View{}
	if h.Questions != nil {
		if resp.Questions, err = h.Questions.Recent(ctx); err != nil {
			return resp, err
		}
	}
	resp.Caregivers = []caregiver{}
	for _, d := range devs {
		if d.RevokedAt == nil && d.Role == "caregiver" {
			resp.Caregivers = append(resp.Caregivers, caregiver{ID: d.ID, Name: d.Name})
		}
	}
	return resp, nil
}

// AlarmInput feeds the alarm rules: the newest valid reading (error codes
// skipped, so a failing sensor ages into "stale") and the AR2 forecast.
func (h *Handlers) AlarmInput(ctx context.Context, now time.Time) (alarms.Input, error) {
	in := alarms.Input{Now: now.UnixMilli()}
	last, err := h.Store.LatestValidSGV(ctx, glucose.MaxErrorCode)
	if errors.Is(err, store.ErrNotFound) {
		return in, nil
	}
	if err != nil {
		return in, err
	}
	in.HasData, in.LastMills, in.SGV = true, last.Date, *last.SGV

	// enough history for the longest allowed "sustained high" window
	from := last.Date - (alarmHistory).Milliseconds()
	recent, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &last.Date, Count: 500})
	if err != nil {
		return in, err
	}
	for _, e := range recent {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			in.Recent = append(in.Recent, alarms.Reading{Date: e.Date, SGV: *e.SGV})
		}
	}

	st, ok, err := h.computeState(ctx, now)
	if err != nil {
		return in, err
	}
	if ok {
		_, in.Forecast = h.describe(st, now, h.thresholds(ctx))
	}
	return in, nil
}

func uploaderFrom(ds store.DeviceStatus) *uploaderState {
	return &uploaderState{Battery: ds.UploaderBattery, Mills: ds.CreatedAt, Device: ds.Device}
}

func (h *Handlers) computeState(ctx context.Context, now time.Time) (glucose.State, bool, error) {
	horizon := now.Add(clockSkew).UnixMilli()
	latest, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Lte: &horizon, Count: 1})
	if err != nil || len(latest) == 0 {
		return glucose.State{}, false, err
	}
	from := latest[0].Date - stateWindow.Milliseconds()
	es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &latest[0].Date, Count: 200})
	if err != nil {
		return glucose.State{}, false, err
	}
	readings := make([]glucose.Reading, 0, len(es))
	for _, e := range es {
		if e.SGV == nil {
			continue
		}
		readings = append(readings, glucose.Reading{Date: e.Date, SGV: *e.SGV, Direction: e.Direction, Noise: e.Noise, Device: e.Device})
	}
	st, ok := glucose.Compute(readings, horizon)
	return st, ok, nil
}

func (h *Handlers) describe(st glucose.State, now time.Time, th config.Thresholds) (*bgState, *glucose.Forecast) {
	bg := &bgState{
		SGV:       st.Last.SGV,
		Mills:     st.Last.Date,
		AgeSec:    max(0, (now.UnixMilli()-st.Last.Date)/1000),
		Error:     st.Error,
		Direction: st.Direction,
		Arrow:     st.Arrow,
		Noise:     st.Last.Noise,
		Device:    st.Last.Device,
	}
	if st.Delta != nil {
		bg.Delta, bg.DeltaExact, bg.Interpolated = &st.Delta.MgDL, &st.Delta.Exact, st.Delta.Interpolated
	}

	if st.Error || st.Delta == nil || time.Duration(bg.AgeSec)*time.Second > forecastMaxAge {
		return bg, nil
	}
	f, ok := glucose.AR2(st.Mean, st.Delta.Mean5MinsAgo, st.Mills, th.Low, th.High, glucose.DefaultConeFactor)
	if !ok {
		return bg, nil
	}
	return bg, &f
}

type series struct {
	T []int64  `json:"t"`
	V []int    `json:"v"`
	D []string `json:"d,omitempty"` // mbg only: where each check came from, see meter.Source
}

type series1 struct {
	t int64
	v int
}

// repeats: the same value (±1) within sameMeterReading of one already shown.
func repeats(seen []series1, p series1) bool {
	for _, s := range seen {
		d := p.t - s.t
		if d < 0 {
			d = -d
		}
		if d <= sameMeterReading.Milliseconds() && p.v-s.v <= 1 && s.v-p.v <= 1 {
			return true
		}
	}
	return false
}

type historyTreatment struct {
	ID        string   `json:"id"`
	T         int64    `json:"t"`
	EventType string   `json:"event_type,omitempty"`
	Insulin   *float64 `json:"insulin,omitempty"`
	Carbs     *float64 `json:"carbs,omitempty"`
	Notes     string   `json:"notes,omitempty"`
	EnteredBy string   `json:"entered_by,omitempty"`
	Glucose   *int     `json:"glucose,omitempty"`   // glucometer, mg/dL ("BG Check")
	Duration  *int     `json:"duration,omitempty"`  // minutes ("Exercise")
	Intensity string   `json:"intensity,omitempty"` // "light" | "moderate" | "vigorous"
	// from the app's own fields in the stored document: "rapid" | "basal" and the brand
	InsulinType string `json:"insulin_type,omitempty"`
	InsulinName string `json:"insulin_name,omitempty"`
}

// exercise reads an activity entry: Nightscout's duration (minutes) and the app's intensity.
func exercise(raw []byte) (*int, string) {
	var d struct {
		Duration  *float64 `json:"duration"`
		Intensity string   `json:"exerciseIntensity"`
	}
	if json.Unmarshal(raw, &d) != nil {
		return nil, ""
	}
	var min *int
	if d.Duration != nil && *d.Duration > 0 {
		v := int(math.Round(*d.Duration))
		min = &v
	}
	return min, d.Intensity
}

// bgCheck is a glucometer reading entered as a treatment ("BG Check"), in mg/dL.
func bgCheck(raw []byte) *int {
	var d struct {
		Glucose *float64 `json:"glucose"`
		Units   string   `json:"units"`
	}
	if json.Unmarshal(raw, &d) != nil || d.Glucose == nil || *d.Glucose <= 0 {
		return nil
	}
	v := *d.Glucose
	if strings.HasPrefix(strings.ToLower(d.Units), "mmol") {
		v *= glucose.MgdlPerMmol
	}
	mg := int(math.Round(v))
	return &mg
}

// sameMeterReading: one glucometer check often arrives twice (as a BG check
// and again as a calibration, or echoed back by xDrip+ after download).
const sameMeterReading = 15 * time.Minute

type historyResponse struct {
	From       int64              `json:"from"`
	To         int64              `json:"to"`
	SGV        series             `json:"sgv"` // sensor error codes are left out
	MBG        series             `json:"mbg"` // glucometer readings uploaded by xDrip+, repeats of a BG check left out
	Treatments []historyTreatment `json:"treatments"`
}

// History serves GET /api/history?from=&to= (unix ms or ISO; default last 6 h),
// oldest first, as parallel arrays so a week stays small over a bad connection.
func (h *Handlers) History(w http.ResponseWriter, r *http.Request) {
	from, to, ok := h.historyRange(r)
	if !ok {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	resp := historyResponse{From: from, To: to, SGV: series{T: []int64{}, V: []int{}}, MBG: series{T: []int64{}, V: []int{}}, Treatments: []historyTreatment{}}

	ts, err := h.Store.ListTreatments(ctx, store.TreatmentQuery{Gte: &from, Lte: &to, Count: 1000})
	if err != nil {
		fail(w, "history treatments", err)
		return
	}
	var checks []series1                // glucometer readings seen so far, for dropping repeats
	for i := len(ts) - 1; i >= 0; i-- { // store returns newest first
		t := ts[i]
		typ, name := insulin.Kind(t.Raw)
		ht := historyTreatment{
			ID: t.ID, T: t.CreatedAt, EventType: t.EventType, Insulin: t.Insulin, Carbs: t.Carbs, Notes: t.Notes, EnteredBy: t.EnteredBy,
			InsulinType: typ, InsulinName: name,
		}
		if t.EventType == "Exercise" {
			ht.Duration, ht.Intensity = exercise(t.Raw)
		}
		if ht.Glucose = bgCheck(t.Raw); ht.Glucose != nil {
			checks = append(checks, series1{t.CreatedAt, *ht.Glucose})
		}
		resp.Treatments = append(resp.Treatments, ht)
	}

	for typ, dst := range map[string]*series{"sgv": &resp.SGV, "mbg": &resp.MBG} {
		es, err := h.Store.QueryEntries(ctx, store.EntryQuery{Type: typ, Gte: &from, Lte: &to, Count: maxHistoryRows, Asc: true})
		if err != nil {
			fail(w, "history entries", err)
			return
		}
		for _, e := range es {
			v := e.SGV
			if typ == "mbg" {
				v = e.MBG
			}
			if v == nil || (typ == "sgv" && *v <= glucose.MaxErrorCode) {
				continue
			}
			if typ == "mbg" {
				p := series1{e.Date, *v}
				if repeats(checks, p) {
					continue
				}
				checks = append(checks, p)
			}
			dst.T = append(dst.T, e.Date)
			dst.V = append(dst.V, *v)
			if typ == "mbg" {
				dst.D = append(dst.D, meter.Source(e.Device))
			}
		}
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handlers) historyRange(r *http.Request) (from, to int64, ok bool) {
	v := r.URL.Query()
	to = h.Now().UnixMilli()
	if s := v.Get("to"); s != "" {
		if to, ok = nstime.Parse(s); !ok {
			return 0, 0, false
		}
	}
	from = to - defaultHistory.Milliseconds()
	if s := v.Get("from"); s != "" {
		if from, ok = nstime.Parse(s); !ok {
			return 0, 0, false
		}
	}
	if from > to || to-from > maxHistory.Milliseconds() {
		return 0, 0, false
	}
	return from, to, true
}

func fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		slog.Error("marshal", "err", err)
		return []byte("null")
	}
	return b
}

type meResponse struct {
	ID           int64  `json:"id"`
	Name         string `json:"name"`
	Role         string `json:"role"`
	DisplayUnits string `json:"display_units"`
}

// Me serves GET /api/me: which device this token is. The app shows it after
// connecting ("Connected as Dad") and uses it to verify a scanned token.
func (h *Handlers) Me(w http.ResponseWriter, r *http.Request) {
	dev, _ := auth.DeviceFrom(r.Context())
	httpx.WriteJSON(w, http.StatusOK, meResponse{ID: dev.ID, Name: dev.Name, Role: dev.Role, DisplayUnits: dev.DisplayUnits})
}
