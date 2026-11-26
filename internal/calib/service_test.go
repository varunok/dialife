package calib

import (
	"context"
	"encoding/json"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"dialife/internal/store"
)

func TestCloseHidesUntilANewerCheckIsOff(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if _, err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	dev, err := st.CreateDevice(ctx, "xdrip", "uploader", "0000", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 11, 24, 12, 0, 0, 0, time.UTC)
	now := start
	svc := &Service{Store: st, Now: func() time.Time { return now }}
	if err := st.UpsertTreatments(ctx, []store.Treatment{{ID: "s", EventType: "Sensor Start", CreatedAt: start.UnixMilli(), Raw: []byte(`{}`)}}, dev.ID, now); err != nil {
		t.Fatal(err)
	}
	// a check of meter m with the sensor flat at s around it, h hours after the start
	check := func(h int, s, m int) {
		t.Helper()
		at := start.Add(time.Duration(h) * time.Hour)
		var es []store.Entry
		for k := -20; k <= 20; k += 5 {
			v, d := s, at.Add(time.Duration(k)*time.Minute).UnixMilli()
			raw, _ := json.Marshal(map[string]int{"sgv": v})
			es = append(es, store.Entry{ID: fmt.Sprint("s", d), Type: "sgv", Date: d, SGV: &v, Raw: raw})
		}
		mv := m
		raw, _ := json.Marshal(map[string]int{"mbg": m})
		es = append(es, store.Entry{ID: fmt.Sprint("m", at.UnixMilli()), Type: "mbg", Date: at.UnixMilli(), MBG: &mv, Device: "Bluetooth Glucose Meter", Raw: raw})
		if _, err := st.SaveEntries(ctx, es, dev.ID, now); err != nil {
			t.Fatal(err)
		}
		now = at.Add(time.Minute)
	}
	check(14, 75, 105)
	check(18, 80, 112)
	check(22, 78, 108)
	a, err := svc.State(ctx)
	if err != nil || a == nil || a.Now == nil || a.Now.MG != 108 {
		t.Fatalf("advice %+v %v", a, err)
	}
	if err := svc.Close(ctx, store.Device{Person: "Mom"}); err != nil {
		t.Fatal(err)
	}
	if a, _ := svc.State(ctx); a != nil {
		t.Fatalf("closed: %+v", a)
	}
	// a newer check still off: back for everyone
	check(26, 76, 107)
	if a, _ := svc.State(ctx); a == nil {
		t.Fatal("a newer check off again brings it back")
	}
	if err := svc.Close(ctx, store.Device{}); err != nil {
		t.Fatal(err)
	}
	if err := svc.Close(ctx, store.Device{}); err != ErrNothing {
		t.Fatalf("nothing to close: %v", err)
	}
}
