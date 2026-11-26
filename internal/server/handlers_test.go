package server

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

// Help asked: another parent answers "I'm coming", everyone hears who; the handler cannot "come" to their own call.
func TestComingToAHelpRequest(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	s := openStream(t, f, dad, nil)
	s.next(t, "state")
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 50)
	s.next(t, "bg")
	var a alarmPayload
	decodeInto(t, s.next(t, "alarm").Data, &a)
	base := fmt.Sprintf("/api/alarms/%d", a.Alarm.ID)
	as := func(token string) map[string]string { return map[string]string{"Authorization": "Bearer " + token} }

	f.do(t, "POST", base+"/handle", nil, as(mom))
	s.next(t, "handled")
	f.do(t, "POST", base+"/checkin", strings.NewReader(`{"answer":"help"}`), as(mom))
	s.next(t, "help")
	if st := f.do(t, "POST", base+"/coming", nil, as(dad)).StatusCode; st != 200 {
		t.Fatalf("coming %d", st)
	}
	if ev := s.next(t, "coming"); !strings.Contains(ev.Data, `"by":"Тато"`) {
		t.Fatalf("coming event %s", ev.Data)
	}
	if st := f.do(t, "POST", "/api/alarms/999/coming", nil, as(dad)).StatusCode; st != 404 {
		t.Fatalf("coming to no alarm: %d", st)
	}
}

// A parent declines a browser: it hears so, and the code cannot be allowed afterwards.
func TestWebLoginDeclined(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	browser := map[string]string{"Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"}
	var start struct{ ID, Secret string }
	json.NewDecoder(f.do(t, "POST", "/api/web/login", nil, browser).Body).Decode(&start)
	code := f.app.webCode(t, start.ID)
	dadHdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	body := `{"id":"` + start.ID + `","code":"` + code + `"}`
	if st := f.do(t, "POST", "/api/web/reject", strings.NewReader(body), dadHdr).StatusCode; st != 204 {
		t.Fatalf("reject %d", st)
	}
	var m map[string]any
	json.NewDecoder(f.do(t, "POST", "/api/web/login/"+start.ID+"/poll", strings.NewReader(`{"secret":"`+start.Secret+`"}`), browser).Body).Decode(&m)
	if m["state"] != "rejected" {
		t.Fatalf("poll after reject: %v", m)
	}
	approve := `{"id":"` + start.ID + `","code":"` + code + `","minutes":90}`
	if st := f.do(t, "POST", "/api/web/approve", strings.NewReader(approve), dadHdr).StatusCode; st == 204 {
		t.Fatal("a declined code allowed after all")
	}
}

// Sensor vs glucometer: a check typed in the app and one from xDrip+ (the same value twice) pair with the sensor around them.
func TestMeterReport(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	upload(t, f, xd, testNow, "Flat", 140, 142, 145, 150, 152, 150, 148, 146, 144, 140, 138, 136, 135)
	hdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`[{"_id":"cccccccccccccccccccccccc","eventType":"BG Check","created_at":"`+
		testNow.Add(-50*time.Minute).UTC().Format(time.RFC3339)+`","glucose":8,"glucoseType":"Finger","units":"mmol"}]`), hdr)
	for range 2 {
		f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":160,"date":%d}`, testNow.Add(-20*time.Minute).UnixMilli())), xdrip(xd))
	}
	resp := f.get(t, "/api/meter", map[string]string{"Authorization": "Bearer " + dad})
	var r struct {
		Ready bool `json:"ready"`
		Need  int  `json:"need"`
		Total int  `json:"total"`
	}
	json.NewDecoder(resp.Body).Decode(&r)
	// two checks of the current stretch (the repeat counted once) are enough for the estimate
	if resp.StatusCode != 200 || r.Total != 2 || !r.Ready || r.Need != 2 {
		t.Fatalf("meter %d %+v", resp.StatusCode, r)
	}
	if st := f.get(t, "/api/meter", nil).StatusCode; st != 401 {
		t.Fatalf("no token: %d", st)
	}
}
