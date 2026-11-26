package recheck

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

	"dialife/internal/store"
)

const minute = int64(60 * 1000)

func TestLowAt(t *testing.T) {
	at := 1000 * minute
	for _, c := range []struct {
		name   string
		alarms []Window
		sgv    []Point
		checks []Point
		want   Low
		low    bool
	}{
		{"a live low alarm", []Window{{From: at - 5*minute}}, []Point{{at - minute, 85}}, nil, Low{Kind: "alarm"}, true},
		{"an alarm already over", []Window{{From: at - 30*minute, Until: at - minute}}, []Point{{at - minute, 85}}, nil, Low{}, false},
		{"the sensor below 70", nil, []Point{{at - 6*minute, 90}, {at - minute, 64}}, nil, Low{Kind: "sensor", MG: 64}, true},
		{"the newest reading counts, not an older low one", nil, []Point{{at - 6*minute, 60}, {at - minute, 72}}, nil, Low{}, false},
		{"a reading too old says nothing", nil, []Point{{at - 11*minute, 60}}, nil, Low{}, false},
		{"exactly 70 is not low", nil, []Point{{at - minute, 70}}, nil, Low{}, false},
		{"a glucometer check below 70", nil, []Point{{at - minute, 80}}, []Point{{at - 10*minute, 59}}, Low{Kind: "meter", MG: 59}, true},
		{"a check too old says nothing", nil, nil, []Point{{at - 16*minute, 59}}, Low{}, false},
	} {
		got, low := LowAt(at, c.alarms, c.sgv, c.checks)
		if got != c.want || low != c.low {
			t.Errorf("%s: %+v %v, want %+v %v", c.name, got, low, c.want, c.low)
		}
	}
}

func TestLatestCheck(t *testing.T) {
	carbs := 1000 * minute
	checks := []Point{{carbs - minute, 58}, {carbs + 3*minute, 60}, {carbs + 14*minute, 66}, {carbs + 31*minute, 90}}
	if _, ok := LatestCheck(carbs, carbs+4*minute, checks); ok {
		t.Fatal("the low itself, entered a few minutes late, is not a recheck")
	}
	if c, ok := LatestCheck(carbs, carbs+20*minute, checks); !ok || c.MG != 66 {
		t.Fatalf("%+v %v", c, ok)
	}
	if c, _ := LatestCheck(carbs, carbs+40*minute, checks); c.MG != 90 {
		t.Fatalf("the newest one: %+v", c)
	}
}

// the whole way: carbs for a low, the reminder, a check still low, new carbs, a check back in range
func TestRecheckFlow(t *testing.T) {
	ctx := context.Background()
	st := openStore(t)
	now := time.UnixMilli(2000 * minute)
	svc := &Service{Store: st, Now: func() time.Time { return now }}
	svc.OnChange = func(context.Context) {}
	carbs := func(id string, g float64, at int64) {
		t.Helper()
		if err := st.UpsertTreatments(ctx, []store.Treatment{{ID: id, EventType: "Carb Correction", CreatedAt: at, Carbs: &g, Raw: []byte(`{}`)}}, dev, now); err != nil {
			t.Fatal(err)
		}
		svc.TreatmentsSaved(ctx, []string{id})
	}
	sgv := func(at int64, v int) {
		t.Helper()
		raw, _ := json.Marshal(map[string]any{"sgv": v})
		if _, err := st.SaveEntries(ctx, []store.Entry{{ID: "s" + time.UnixMilli(at).Format("150405"), Type: "sgv", Date: at, SGV: &v, Raw: raw}}, dev, now); err != nil {
			t.Fatal(err)
		}
	}
	meterCheck := func(at int64, v int) {
		t.Helper()
		raw, _ := json.Marshal(map[string]any{"mbg": v})
		if _, err := st.SaveEntries(ctx, []store.Entry{{ID: "m" + time.UnixMilli(at).Format("150405"), Type: "mbg", Date: at, MBG: &v, Device: "Bluetooth Glucose Meter", Raw: raw}}, dev, now); err != nil {
			t.Fatal(err)
		}
		svc.Tick(ctx)
	}
	state := func() *Recheck {
		t.Helper()
		r, err := svc.State(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}

	// carbs while the sensor was fine: nothing
	sgv(now.UnixMilli()-2*minute, 110)
	carbs("a", 10, now.UnixMilli())
	if r := state(); r != nil {
		t.Fatalf("no low, no recheck: %+v", r)
	}

	// carbs on a low
	now = now.Add(time.Hour)
	t0 := now.UnixMilli()
	sgv(t0-2*minute, 62)
	carbs("b", 15, t0)
	if r := state(); r == nil || r.State != "waiting" || r.Low != (Low{Kind: "sensor", MG: 62}) || r.DueAt != t0+15*minute || r.Carbs != 15 {
		t.Fatalf("opened: %+v", r)
	}

	// 15 minutes on: due
	now = now.Add(15 * time.Minute)
	svc.Tick(ctx)
	if r := state(); r == nil || r.State != "due" {
		t.Fatalf("due: %+v", r)
	}

	// still low on the glucometer
	now = now.Add(2 * time.Minute)
	meterCheck(now.UnixMilli(), 64)
	if r := state(); r == nil || r.State != "still_low" || r.CheckMG != 64 {
		t.Fatalf("still low: %+v", r)
	}

	// more carbs: a new recheck replaces it, counted from them
	now = now.Add(time.Minute)
	t1 := now.UnixMilli()
	carbs("c", 10, t1)
	if r := state(); r == nil || r.CarbsT != t1 || r.State != "waiting" || r.Low.Kind != "meter" {
		t.Fatalf("again: %+v", r)
	}

	// back in range: over for everyone
	now = now.Add(16 * time.Minute)
	meterCheck(now.UnixMilli(), 96)
	if r := state(); r != nil {
		t.Fatalf("in range: %+v", r)
	}
	var outcomes []string
	rows, _ := st.Reader().QueryContext(ctx, `SELECT outcome FROM recheck ORDER BY id`)
	for rows.Next() {
		var o string
		_ = rows.Scan(&o)
		outcomes = append(outcomes, o)
	}
	rows.Close()
	if len(outcomes) != 2 || outcomes[0] != "again" || outcomes[1] != "in_range" {
		t.Fatalf("outcomes %v", outcomes)
	}
}

func TestRecheckClosedAndExpired(t *testing.T) {
	ctx := context.Background()
	st := openStore(t)
	now := time.UnixMilli(3000 * minute)
	svc := &Service{Store: st, Now: func() time.Time { return now }}
	low := func(id string) int64 {
		at := now.UnixMilli()
		if id == "x" { // one low alarm, still live
			if _, err := st.InsertAlarm(ctx, store.Alarm{Kind: "low", Level: "warn", StartedAt: at - 3*minute, LastNotifiedAt: at}); err != nil {
				t.Fatal(err)
			}
		}
		g := 15.0
		if err := st.UpsertTreatments(ctx, []store.Treatment{{ID: id, EventType: "Carb Correction", CreatedAt: at, Carbs: &g, Raw: []byte(`{}`)}}, dev, now); err != nil {
			t.Fatal(err)
		}
		svc.TreatmentsSaved(ctx, []string{id})
		return at
	}

	// closed by a parent
	low("x")
	r, _ := svc.State(ctx)
	if r == nil || r.Low.Kind != "alarm" {
		t.Fatalf("a live low alarm opens it: %+v", r)
	}
	if err := svc.Close(ctx, r.ID+1, store.Device{}); err != ErrNotFound {
		t.Fatalf("another id: %v", err)
	}
	if err := svc.Close(ctx, r.ID, store.Device{ID: 0, Person: "Dad"}); err != nil {
		t.Fatal(err)
	}
	if r, _ := svc.State(ctx); r != nil {
		t.Fatalf("closed: %+v", r)
	}

	// nobody measured: gone 2 hours after it was due
	now = now.Add(time.Hour)
	low("y")
	now = now.Add(15*time.Minute + Expire)
	svc.Tick(ctx)
	if r, _ := svc.State(ctx); r != nil {
		t.Fatalf("expired: %+v", r)
	}

	// carbs logged with a time long past are history, not a treatment
	now = now.Add(time.Hour)
	g := 20.0
	if err := st.UpsertTreatments(ctx, []store.Treatment{{ID: "z", EventType: "Carb Correction", CreatedAt: now.Add(-time.Hour).UnixMilli(), Carbs: &g, Raw: []byte(`{}`)}}, dev, now); err != nil {
		t.Fatal(err)
	}
	svc.TreatmentsSaved(ctx, []string{"z"})
	if r, _ := svc.State(ctx); r != nil {
		t.Fatalf("old carbs: %+v", r)
	}
}

// openStore is a migrated database with one device; its ID is dev.
func openStore(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if _, err := st.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	d, err := st.CreateDevice(context.Background(), "xdrip", "uploader", "0000", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	dev = d.ID
	return st
}

var dev int64
