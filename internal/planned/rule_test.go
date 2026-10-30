package planned

import (
	"testing"
	"time"
)

func TestNormalize(t *testing.T) {
	now := time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
	ok := Dose{ID: " p1 ", MealID: "m1", Dose: 4, Carbs: 59, CreatedAt: now.UnixMilli()}
	if !ok.Normalize(now) || ok.ID != "p1" {
		t.Errorf("a good dose, trimmed: %+v", ok)
	}
	for name, d := range map[string]Dose{
		"no id":           {MealID: "m1", CreatedAt: now.UnixMilli()},
		"no meal":         {ID: "p1", CreatedAt: now.UnixMilli()},
		"over 100 units":  {ID: "p1", MealID: "m1", Dose: 101, CreatedAt: now.UnixMilli()},
		"negative carbs":  {ID: "p1", MealID: "m1", Carbs: -1, CreatedAt: now.UnixMilli()},
		"no time":         {ID: "p1", MealID: "m1"},
		"over an hour on": {ID: "p1", MealID: "m1", CreatedAt: now.Add(61 * time.Minute).UnixMilli()},
	} {
		if d.Normalize(now) {
			t.Errorf("%s: taken", name)
		}
	}
}

func TestOpen(t *testing.T) {
	now := time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
	ds := []Dose{
		{ID: "old", CreatedAt: now.Add(-Expire - time.Minute).UnixMilli()},
		{ID: "edge", CreatedAt: now.Add(-Expire).UnixMilli()},
		{ID: "new", CreatedAt: now.UnixMilli()},
	}
	got := Open(ds, now)
	if len(got) != 2 || got[0].ID != "edge" || got[1].ID != "new" {
		t.Errorf("past Expire it is gone: %+v", got)
	}
	if Open(nil, now) == nil {
		t.Error("none is an empty list, not null, for the phones")
	}
}
