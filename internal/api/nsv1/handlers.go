package nsv1

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/nstime"
	"dialife/internal/store"
)

// Readings outside this window are rejected: before CGM data could exist,
// or too far in the future to be anything but a broken phone clock.
var (
	minDate      = time.Date(2015, 1, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	maxClockSkew = time.Hour
)

const (
	defaultTreatmentCount = 100
	maxTreatmentCount     = 1000
)

type Handlers struct {
	Store  *store.Store
	Now    func() time.Time
	Events Notifier // optional
}

// Notifier hears about stored uploads so live subscribers can be told.
type Notifier interface {
	EntriesSaved(ctx context.Context, es []store.Entry)
	TreatmentsChanged(ctx context.Context, op string, ids []string)
	DeviceStatusSaved(ctx context.Context, ds []store.DeviceStatus)
}

// uploader returns the authenticated device; auth middleware guarantees it.
func uploader(r *http.Request) store.Device {
	d, _ := auth.DeviceFrom(r.Context())
	return d
}

func (h *Handlers) PostEntries(w http.ResponseWriter, r *http.Request) {
	docs, ok := h.read(w, r)
	if !ok {
		return
	}
	now := h.Now()
	dev := uploader(r)

	var (
		entries []store.Entry
		kept    []doc
	)
	for _, d := range docs {
		e, err := h.parseEntry(d, now)
		if err != nil {
			slog.Warn("entry skipped", "device", dev.Name, "err", err, "doc", string(d.marshal()))
			continue
		}
		entries = append(entries, e)
		kept = append(kept, d)
	}

	added, err := h.Store.SaveEntries(r.Context(), entries, dev.ID, now)
	if err != nil {
		h.fail(w, "save entries", err)
		return
	}
	for i := range kept {
		kept[i]["_id"] = entries[i].ID
	}
	if h.Events != nil && added > 0 {
		h.Events.EntriesSaved(r.Context(), entries)
	}
	slog.Info("entries", "device", dev.Name, "received", len(docs), "new", added,
		"duplicate", len(entries)-added, "skipped", len(docs)-len(entries))
	httpx.WriteJSON(w, http.StatusOK, nonNil(kept))
}

func (h *Handlers) parseEntry(d doc, now time.Time) (store.Entry, error) {
	date, ok := d.firstTime("date", "dateString", "sysTime")
	if !ok {
		return store.Entry{}, errors.New("no usable date")
	}
	if date < minDate || date > now.Add(maxClockSkew).UnixMilli() {
		return store.Entry{}, errors.New("date out of range")
	}

	typ := d.str("type")
	if typ == "" {
		switch {
		case d["sgv"] != nil:
			typ = "sgv"
		case d["mbg"] != nil:
			typ = "mbg"
		default:
			return store.Entry{}, errors.New("no type")
		}
	}
	if len(typ) > 16 {
		return store.Entry{}, errors.New("type too long")
	}

	e := store.Entry{
		ID:        stableID(typ, strconv.FormatInt(date, 10)),
		Type:      typ,
		Date:      date,
		SGV:       d.intPtr("sgv"),
		MBG:       d.intPtr("mbg"),
		Direction: d.str("direction"),
		Delta:     d.floatPtr("delta"),
		Noise:     d.intPtr("noise"),
		Device:    d.str("device"),
	}
	switch typ {
	case "sgv":
		if e.SGV == nil || *e.SGV < 0 || *e.SGV > 1000 {
			return store.Entry{}, errors.New("sgv missing or out of range")
		}
	case "mbg":
		if e.MBG == nil || *e.MBG <= 0 || *e.MBG > 1000 {
			return store.Entry{}, errors.New("mbg missing or out of range")
		}
	}
	delete(d, "_id")
	e.Raw = d.marshal()
	return e, nil
}

func (h *Handlers) PostDeviceStatus(w http.ResponseWriter, r *http.Request) {
	docs, ok := h.read(w, r)
	if !ok {
		return
	}
	now := h.Now()
	dev := uploader(r)

	out := make([]store.DeviceStatus, 0, len(docs))
	for _, d := range docs {
		created, ok := d.firstTime("created_at", "date", "mills")
		if !ok {
			created = now.UnixMilli() // xDrip+ sends no time: it reports "now"
		}
		battery := d.intPtr("uploaderBattery")
		if up := d.sub("uploader"); up != nil && battery == nil {
			battery = up.intPtr("battery")
		}
		delete(d, "_id")
		id := randomID()
		out = append(out, store.DeviceStatus{
			ID: id, CreatedAt: created, Device: d.str("device"), UploaderBattery: battery, Raw: d.marshal(),
		})
		d["_id"] = id
	}
	if err := h.Store.SaveDeviceStatuses(r.Context(), out, dev.ID, now); err != nil {
		h.fail(w, "save devicestatus", err)
		return
	}
	if h.Events != nil {
		h.Events.DeviceStatusSaved(r.Context(), out)
	}
	httpx.WriteJSON(w, http.StatusOK, docs)
}

func (h *Handlers) PostActivity(w http.ResponseWriter, r *http.Request) {
	docs, ok := h.read(w, r)
	if !ok {
		return
	}
	now := h.Now()
	dev := uploader(r)

	out := make([]store.Activity, 0, len(docs))
	for _, d := range docs {
		created, ok := d.firstTime("created_at", "timestamp", "date", "mills")
		if !ok {
			created = now.UnixMilli()
		}
		delete(d, "_id")
		raw := d.marshal()
		id := stableID("activity", string(raw)) // identical retries collapse
		out = append(out, store.Activity{ID: id, CreatedAt: created, Raw: raw})
		d["_id"] = id
	}
	if err := h.Store.SaveActivities(r.Context(), out, dev.ID, now); err != nil {
		h.fail(w, "save activity", err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, docs)
}

// UpsertTreatments serves both POST and PUT /treatments.
func (h *Handlers) UpsertTreatments(w http.ResponseWriter, r *http.Request) {
	docs, ok := h.read(w, r)
	if !ok {
		return
	}
	now := h.Now()
	dev := uploader(r)

	var (
		ts   []store.Treatment
		kept []doc
	)
	for _, d := range docs {
		t, err := parseTreatment(d, now)
		if err != nil {
			slog.Warn("treatment skipped", "device", dev.Name, "err", err, "doc", string(d.marshal()))
			continue
		}
		ts = append(ts, t)
		kept = append(kept, d)
	}
	if err := h.Store.UpsertTreatments(r.Context(), ts, dev.ID, now); err != nil {
		h.fail(w, "save treatments", err)
		return
	}
	ids := make([]string, len(ts))
	for i := range kept {
		kept[i]["_id"] = ts[i].ID
		ids[i] = ts[i].ID
	}
	if h.Events != nil {
		h.Events.TreatmentsChanged(r.Context(), "upsert", ids)
	}
	slog.Info("treatments", "device", dev.Name, "method", r.Method, "saved", len(ts), "skipped", len(docs)-len(ts))
	httpx.WriteJSON(w, http.StatusOK, nonNil(kept))
}

func parseTreatment(d doc, now time.Time) (store.Treatment, error) {
	uuid := d.str("uuid")
	id := d.str("_id")
	switch {
	case isHexID(id):
	case uuid != "":
		id = uuidToID(uuid)
	default:
		id = randomID()
	}

	created, ok := d.firstTime("created_at", "timestamp", "mills", "date")
	if !ok {
		created = now.UnixMilli()
	}
	if created < minDate || created > now.Add(maxClockSkew).UnixMilli() {
		return store.Treatment{}, errors.New("created_at out of range")
	}

	delete(d, "_id")
	return store.Treatment{
		ID:        id,
		UUID:      uuid,
		EventType: d.str("eventType"),
		CreatedAt: created,
		Insulin:   d.floatPtr("insulin"),
		Carbs:     d.floatPtr("carbs"),
		Notes:     d.str("notes"),
		EnteredBy: d.str("enteredBy"),
		Raw:       d.marshal(),
	}, nil
}

func (h *Handlers) GetTreatments(w http.ResponseWriter, r *http.Request) {
	q, err := treatmentQuery(r)
	if err != nil {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	ts, err := h.Store.ListTreatments(r.Context(), q)
	if err != nil {
		h.fail(w, "list treatments", err)
		return
	}
	lastMod, err := h.Store.TreatmentsLastModified(r.Context())
	if err != nil {
		h.fail(w, "treatments last modified", err)
		return
	}
	if lastMod > 0 {
		w.Header().Set("Last-Modified", time.UnixMilli(lastMod).UTC().Format(http.TimeFormat))
	}

	out := make([]doc, 0, len(ts))
	for _, t := range ts {
		d, err := readDocs(bytes.NewReader(t.Raw))
		if err != nil || len(d) != 1 {
			slog.Error("stored treatment unreadable", "id", t.ID, "err", err)
			continue
		}
		d[0]["_id"] = t.ID
		d[0]["created_at"] = time.UnixMilli(t.CreatedAt).UTC().Format("2006-01-02T15:04:05.000Z")
		d[0]["mills"] = t.CreatedAt
		out = append(out, d[0])
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func treatmentQuery(r *http.Request) (store.TreatmentQuery, error) {
	v := r.URL.Query()
	q := store.TreatmentQuery{UUID: v.Get("find[uuid]")}
	var err error
	if q.Count, err = parseCount(v, defaultTreatmentCount, maxTreatmentCount); err != nil {
		return q, err
	}
	err = parseBounds(v, "created_at", &q.Gte, &q.Gt, &q.Lte, &q.Lt)
	return q, err
}

func parseCount(v url.Values, def, maxCount int) (int, error) {
	c := v.Get("count")
	if c == "" {
		return def, nil
	}
	n, err := strconv.Atoi(c)
	if err != nil || n < 1 {
		return 0, errors.New("bad count")
	}
	return min(n, maxCount), nil
}

// parseBounds reads find[field][$gte|$gt|$lte|$lt] as unix ms or ISO time.
func parseBounds(v url.Values, field string, gte, gt, lte, lt **int64) error {
	for op, dst := range map[string]**int64{"$gte": gte, "$gt": gt, "$lte": lte, "$lt": lt} {
		s := v.Get("find[" + field + "][" + op + "]")
		if s == "" {
			continue
		}
		ms, ok := nstime.Parse(s)
		if !ok {
			return errors.New("bad " + field + " bound")
		}
		*dst = &ms
	}
	return nil
}

func (h *Handlers) DeleteTreatment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	deleted, err := h.Store.DeleteTreatment(r.Context(), id, h.Now())
	if err != nil {
		h.fail(w, "delete treatment", err)
		return
	}
	slog.Info("treatment delete", "device", uploader(r).Name, "id", id, "deleted", deleted)
	if h.Events != nil && deleted {
		h.Events.TreatmentsChanged(r.Context(), "delete", []string{id})
	}
	// always 200: a missing treatment must not keep xDrip+ retrying forever
	n := 0
	if deleted {
		n = 1
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]int{"n": n, "ok": 1})
}

// read decodes the request body; on failure it has already answered.
func (h *Handlers) read(w http.ResponseWriter, r *http.Request) ([]doc, bool) {
	docs, err := readDocs(r.Body)
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			httpx.Error(w, http.StatusRequestEntityTooLarge)
			return nil, false
		}
		slog.Warn("bad upload body", "device", uploader(r).Name, "path", r.URL.Path, "err", err)
		httpx.Error(w, http.StatusBadRequest)
		return nil, false
	}
	return docs, true
}

func (h *Handlers) fail(w http.ResponseWriter, what string, err error) {
	slog.Error(what, "err", err)
	httpx.Error(w, http.StatusInternalServerError)
}

func nonNil(ds []doc) []doc {
	if ds == nil {
		return []doc{}
	}
	return ds
}
