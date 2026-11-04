package forecast

import (
	"context"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"dialife/internal/store"
)

// The sensor's numbers after a shower are not what the sugar did: the
// forecast is not scored against them.
func TestQualityLeavesOutAShower(t *testing.T) {
	ctx := context.Background()
	s, err := store.Open(filepath.Join(t.TempDir(), "f.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if _, err := s.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	d, err := s.CreateDevice(ctx, "xDrip", "uploader", "h", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 3, 22, 0, 0, 0, time.UTC)
	start := now.Add(-4 * time.Hour)
	var es []store.Entry
	for i := 0; i < 48; i++ {
		v := 120
		if i >= 20 && i < 28 { // the shower: the sensor drops, the sugar does not
			v = 100
		}
		es = append(es, store.Entry{ID: fmt.Sprint(i), Type: "sgv", Date: start.Add(time.Duration(i) * 5 * time.Minute).UnixMilli(), SGV: &v})
	}
	if _, err := s.SaveEntries(ctx, es, d.ID, now); err != nil {
		t.Fatal(err)
	}
	score := func() Quality {
		all, _, _ := (&Service{Store: s}).trendQuality(ctx, now)
		return all
	}
	if q := score(); q.HitPct == 100 {
		t.Fatalf("the drop must spoil the score without a shower logged: %+v", q)
	}
	at := start.Add(100 * time.Minute) // reading 20; 10 min of shower + 30 cover 20..27
	err = s.UpsertTreatments(ctx, []store.Treatment{{ID: "s", EventType: "Shower", CreatedAt: at.UnixMilli(), Raw: []byte(`{"duration":10}`)}}, d.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	if q := score(); q.N == 0 || q.HitPct != 100 {
		t.Fatalf("after a shower the drop is not scored: %+v", q)
	}
}
