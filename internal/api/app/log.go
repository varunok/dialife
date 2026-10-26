package app

import (
	"net/http"
	"strconv"
	"time"

	"dialife/internal/alarms"
	"dialife/internal/httpx"
)

const (
	maxLogDays     = 90
	maxLogEpisodes = 200
)

type logEvent struct {
	T      int64  `json:"t"`
	Type   string `json:"type"` // alarm, handled, checkin, checkin_ok, help, coming, clear
	Reason string `json:"reason,omitempty"`
	By     string `json:"by,omitempty"`
	SGV    *int   `json:"sgv,omitempty"`
}

type logEpisode struct {
	alarms.View
	TriggerSGV *int       `json:"trigger_sgv,omitempty"`
	ClearedAt  *int64     `json:"cleared_at,omitempty"`
	ResolvedBy string     `json:"resolved_by,omitempty"`
	Manual     bool       `json:"manual,omitempty"`
	Events     []logEvent `json:"events"` // oldest first; empty for episodes from before the log existed
}

// AlarmLog serves GET /api/alarms/log?days=7 (up to 90): episodes, newest
// first, each with its steps — who took it and when, questions, help, end.
func (h *Handlers) AlarmLog(w http.ResponseWriter, r *http.Request) {
	days := 7
	if v := r.URL.Query().Get("days"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > maxLogDays {
			httpx.Error(w, http.StatusBadRequest)
			return
		}
		days = n
	}
	ctx := r.Context()
	from := h.Now().Add(-time.Duration(days) * 24 * time.Hour).UnixMilli()
	as, err := h.Store.AlarmsSince(ctx, from, maxLogEpisodes)
	if err != nil {
		fail(w, "alarm log", err)
		return
	}
	evs, err := h.Store.AlarmEventsSince(ctx, from)
	if err != nil {
		fail(w, "alarm log events", err)
		return
	}
	byAlarm := map[int64][]logEvent{}
	for _, e := range evs {
		byAlarm[e.AlarmID] = append(byAlarm[e.AlarmID], logEvent{T: e.At, Type: e.Type, Reason: e.Reason, By: e.By, SGV: e.SGV})
	}
	out := make([]logEpisode, 0, len(as))
	for _, a := range as {
		ev := byAlarm[a.ID]
		if ev == nil {
			ev = []logEvent{}
		}
		out = append(out, logEpisode{View: alarms.ToView(a), TriggerSGV: a.TriggerSGV, ClearedAt: a.ClearedAt,
			ResolvedBy: a.ResolvedByName, Manual: a.ResolvedManually, Events: ev})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"episodes": out})
}
