// Package appupdate hands the parents' app its newer version from the
// family's own server, not a store: `dl app-push` puts the APK and its
// description in the server's data folder (app/latest.json and the file it
// names), the phones see it in /api/state and fetch it from /api/app/apk,
// check its SHA-256 and give it to Android's installer.
package appupdate

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"dialife/internal/auth"
	"dialife/internal/httpx"
)

// Release is the newest APK on the server.
type Release struct {
	Name   string `json:"name"`            // versionName, e.g. "0.7.1"
	Code   int    `json:"code"`            // versionCode: a phone updates when its own is lower
	SHA256 string `json:"sha256"`          // of the file, hex
	Size   int64  `json:"size"`            // bytes
	Notes  string `json:"notes,omitempty"` // what is new, as the family should read it
	Cert   string `json:"cert,omitempty"`  // the signing certificate's SHA-256: every update has to match it
	At     int64  `json:"at"`              // when it was pushed, unix ms
	File   string `json:"file"`            // the APK's name in the same folder
}

var fileName = regexp.MustCompile(`^dialife-[0-9A-Za-z.+-]+\.apk$`)

// Service reads Dir/latest.json, again whenever it changes.
type Service struct {
	Dir string // the server's data folder + "/app"

	mu    sync.Mutex
	mtime time.Time
	rel   *Release
}

// Latest is the newest release; nil when none was pushed (or it is unreadable).
func (s *Service) Latest() *Release {
	if s == nil || s.Dir == "" {
		return nil
	}
	path := filepath.Join(s.Dir, "latest.json")
	st, err := os.Stat(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			slog.Error("app update", "err", err)
		}
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if st.ModTime().Equal(s.mtime) {
		return s.rel
	}
	s.mtime, s.rel = st.ModTime(), nil
	raw, err := os.ReadFile(path)
	if err != nil {
		slog.Error("app update", "err", err)
		return nil
	}
	var r Release
	if err := json.Unmarshal(raw, &r); err != nil || r.Code <= 0 || len(r.SHA256) != 64 || !fileName.MatchString(r.File) {
		slog.Error("app update: latest.json unreadable or incomplete", "err", err)
		return nil
	}
	if _, err := os.Stat(filepath.Join(s.Dir, r.File)); err != nil {
		slog.Error("app update: the APK it names is missing", "file", r.File)
		return nil
	}
	s.rel = &r
	return s.rel
}

// HTTP serves the APK.
type HTTP struct{ Service *Service }

// Routes adds GET /api/app/apk for the phones (not the web viewer).
func (h HTTP) Routes(mux *http.ServeMux, anyRole func(http.Handler) http.Handler) {
	mux.Handle("GET /api/app/apk", anyRole(http.HandlerFunc(h.apk)))
}

func (h HTTP) apk(w http.ResponseWriter, r *http.Request) {
	if dev, _ := auth.DeviceFrom(r.Context()); dev.WebSessionID != 0 {
		httpx.Error(w, http.StatusForbidden) // a browser has nothing to install
		return
	}
	rel := h.Service.Latest()
	if rel == nil {
		httpx.Error(w, http.StatusNotFound)
		return
	}
	f, err := os.Open(filepath.Join(h.Service.Dir, rel.File))
	if err != nil {
		slog.Error("app update apk", "err", err)
		httpx.Error(w, http.StatusNotFound)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+rel.File+`"`)
	http.ServeContent(w, r, rel.File, time.UnixMilli(rel.At), f)
}
