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

type doseBody struct {
	Ratio    float64
	Rule     string
	Step     float64
	FreshMin int     `json:"fresh_min"`
	MaxRapid float64 `json:"max_rapid"`
	Table    []struct {
		MG int
		U  float64
	}
	UpdatedBy string `json:"updated_by"`
}

type plannedBody struct {
	ID     string
	MealID string `json:"meal_id"`
	Dose   float64
	By     string
	State  string
}

func doseState(t *testing.T, f *fixture, token string) (doseBody, []plannedBody, []questionView) {
	t.Helper()
	var st struct {
		Dose      doseBody
		Planned   []plannedBody
		Questions []questionView
	}
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token}).Body).Decode(&st)
	return st.Dose, st.Planned, st.Questions
}

func TestDoseSettingsAreTheFamilys(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}

	d, _, _ := doseState(t, f, dad)
	if d.Ratio != 20 || d.Rule != "halfDown" || d.Step != 1 || d.FreshMin != 15 || len(d.Table) != 5 || d.Table[3].MG != 260 || d.Table[3].U != 4 {
		t.Fatalf("defaults: %+v", d)
	}
	body := `{"ratio":15,"rule":"nearest","step":0.5,"fresh_min":10,"max_rapid":8,"max_basal":20,"max_carbs":120,"table":[{"mg":200,"u":2},{"mg":150,"u":1}]}`
	if code := f.do(t, "PUT", "/api/dose", strings.NewReader(body), h).StatusCode; code != 200 {
		t.Fatalf("put: %d", code)
	}
	d, _, _ = doseState(t, f, dad)
	if d.Ratio != 15 || d.Rule != "nearest" || d.Step != 0.5 || d.UpdatedBy != "Мама" || len(d.Table) != 2 || d.Table[0].MG != 150 {
		t.Fatalf("the other phone sees mom's change, the table sorted: %+v", d)
	}
	for _, bad := range []string{
		strings.Replace(body, `"nearest"`, `"sometimes"`, 1),
		strings.Replace(body, `"step":0.5`, `"step":2`, 1),
		strings.Replace(body, `{"mg":150,"u":1}`, `{"mg":200,"u":1}`, 1), // the same threshold twice
		strings.Replace(body, `"max_rapid":8`, `"max_rapid":0`, 1),
	} {
		if code := f.do(t, "PUT", "/api/dose", strings.NewReader(bad), h).StatusCode; code != 400 {
			t.Fatalf("%s: %d", bad, code)
		}
	}
	if code := f.do(t, "PUT", "/api/dose", strings.NewReader(body), xdrip(xd)).StatusCode; code != 403 {
		t.Fatalf("uploader: %d", code)
	}
}

func TestPlannedDoseIsAskedAboutAndSettled(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	momID, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	steady := make([]int, 20)
	for i := range steady {
		steady[i] = 150
	}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", steady...)
	mealAt := testNow.Add(-5 * time.Minute)
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"_id":"0123456789abcdef01234567","eventType":"Carb Correction","carbs":59,"created_at":"`+
		mealAt.UTC().Format(time.RFC3339)+`"}`), h)
	plan := func(dose float64) int {
		return f.do(t, "POST", "/api/planned", strings.NewReader(fmt.Sprintf(`{"id":"p1","meal_id":"0123456789abcdef01234567","dose":%v,"food":2.95,"corr":1,"carbs":59,"meter":186,"meter_at":%d,"created_at":%d}`,
			dose, testNow.Add(-6*time.Minute).UnixMilli(), testNow.UnixMilli())), h).StatusCode
	}
	if code := plan(3); code != 204 {
		t.Fatalf("plan: %d", code)
	}
	if code := plan(4); code != 204 {
		t.Fatalf("changed while planned: %d", code)
	}
	_, ps, _ := doseState(t, f, dad)
	if len(ps) != 1 || ps[0].Dose != 4 || ps[0].By != "Мама" || ps[0].MealID != "0123456789abcdef01234567" || ps[0].State != "planned" {
		t.Fatalf("dad's phone sees it: %+v", ps)
	}

	// an hour later still not given: asked, instead of "a meal without insulin"
	f.advance(61 * time.Minute)
	if err := f.app.Questions.Evaluate(context.Background()); err != nil {
		t.Fatal(err)
	}
	var st struct {
		Questions []struct {
			ID        int64
			Kind      string
			From      int64
			PlannedID string `json:"planned_id"`
			Dose      float64
		}
	}
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&st)
	if len(st.Questions) != 1 || st.Questions[0].Kind != "planned" || st.Questions[0].PlannedID != "p1" || st.Questions[0].Dose != 4 || st.Questions[0].From != mealAt.UnixMilli() {
		t.Fatalf("%+v", st.Questions)
	}
	if code := answerQ(t, f, dad, st.Questions[0].ID, "food"); code != 400 {
		t.Fatalf("a planned dose is settled by the dose itself: %d", code)
	}

	// mom confirms the injection: the question and the card go everywhere
	resp := f.do(t, "POST", "/api/planned/p1/given", strings.NewReader(`{"insulin_id":"ins1"}`), h)
	var got plannedBody
	json.NewDecoder(resp.Body).Decode(&got)
	if resp.StatusCode != 200 || got.State != "given" {
		t.Fatalf("given: %d %+v", resp.StatusCode, got)
	}
	_, ps, qs := doseState(t, f, dad)
	if len(ps) != 0 || len(qs) != 0 {
		t.Fatalf("settled: %+v %+v", ps, qs)
	}
	q, err := f.store.Question(context.Background(), st.Questions[0].ID)
	if err != nil || q.Outcome != "given" || q.TreatmentID != "ins1" || q.ClosedBy != "Мама" {
		t.Fatalf("%+v %v", q, err)
	}
	// dad confirms too, a moment late: nothing changes
	resp = f.do(t, "POST", "/api/planned/p1/skip", nil, map[string]string{"Authorization": "Bearer " + dad})
	json.NewDecoder(resp.Body).Decode(&got)
	if resp.StatusCode != 200 || got.State != "given" {
		t.Fatalf("late skip: %d %+v", resp.StatusCode, got)
	}
	if code := plan(5); code != 409 {
		t.Fatalf("a given dose cannot change: %d", code)
	}
	if code := f.do(t, "POST", "/api/planned/nope/given", nil, h).StatusCode; code != 404 {
		t.Fatalf("unknown: %d", code)
	}
	p, _ := f.store.PlannedDose(context.Background(), "p1")
	if p.ClosedBy == nil || *p.ClosedBy != momID {
		t.Fatalf("closed by %+v", p.ClosedBy)
	}
}

func TestPlannedDoseExpires(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/planned", strings.NewReader(fmt.Sprintf(`{"id":"p2","meal_id":"m","dose":2,"food":2,"corr":0,"carbs":40,"created_at":%d}`, testNow.UnixMilli())), h)
	f.advance(3*time.Hour + time.Minute)
	if _, ps, _ := doseState(t, f, mom); len(ps) != 0 {
		t.Fatalf("past three hours it is no longer shown: %+v", ps)
	}
	if err := f.app.Questions.Evaluate(context.Background()); err != nil {
		t.Fatal(err)
	}
	if p, err := f.store.PlannedDose(context.Background(), "p2"); err != nil || p.State != "expired" {
		t.Fatalf("%+v %v", p, err)
	}
	if code := f.do(t, "POST", "/api/planned", strings.NewReader(`{"id":"","meal_id":"m","dose":2,"created_at":1}`), h).StatusCode; code != 400 {
		t.Fatalf("no id: %d", code)
	}
}

func TestInsulinKeepsItsMealAndRole(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Meal Bolus","insulin":3,"mealId":"meal1","insulinRole":"meal","created_at":"`+
		testNow.Add(-time.Hour).UTC().Format(time.RFC3339)+`"}`), h)
	var hist struct {
		Treatments []struct {
			MealID      string `json:"meal_id"`
			InsulinRole string `json:"insulin_role"`
		}
	}
	json.NewDecoder(f.get(t, "/api/history", h).Body).Decode(&hist)
	if len(hist.Treatments) != 1 || hist.Treatments[0].MealID != "meal1" || hist.Treatments[0].InsulinRole != "meal" {
		t.Fatalf("%+v", hist)
	}
}

// a rise answered "asleep": the next rise within two hours of its start asks nothing
func TestAsleepSilencesRisesForTwoHours(t *testing.T) {
	second := func(answer string) []questionView {
		f := newFixture(t)
		_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
		_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
		upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)
		qs := questionsOf(t, f, mom)
		if len(qs) != 1 || qs[0].Kind != "rise" {
			t.Fatalf("first rise: %+v", qs)
		}
		if code := answerQ(t, f, mom, qs[0].ID, answer); code != 200 {
			t.Fatalf("%s: %d", answer, code)
		}
		f.advance(80 * time.Minute)
		upload(t, f, xd, testNow.Add(79*time.Minute), "SingleUp", 168, 163, 158, 152, 147, 143, 140, 140, 141, 150, 162, 175, 188, 201, 214, 227)
		f.app.Questions.Evaluate(context.Background())
		return questionsOf(t, f, mom)
	}
	if qs := second("nothing"); len(qs) != 1 || qs[0].Kind != "rise" {
		t.Fatalf("awake, the second rise is asked about: %+v", qs)
	}
	if qs := second("asleep"); len(qs) != 0 {
		t.Fatalf("asleep: %+v", qs)
	}
}
