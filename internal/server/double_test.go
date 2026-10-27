package server

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

func dose(t *testing.T, f *fixture, token string, ago time.Duration, units float64, extra string) string {
	t.Helper()
	resp := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(`{"eventType":"Correction Bolus","insulin":%g,"created_at":"%s"%s}`,
		units, testNow.Add(-ago).UTC().Format(time.RFC3339), extra)), xdrip(token))
	var out []struct {
		ID string `json:"_id"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	if len(out) != 1 {
		t.Fatalf("dose not saved")
	}
	return out[0].ID
}

func liveIDs(t *testing.T, f *fixture, token string) []string {
	t.Helper()
	var ts []struct {
		ID string `json:"_id"`
	}
	json.NewDecoder(f.get(t, "/api/v1/treatments", map[string]string{"Authorization": "Bearer " + token}).Body).Decode(&ts)
	var ids []string
	for _, x := range ts {
		ids = append(ids, x.ID)
	}
	return ids
}

func TestDoubleDoseFromXDripAsksEveryone(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	first := dose(t, f, dad, 15*time.Minute, 2, "")
	second := dose(t, f, xd, 8*time.Minute, 2, "")
	qs := questionsOf(t, f, mom)
	if len(qs) != 1 || qs[0].Kind != "double" || len(qs[0].Waiting) != 2 {
		t.Fatalf("2 + 2 units 7 minutes apart, one from xDrip+: %+v", qs)
	}
	// "one injection" from both: the xDrip+ copy goes
	answerQ(t, f, dad, qs[0].ID, "one")
	if code := answerQ(t, f, mom, qs[0].ID, "one"); code != 200 {
		t.Fatalf("answer %d", code)
	}
	ids := liveIDs(t, f, mom)
	if len(ids) != 1 || ids[0] != first {
		t.Fatalf("left %v, want only %s (not %s)", ids, first, second)
	}
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("settled: %+v", qs)
	}
	if code := answerQ(t, f, mom, qs[0].ID, "food"); code != 409 {
		t.Fatalf("closed question answered: %d", code)
	}
}

func TestTwoDosesKeepAWatch(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	dose(t, f, xd, 15*time.Minute, 2, "")
	dose(t, f, xd, 10*time.Minute, 1.5, "")
	qs := questionsOf(t, f, mom)
	if len(qs) != 1 {
		t.Fatalf("%+v", qs)
	}
	if code := answerQ(t, f, mom, qs[0].ID, "food"); code != 400 {
		t.Fatalf("a food answer to a double dose: %d", code)
	}
	answerQ(t, f, mom, qs[0].ID, "two")
	if len(liveIDs(t, f, mom)) != 2 {
		t.Fatal("two doses: both stay")
	}
	qs = questionsOf(t, f, mom)
	if len(qs) != 1 || qs[0].State != "answered" || qs[0].Outcome != "two" {
		t.Fatalf("the watch for a low shows: %+v", qs)
	}
	f.advance(3 * time.Hour)
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("3 h after the second dose the watch ends: %+v", qs)
	}
}

func TestDosesNotAskedAbout(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	// a correction 40 minutes after: normal
	dose(t, f, xd, 60*time.Minute, 2, "")
	dose(t, f, xd, 20*time.Minute, 1, "")
	// two parents' doses: their app asked already
	dose(t, f, dad, 5*time.Hour, 2, "")
	dose(t, f, mom, 5*time.Hour-5*time.Minute, 2, "")
	// a parent saved it after the app warned about the xDrip+ one
	x := dose(t, f, xd, 8*time.Hour, 3, "")
	dose(t, f, mom, 8*time.Hour-10*time.Minute, 1, `,"confirmedNew":"`+x+`"`)
	f.app.Questions.Evaluate(context.Background())
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("nothing to ask: %+v", qs)
	}
}

func TestBasalTwiceInADay(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	basal := func(token string, ago time.Duration) {
		f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(`{"eventType":"Basal Injection","insulin":10,"created_at":"%s"}`,
			testNow.Add(-ago).UTC().Format(time.RFC3339))), xdrip(token))
	}
	basal(mom, 10*time.Hour)
	basal(xd, time.Hour)
	qs := questionsOf(t, f, mom)
	if len(qs) != 1 || qs[0].Kind != "double" {
		t.Fatalf("long-acting 9 hours apart: %+v", qs)
	}
	var st struct {
		Questions []struct {
			Insulin string
			Doses   []struct {
				Units float64
				By    string
			}
		}
	}
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + mom}).Body).Decode(&st)
	q := st.Questions[0]
	if q.Insulin != "basal" || len(q.Doses) != 2 || q.Doses[0].By != "xDrip+" || q.Doses[1].By != "Мама" {
		t.Fatalf("described %+v", q)
	}
}

func TestInsulinIntentIsShared(t *testing.T) {
	f := newFixture(t)
	dadID, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	h := func(tok string) map[string]string {
		return map[string]string{"Authorization": "Bearer " + tok, "Content-Type": "application/json"}
	}
	type intent struct {
		DeviceID int64 `json:"device_id"`
		Person   string
		Insulin  string
	}
	intents := func() []intent {
		var st struct{ Intents []intent }
		json.NewDecoder(f.get(t, "/api/state", h(mom)).Body).Decode(&st)
		return st.Intents
	}
	if code := f.do(t, "POST", "/api/insulin/intent", strings.NewReader(`{"insulin":"rapid"}`), h(dad)).StatusCode; code != 204 {
		t.Fatalf("intent %d", code)
	}
	if in := intents(); len(in) != 1 || in[0].DeviceID != dadID || in[0].Person != "Тато" || in[0].Insulin != "rapid" {
		t.Fatalf("mom sees %+v", in)
	}
	f.advance(11 * time.Minute)
	if in := intents(); len(in) != 0 {
		t.Fatalf("a forgotten form ends by itself: %+v", in)
	}
	f.do(t, "POST", "/api/insulin/intent", strings.NewReader(`{"insulin":"basal"}`), h(dad))
	f.do(t, "DELETE", "/api/insulin/intent", nil, h(dad))
	if in := intents(); len(in) != 0 {
		t.Fatalf("closed form: %+v", in)
	}
}
