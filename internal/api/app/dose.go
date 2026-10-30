package app

import (
	"context"
	"log/slog"
	"time"

	"dialife/internal/dose"
	"dialife/internal/forecast"
	"dialife/internal/wait"
)

// waitSettings is the part of the dose settings the wait for food goes by.
func waitSettings(d dose.Settings) wait.Settings {
	return wait.Settings{Ratio: d.Ratio, WaitMin: d.WaitMin, WaitHighMin: d.WaitHighMin, WaitHighFrom: d.WaitHighFrom, WaitLowBelow: d.WaitLowBelow}
}

// PublishState sends every phone a fresh snapshot: something in it changed
// that is not a reading (the dose settings, a planned dose). It goes out as a
// "bg" event, which the phones take as the whole state.
func (h *Handlers) PublishState(ctx context.Context) {
	now := h.Now()
	snap, err := h.snapshot(ctx, now)
	if err != nil {
		slog.Error("state publish", "err", err)
		return
	}
	h.Bus.Publish(EventBG, snap, now)
}

// forecastParams is what the on-board forecast takes from the dose settings.
func forecastParams(d dose.Settings) forecast.Params {
	return forecast.Params{
		ISF: d.EffectiveISF(), Ratio: d.Ratio,
		DIA: time.Duration(d.DIAMin) * time.Minute, CarbFor: time.Duration(d.CarbMin) * time.Minute,
	}
}
