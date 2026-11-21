package server

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
	"dialife/internal/notify"
)

type sensorlessBody struct {
	Since     int64
	By        string
	LastCheck *struct{ T, MG int64 } `json:"last_check"`
	RemindAt  int64                  `json:"remind_at"`
	RemindDue bool                   `json:"remind_due"`
	Card      *struct {
		T, MG int64
		Kind  string
	}
}

type sensorlessState struct {
	Sensorless         *sensorlessBody
	SensorlessSettings struct {
		RemindMin int `json:"remind_min"`
	} `json:"sensorless_settings"`
	Alarms []struct{ Kind string }
}

func sensorlessOf(t *testing.T, f *fixture, token string) sensorlessState {
	t.Helper()
	var st sensorlessState
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token}).Body).Decode(&st)
	return st
}

func setSensorless(t *testing.T, f *fixture, token string, on bool) int {
	t.Helper()
	return f.do(t, "PUT", "/api/sensorless", strings.NewReader(fmt.Sprintf(`{"on":%v}`, on)),
		map[string]string{"Authorization": "Bearer " + token}).StatusCode
}

// a glucometer check logged in the app
func postCheck(t *testing.T, f *fixture, token, id string, at time.Time, mg int) {
	t.Helper()
	body := fmt.Sprintf(`[{"_id":%q,"eventType":"BG Check","created_at":%q,"glucose":%d,"units":"mg/dl"}]`, id, at.UTC().Format(time.RFC3339), mg)
	hdr := map[string]string{"Authorization": "Bearer " + token, "Content-Type": "application/json"}
	if st := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(body), hdr).StatusCode; st != 200 {
		t.Fatalf("check: status %d", st)
	}
}

// The sensor ran out: its "no data" alarm ends when a parent turns the mode
// on, and nothing alarms however long the silence lasts.
func TestSensorlessSilencesAlarms(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	upload(t, f, xd, testNow.Add(-40*time.Minute), "Flat", 120, 121)
	ctx := context.Background()
	if err := f.app.Alarms.Evaluate(ctx); err != nil {
		t.Fatal(err)
	}
	if st := sensorlessOf(t, f, dad); len(st.Alarms) != 1 || st.Alarms[0].Kind != "stale" {
		t.Fatalf("before: alarms %+v, want the stale one", st.Alarms)
	}

	if code := setSensorless(t, f, dad, true); code != 200 {
		t.Fatalf("on: status %d", code)
	}
	st := sensorlessOf(t, f, dad)
	if len(st.Alarms) != 0 {
		t.Fatalf("alarms still live in the mode: %+v", st.Alarms)
	}
	if st.Sensorless == nil || st.Sensorless.Since != testNow.UnixMilli() || st.Sensorless.By != "Тато" {
		t.Fatalf("mode state %+v", st.Sensorless)
	}
	if st.SensorlessSettings.RemindMin != 180 {
		t.Fatalf("default reminder %d min, want 180", st.SensorlessSettings.RemindMin)
	}
	f.advance(6 * time.Hour)
	if err := f.app.Alarms.Evaluate(ctx); err != nil {
		t.Fatal(err)
	}
	if st := sensorlessOf(t, f, dad); len(st.Alarms) != 0 {
		t.Fatalf("an alarm in the mode: %+v", st.Alarms)
	}

	// off by hand with still no sensor: "no data" rings at once
	if code := setSensorless(t, f, dad, false); code != 200 {
		t.Fatalf("off: status %d", code)
	}
	if st := sensorlessOf(t, f, dad); st.Sensorless != nil || len(st.Alarms) != 1 || st.Alarms[0].Kind != "stale" {
		t.Fatalf("after off: %+v", st)
	}
}

// While a sensor is sending the mode makes no sense: refused.
func TestSensorlessRefusedWhileTheSensorSends(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 120, 121)
	if code := setSensorless(t, f, mom, true); code != 409 {
		t.Fatalf("on with a sensor sending: status %d, want 409", code)
	}
	if code := f.do(t, "PUT", "/api/sensorless", strings.NewReader(`{}`), map[string]string{"Authorization": "Bearer " + mom}).StatusCode; code != 400 {
		t.Fatalf("no \"on\": status %d", code)
	}
}

// A check out of range brings a card for everyone until someone closes it;
// the same reading arriving again from xDrip+ does not bring it back.
func TestSensorlessCheckCard(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	postCheck(t, f, dad, "c00000000000000000000001", testNow.Add(-time.Hour), 55) // before the mode: no card
	setSensorless(t, f, dad, true)
	if st := sensorlessOf(t, f, mom); st.Sensorless.Card != nil || st.Sensorless.LastCheck == nil || st.Sensorless.LastCheck.MG != 55 {
		t.Fatalf("a check from before the mode: %+v", st.Sensorless)
	}

	f.advance(time.Hour)
	at := f.now().Add(-time.Minute)
	postCheck(t, f, dad, "c00000000000000000000002", at, 62)
	st := sensorlessOf(t, f, mom)
	if c := st.Sensorless.Card; c == nil || c.Kind != "low" || c.MG != 62 || c.T != at.UnixMilli() {
		t.Fatalf("card %+v", st.Sensorless.Card)
	}
	if code := f.do(t, "POST", "/api/sensorless/checks/123/close", nil, map[string]string{"Authorization": "Bearer " + mom}).StatusCode; code != 404 {
		t.Fatalf("closing a check that is not the card: status %d", code)
	}
	path := fmt.Sprintf("/api/sensorless/checks/%d/close", at.UnixMilli())
	if code := f.do(t, "POST", path, nil, map[string]string{"Authorization": "Bearer " + mom}).StatusCode; code != 204 {
		t.Fatalf("close: status %d", code)
	}
	if st := sensorlessOf(t, f, dad); st.Sensorless.Card != nil {
		t.Fatalf("card still on dad's phone: %+v", st.Sensorless.Card)
	}
	// xDrip+ echoes it back as a meter entry half a minute later
	f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":62,"date":%d}`, at.Add(30*time.Second).UnixMilli())), xdrip(xd))
	if st := sensorlessOf(t, f, dad); st.Sensorless.Card != nil {
		t.Fatalf("the echo brought the card back: %+v", st.Sensorless.Card)
	}

	f.advance(time.Minute)
	postCheck(t, f, dad, "c00000000000000000000003", f.now(), 250)
	if c := sensorlessOf(t, f, dad).Sensorless.Card; c == nil || c.Kind != "high" {
		t.Fatalf("high check: card %+v", c)
	}
	f.advance(time.Minute)
	postCheck(t, f, dad, "c00000000000000000000004", f.now(), 130)
	if c := sensorlessOf(t, f, dad).Sensorless.Card; c != nil {
		t.Fatalf("in range now, card %+v", c)
	}
}

// After the set time without a check every phone is reminded once; the next
// check takes the reminder away. A sensor sending again ends the mode by
// itself and says so.
func TestSensorlessReminderAndSensorBack(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	fake := newFakeNtfy(t)
	startNtfy(t, f, fake, []notify.Target{{Topic: "dad", DeviceID: dadID}})
	ctx := context.Background()

	if code := f.do(t, "PUT", "/api/sensorless/settings", strings.NewReader(`{"remind_min":45}`), map[string]string{"Authorization": "Bearer " + dad}).StatusCode; code != 400 {
		t.Fatalf("45 min: status %d, want 400", code)
	}
	if code := f.do(t, "PUT", "/api/sensorless/settings", strings.NewReader(`{"remind_min":120}`), map[string]string{"Authorization": "Bearer " + dad}).StatusCode; code != 200 {
		t.Fatalf("settings: status %d", code)
	}
	setSensorless(t, f, dad, true)
	if st := sensorlessOf(t, f, dad); st.Sensorless.RemindAt != testNow.Add(2*time.Hour).UnixMilli() || st.Sensorless.RemindDue {
		t.Fatalf("reminder %+v", st.Sensorless)
	}

	f.advance(2 * time.Hour)
	f.app.Sensorless.Tick(ctx)
	f.app.Sensorless.Tick(ctx) // once only
	m := fake.wait(t, 1)
	if m[0].Title != "Час виміряти цукор з пальця" || !strings.Contains(m[0].Message, "замірів з пальця не було") {
		t.Fatalf("reminder %+v", m[0])
	}
	if n := fake.settle(); n != 1 {
		t.Fatalf("%d messages, want 1", n)
	}
	if !sensorlessOf(t, f, dad).Sensorless.RemindDue {
		t.Fatal("reminder not due in the state")
	}

	postCheck(t, f, dad, "c00000000000000000000001", f.now(), 140)
	if st := sensorlessOf(t, f, dad); st.Sensorless.RemindDue || st.Sensorless.RemindAt != f.now().Add(2*time.Hour).UnixMilli() {
		t.Fatalf("after a check: %+v", st.Sensorless)
	}
	waitFor(t, func() bool { return strings.Contains(strings.Join(fake.clears(), " "), "sensorless-remind") })

	// a new sensor: one reading is not enough, two are
	f.advance(10 * time.Minute)
	upload(t, f, xd, f.now(), "Flat", 130)
	if sensorlessOf(t, f, dad).Sensorless == nil {
		t.Fatal("one reading ended the mode")
	}
	f.advance(5 * time.Minute)
	upload(t, f, xd, f.now(), "Flat", 132)
	if st := sensorlessOf(t, f, dad); st.Sensorless != nil {
		t.Fatalf("still on with the sensor sending: %+v", st.Sensorless)
	}
	m = fake.wait(t, 2)
	if m[1].Title != "Сенсор знову надсилає дані" {
		t.Fatalf("sensor back %+v", m[1])
	}

	var h struct {
		Sensorless []struct {
			From, Until int64
			End         string
		}
	}
	path := fmt.Sprintf("/api/history?from=%d&to=%d", testNow.Add(-time.Hour).UnixMilli(), f.now().UnixMilli())
	json.NewDecoder(f.get(t, path, map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&h)
	if len(h.Sensorless) != 1 || h.Sensorless[0].From != testNow.UnixMilli() || h.Sensorless[0].Until != f.now().UnixMilli() || h.Sensorless[0].End != "sensor" {
		t.Fatalf("history %+v", h.Sensorless)
	}
}

// A rise is no question in the mode (readings from before it began).
func TestNoQuestionInSensorless(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	setSensorless(t, f, mom, true)
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("asked in the mode: %+v", qs)
	}
}

func waitFor(t *testing.T, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
