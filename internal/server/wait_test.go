package server

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

func TestWaitClosesForEveryPhone(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	type waitBody struct {
		DoseID     string `json:"dose_id"`
		Closed     bool
		MoreClosed bool `json:"more_closed"`
	}
	wait := func(token string) *waitBody {
		t.Helper()
		var st struct{ Wait *waitBody }
		json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token}).Body).Decode(&st)
		return st.Wait
	}
	closeIt := func(token, id, kind string) int {
		t.Helper()
		return f.do(t, "POST", "/api/wait/"+id+"/close", strings.NewReader(`{"kind":"`+kind+`"}`), map[string]string{"Authorization": "Bearer " + token}).StatusCode
	}

	// food first, the dose 3 min later
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(`{"eventType":"Carb Correction","carbs":40,"created_at":"%s"}`,
		testNow.Add(-8*time.Minute).UTC().Format(time.RFC3339))), xdrip(xd))
	if w := wait(dad); w != nil {
		t.Fatalf("food with no dose has no wait: %+v", w)
	}
	id := dose(t, f, xd, 5*time.Minute, 3, `,"insulinRole":"meal"`)
	if w := wait(mom); w == nil || w.DoseID != id || w.Closed || w.MoreClosed {
		t.Fatalf("food 3 min before the dose opens it: %+v", w)
	}

	if code := closeIt(dad, id, "eaten"); code != 400 {
		t.Fatalf("unknown kind: %d", code)
	}
	if code := closeIt(dad, id, "wait"); code != 204 {
		t.Fatalf("already ate: %d", code)
	}
	if code := closeIt(mom, id, "wait"); code != 204 {
		t.Fatalf("closed twice at once: %d", code)
	}
	if w := wait(mom); w == nil || !w.Closed || w.MoreClosed {
		t.Fatalf("closed for the other phone too, the warning still open: %+v", w)
	}
	closeIt(mom, id, "more")
	if w := wait(dad); w != nil {
		t.Fatalf("both closed: %+v", w)
	}
}

func TestWaitGoesWithItsFood(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	c := openStream(t, f, mom, nil)
	c.next(t, "state")
	type state struct {
		Wait *struct {
			DoseID string `json:"dose_id"`
		}
	}

	id := dose(t, f, xd, 5*time.Minute, 3, `,"insulinRole":"meal"`)
	c.next(t, "treatment")
	var st state
	decodeInto(t, c.next(t, "bg").Data, &st)
	if st.Wait != nil {
		t.Fatalf("a dose alone: %+v", st.Wait)
	}

	resp := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(`{"eventType":"Carb Correction","carbs":40,"created_at":"%s"}`,
		testNow.Add(-3*time.Minute).UTC().Format(time.RFC3339))), xdrip(xd))
	var out []struct {
		ID string `json:"_id"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	if len(out) != 1 {
		t.Fatal("food not saved")
	}
	c.next(t, "treatment")
	decodeInto(t, c.next(t, "bg").Data, &st)
	if st.Wait == nil || st.Wait.DoseID != id {
		t.Fatalf("the food beside the dose opens it at once: %+v", st.Wait)
	}

	f.do(t, "DELETE", "/api/v1/treatments/"+out[0].ID, nil, xdrip(xd))
	c.next(t, "treatment")
	st.Wait = nil
	decodeInto(t, c.next(t, "bg").Data, &st)
	if st.Wait != nil {
		t.Fatalf("the food deleted, the wait goes at once: %+v", st.Wait)
	}
}
