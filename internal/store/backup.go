package store

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const backupPrefix, backupSuffix = "dialife-", ".db"

// BackupName is dialife-YYYY-MM-DD.db for the given (UTC) day.
func BackupName(day time.Time) string {
	return backupPrefix + day.UTC().Format("2006-01-02") + backupSuffix
}

// Backup writes a consistent snapshot of the live database into dir (VACUUM
// INTO, safe while the server runs), checks the copy's integrity, and only
// then moves it into place. Returns the final path.
func (s *Store) Backup(ctx context.Context, dir string, now time.Time) (string, error) {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return "", err
	}
	final := filepath.Join(dir, BackupName(now))
	tmp := filepath.Join(dir, ".tmp-"+BackupName(now))
	os.Remove(tmp)
	if _, err := s.w.ExecContext(ctx, `VACUUM INTO ?`, tmp); err != nil {
		return "", fmt.Errorf("vacuum into: %w", err)
	}
	if err := checkIntegrity(ctx, tmp); err != nil {
		os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, final); err != nil {
		os.Remove(tmp)
		return "", err
	}
	return final, nil
}

func checkIntegrity(ctx context.Context, path string) error {
	db, err := sql.Open("sqlite", "file:"+path+"?mode=ro")
	if err != nil {
		return err
	}
	defer db.Close()
	var res string
	if err := db.QueryRowContext(ctx, `PRAGMA integrity_check`).Scan(&res); err != nil {
		return fmt.Errorf("integrity check: %w", err)
	}
	if res != "ok" {
		return fmt.Errorf("backup failed integrity check: %s", res)
	}
	return nil
}

// PruneBackups keeps the newest keepDaily backups plus the newest keepMonthly
// first-of-month ones (dialife-YYYY-MM-01.db) and deletes the rest.
// Files not named like backups are never touched.
func PruneBackups(dir string, keepDaily, keepMonthly int) (deleted []string, err error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, e := range entries {
		n := e.Name()
		if e.Type().IsRegular() && isBackupName(n) {
			names = append(names, n)
		}
	}
	for _, n := range backupsToDelete(names, keepDaily, keepMonthly) {
		if err := os.Remove(filepath.Join(dir, n)); err != nil {
			return deleted, err
		}
		deleted = append(deleted, n)
	}
	return deleted, nil
}

func isBackupName(n string) bool {
	if !strings.HasPrefix(n, backupPrefix) || !strings.HasSuffix(n, backupSuffix) {
		return false
	}
	_, err := time.Parse("2006-01-02", strings.TrimSuffix(strings.TrimPrefix(n, backupPrefix), backupSuffix))
	return err == nil
}

func backupsToDelete(names []string, keepDaily, keepMonthly int) []string {
	sort.Sort(sort.Reverse(sort.StringSlice(names))) // ISO dates: newest first
	keep := map[string]bool{}
	monthly := 0
	for i, n := range names {
		if i < keepDaily {
			keep[n] = true
		}
		if strings.HasSuffix(n, "-01"+backupSuffix) && monthly < keepMonthly {
			keep[n] = true
			monthly++
		}
	}
	var out []string
	for _, n := range names {
		if !keep[n] {
			out = append(out, n)
		}
	}
	return out
}
