package auth

import (
	"strings"
	"testing"
)

func TestNewTokenFormat(t *testing.T) {
	seen := map[string]bool{}
	for range 200 {
		tok, err := NewToken()
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(tok, tokenPrefix) || len(tok) != len(tokenPrefix)+tokenDigits {
			t.Fatalf("bad token %q (len %d)", tok, len(tok))
		}
		for _, c := range tok[len(tokenPrefix):] {
			if !strings.ContainsRune("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", c) {
				t.Fatalf("non-base62 char %q in %q", c, tok)
			}
		}
		if seen[tok] {
			t.Fatalf("duplicate token %q", tok)
		}
		seen[tok] = true
	}
}

func TestHashMatchesXDrip(t *testing.T) {
	// xDrip+ sends Guava's sha1 of the UTF-8 secret as lowercase hex.
	if got, want := Hash("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d"; got != want {
		t.Fatalf("Hash(abc) = %s, want %s", got, want)
	}
}

func TestParseRole(t *testing.T) {
	for _, ok := range []string{"uploader", "caregiver", "reader"} {
		if _, err := ParseRole(ok); err != nil {
			t.Errorf("ParseRole(%q): %v", ok, err)
		}
	}
	for _, bad := range []string{"", "admin", "Uploader"} {
		if _, err := ParseRole(bad); err == nil {
			t.Errorf("ParseRole(%q) accepted", bad)
		}
	}
}
