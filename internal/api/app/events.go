package app

import (
	"context"
	"log/slog"

	"dialife/internal/store"
)

// SSE event types. "state" is sent once on connect; the rest are live.
const (
	EventState     = "state"     // full snapshot, same body as GET /api/state
	EventBG        = "bg"        // the newest reading changed: snapshot without display_units
	EventHistory   = "history"   // older readings arrived (backfill): refetch /api/history for {from,to}
	EventTreatment = "treatment" // treatments changed: {op, ids}
	EventUploader  = "uploader"  // the collector phone reported battery
	EventLogout    = "logout"    // a web session was closed: {session}; its own stream ends with it
)

type historyEvent struct {
	From int64 `json:"from"`
	To   int64 `json:"to"`
}

type treatmentEvent struct {
	Op  string   `json:"op"` // "upsert" | "delete" | "changed" (on reconnect)
	IDs []string `json:"ids,omitempty"`
}

// EntriesSaved publishes after an upload. A batch whose newest reading is now
// the latest overall produces a bg event; anything else new in the batch (older
// readings, glucometer checks) produces a single history event instead of one each.
func (h *Handlers) EntriesSaved(ctx context.Context, es []store.Entry) {
	var (
		newest           *store.Entry
		meters           bool // a new glucometer check: the chart has to refetch to show it
		older            int
		backFrom, backTo int64
	)
	for i := range es {
		e := &es[i]
		if e.New && e.Type == "mbg" {
			meters = true
		}
		if !e.New || e.Type != "sgv" {
			continue
		}
		if newest == nil || e.Date > newest.Date {
			newest = e
		}
	}
	if newest == nil && !meters {
		return
	}

	now := h.Now()
	var snap stateResponse
	isLatest := false
	if newest != nil {
		var err error
		if snap, err = h.snapshot(ctx, now); err != nil {
			slog.Error("event snapshot", "err", err)
			return
		}
		isLatest = snap.BG != nil && snap.BG.Mills == newest.Date
	}

	for _, e := range es {
		if !e.New || (e.Type != "sgv" && e.Type != "mbg") || (isLatest && e.Type == "sgv" && e.Date == newest.Date) {
			continue
		}
		if older == 0 || e.Date < backFrom {
			backFrom = e.Date
		}
		backTo = max(backTo, e.Date)
		older++
	}

	if older > 0 {
		h.Bus.Publish(EventHistory, historyEvent{From: backFrom, To: backTo}, now)
	}
	if isLatest {
		h.Bus.Publish(EventBG, snap, now)
		h.evaluateAlarms(ctx)
		h.evaluateQuestions(ctx)
	}
}

// evaluateQuestions: a new reading may make a question, a new entry may answer one.
func (h *Handlers) evaluateQuestions(ctx context.Context) {
	if h.Questions == nil {
		return
	}
	if err := h.Questions.Evaluate(ctx); err != nil {
		slog.Error("questions evaluate", "err", err)
	}
}

func (h *Handlers) evaluateAlarms(ctx context.Context) {
	if h.Alarms == nil {
		return
	}
	if err := h.Alarms.Evaluate(ctx); err != nil {
		slog.Error("alarm evaluate", "err", err)
	}
}

func (h *Handlers) TreatmentsChanged(ctx context.Context, op string, ids []string) {
	if len(ids) == 0 {
		return
	}
	h.Bus.Publish(EventTreatment, treatmentEvent{Op: op, IDs: ids}, h.Now())
	h.evaluateQuestions(ctx)
}

func (h *Handlers) DeviceStatusSaved(_ context.Context, ds []store.DeviceStatus) {
	var newest *store.DeviceStatus
	for i := range ds {
		if newest == nil || ds[i].CreatedAt > newest.CreatedAt {
			newest = &ds[i]
		}
	}
	if newest != nil {
		h.Bus.Publish(EventUploader, uploaderFrom(*newest), h.Now())
	}
}
