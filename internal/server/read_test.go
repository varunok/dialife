package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

type stateBody struct {
	Now          int64          `json:"now"`
	DisplayUnits string         `json:"display_units"`
	Thresholds   map[string]int `json:"thresholds"`
	BG           *struct {
		SGV          int      `json:"sgv"`
		Mills        int64    `json:"mills"`
		AgeSec       int64    `json:"age_sec"`
		Error        bool     `json:"error"`
		Direction    string   `json:"direction"`
		Arrow        string   `json:"arrow"`
		Delta        *int     `json:"delta"`
		DeltaExact   *float64 `json:"delta_exact"`
		Interpolated bool     `json:"interpolated"`
	} `json:"bg"`
	AR2 *struct {
		Predicted []struct {
			T   int64 `json:"t"`
			SGV int   `json:"sgv"`
		} `json:"predicted"`
		Cone  []map[string]int64 `json:"cone"`
		Level string             `json:"level"`
		Event string             `json:"event"`
	} `json:"ar2"`
	Uploader *struct {
		Battery *int   `json:"battery"`
		Device  string `json:"device"`
	} `json:"uploader"`
}

// upload posts sgv readings, newest last, one every 5 minutes ending at `last`.
func upload(t *testing.T, f *fixture, token string, last time.Time, dir string, sgvs ...int) {
	t.Helper()
	var docs []string
	for i, v := range sgvs {
		ts := last.Add(-time.Duration(len(sgvs)-1-i) * 5 * time.Minute).UnixMilli()
		docs = append(docs, fmt.Sprintf(`{"type":"sgv","date":%d,"sgv":%d,"direction":%q,"device":"xDrip-DexcomG5"}`, ts, v, dir))
	}
	resp := f.do(t, "POST", "/api/v1/entries", strings.NewReader("["+strings.Join(docs, ",")+"]"), xdrip(token))
	if resp.StatusCode != 200 {
		t.Fatalf("upload: status %d", resp.StatusCode)
	}
}

func getState(t *testing.T, f *fixture, token string) stateBody {
	t.Helper()
	resp := f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + token})
	if resp.StatusCode != 200 {
		t.Fatalf("state: status %d", resp.StatusCode)
	}
	var s stateBody
	if err := json.NewDecoder(resp.Body).Decode(&s); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestStateEmpty(t *testing.T) {
	f := newFixture(t)
	_, app := f.addDevice(t, "dad", auth.RoleCaregiver)
	s := getState(t, f, app)
	if s.BG != nil || s.AR2 != nil || s.Uploader != nil {
		t.Fatalf("empty db produced data: %+v", s)
	}
	if s.Now != testNow.UnixMilli() || s.DisplayUnits != "mg/dl" || s.Thresholds["bgTargetBottom"] != 70 {
		t.Fatalf("state %+v", s)
	}
}

func TestStateFallingTowardsLow(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, app := f.addDevice(t, "dad", auth.RoleCaregiver)
	last := testNow.Add(-2 * time.Minute)
	upload(t, f, xd, last, "SingleDown", 100, 90, 80, 70)
	f.do(t, "POST", "/api/v1/devicestatus", bytes.NewReader(fixtureFile(t, "devicestatus.json")), xdrip(xd))

	s := getState(t, f, app)
	bg := s.BG
	if bg == nil || bg.SGV != 70 || bg.Mills != last.UnixMilli() || bg.AgeSec != 120 || bg.Error ||
		bg.Direction != "FortyFiveDown" || bg.Arrow != "↘" || *bg.Delta != -10 || bg.Interpolated { // −30 in 15 min = −2/min; the uploader's "SingleDown" is ignored
		t.Fatalf("bg %+v", bg)
	}
	// the chart's forecast: −2/min damped to 40% → 66 … 46 in 30 min (the alarms keep AR2, tested in glucose)
	if s.AR2 == nil || len(s.AR2.Predicted) != 6 || s.AR2.Predicted[0].SGV != 66 || s.AR2.Predicted[5].SGV != 46 ||
		s.AR2.Predicted[0].T != last.Add(5*time.Minute).UnixMilli() || len(s.AR2.Cone) != 6 {
		t.Fatalf("forecast %+v", s.AR2)
	}
	if s.Uploader == nil || *s.Uploader.Battery != 63 || s.Uploader.Device != "SM-A525F" {
		t.Fatalf("uploader %+v", s.Uploader)
	}
}

func TestStateStaleHasNoForecast(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(-time.Hour), "Flat", 120, 121)
	s := getState(t, f, xd)
	if s.BG == nil || s.BG.AgeSec != 3600 || s.AR2 != nil {
		t.Fatalf("stale state: bg %+v ar2 %+v", s.BG, s.AR2)
	}
}

func TestStateSensorError(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(-time.Minute), "NOT COMPUTABLE", 120, 118, 10)
	s := getState(t, f, xd)
	if s.BG == nil || !s.BG.Error || s.BG.SGV != 10 || s.AR2 != nil || s.BG.Arrow != "-" {
		t.Fatalf("error state: bg %+v ar2 %+v", s.BG, s.AR2)
	}
}

func TestStateToleratesPhoneClockAhead(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(3*time.Minute), "Flat", 110, 112)
	if s := getState(t, f, xd); s.BG == nil || s.BG.SGV != 112 || s.BG.AgeSec != 0 {
		t.Fatalf("reading 3 min ahead ignored: %+v", s.BG)
	}
}

func TestHistory(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	bearer := map[string]string{"Authorization": "Bearer " + xd}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 150, 10, 140) // 10 is a sensor error
	upload(t, f, xd, testNow.Add(-8*time.Hour), "Flat", 200)          // outside default 6 h
	f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":135,"date":%d}`, testNow.Add(-30*time.Minute).UnixMilli())), xdrip(xd))
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(fmt.Sprintf(
		`[{"eventType":"Meal Bolus","created_at":"%s","carbs":40,"insulin":3},{"eventType":"Note","created_at":"%s","notes":"ок"}]`,
		testNow.Add(-2*time.Hour).Format(time.RFC3339), testNow.Add(-time.Hour).Format(time.RFC3339))), xdrip(xd))

	var h struct {
		From, To int64
		SGV, MBG struct {
			T []int64
			V []int
		}
		Treatments []struct {
			T         int64
			EventType string   `json:"event_type"`
			Carbs     *float64 `json:"carbs"`
		}
	}
	resp := f.get(t, "/api/history", bearer)
	if err := json.NewDecoder(resp.Body).Decode(&h); err != nil {
		t.Fatal(err)
	}
	if h.To != testNow.UnixMilli() || h.From != testNow.Add(-6*time.Hour).UnixMilli() {
		t.Fatalf("range %d..%d", h.From, h.To)
	}
	if len(h.SGV.V) != 2 || h.SGV.V[0] != 150 || h.SGV.V[1] != 140 || h.SGV.T[0] >= h.SGV.T[1] {
		t.Fatalf("sgv series %+v (want 150, 140 oldest first, error code dropped)", h.SGV)
	}
	if len(h.MBG.V) != 1 || h.MBG.V[0] != 135 {
		t.Fatalf("mbg series %+v", h.MBG)
	}
	if len(h.Treatments) != 2 || h.Treatments[0].EventType != "Meal Bolus" || *h.Treatments[0].Carbs != 40 {
		t.Fatalf("treatments %+v", h.Treatments)
	}

	from := testNow.Add(-9 * time.Hour).UnixMilli()
	resp = f.get(t, fmt.Sprintf("/api/history?from=%d", from), bearer)
	h.SGV.V = nil
	json.NewDecoder(resp.Body).Decode(&h)
	if len(h.SGV.V) != 3 {
		t.Fatalf("explicit from: %d sgv", len(h.SGV.V))
	}

	for _, bad := range []string{"?from=yesterday", "?from=2026-10-25T00:00:00Z&to=2026-10-24T00:00:00Z", "?step=-5", "?step=abc"} {
		if resp := f.get(t, "/api/history"+bad, bearer); resp.StatusCode != 400 {
			t.Errorf("%s: status %d, want 400", bad, resp.StatusCode)
		}
	}
}

func TestHistoryEntriesAheadOfNow(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	hdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	// a phone whose clock runs 20 min ahead logs a meal at its "now"
	at := testNow.Add(20 * time.Minute).UTC().Format(time.RFC3339)
	body := `[{"_id":"dddddddddddddddddddddddd","eventType":"Meal Bolus","created_at":"` + at + `","carbs":30}]`
	if resp := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(body), hdr); resp.StatusCode != 200 {
		t.Fatalf("post %d", resp.StatusCode)
	}
	count := func(q string) int {
		var h struct{ Treatments []struct{ ID string } }
		json.NewDecoder(f.get(t, "/api/history"+q, hdr).Body).Decode(&h)
		return len(h.Treatments)
	}
	// a view up to now gets it, so it shows once its time comes
	if n := count(""); n != 1 {
		t.Fatalf("default view: %d treatments, want the one 20 min ahead", n)
	}
	if n := count(fmt.Sprintf("?from=%d&to=%d", testNow.Add(-time.Hour).UnixMilli(), testNow.Add(-2*time.Minute).UnixMilli())); n != 1 {
		t.Fatalf("view up to a phone's slow now: %d treatments", n)
	}
	// a view of the past keeps to its bounds
	if n := count(fmt.Sprintf("?from=%d&to=%d", testNow.Add(-5*time.Hour).UnixMilli(), testNow.Add(-2*time.Hour).UnixMilli())); n != 0 {
		t.Fatalf("past view: %d treatments, want none", n)
	}
}

func TestNightscoutEntriesRead(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, panel := f.addDevice(t, "panel", auth.RoleReader)
	hdr := xdrip(panel)
	vals := make([]int, 15)
	for i := range vals {
		vals[i] = 100 + i
	}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", vals...)
	f.do(t, "POST", "/api/v1/entries", bytes.NewReader(fixtureFile(t, "entries.json")), xdrip(xd))

	if got := decodeArray(t, f.get(t, "/api/v1/entries.json", hdr)); len(got) != 10 || got[0]["sgv"] != 114.0 {
		t.Fatalf("default entries: %d, first %v", len(got), got[0]["sgv"])
	}
	cur := decodeArray(t, f.get(t, "/api/v1/entries/current.json", hdr))
	if len(cur) != 1 || cur[0]["sgv"] != 114.0 || cur[0]["type"] != "sgv" || len(cur[0]["_id"].(string)) != 24 ||
		!strings.HasSuffix(cur[0]["dateString"].(string), "Z") {
		t.Fatalf("current %v", cur)
	}
	if got := decodeArray(t, f.get(t, "/api/v1/entries/sgv.json?count=100", hdr)); len(got) != 17 {
		t.Fatalf("sgv count=100: %d (15 uploaded + 2 from fixture)", len(got))
	}
	if got := decodeArray(t, f.get(t, "/api/v1/entries/mbg.json", hdr)); len(got) != 1 || got[0]["mbg"] != 104.0 {
		t.Fatalf("mbg: %v", got)
	}
	since := testNow.Add(-11 * time.Minute).UnixMilli()
	if got := decodeArray(t, f.get(t, fmt.Sprintf("/api/v1/entries/sgv.json?find[date][$gte]=%d", since), hdr)); len(got) != 3 {
		t.Fatalf("find[date][$gte]: %d", len(got))
	}
	// at or before 00:00Z: fixture sgv at exactly 00:00 + uploaded 23:49, 23:54, 23:59
	if got := decodeArray(t, f.get(t, "/api/v1/entries.json?find[dateString][$lte]=2026-10-25T00:00:00Z", hdr)); len(got) != 4 {
		t.Fatalf("find[dateString][$lte]: %d", len(got))
	}
	if resp := f.get(t, "/api/v1/entries.json?find[date][$gte]=soon", hdr); resp.StatusCode != 400 {
		t.Fatalf("bad bound: status %d", resp.StatusCode)
	}
	if resp := f.get(t, "/api/state", nil); resp.StatusCode != 401 {
		t.Fatalf("state without token: %d", resp.StatusCode)
	}
}

func TestStats(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	upload(t, f, xd, f.now().Add(-time.Minute), "Flat", 100, 120, 260, 40)

	resp := f.do(t, "GET", "/api/stats?days=1", nil, map[string]string{"Authorization": "Bearer " + mom})
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	var s struct {
		Count int `json:"count"`
		Days  int `json:"days"`
		Zones struct {
			VeryLow float64 `json:"very_low"`
		} `json:"zones"`
		AGP []json.RawMessage `json:"agp"`
	}
	json.NewDecoder(resp.Body).Decode(&s)
	if s.Count != 4 || s.Days != 1 || s.Zones.VeryLow != 25 || len(s.AGP) == 0 {
		t.Fatalf("stats %+v", s)
	}
	if got := f.do(t, "GET", "/api/stats?days=0", nil, map[string]string{"Authorization": "Bearer " + mom}).StatusCode; got != 400 {
		t.Fatalf("days=0: %d", got)
	}
}

func TestInsulinTypeInHistoryAndStats(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	hdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	at := f.now().Add(-time.Hour).UTC().Format(time.RFC3339)
	body := `[{"_id":"aaaaaaaaaaaaaaaaaaaaaaaa","eventType":"Basal Injection","created_at":"` + at + `","insulin":10,"insulinType":"basal","insulinName":"Lantus"},
		{"_id":"bbbbbbbbbbbbbbbbbbbbbbbb","eventType":"Correction Bolus","created_at":"` + at + `","insulin":2}]`
	if resp := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(body), hdr); resp.StatusCode != 200 {
		t.Fatalf("post %d", resp.StatusCode)
	}
	from, to := f.now().Add(-2*time.Hour).UnixMilli(), f.now().UnixMilli()
	resp := f.do(t, "GET", fmt.Sprintf("/api/history?from=%d&to=%d", from, to), nil, hdr)
	var h struct {
		Treatments []struct {
			ID          string `json:"id"`
			InsulinType string `json:"insulin_type"`
			InsulinName string `json:"insulin_name"`
		} `json:"treatments"`
	}
	json.NewDecoder(resp.Body).Decode(&h)
	if len(h.Treatments) != 2 || h.Treatments[0].InsulinType != "basal" || h.Treatments[0].InsulinName != "Lantus" || h.Treatments[1].InsulinType != "" {
		t.Fatalf("history %+v", h)
	}
	resp = f.do(t, "GET", "/api/stats?days=1", nil, hdr)
	var s struct {
		Daily []struct{ Basal, Rapid float64 } `json:"daily"`
	}
	json.NewDecoder(resp.Body).Decode(&s)
	if len(s.Daily) != 1 || s.Daily[0].Basal != 10 || s.Daily[0].Rapid != 2 {
		t.Fatalf("daily %+v", s)
	}
}

func TestXDripInsulinAndMeterReadings(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	at := func(d time.Duration) string { return testNow.Add(-d).UTC().Format(time.RFC3339) }
	// xDrip+: type from its own profile names, no insulinType of ours
	f.do(t, "PUT", "/api/v1/treatments", strings.NewReader(`{"uuid":"11111111-2222-4333-8444-555555555555","eventType":"<none>","created_at":"`+at(3*time.Hour)+
		`","insulin":18,"insulinInjections":"[{\"insulin\":\"Lantus\",\"units\":18.0}]"}`), xdrip(xd))
	f.do(t, "PUT", "/api/v1/treatments", strings.NewReader(`{"uuid":"66666666-7777-4888-8999-aaaaaaaaaaaa","eventType":"<none>","created_at":"`+at(2*time.Hour)+
		`","insulin":1,"insulinInjections":"[{\"insulin\":\"Novorapid\",\"units\":1.0}]"}`), xdrip(xd))
	// a glucometer check from the app, then xDrip+ uploads the same value as mbg twice
	hdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`[{"_id":"cccccccccccccccccccccccc","eventType":"BG Check","created_at":"`+at(time.Hour)+
		`","glucose":8,"glucoseType":"Finger","units":"mmol"}]`), hdr)
	for _, d := range []time.Duration{time.Hour - time.Minute, 50 * time.Minute, 30 * time.Minute} {
		v := 144
		if d == 30*time.Minute {
			v = 120
		}
		f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":%d,"date":%d}`, v, testNow.Add(-d).UnixMilli())), xdrip(xd))
	}

	from, to := testNow.Add(-4*time.Hour).UnixMilli(), testNow.UnixMilli()
	resp := f.do(t, "GET", fmt.Sprintf("/api/history?from=%d&to=%d", from, to), nil, hdr)
	var h struct {
		MBG        struct{ V []int }
		Treatments []struct {
			InsulinType string `json:"insulin_type"`
			InsulinName string `json:"insulin_name"`
			Glucose     *int
		}
	}
	json.NewDecoder(resp.Body).Decode(&h)
	if len(h.Treatments) != 3 || h.Treatments[0].InsulinType != "basal" || h.Treatments[0].InsulinName != "" ||
		h.Treatments[1].InsulinType != "rapid" || h.Treatments[2].Glucose == nil || *h.Treatments[2].Glucose != 144 {
		t.Fatalf("treatments %+v", h.Treatments)
	}
	if len(h.MBG.V) != 1 || h.MBG.V[0] != 120 {
		t.Fatalf("mbg %v: repeats of the 144 check must be left out", h.MBG.V)
	}
}

func TestHistoryLongSpanIsAggregated(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	bearer := map[string]string{"Authorization": "Bearer " + xd}
	// 12 readings ending at 00:55 UTC: 00:00..00:55, values 100..155
	vals := make([]int, 12)
	for i := range vals {
		vals[i] = 100 + i*5
	}
	upload(t, f, xd, testNow.Add(-5*time.Minute), "Flat", vals...)

	var h struct {
		Step int
		SGV  struct {
			T         []int64
			V, Lo, Hi []int
		}
	}
	// three days: 15-minute intervals with mean and range, aligned on the clock
	from := testNow.Add(-3 * 24 * time.Hour).UnixMilli()
	json.NewDecoder(f.get(t, fmt.Sprintf("/api/history?from=%d", from), bearer).Body).Decode(&h)
	if h.Step != 15 || len(h.SGV.V) != 4 {
		t.Fatalf("step %d, %d intervals: %+v", h.Step, len(h.SGV.V), h.SGV)
	}
	if h.SGV.T[0] != testNow.Add(-time.Hour).UnixMilli() || h.SGV.V[0] != 105 || h.SGV.Lo[0] != 100 || h.SGV.Hi[0] != 110 {
		t.Fatalf("first interval %d %d %d..%d", h.SGV.T[0], h.SGV.V[0], h.SGV.Lo[0], h.SGV.Hi[0])
	}
	// asking finer than the span allows still gets 15 minutes; coarser is honoured
	json.NewDecoder(f.get(t, fmt.Sprintf("/api/history?from=%d&step=0", from), bearer).Body).Decode(&h)
	if h.Step != 15 {
		t.Fatalf("step 0 on 3 days: %d", h.Step)
	}
	json.NewDecoder(f.get(t, fmt.Sprintf("/api/history?from=%d&step=60", from), bearer).Body).Decode(&h)
	if h.Step != 60 || len(h.SGV.V) != 1 || h.SGV.V[0] != 128 || h.SGV.Lo[0] != 100 || h.SGV.Hi[0] != 155 {
		t.Fatalf("hourly: %+v", h)
	}
}

func TestForecastQualityAndBlindSpots(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	// two steady hours: every replayed forecast comes true
	vals := make([]int, 24)
	for i := range vals {
		vals[i] = 130
	}
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", vals...)
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Meal Bolus","carbs":30,"insulin":2,"created_at":"`+
		testNow.Add(-40*time.Minute).UTC().Format(time.RFC3339)+`"}`), xdrip(xd))
	var s struct {
		Q struct {
			Err80   int `json:"err80"`
			HitPct  int `json:"hit_pct"`
			N       int
			Ignores []struct{ Kind string } `json:"ignores"`
		} `json:"forecast_quality"`
	}
	json.NewDecoder(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + dad}).Body).Decode(&s)
	if s.Q.N == 0 || s.Q.HitPct != 100 || s.Q.Err80 != 0 {
		t.Fatalf("quality %+v", s.Q)
	}
	if len(s.Q.Ignores) != 1 || s.Q.Ignores[0].Kind != "food" {
		t.Fatalf("the meal 40 min ago is what the trend cannot see: %+v", s.Q.Ignores)
	}
}
