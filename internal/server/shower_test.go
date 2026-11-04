package server

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

// postShower logs a shower that began at "at" and lasted min minutes.
func postShower(t *testing.T, f *fixture, token, id string, at time.Time, min int) {
	t.Helper()
	body := fmt.Sprintf(`[{"_id":%q,"eventType":"Shower","created_at":%q,"duration":%d}]`, id, at.UTC().Format(time.RFC3339), min)
	hdr := map[string]string{"Authorization": "Bearer " + token, "Content-Type": "application/json"}
	if st := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(body), hdr).StatusCode; st != 200 {
		t.Fatalf("shower: status %d", st)
	}
}

type window struct{ From, Until int64 }

// A shower makes the sensor untrusted for its own length and half an hour
// after: in the state while it lasts, on the chart's history for good.
func TestShowerWindow(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	at := testNow.Add(-20 * time.Minute)
	postShower(t, f, dad, "dddddddddddddddddddddddd", at, 15)
	from, until := at.UnixMilli(), at.Add(45*time.Minute).UnixMilli()

	state := func() *window {
		var st struct{ Untrusted *window }
		json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&st)
		return st.Untrusted
	}
	if w := state(); w == nil || w.From != from || w.Until != until {
		t.Fatalf("untrusted now: %+v, want %d..%d", w, from, until)
	}

	var h struct {
		Untrusted  []window
		Treatments []struct {
			EventType string `json:"event_type"`
			Duration  *int
		}
	}
	path := fmt.Sprintf("/api/history?from=%d&to=%d", testNow.Add(-2*time.Hour).UnixMilli(), testNow.UnixMilli())
	json.NewDecoder(f.get(t, path, map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&h)
	if len(h.Untrusted) != 1 || h.Untrusted[0].From != from || h.Untrusted[0].Until != until {
		t.Fatalf("history windows %+v", h.Untrusted)
	}
	if len(h.Treatments) != 1 || h.Treatments[0].EventType != "Shower" || h.Treatments[0].Duration == nil || *h.Treatments[0].Duration != 15 {
		t.Fatalf("history shower %+v", h.Treatments)
	}

	f.advance(26 * time.Minute) // 15 min of shower + 30 after have passed
	if w := state(); w != nil {
		t.Fatalf("still untrusted after the window: %+v", w)
	}
}

// A glucometer check during the window stays out of the sensor-vs-meter model.
func TestMeterLeavesOutChecksAfterAShower(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	upload(t, f, xd, testNow, "Flat", 140, 142, 145, 150, 152, 150, 148, 146, 144, 140, 138, 136, 135)
	postShower(t, f, dad, "dddddddddddddddddddddddd", testNow.Add(-30*time.Minute), 10)
	for _, ago := range []time.Duration{50 * time.Minute, 20 * time.Minute} { // before it, and in it
		f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":160,"date":%d}`, testNow.Add(-ago).UnixMilli())), xdrip(xd))
	}
	var r struct{ Total int }
	json.NewDecoder(f.get(t, "/api/meter", map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&r)
	if r.Total != 1 {
		t.Fatalf("meter pairs %d, want 1: the check after the shower must not count", r.Total)
	}
}

// A rise the sensor shows during the window asks nobody "did you forget to log?".
func TestNoQuestionAfterAShower(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	postShower(t, f, mom, "dddddddddddddddddddddddd", testNow.Add(-20*time.Minute), 10)
	// the same rise asks in TestQuestionNeedsEveryoneToAgree
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("asked after a shower: %+v", qs)
	}
}
