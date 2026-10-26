package httpx

import (
	"compress/gzip"
	"io"
	"net/http"
	"strings"
)

// MaxBody caps request bodies both on the wire and after gzip decompression
// (so a small "zip bomb" cannot expand without limit). Roughly ten days of
// xDrip+ backfill fits; a rejected oversized batch would be retried forever.
const MaxBody = 4 << 20

type gzipBody struct {
	io.Reader
	zr  *gzip.Reader
	raw io.Closer
}

func (g *gzipBody) Close() error {
	g.zr.Close()
	return g.raw.Close()
}

// Body limits request size and transparently decompresses
// "Content-Encoding: gzip", which xDrip+ uses once status.json advertises it.
func Body(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, MaxBody)

		switch enc := strings.ToLower(strings.TrimSpace(r.Header.Get("Content-Encoding"))); enc {
		case "", "identity":
		case "gzip":
			zr, err := gzip.NewReader(r.Body)
			if err != nil {
				Error(w, http.StatusBadRequest)
				return
			}
			r.Body = http.MaxBytesReader(w, &gzipBody{Reader: zr, zr: zr, raw: r.Body}, MaxBody)
			r.Header.Del("Content-Encoding")
			r.ContentLength = -1
		default:
			Error(w, http.StatusUnsupportedMediaType)
			return
		}
		next.ServeHTTP(w, r)
	})
}
