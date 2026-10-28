package app

import "testing"

func TestMealGap(t *testing.T) {
	min := func(m int) int64 { return int64(m) * 60_000 }
	if g := mealGap([]int64{min(0), min(20)}); g != mealGapDefault {
		t.Fatalf("little history: %d, want %d", g, mealGapDefault)
	}
	// a supplement 5–10 min after the meal, meals hours apart
	var short []int64
	for d := range 12 {
		at := d * 24 * 60
		short = append(short, min(at), min(at+5+d%6), min(at+240))
	}
	if g := mealGap(short); g != 19 {
		t.Fatalf("short habit: %d, want 19", g)
	}
	// supplements up to 50 min later: still never past 30
	var long []int64
	for d := range 12 {
		at := d * 24 * 60
		long = append(long, min(at), min(at+40+d%11))
	}
	if g := mealGap(long); g != mealGapMax {
		t.Fatalf("long habit: %d, want %d", g, mealGapMax)
	}
}
