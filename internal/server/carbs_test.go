package server

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

func TestCarbSuggestFromTheFamilysOwnMeals(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Carb Correction","carbs":40,"created_at":"`+
		testNow.Add(-time.Hour).UTC().Format(time.RFC3339)+`","foodItems":[{"name":"Борщ","portion":"тарілка","carbs":18,"source":"manual"},{"name":"Хліб","portion":"2 скибки","carbs":22,"source":"estimate"}]}`), h)

	resp := f.do(t, "POST", "/api/carbs/suggest", strings.NewReader(`{"items":[{"name":"борщ"},{"name":"хліб","portion":"1 скибка"},{"name":"яблуко"}]}`), h)
	var out struct {
		Models bool
		Items  []struct {
			Mine *struct {
				Carbs   int
				Portion string
				Times   int
			}
			Estimate *struct{ Carbs int }
		}
	}
	json.NewDecoder(resp.Body).Decode(&out)
	if resp.StatusCode != 200 || out.Models || len(out.Items) != 3 {
		t.Fatalf("%d %+v", resp.StatusCode, out)
	}
	if m := out.Items[0].Mine; m == nil || m.Carbs != 18 || m.Portion != "тарілка" {
		t.Fatalf("borscht %+v", m)
	}
	if m := out.Items[1].Mine; m == nil || m.Carbs != 11 {
		t.Fatalf("one slice of the two: %+v", m)
	}
	if out.Items[2].Mine != nil || out.Items[2].Estimate != nil {
		t.Fatalf("nothing known, no models: %+v", out.Items[2])
	}
	// the meal's items come back with the history
	var hist struct {
		Treatments []struct {
			FoodItems []struct{ Name, Source string } `json:"food_items"`
		}
	}
	json.NewDecoder(f.get(t, "/api/history", h).Body).Decode(&hist)
	if len(hist.Treatments) != 1 || len(hist.Treatments[0].FoodItems) != 2 || hist.Treatments[0].FoodItems[1].Source != "estimate" {
		t.Fatalf("%+v", hist)
	}
	// only parents ask; nothing empty or huge
	if code := f.do(t, "POST", "/api/carbs/suggest", strings.NewReader(`{"items":[{"name":"x"}]}`), xdrip(xd)).StatusCode; code != 403 {
		t.Fatalf("uploader: %d", code)
	}
	if code := f.do(t, "POST", "/api/carbs/suggest", strings.NewReader(`{"items":[]}`), h).StatusCode; code != 400 {
		t.Fatalf("empty: %d", code)
	}
}
