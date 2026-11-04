package trust

import (
	"testing"
	"time"

	"dialife/internal/store"
)

func TestOf(t *testing.T) {
	const at = 1_000_000
	min := time.Minute.Milliseconds()
	cases := []struct {
		name  string
		tr    store.Treatment
		until int64
		ok    bool
	}{
		{"shower of 15 min", store.Treatment{EventType: Shower, CreatedAt: at, Raw: []byte(`{"duration":15}`)}, at + 45*min, true},
		{"no duration: 10 min", store.Treatment{EventType: Shower, CreatedAt: at, Raw: []byte(`{}`)}, at + 40*min, true},
		{"a typo is capped", store.Treatment{EventType: Shower, CreatedAt: at, Raw: []byte(`{"duration":900}`)}, at + 150*min, true},
		{"exercise is not a shower", store.Treatment{EventType: "Exercise", CreatedAt: at, Raw: []byte(`{"duration":15}`)}, 0, false},
	}
	for _, c := range cases {
		w, ok := Of(c.tr)
		if ok != c.ok || (ok && (w.From != at || w.Until != c.until || w.Reason != "shower")) {
			t.Errorf("%s: %+v %v", c.name, w, ok)
		}
	}
}

func TestDrop(t *testing.T) {
	ws := []Window{{From: 10, Until: 20}}
	got := Drop([]int64{5, 10, 15, 19, 20, 25}, func(v int64) int64 { return v }, ws)
	if len(got) != 3 || got[0] != 5 || got[1] != 20 || got[2] != 25 {
		t.Fatalf("drop %v: the window is [from, until)", got)
	}
	if Inside(nil, 15) {
		t.Fatal("no windows: everything is trusted")
	}
}
