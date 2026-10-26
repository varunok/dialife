package nsv1

import (
	"bytes"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"dialife/internal/httpx"
	"dialife/internal/store"
)

const (
	defaultEntryCount = 10 // Nightscout's ENTRIES_DEFAULT_COUNT
	maxEntryCount     = 10000
)

// GetEntries serves GET /api/v1/entries(.json): newest first, all types.
func (h *Handlers) GetEntries(w http.ResponseWriter, r *http.Request) {
	q, err := entryQuery(r)
	if err != nil {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	h.writeEntries(w, r, q)
}

// GetEntriesSpec serves GET /api/v1/entries/{spec}: "sgv.json", "mbg", "current.json"…
func (h *Handlers) GetEntriesSpec(w http.ResponseWriter, r *http.Request) {
	spec := strings.TrimSuffix(r.PathValue("spec"), ".json")
	q, err := entryQuery(r)
	if err != nil || spec == "" || len(spec) > 16 {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	if spec == "current" {
		q.Type, q.Count = "sgv", 1
	} else {
		q.Type = spec
	}
	h.writeEntries(w, r, q)
}

func entryQuery(r *http.Request) (store.EntryQuery, error) {
	v := r.URL.Query()
	q := store.EntryQuery{Type: v.Get("find[type]")}
	var err error
	if q.Count, err = parseCount(v, defaultEntryCount, maxEntryCount); err != nil {
		return q, err
	}
	if err := parseBounds(v, "date", &q.Gte, &q.Gt, &q.Lte, &q.Lt); err != nil {
		return q, err
	}
	// dateString bounds are ISO strings in Nightscout; both forms map onto date
	var g, gt, l, lt *int64
	if err := parseBounds(v, "dateString", &g, &gt, &l, &lt); err != nil {
		return q, err
	}
	if q.Gte == nil {
		q.Gte = g
	}
	if q.Gt == nil {
		q.Gt = gt
	}
	if q.Lte == nil {
		q.Lte = l
	}
	if q.Lt == nil {
		q.Lt = lt
	}
	if len(q.Type) > 16 {
		return q, errors.New("bad type")
	}
	return q, nil
}

func (h *Handlers) writeEntries(w http.ResponseWriter, r *http.Request, q store.EntryQuery) {
	es, err := h.Store.QueryEntries(r.Context(), q)
	if err != nil {
		h.fail(w, "query entries", err)
		return
	}
	out := make([]doc, 0, len(es))
	for _, e := range es {
		ds, err := readDocs(bytes.NewReader(e.Raw))
		if err != nil || len(ds) != 1 {
			slog.Error("stored entry unreadable", "id", e.ID, "err", err)
			continue
		}
		d := ds[0]
		// canonical fields win over whatever the uploader sent
		d["_id"] = e.ID
		d["type"] = e.Type
		d["date"] = e.Date
		d["mills"] = e.Date
		d["dateString"] = time.UnixMilli(e.Date).UTC().Format("2006-01-02T15:04:05.000Z")
		out = append(out, d)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}
