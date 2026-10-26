package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if _, err := s.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestMigrateIsIdempotent(t *testing.T) {
	s := openTest(t)
	n, err := s.Migrate(context.Background())
	if err != nil || n != 0 {
		t.Fatalf("second migrate: n=%d err=%v", n, err)
	}
}

func TestDeviceLifecycle(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	now := time.UnixMilli(1_700_000_000_000)

	d, err := s.CreateDevice(ctx, "xDrip", "uploader", "hash1", now)
	if err != nil {
		t.Fatal(err)
	}
	if d.DisplayUnits != "mg/dl" || !d.CreatedAt.Equal(now) || d.LastSeenAt != nil {
		t.Fatalf("unexpected device %+v", d)
	}

	got, err := s.ActiveDeviceByTokenSHA1(ctx, "hash1")
	if err != nil || got.ID != d.ID {
		t.Fatalf("lookup: %+v %v", got, err)
	}

	if _, err := s.CreateDevice(ctx, "xDrip", "reader", "hash2", now); err == nil {
		t.Fatal("duplicate active name accepted")
	}

	if err := s.TouchDevice(ctx, d.ID, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeDevice(ctx, d.ID, now.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeDevice(ctx, d.ID, now.Add(time.Hour)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second revoke: %v", err)
	}
	if _, err := s.ActiveDeviceByTokenSHA1(ctx, "hash1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked device still active: %v", err)
	}

	// name is free again after revoke
	if _, err := s.CreateDevice(ctx, "xDrip", "uploader", "hash3", now); err != nil {
		t.Fatalf("reuse name after revoke: %v", err)
	}

	all, err := s.ListDevices(ctx)
	if err != nil || len(all) != 2 {
		t.Fatalf("list: %d %v", len(all), err)
	}
	if all[0].LastSeenAt == nil || all[0].RevokedAt == nil {
		t.Fatalf("timestamps not stored: %+v", all[0])
	}
}

func TestReaderPoolIsReadOnly(t *testing.T) {
	s := openTest(t)
	if _, err := s.r.Exec(`DELETE FROM devices`); err == nil {
		t.Fatal("write through reader pool succeeded")
	}
}

func TestRotateToken(t *testing.T) {
	ctx := context.Background()
	s := openTest(t)
	now := time.UnixMilli(1_700_000_000_000)
	d, _ := s.CreateDevice(ctx, "Тато", "caregiver", "old", now)

	got, err := s.RotateToken(ctx, d.ID, "new")
	if err != nil || got.ID != d.ID || got.Name != "Тато" {
		t.Fatalf("rotate: %+v %v", got, err)
	}
	if _, err := s.ActiveDeviceByTokenSHA1(ctx, "old"); !errors.Is(err, ErrNotFound) {
		t.Fatal("old token still works")
	}
	if dev, err := s.ActiveDeviceByTokenSHA1(ctx, "new"); err != nil || dev.ID != d.ID {
		t.Fatalf("new token: %+v %v", dev, err)
	}
	s.RevokeDevice(ctx, d.ID, now)
	if _, err := s.RotateToken(ctx, d.ID, "newer"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked device rotated: %v", err)
	}
}
