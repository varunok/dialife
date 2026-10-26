package store

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"testing"
	"time"
)

func TestBackupIsCompleteAndReadable(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	now := time.Date(2026, 10, 25, 6, 30, 0, 0, time.UTC)
	sgv := 120
	if _, err := s.SaveEntries(ctx, []Entry{{ID: "a", Type: "sgv", Date: 1, SGV: &sgv, Raw: []byte("{}")}}, 0, now); err == nil {
		// device_id 0 has no device row; foreign keys must reject it
		t.Fatal("entry with unknown device stored")
	}
	d, _ := s.CreateDevice(ctx, "xDrip", "uploader", "h", now)
	if _, err := s.SaveEntries(ctx, []Entry{{ID: "a", Type: "sgv", Date: 1, SGV: &sgv, Raw: []byte("{}")}}, d.ID, now); err != nil {
		t.Fatal(err)
	}

	dir := filepath.Join(t.TempDir(), "backups")
	path, err := s.Backup(ctx, dir, now)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(path) != "dialife-2026-10-25.db" {
		t.Fatalf("name %s", path)
	}
	db, _ := sql.Open("sqlite", "file:"+path+"?mode=ro")
	defer db.Close()
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM entries`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("backup has %d entries: %v", n, err)
	}
	if left, _ := filepath.Glob(filepath.Join(dir, ".tmp-*")); len(left) != 0 {
		t.Fatalf("temp files left: %v", left)
	}

	// running twice on one day replaces that day's file
	if _, err := s.Backup(ctx, dir, now.Add(time.Hour)); err != nil {
		t.Fatalf("second backup same day: %v", err)
	}
}

func TestBackupRetention(t *testing.T) {
	var names []string
	day := time.Date(2025, 9, 1, 0, 0, 0, 0, time.UTC)
	for i := 0; i < 420; i++ { // ~14 months of daily backups
		names = append(names, BackupName(day.AddDate(0, 0, i)))
	}
	del := backupsToDelete(slices.Clone(names), 14, 12)
	keep := map[string]bool{}
	for _, n := range names {
		keep[n] = true
	}
	for _, n := range del {
		delete(keep, n)
	}
	var kept []string
	for n := range keep {
		kept = append(kept, n)
	}
	sort.Strings(kept)

	newest := BackupName(day.AddDate(0, 0, 419))
	if !keep[newest] || len(kept) != 14+12 && len(kept) != 14+11 {
		t.Fatalf("kept %d: %v", len(kept), kept)
	}
	monthly := 0
	for _, n := range kept {
		if n[len(n)-6:] == "-01.db" {
			monthly++
		}
	}
	if monthly < 12 {
		t.Fatalf("only %d monthly kept: %v", monthly, kept)
	}
	if keep["dialife-2025-09-01.db"] {
		t.Fatal("13+ month old monthly kept")
	}
}

func TestPruneIgnoresForeignFiles(t *testing.T) {
	dir := t.TempDir()
	for i := 1; i <= 20; i++ {
		os.WriteFile(filepath.Join(dir, fmt.Sprintf("dialife-2026-10-%02d.db", i)), nil, 0o640)
	}
	for _, other := range []string{"notes.txt", "dialife-latest.db", ".tmp-dialife-2026-10-21.db"} {
		os.WriteFile(filepath.Join(dir, other), nil, 0o640)
	}
	deleted, err := PruneBackups(dir, 14, 12)
	if err != nil {
		t.Fatal(err)
	}
	// 20 dailies: newest 14 kept, 10-01 kept as monthly → 5 deleted
	if len(deleted) != 5 || slices.Contains(deleted, "dialife-2026-10-01.db") {
		t.Fatalf("deleted %v", deleted)
	}
	for _, other := range []string{"notes.txt", "dialife-latest.db", ".tmp-dialife-2026-10-21.db"} {
		if _, err := os.Stat(filepath.Join(dir, other)); err != nil {
			t.Fatalf("%s was touched", other)
		}
	}
}
