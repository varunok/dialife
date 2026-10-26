package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"testing"

	"dialife/internal/auth"
)

func fixtureFile(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../../testdata/xdrip/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// xdrip returns the header xDrip+ sends: sha1 of the secret.
func xdrip(token string) map[string]string {
	return map[string]string{"api-secret": auth.Hash(token), "Content-Type": "application/json"}
}

func decodeArray(t *testing.T, resp *http.Response) []map[string]any {
	t.Helper()
	var out []map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestPostEntriesFromXDrip(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	body := fixtureFile(t, "entries.json")

	resp := f.do(t, "POST", "/api/v1/entries", bytes.NewReader(body), xdrip(token))
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	saved := decodeArray(t, resp)
	if len(saved) != 4 {
		t.Fatalf("saved %d docs, want 4", len(saved))
	}
	firstID := saved[0]["_id"]
	if id, _ := firstID.(string); len(id) != 24 {
		t.Fatalf("bad _id %v", firstID)
	}

	// retry of the same batch (xDrip+ retries on any network hiccup): no duplicates, same ids
	resp = f.do(t, "POST", "/api/v1/entries.json", bytes.NewReader(body), xdrip(token))
	again := decodeArray(t, resp)
	if again[0]["_id"] != firstID {
		t.Fatalf("retry changed _id: %v vs %v", again[0]["_id"], firstID)
	}

	sgv, err := f.store.RecentEntries(context.Background(), "sgv", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(sgv) != 2 {
		t.Fatalf("stored %d sgv, want 2", len(sgv))
	}
	latest := sgv[0]
	if *latest.SGV != 108 || latest.Direction != "FortyFiveDown" || *latest.Delta != -4.001 ||
		*latest.Noise != 1 || latest.Device != "xDrip-DexcomG5" || latest.Date != 1792886700000 {
		t.Fatalf("latest sgv %+v", latest)
	}
	if !strings.Contains(string(latest.Raw), `"filtered":114117.65`) {
		t.Fatalf("raw lost fields: %s", latest.Raw)
	}

	for typ, want := range map[string]int{"mbg": 1, "cal": 1} {
		es, _ := f.store.RecentEntries(context.Background(), typ, 10)
		if len(es) != want {
			t.Errorf("%s: %d stored, want %d", typ, len(es), want)
		}
	}
}

func TestPostEntriesGzip(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)

	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	zw.Write(fixtureFile(t, "entries.json"))
	zw.Close()

	hdr := xdrip(token)
	hdr["Content-Encoding"] = "gzip"
	if resp := f.do(t, "POST", "/api/v1/entries", &buf, hdr); resp.StatusCode != 200 {
		t.Fatalf("gzip upload: status %d", resp.StatusCode)
	}
	if es, _ := f.store.RecentEntries(context.Background(), "sgv", 10); len(es) != 2 {
		t.Fatalf("stored %d sgv from gzip body", len(es))
	}

	hdr["Content-Encoding"] = "br"
	if resp := f.do(t, "POST", "/api/v1/entries", strings.NewReader("x"), hdr); resp.StatusCode != 415 {
		t.Fatalf("unknown encoding: status %d", resp.StatusCode)
	}
}

func TestStatusAdvertisesGzip(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	resp := f.get(t, "/api/v1/status.json", xdrip(token))
	if !strings.Contains(resp.Header.Get("Accept-Encoding"), "gzip") {
		t.Fatalf("Accept-Encoding = %q", resp.Header.Get("Accept-Encoding"))
	}
}

func TestBadEntriesAreSkippedNotFatal(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	future := testNow.Add(3 * 3600e9).UnixMilli()
	body := `[
	  {"type":"sgv","date":1792886400000,"sgv":120},
	  {"type":"sgv","date":` + jsonInt(future) + `,"sgv":99},
	  {"type":"sgv","date":1792886700000},
	  {"type":"sgv","date":1792887000000,"sgv":5000},
	  {"sgv":130,"dateString":"2026-10-24T21:15:00.000-0300"},
	  {"type":"sgv","sgv":140}
	]`
	resp := f.do(t, "POST", "/api/v1/entries", strings.NewReader(body), xdrip(token))
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got := decodeArray(t, resp); len(got) != 2 {
		t.Fatalf("accepted %d, want 2 (valid one + type inferred from dateString)", len(got))
	}

	if resp := f.do(t, "POST", "/api/v1/entries", strings.NewReader("{broken"), xdrip(token)); resp.StatusCode != 400 {
		t.Fatalf("broken json: status %d", resp.StatusCode)
	}
}

func TestBodyTooLarge(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	big := `[{"type":"sgv","date":1792886400000,"sgv":120,"pad":"` + strings.Repeat("x", 5<<20) + `"}]`
	if resp := f.do(t, "POST", "/api/v1/entries", strings.NewReader(big), xdrip(token)); resp.StatusCode != 413 {
		t.Fatalf("status %d, want 413", resp.StatusCode)
	}

	// a gzip bomb: tiny on the wire, huge when expanded
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	zw.Write([]byte(big))
	zw.Close()
	hdr := xdrip(token)
	hdr["Content-Encoding"] = "gzip"
	if resp := f.do(t, "POST", "/api/v1/entries", &buf, hdr); resp.StatusCode != 413 {
		t.Fatalf("gzip bomb: status %d, want 413", resp.StatusCode)
	}
}

func TestDeviceStatusFromXDrip(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	resp := f.do(t, "POST", "/api/v1/devicestatus", bytes.NewReader(fixtureFile(t, "devicestatus.json")), xdrip(token))
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	ds, err := f.store.LatestDeviceStatus(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if ds.UploaderBattery == nil || *ds.UploaderBattery != 63 || ds.Device != "SM-A525F" || ds.CreatedAt != testNow.UnixMilli() {
		t.Fatalf("devicestatus %+v", ds)
	}
}

func TestActivityAccepted(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	body := `[{"type":"steps","timestamp":1792886400000,"steps":120}]`
	for range 2 { // retries must not fail on the duplicate id
		if resp := f.do(t, "POST", "/api/v1/activity", strings.NewReader(body), xdrip(token)); resp.StatusCode != 200 {
			t.Fatalf("status %d", resp.StatusCode)
		}
	}
}

func TestTreatmentLifecycleLikeXDrip(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "xDrip", auth.RoleUploader)
	put := fixtureFile(t, "treatment_put.json")

	for range 2 { // PUT is an upsert; xDrip+ may repeat it
		if resp := f.do(t, "PUT", "/api/v1/treatments", bytes.NewReader(put), xdrip(token)); resp.StatusCode != 200 {
			t.Fatalf("put: status %d", resp.StatusCode)
		}
	}

	// xDrip+ delete flow: find by uuid → take _id → DELETE
	resp := f.get(t, "/api/v1/treatments.json?find[uuid]=2f1c6c1e-8b3a-4d2e-9f00-1a2b3c4d5e6f", xdrip(token))
	if resp.Header.Get("Last-Modified") == "" {
		t.Error("no Last-Modified header")
	}
	found := decodeArray(t, resp)
	if len(found) != 1 {
		t.Fatalf("find by uuid: %d results", len(found))
	}
	tr := found[0]
	if tr["_id"] != "2f1c6c1e8b3a4d2e9f001a2b" || tr["notes"] != "Обід" || tr["carbs"] != 45.0 || tr["insulin"] != 3.5 ||
		tr["created_at"] != "2026-10-25T00:00:00.000Z" {
		t.Fatalf("treatment %v", tr)
	}

	if resp := f.do(t, "DELETE", "/api/v1/treatments/2f1c6c1e8b3a4d2e9f001a2b", nil, xdrip(token)); resp.StatusCode != 200 {
		t.Fatalf("delete: status %d", resp.StatusCode)
	}
	if got := decodeArray(t, f.get(t, "/api/v1/treatments?find[uuid]=2f1c6c1e-8b3a-4d2e-9f00-1a2b3c4d5e6f", xdrip(token))); len(got) != 0 {
		t.Fatalf("deleted treatment still listed: %v", got)
	}
	// deleting again must still be 200, or xDrip+ retries forever
	if resp := f.do(t, "DELETE", "/api/v1/treatments/2f1c6c1e8b3a4d2e9f001a2b", nil, xdrip(token)); resp.StatusCode != 200 {
		t.Fatalf("second delete: status %d", resp.StatusCode)
	}
	// re-upload revives it
	f.do(t, "PUT", "/api/v1/treatments", bytes.NewReader(put), xdrip(token))
	if got := decodeArray(t, f.get(t, "/api/v1/treatments", xdrip(token))); len(got) != 1 {
		t.Fatalf("revived treatment: %d listed", len(got))
	}
}

func TestTreatmentsQuery(t *testing.T) {
	f := newFixture(t)
	_, token := f.addDevice(t, "app", auth.RoleCaregiver)
	bearer := map[string]string{"Authorization": "Bearer " + token}
	body := `[
	  {"eventType":"Meal Bolus","created_at":"2026-10-24T10:00:00Z","carbs":30,"insulin":2},
	  {"eventType":"Correction Bolus","created_at":"2026-10-24T14:00:00Z","insulin":1},
	  {"eventType":"Note","created_at":"2026-10-24T18:00:00Z","notes":"футбол"}
	]`
	if resp := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(body), bearer); resp.StatusCode != 200 {
		t.Fatalf("post: status %d", resp.StatusCode)
	}

	all := decodeArray(t, f.get(t, "/api/v1/treatments.json", bearer))
	if len(all) != 3 || all[0]["eventType"] != "Note" {
		t.Fatalf("want 3 newest first, got %v", all)
	}
	since := decodeArray(t, f.get(t, "/api/v1/treatments.json?find[created_at][$gte]=2026-10-24T12:00:00Z", bearer))
	if len(since) != 2 {
		t.Fatalf("$gte: %d results", len(since))
	}
	if got := decodeArray(t, f.get(t, "/api/v1/treatments.json?count=1", bearer)); len(got) != 1 {
		t.Fatalf("count=1: %d results", len(got))
	}
	if resp := f.get(t, "/api/v1/treatments.json?count=abc", bearer); resp.StatusCode != 400 {
		t.Fatalf("bad count: status %d", resp.StatusCode)
	}
}

func TestIngestRoles(t *testing.T) {
	f := newFixture(t)
	_, reader := f.addDevice(t, "panel", auth.RoleReader)
	_, carer := f.addDevice(t, "mom", auth.RoleCaregiver)
	entry := `[{"type":"sgv","date":1792886400000,"sgv":120}]`
	treat := `{"eventType":"Note","created_at":"2026-10-24T18:00:00Z"}`

	cases := []struct {
		name, method, path, body, token string
		want                            int
	}{
		{"reader cannot upload entries", "POST", "/api/v1/entries", entry, reader, 403},
		{"caregiver cannot upload entries", "POST", "/api/v1/entries", entry, carer, 403},
		{"reader cannot add treatments", "POST", "/api/v1/treatments", treat, reader, 403},
		{"reader cannot delete treatments", "DELETE", "/api/v1/treatments/0123456789abcdef01234567", "", reader, 403},
		{"reader can read treatments", "GET", "/api/v1/treatments", "", reader, 200},
		{"caregiver can add treatments", "POST", "/api/v1/treatments", treat, carer, 200},
		{"no token", "POST", "/api/v1/entries", entry, "", 401},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			hdr := map[string]string{}
			if c.token != "" {
				hdr["Authorization"] = "Bearer " + c.token
			}
			if got := f.do(t, c.method, c.path, strings.NewReader(c.body), hdr).StatusCode; got != c.want {
				t.Fatalf("status %d, want %d", got, c.want)
			}
		})
	}

	if es, _ := f.store.RecentEntries(context.Background(), "sgv", 10); len(es) != 0 {
		t.Fatalf("forbidden upload was stored: %v", es)
	}
}

func jsonInt(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
