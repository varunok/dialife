package forecast

import (
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC).UnixMilli()

func readings(vs ...int) []Point {
	ps := make([]Point, len(vs))
	for i, v := range vs {
		ps[i] = Point{T: t0 + int64(i)*5*60000, V: v}
	}
	return ps
}

func TestTrendDampsTheRise(t *testing.T) {
	// +2 mg/dL a minute for half an hour
	rs := readings(100, 110, 120, 130, 140, 150, 160)
	f, ok := Trend(rs, Quality{Err80: 20, N: 100})
	if !ok || len(f.Predicted) != 6 {
		t.Fatalf("%v %+v", ok, f)
	}
	// 30 min ahead: 160 + 2·30·0.4 = 184
	if got := f.Predicted[5].SGV; got != 184 {
		t.Fatalf("in 30 min %d, want 184", got)
	}
	if c := f.Cone[5]; c.Lo != 164 || c.Hi != 204 {
		t.Fatalf("cone at 30 min %+v, want ±20", c)
	}
	if _, ok := Trend(readings(100, 110), Quality{}); ok {
		t.Fatal("two readings are not a trend")
	}
}

func TestBacktestOnASteadyLine(t *testing.T) {
	// perfectly steady: every forecast comes true
	vs := make([]int, 40)
	for i := range vs {
		vs[i] = 120
	}
	q := Backtest(readings(vs...))
	if q.N == 0 || q.HitPct != 100 || q.Err80 != 0 {
		t.Fatalf("%+v", q)
	}
	// a rise that stops: the damped trend overshoots a little, still mostly within 15
	vs = vs[:0]
	for i := 0; i < 40; i++ {
		v := 100 + 3*i
		if i > 20 {
			v = 160
		}
		vs = append(vs, v)
	}
	q = Backtest(readings(vs...))
	if q.N == 0 || q.HitPct < 50 || q.HitPct > 100 {
		t.Fatalf("%+v", q)
	}
}

func TestBacktestSplitByFood(t *testing.T) {
	// flat for a day, then a meal: the sugar rises and the trend lags behind it
	var rs []Point
	t0 := int64(0)
	for i := range 288 {
		v := 120
		if i >= 200 && i < 236 {
			v = 120 + (i-200)*4
		} else if i >= 236 {
			v = 264
		}
		rs = append(rs, Point{T: t0 + int64(i)*300_000, V: v})
	}
	quiet, fed := BacktestSplit(rs, []int64{rs[200].T})
	if quiet.N == 0 || fed.N == 0 || quiet.N+fed.N != Backtest(rs).N {
		t.Fatalf("every forecast in one of the two: %+v %+v", quiet, fed)
	}
	if fed.HitPct >= quiet.HitPct {
		t.Fatalf("after food it should do worse: quiet %+v, after food %+v", quiet, fed)
	}
}
