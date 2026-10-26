package server

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/alarms"
	"dialife/internal/auth"
)

type alarmPayload struct {
	Alarm struct {
		ID             int64  `json:"id"`
		Kind           string `json:"kind"`
		Level          string `json:"level"`
		State          string `json:"state"`
		SGV            int    `json:"sgv"`
		HandledBy      string `json:"handled_by"`
		HandledByID    int64  `json:"handled_by_id"`
		QuietUntil     *int64 `json:"quiet_until"`
		CheckinPending bool   `json:"checkin_pending"`
	} `json:"alarm"`
	Reason string `json:"reason"`
	By     string `json:"by"`
}

func TestLowAlarmReachesBothParentsAndHandleSilencesBoth(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	momStream, dadStream := openStream(t, f, mom, nil), openStream(t, f, dad, nil)
	momStream.next(t, "state")
	dadStream.next(t, "state")

	upload(t, f, xd, testNow.Add(-time.Minute), "FortyFiveDown", 75, 68)

	var fired alarmPayload
	for _, s := range []*sseClient{momStream, dadStream} {
		s.next(t, "history") // the older reading of the pair
		s.next(t, "bg")
		decodeInto(t, s.next(t, "alarm").Data, &fired)
		if fired.Alarm.Kind != "low" || fired.Alarm.Level != "warn" || fired.Alarm.SGV != 68 || fired.Reason != "new" || fired.Alarm.State != "firing" {
			t.Fatalf("alarm %+v", fired)
		}
	}

	resp := f.do(t, "POST", fmt.Sprintf("/api/alarms/%d/handle", fired.Alarm.ID), nil,
		map[string]string{"Authorization": "Bearer " + dad})
	if resp.StatusCode != 200 {
		t.Fatalf("handle: status %d", resp.StatusCode)
	}

	var handled alarmPayload
	decodeInto(t, momStream.next(t, "handled").Data, &handled)
	if handled.By != "Тато" || handled.Alarm.State != "handled" || handled.Alarm.HandledBy != "Тато" {
		t.Fatalf("mom sees %+v", handled)
	}
	dadStream.next(t, "handled")

	st := getStateRaw(t, f, mom)
	if !strings.Contains(st, `"handled_by":"Тато"`) || !strings.Contains(st, `"state":"handled"`) {
		t.Fatalf("state lacks the handled episode: %s", st)
	}

	// still low, being handled: the next reading is quiet for both
	upload(t, f, xd, testNow.Add(-time.Minute+time.Second), "Flat", 67)
	momStream.next(t, "bg")
	momStream.expectQuiet(t)
}

func TestBackfilledLowsDoNotAlarm(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 120)
	s := openStream(t, f, xd, nil)
	s.next(t, "state")

	// two hours ago she was low; that is history now, not an emergency
	upload(t, f, xd, testNow.Add(-2*time.Hour), "Flat", 60, 50, 45, 52, 64)
	s.next(t, "history")
	s.expectQuiet(t)
	if st := getStateRaw(t, f, xd); !strings.Contains(st, `"alarms":[]`) {
		t.Fatalf("alarms after backfill: %s", st)
	}
}

func TestAlarmActionsOverHTTP(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, panel := f.addDevice(t, "panel", auth.RoleReader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	s := openStream(t, f, mom, nil)
	s.next(t, "state")
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 50)
	s.next(t, "bg")
	var a alarmPayload
	decodeInto(t, s.next(t, "alarm").Data, &a)
	base := fmt.Sprintf("/api/alarms/%d", a.Alarm.ID)

	cases := []struct {
		name, path, body, token string
		want                    int
	}{
		{"reader cannot handle", base + "/handle", "", panel, 403},
		{"uploader cannot handle", base + "/handle", "", xd, 403},
		{"no token", base + "/handle", "", "", 401},
		{"unknown alarm", "/api/alarms/999/handle", "", mom, 404},
		{"bad id", "/api/alarms/abc/handle", "", mom, 400},
		{"bad body", base + "/handle", "{", mom, 400},
		{"negative quiet", base + "/handle", `{"quiet_min":-5}`, mom, 400},
		{"no quiet for an urgent low", base + "/handle", `{"quiet_min":120}`, mom, 409},
		{"checkin needs an answer", base + "/checkin", `{"answer":"maybe"}`, mom, 400},
		{"older /ack still takes it", base + "/ack", "", mom, 200},
		{"checkin ok", base + "/checkin", `{"answer":"ok"}`, mom, 200},
		{"help", base + "/checkin", `{"answer":"help"}`, mom, 200},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			hdr := map[string]string{}
			if c.token != "" {
				hdr["Authorization"] = "Bearer " + c.token
			}
			if got := f.do(t, "POST", c.path, strings.NewReader(c.body), hdr).StatusCode; got != c.want {
				t.Fatalf("status %d, want %d", got, c.want)
			}
		})
	}
	s.next(t, "handled")
	s.next(t, "checkin_ok")
	if ev := s.next(t, "help"); !strings.Contains(ev.Data, `"by":"Мама"`) {
		t.Fatalf("help event %s", ev.Data)
	}

	resp := f.do(t, "POST", base+"/resolve", nil, map[string]string{"Authorization": "Bearer " + mom})
	var res struct {
		Alarm   struct{ State string } `json:"alarm"`
		Warning string                 `json:"warning"`
	}
	json.NewDecoder(resp.Body).Decode(&res)
	if resp.StatusCode != 200 || res.Alarm.State != "cleared" || res.Warning == "" {
		t.Fatalf("resolve: %d %+v", resp.StatusCode, res)
	}
	if ev := s.next(t, "clear"); !strings.Contains(ev.Data, `"manual":true`) {
		t.Fatalf("clear event %s", ev.Data)
	}
	if got := f.do(t, "POST", base+"/resolve", nil, map[string]string{"Authorization": "Bearer " + mom}).StatusCode; got != 404 {
		t.Fatalf("second resolve: %d", got)
	}

	// the log tells the whole episode, step by step
	resp = f.do(t, "GET", "/api/alarms/log?days=1", nil, map[string]string{"Authorization": "Bearer " + panel})
	var log struct {
		Episodes []struct {
			ID         int64  `json:"id"`
			State      string `json:"state"`
			Manual     bool   `json:"manual"`
			ResolvedBy string `json:"resolved_by"`
			Events     []struct {
				Type string `json:"type"`
				By   string `json:"by"`
			} `json:"events"`
		} `json:"episodes"`
	}
	json.NewDecoder(resp.Body).Decode(&log)
	if resp.StatusCode != 200 || len(log.Episodes) != 1 {
		t.Fatalf("log: %d %+v", resp.StatusCode, log)
	}
	ep := log.Episodes[0]
	var steps []string
	for _, e := range ep.Events {
		steps = append(steps, e.Type+":"+e.By)
	}
	want := "alarm: handled:Мама checkin_ok:Мама help:Мама clear:"
	if ep.ID != a.Alarm.ID || ep.State != "cleared" || !ep.Manual || ep.ResolvedBy != "Мама" || strings.Join(steps, " ") != want {
		t.Fatalf("log episode %+v\nsteps %v", ep, steps)
	}
}

func getStateRaw(t *testing.T, f *fixture, token string) string {
	t.Helper()
	resp := f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token})
	var b strings.Builder
	buf := make([]byte, 4096)
	for {
		n, err := resp.Body.Read(buf)
		b.Write(buf[:n])
		if err != nil {
			break
		}
	}
	return b.String()
}

func TestHouseholdSettingsOverHTTP(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	st, _ := alarms.ParseAssignments(alarms.SettingsOf(alarms.DefaultRules),
		[]string{"low=80", "high=250", "urgent_high=300", "high_for_min=30", "predicted_high=off"})
	if err := alarms.SaveSettings(context.Background(), f.store, st, testNow); err != nil {
		t.Fatal(err)
	}

	raw := getStateRaw(t, f, mom)
	if !strings.Contains(raw, `"thresholds":{"bgLow":55,"bgTargetBottom":80,"bgTargetTop":250,"bgHigh":300}`) {
		t.Fatalf("state thresholds: %s", raw)
	}
	resp := f.get(t, "/api/v1/status.json", xdrip(xd))
	var status struct {
		Settings struct{ Thresholds map[string]int } `json:"settings"`
	}
	json.NewDecoder(resp.Body).Decode(&status)
	if status.Settings.Thresholds["bgTargetTop"] != 250 {
		t.Fatalf("status.json thresholds %+v", status.Settings.Thresholds)
	}

	s := openStream(t, f, mom, nil)
	s.next(t, "state")
	// a post-meal peak above 250 for only ~15 minutes: data flows, no alarm
	// (the meal is logged, so no "did you forget?" question either)
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Meal Bolus","carbs":40,"created_at":"`+
		testNow.Add(-40*time.Minute).UTC().Format(time.RFC3339)+`"}`), xdrip(xd))
	s.next(t, "treatment")
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 200, 220, 240, 255, 268, 281)
	s.next(t, "history")
	s.next(t, "bg")
	s.expectQuiet(t)
}

func TestSustainedHighOverHTTP(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	st, _ := alarms.ParseAssignments(alarms.SettingsOf(alarms.DefaultRules), []string{"low=80", "high=250", "urgent_high=300", "high_for_min=30"})
	alarms.SaveSettings(context.Background(), f.store, st, testNow)

	s := openStream(t, f, mom, nil)
	s.next(t, "state")
	// above 250 for 35 minutes
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 240, 255, 262, 270, 268, 271, 266, 259)
	s.next(t, "history")
	s.next(t, "bg")
	var a alarmPayload
	decodeInto(t, s.next(t, "alarm").Data, &a)
	if a.Alarm.Kind != "high" || a.Alarm.Level != "warn" {
		t.Fatalf("sustained high %+v", a)
	}
}
