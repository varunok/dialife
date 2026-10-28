package server

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
	"dialife/internal/store"
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

func TestFoodsCatalogAndTableSuggestions(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	h := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	err := f.store.ReplaceFoodTable(context.Background(), store.FoodTable{
		FoodSource: store.FoodSource{Source: "sample", Title: "Sample", At: testNow.UnixMilli()},
		Categories: []store.FoodCategory{{ID: "fruit", Names: store.Langs{"uk": "Фрукти", "en": "Fruit", "es": "Frutas"}}},
		Items: []store.FoodRef{{ID: "banana", Cat: "fruit", Carbs: 15, Qty: 1, Unit: "pc",
			Names: store.Langs{"uk": "Банан", "en": "Banana", "es": "Banana"}, Portions: store.Langs{"uk": "1 маленький", "en": "1 small", "es": "1 chica"}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Carb Correction","carbs":18,"created_at":"`+
		testNow.Add(-time.Hour).UTC().Format(time.RFC3339)+`","foodItems":[{"name":"Борщ","portion":"тарілка","carbs":18}]}`), h)

	var cat struct {
		Tables []struct {
			Source     string
			Categories []struct{ ID string }
			Items      []struct {
				ID       string
				Carbs    int
				Names    map[string]string
				Portions map[string]string
			}
		}
		Frequent []struct {
			Name  string
			Times int
		}
	}
	resp := f.get(t, "/api/foods", h)
	json.NewDecoder(resp.Body).Decode(&cat)
	if resp.StatusCode != 200 || len(cat.Tables) != 1 || len(cat.Tables[0].Items) != 1 || cat.Tables[0].Items[0].Names["es"] != "Banana" {
		t.Fatalf("%d %+v", resp.StatusCode, cat)
	}
	if len(cat.Frequent) != 1 || cat.Frequent[0].Name != "Борщ" {
		t.Fatalf("frequent %+v", cat.Frequent)
	}

	var out struct {
		Items []struct {
			Table []struct {
				ID     string
				Carbs  int
				Scaled bool
			}
		}
	}
	json.NewDecoder(f.do(t, "POST", "/api/carbs/suggest", strings.NewReader(`{"items":[{"name":"банан","portion":"2"}]}`), h).Body).Decode(&out)
	if len(out.Items) != 1 || len(out.Items[0].Table) != 1 || out.Items[0].Table[0].Carbs != 30 || !out.Items[0].Table[0].Scaled {
		t.Fatalf("%+v", out)
	}
}
