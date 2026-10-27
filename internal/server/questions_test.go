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

type questionView struct {
	ID         int64
	Kind       string
	State      string
	Round      int
	Outcome    string
	Waiting    []string
	WaitingIDs []int64 `json:"waiting_ids"`
	Answers    []struct{ Person, Answer string }
}

func questionsOf(t *testing.T, f *fixture, token string) []questionView {
	t.Helper()
	var st struct{ Questions []questionView }
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token}).Body).Decode(&st)
	return st.Questions
}

func answerQ(t *testing.T, f *fixture, token string, id int64, answer string) int {
	t.Helper()
	return f.do(t, "POST", fmt.Sprintf("/api/questions/%d/answer", id), strings.NewReader(`{"answer":"`+answer+`"}`),
		map[string]string{"Authorization": "Bearer " + token, "Content-Type": "application/json"}).StatusCode
}

func TestQuestionNeedsEveryoneToAgree(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	dad2ID, dad2 := f.addDevice(t, "tato", auth.RoleCaregiver)
	momID, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	if err := f.store.SetPerson(context.Background(), dad2ID, "Тато"); err != nil {
		t.Fatal(err)
	}
	// a rise with no food logged
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)
	qs := questionsOf(t, f, mom)
	if len(qs) != 1 || qs[0].Kind != "rise" || len(qs[0].Waiting) != 2 || len(qs[0].WaitingIDs) != 3 {
		t.Fatalf("asked %+v (dad has two phones, one voice)", qs)
	}
	id := qs[0].ID
	_ = dadID

	// dad (from his second phone) and mom disagree: both are asked again
	if code := answerQ(t, f, dad2, id, "nothing"); code != 200 {
		t.Fatalf("answer %d", code)
	}
	if q := questionsOf(t, f, mom)[0]; len(q.Waiting) != 1 || q.Waiting[0] != "Мама" || len(q.WaitingIDs) != 1 || q.WaitingIDs[0] != momID {
		t.Fatalf("after dad %+v", q)
	}
	answerQ(t, f, mom, id, "food_unknown")
	q := questionsOf(t, f, mom)[0]
	if q.State != "open" || q.Round != 2 || len(q.Waiting) != 2 {
		t.Fatalf("disagreement must ask everyone again: %+v", q)
	}
	// dad gives in from his first phone, mom keeps hers: settled
	answerQ(t, f, dad, id, "food_unknown")
	answerQ(t, f, mom, id, "food_unknown")
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("answered question must leave the state: %+v", qs)
	}
	if code := answerQ(t, f, mom, id, "nothing"); code != 409 {
		t.Fatalf("answer to a closed question: %d", code)
	}
}

func TestUnansweredBecomesSuspectAnyoneClears(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleDown", 220, 222, 218, 208, 196, 184, 172, 162)
	qs := questionsOf(t, f, dad)
	if len(qs) != 1 || qs[0].Kind != "fall" {
		t.Fatalf("%+v", qs)
	}
	f.advance(12*time.Hour + time.Minute)
	if err := f.app.Questions.Evaluate(context.Background()); err != nil {
		t.Fatal(err)
	}
	if q := questionsOf(t, f, dad)[0]; q.State != "unknown" {
		t.Fatalf("after 12 h: %+v", q)
	}
	resp := f.do(t, "POST", fmt.Sprintf("/api/questions/%d/dismiss", qs[0].ID), nil, map[string]string{"Authorization": "Bearer " + dad})
	if resp.StatusCode != 200 || len(questionsOf(t, f, dad)) != 0 {
		t.Fatalf("dismiss %d", resp.StatusCode)
	}
}

func TestEntryFromXDripSettlesQuestion(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)
	if len(questionsOf(t, f, mom)) != 1 {
		t.Fatal("no question")
	}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Carb Correction","carbs":20,"created_at":"`+
		testNow.Add(-40*time.Minute).UTC().Format(time.RFC3339)+`"}`), xdrip(xd))
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("logged food must settle it: %+v", qs)
	}
}

func TestMealWithoutInsulinAsks(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	steady := make([]int, 20)
	for i := range steady {
		steady[i] = 150
	}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", steady...)
	meal := func(ago time.Duration, carbs int) {
		f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(`{"eventType":"Carb Correction","carbs":%d,"created_at":"%s"}`,
			carbs, testNow.Add(-ago).UTC().Format(time.RFC3339))), xdrip(xd))
	}
	meal(70*time.Minute, 40)
	if err := f.app.Questions.Evaluate(context.Background()); err != nil {
		t.Fatal(err)
	}
	qs := questionsOf(t, f, mom)
	if len(qs) != 1 || qs[0].Kind != "bolus" {
		t.Fatalf("a meal an hour ago without insulin: %+v", qs)
	}
	// the bolus logged late (40 min after the meal) settles it
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Correction Bolus","insulin":2,"created_at":"`+
		testNow.Add(-30*time.Minute).UTC().Format(time.RFC3339)+`"}`), xdrip(xd))
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("insulin logged: %+v", qs)
	}
}

func TestCarbsForALowAskNothing(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	low := make([]int, 20)
	for i := range low {
		low[i] = 75
	}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", low...)
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Carb Correction","carbs":15,"created_at":"`+
		testNow.Add(-70*time.Minute).UTC().Format(time.RFC3339)+`"}`), xdrip(xd))
	f.app.Questions.Evaluate(context.Background())
	if qs := questionsOf(t, f, mom); len(qs) != 0 {
		t.Fatalf("15 g at 75 mg/dL treat a low: %+v", qs)
	}
}
