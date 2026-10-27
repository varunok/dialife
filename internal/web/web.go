// Package web serves the read-only web viewer: a few static files embedded
// in the binary. The page logs in by a QR code a parent scans in the app and
// then reads the same API the app does, with a session token in the header.
package web

import (
	"embed"
	"io/fs"
	"net/http"
)

//go:embed static
var files embed.FS

// Handler serves /web/… and sends / there.
func Handler() http.Handler {
	sub, _ := fs.Sub(files, "static")
	static := http.StripPrefix("/web/", http.FileServerFS(sub))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/web/", http.StatusFound)
			return
		}
		h := w.Header()
		// everything the page needs is its own; nothing loads from elsewhere
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-cache")
		static.ServeHTTP(w, r)
	})
}
