package stats

import (
	"testing"
	"time"

	"dialife/internal/config"
)

var th = config.Thresholds{UrgentLow: 55, Low: 80, High: 250, UrgentHigh: 300}

func day(vals ...int) []Reading {
	start := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	out := make([]Reading, len(vals))
	for i, v := range vals {
		out[i] = Reading{Mills: start.Add(time.Duration(i) * 5 * time.Minute).UnixMilli(), SGV: v}
	}
	return out
}

func TestZonesMeanGMI(t *testing.T) {
	rs := day(50, 70, 100, 100, 260, 320, 100, 100, 100, 100)
	from := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	s := Compute(rs, from, from.Add(24*time.Hour), th, time.UTC)
	if s.Count != 10 || s.Days != 1 {
		t.Fatalf("count %d days %d", s.Count, s.Days)
	}
	z := s.Zones
	if z.VeryLow != 10 || z.Low != 10 || z.InRange != 60 || z.High != 10 || z.VeryHigh != 10 {
		t.Fatalf("zones %+v", z)
	}
	if s.Mean != 130 || s.GMI != 6.4 { // 3.31 + 0.02392*130 = 6.42
		t.Fatalf("mean %v gmi %v", s.Mean, s.GMI)
	}
	if s.TIR70 != 70 { // 70, six 100s
		t.Fatalf("tir 70-180 %v", s.TIR70)
	}
	if s.Coverage != 3.5 { // 10 of 288
		t.Fatalf("coverage %v", s.Coverage)
	}
}

func TestHyposNeedFifteenMinutes(t *testing.T) {
	// 10 min low (not a hypo), then 20 min low (one hypo)
	rs := day(100, 70, 70, 70, 100, 70, 70, 70, 70, 70, 100)
	if n := hypos(rs, 80); n != 1 { // the first run, 5 → 15 min, is only 10 min long
		t.Fatalf("hypos %d", n)
	}
}

func TestGapEndsHypo(t *testing.T) {
	rs := day(70, 70)
	rs = append(rs, Reading{Mills: rs[1].Mills + int64(40*time.Minute/time.Millisecond), SGV: 70})
	if n := hypos(rs, 80); n != 0 {
		t.Fatalf("a run across a 40 min gap counted: %d", n)
	}
}

func TestAGPPercentiles(t *testing.T) {
	var rs []Reading
	for d := 0; d < 5; d++ { // the same 00:00 slot on five days: 100..140
		ts := time.Date(2026, 10, 1+d, 0, 10, 0, 0, time.UTC)
		rs = append(rs, Reading{Mills: ts.UnixMilli(), SGV: 100 + d*10})
	}
	a := agp(rs, time.UTC)
	if len(a) != 1 || a[0].Minute != 0 || a[0].P50 != 120 || a[0].P5 != 102 || a[0].P95 != 138 || a[0].N != 5 {
		t.Fatalf("agp %+v", a)
	}
}

func TestTotalsPerLocalDay(t *testing.T) {
	loc, _ := time.LoadLocation("Etc/GMT+3") // UTC-3
	at := func(s string) int64 { tt, _ := time.ParseInLocation("2006-01-02 15:04", s, loc); return tt.UnixMilli() }
	got := Totals([]Dose{
		{Mills: at("2026-10-24 21:00"), Insulin: 10, Basal: true},
		{Mills: at("2026-10-24 23:50"), Insulin: 1.5, Carbs: 20},
		{Mills: at("2026-10-25 08:00"), Insulin: 2},
		{Mills: at("2026-10-25 08:05"), Carbs: 45},
	}, loc)
	if len(got) != 2 || got[0] != (DayTotal{"2026-10-25", 0, 2, 45}) || got[1] != (DayTotal{"2026-10-24", 10, 1.5, 20}) {
		t.Fatalf("totals %+v", got)
	}
}

func TestEmpty(t *testing.T) {
	now := time.Now()
	s := Compute(nil, now.Add(-24*time.Hour), now, th, time.UTC)
	if s.Count != 0 || s.AGP == nil {
		t.Fatalf("empty %+v", s)
	}
}
