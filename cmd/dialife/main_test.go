package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
	"dialife/internal/config"
	"dialife/internal/store"
)

// newConfig is a config with a fresh database of its own.
func newConfig(t *testing.T) config.Config {
	t.Helper()
	return config.Config{DBPath: filepath.Join(t.TempDir(), "dialife.db"), TZ: "UTC"}
}

// run calls a command and returns what it printed; the commands write to os.Stdout.
func run(t *testing.T, f func() error) (string, error) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	out := make(chan string)
	go func() {
		b, _ := io.ReadAll(r)
		out <- string(b)
	}()
	stdout := os.Stdout
	os.Stdout = w
	err = f()
	os.Stdout = stdout
	w.Close()
	return <-out, err
}

func mustRun(t *testing.T, f func() error) string {
	t.Helper()
	s, err := run(t, f)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// openStore opens the command's database, for checking what a command wrote.
func openStore(t *testing.T, cfg config.Config) *store.Store {
	t.Helper()
	s, err := openMigrated(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

var tokenLine = regexp.MustCompile(`(?m)^(\S{20,})$`)

func TestDeviceCommands(t *testing.T) {
	cfg := newConfig(t)
	ctx := context.Background()

	out := mustRun(t, func() error { return device(cfg, []string{"add", "-name", "Pixel", "-role", "caregiver"}) })
	if !strings.Contains(out, `device #1 "Pixel" (caregiver) created`) {
		t.Fatalf("add: %q", out)
	}
	token := tokenLine.FindString(out)
	s := openStore(t, cfg)
	if d, err := s.ActiveDeviceByTokenSHA1(ctx, auth.Hash(token)); err != nil || d.ID != 1 {
		t.Fatalf("the printed token is not the device's: %+v %v", d, err)
	}

	out = mustRun(t, func() error { return device(cfg, []string{"person", "-id", "1", "-name", "Тато"}) })
	if !strings.Contains(out, `device #1 belongs to "Тато"`) {
		t.Fatalf("person: %q", out)
	}
	out = mustRun(t, func() error { return device(cfg, []string{"list"}) })
	if !strings.Contains(out, "Pixel") || !strings.Contains(out, "Тато") || !strings.Contains(out, "caregiver") {
		t.Fatalf("list: %q", out)
	}

	out = mustRun(t, func() error { return device(cfg, []string{"rotate", "-id", "1"}) })
	newToken := tokenLine.FindString(out)
	if newToken == "" || newToken == token {
		t.Fatalf("rotate: %q", out)
	}
	if _, err := s.ActiveDeviceByTokenSHA1(ctx, auth.Hash(token)); err == nil {
		t.Fatal("the old token still works after rotate")
	}
	if _, err := s.ActiveDeviceByTokenSHA1(ctx, auth.Hash(newToken)); err != nil {
		t.Fatalf("the new token does not work: %v", err)
	}

	out = mustRun(t, func() error { return device(cfg, []string{"revoke", "-id", "1"}) })
	if !strings.Contains(out, "device #1 revoked") {
		t.Fatalf("revoke: %q", out)
	}
	if _, err := s.ActiveDeviceByTokenSHA1(ctx, auth.Hash(newToken)); err == nil {
		t.Fatal("a revoked device still gets in")
	}
}

func TestDeviceCommandErrors(t *testing.T) {
	cfg := newConfig(t)
	for name, args := range map[string][]string{
		"nothing":             {},
		"unknown":             {"rename"},
		"add without a name":  {"add", "-role", "reader"},
		"add with a bad role": {"add", "-name", "x", "-role", "admin"},
		"rotate unknown":      {"rotate", "-id", "9"},
		"revoke unknown":      {"revoke", "-id", "9"},
		"person unknown":      {"person", "-id", "9", "-name", "x"},
		"a bad flag":          {"add", "-x"},
	} {
		if _, err := run(t, func() error { return device(cfg, args) }); err == nil {
			t.Errorf("%s: no error", name)
		}
	}
}

func TestAlarmSettings(t *testing.T) {
	cfg := newConfig(t)
	out := mustRun(t, func() error { return alarmSettings(cfg, []string{"set", "high=230", "high_for_min=45"}) })
	if !strings.Contains(out, "saved") || !strings.Contains(out, "> 230 mg/dL for 45 min") {
		t.Fatalf("set: %q", out)
	}
	out = mustRun(t, func() error { return alarmSettings(cfg, []string{"show"}) })
	if !strings.Contains(out, "> 230 mg/dL for 45 min") {
		t.Fatalf("show after set: %q", out)
	}
	for _, args := range [][]string{{}, {"edit"}, {"set"}, {"set", "high=nope"}} {
		if _, err := run(t, func() error { return alarmSettings(cfg, args) }); err == nil {
			t.Errorf("%v: no error", args)
		}
	}
}

func TestLiveAlarms(t *testing.T) {
	cfg := newConfig(t)
	if out := mustRun(t, func() error { return alarmSettings(cfg, []string{"live"}) }); !strings.Contains(out, "no live alarms") {
		t.Fatalf("none: %q", out)
	}
	s := openStore(t, cfg)
	ctx := context.Background()
	d, err := s.CreateDevice(ctx, "Pixel", "caregiver", auth.Hash("t"), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	sgv := 62
	id, err := s.InsertAlarm(ctx, store.Alarm{Kind: "low", Level: "warn", StartedAt: time.Now().UnixMilli(), LastSGV: &sgv})
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.AlarmByID(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	a.HandledBy, a.HandledAt, a.HelpAt = &d.ID, &now, &now
	if err := s.UpdateAlarm(ctx, a); err != nil {
		t.Fatal(err)
	}
	out := mustRun(t, func() error { return alarmSettings(cfg, []string{"live"}) })
	for _, want := range []string{"low", "62", "help requested", "Pixel"} {
		if !strings.Contains(out, want) {
			t.Errorf("live: no %q in %q", want, out)
		}
	}
}

func TestReadings(t *testing.T) {
	cfg := newConfig(t)
	if out := mustRun(t, func() error { return readings(cfg, nil) }); !strings.Contains(out, "no readings yet") {
		t.Fatalf("none: %q", out)
	}
	s := openStore(t, cfg)
	start := time.Now().Add(-time.Hour).Truncate(time.Minute)
	var es []store.Entry
	for i := 0; i < 12; i++ { // rising 6 mg/dL every 5 minutes, and a sensor error code
		v := 100 + 6*i
		es = append(es, store.Entry{ID: fmt.Sprint("e", i), Type: "sgv", Date: start.Add(time.Duration(i) * 5 * time.Minute).UnixMilli(), SGV: &v})
	}
	code := 5
	es = append(es, store.Entry{ID: "code", Type: "sgv", Date: start.Add(-5 * time.Minute).UnixMilli(), SGV: &code})
	up, err := s.CreateDevice(context.Background(), "xDrip", "uploader", auth.Hash("u"), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.SaveEntries(context.Background(), es, up.ID, time.Now()); err != nil {
		t.Fatal(err)
	}
	out := mustRun(t, func() error { return readings(cfg, []string{"-n", "3"}) })
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 5 { // the age line, the heads, three readings
		t.Fatalf("want 3 readings: %q", out)
	}
	if !strings.Contains(lines[2], "166") || !strings.Contains(lines[2], "+6") {
		t.Errorf("the newest reading and its delta: %q", lines[2])
	}
	if strings.Contains(out, "\t5\t") {
		t.Errorf("a sensor error code shown: %q", out)
	}
	if _, err := run(t, func() error { return readings(cfg, []string{"-n", "x"}) }); err == nil {
		t.Error("a bad -n: no error")
	}
}

const foodTable = `{
  "source": "test", "title": "Test table",
  "categories": [{"id": "grain", "names": {"uk": "Крупи", "en": "Grains", "es": "Cereales"}}],
  "items": [{"id": "rice", "cat": "grain", "carbs": 25, "qty": 1, "unit": "pc",
    "names": {"uk": "Рис", "en": "Rice", "es": "Arroz"},
    "portions": {"uk": "100 г", "en": "100 g", "es": "100 g"}}]
}`

func TestFoodsImportAndShow(t *testing.T) {
	cfg := newConfig(t)
	file := filepath.Join(t.TempDir(), "foods.json")
	if err := os.WriteFile(file, []byte(foodTable), 0o600); err != nil {
		t.Fatal(err)
	}
	out := mustRun(t, func() error { return foods(cfg, []string{"import", file}) })
	if !strings.Contains(out, "test: 1 categories, 1 foods") {
		t.Fatalf("import: %q", out)
	}
	out = mustRun(t, func() error { return foods(cfg, []string{"show"}) })
	if !strings.Contains(out, "Test table") {
		t.Fatalf("show: %q", out)
	}
	for _, args := range [][]string{{}, {"import"}, {"drop"}, {"import", filepath.Join(t.TempDir(), "none.json")}} {
		if _, err := run(t, func() error { return foods(cfg, args) }); err == nil {
			t.Errorf("%v: no error", args)
		}
	}
}

func TestFoodFileIsCheckedBeforeWriting(t *testing.T) {
	bad := map[string]string{
		"not json":           `{`,
		"no items":           `{"source": "s", "title": "t", "items": []}`,
		"category not in es": `{"source": "s", "title": "t", "categories": [{"id": "c", "names": {"uk": "к", "en": "c"}}], "items": [{"id": "a", "cat": "c"}]}`,
		"unknown category":   strings.Replace(foodTable, `"cat": "grain"`, `"cat": "fruit"`, 1),
		"repeated id":        strings.Replace(foodTable, `"es": "100 g"}}]`, `"es": "100 g"}}, {"id": "rice", "cat": "grain", "names": {"uk": "Р", "en": "R", "es": "A"}}]`, 1),
		"carbs out of range": strings.Replace(foodTable, `"carbs": 25`, `"carbs": 900`, 1),
		"a bad unit":         strings.Replace(foodTable, `"unit": "pc"`, `"unit": "cup"`, 1),
		"no name in en":      strings.Replace(foodTable, `"en": "Rice", `, ``, 1),
		"carbs100 no source": strings.Replace(foodTable, `"unit": "pc",`, `"unit": "pc", "carbs100": 70,`, 1),
		"no portion in uk":   strings.Replace(foodTable, `"uk": "100 г", `, ``, 1),
	}
	for name, body := range bad {
		if _, err := readFoodFile(strings.NewReader(body)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	if _, err := readFoodFile(strings.NewReader(foodTable)); err != nil {
		t.Errorf("a good table: %v", err)
	}
}

func TestMigrateAndBackup(t *testing.T) {
	cfg := newConfig(t)
	if err := migrate(cfg); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if _, err := run(t, func() error { return backup(cfg, []string{"-dir", dir}) }); err != nil {
		t.Fatal(err)
	}
	if fs, _ := os.ReadDir(dir); len(fs) != 1 {
		t.Fatalf("want one backup, got %d", len(fs))
	}
	if _, err := run(t, func() error { return backup(cfg, []string{"-x"}) }); err == nil {
		t.Error("a bad flag: no error")
	}
}

func TestLocFallsBackToUTC(t *testing.T) {
	if loc(config.Config{TZ: "Nowhere/City"}) != time.UTC {
		t.Error("an unknown zone is not UTC")
	}
	if loc(config.Config{TZ: "Europe/Kyiv"}).String() != "Europe/Kyiv" {
		t.Error("a known zone is lost")
	}
	if fmtTime(nil) != "-" {
		t.Error("no time is not -")
	}
}
