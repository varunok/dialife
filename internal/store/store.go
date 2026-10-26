// Package store owns the SQLite database: connections, migrations and queries.
package store

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"github.com/pressly/goose/v3"
	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrations embed.FS

var ErrNotFound = errors.New("not found")

// Store keeps a single-connection writer (SQLite allows one writer at a time)
// and a small read-only pool, so readers never hit "database is locked".
type Store struct {
	w *sql.DB
	r *sql.DB
}

const pragmas = "_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"

func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, fmt.Errorf("create db dir: %w", err)
	}
	dsn := "file:" + path + "?" + pragmas

	w, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)

	r, err := sql.Open("sqlite", dsn+"&_pragma=query_only(1)")
	if err != nil {
		w.Close()
		return nil, err
	}
	r.SetMaxOpenConns(4)

	s := &Store{w: w, r: r}
	if err := s.Ping(context.Background()); err != nil {
		s.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return errors.Join(s.r.Close(), s.w.Close())
}

func (s *Store) Ping(ctx context.Context) error {
	if err := s.w.PingContext(ctx); err != nil {
		return err
	}
	return s.r.PingContext(ctx)
}

// Migrate applies pending migrations and returns how many were applied.
func (s *Store) Migrate(ctx context.Context) (int, error) {
	fsys, err := fs.Sub(migrations, "migrations")
	if err != nil {
		return 0, err
	}
	p, err := goose.NewProvider(goose.DialectSQLite3, s.w, fsys)
	if err != nil {
		return 0, err
	}
	res, err := p.Up(ctx)
	return len(res), err
}
