// Package nsv1 implements the Nightscout-compatible /api/v1 endpoints used by xDrip+.
package nsv1

import (
	"context"
	"net/http"
	"time"

	"dialife/internal/config"
	"dialife/internal/httpx"
)

type statusResponse struct {
	Status            string         `json:"status"`
	Name              string         `json:"name"`
	Version           string         `json:"version"`
	ServerTime        string         `json:"serverTime"`
	ServerTimeEpoch   int64          `json:"serverTimeEpoch"`
	APIEnabled        bool           `json:"apiEnabled"`
	CareportalEnabled bool           `json:"careportalEnabled"`
	Settings          statusSettings `json:"settings"`
}

type statusSettings struct {
	Units      string            `json:"units"`
	Thresholds config.Thresholds `json:"thresholds"`
}

// Status answers GET /api/v1/status.json. xDrip+ polls it to check the server.
func Status(version string, thresholds func(context.Context) config.Thresholds, now func() time.Time) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		t := now().UTC()
		// xDrip+ switches its uploads to gzip when it sees this (and never switches back)
		w.Header().Set("Accept-Encoding", "gzip")
		httpx.WriteJSON(w, http.StatusOK, statusResponse{
			Status:            "ok",
			Name:              "dialife",
			Version:           version,
			ServerTime:        t.Format(time.RFC3339Nano),
			ServerTimeEpoch:   t.UnixMilli(),
			APIEnabled:        true,
			CareportalEnabled: true,
			Settings:          statusSettings{Units: "mg/dl", Thresholds: thresholds(r.Context())},
		})
	}
}
