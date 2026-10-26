package nstime

import (
	"encoding/json"
	"testing"
	"time"
)

func TestParseTime(t *testing.T) {
	want := time.Date(2026, 10, 25, 0, 0, 0, 0, time.UTC).UnixMilli()
	cases := []any{
		json.Number("1792886400000"),
		"1792886400000",
		"2026-10-25T00:00:00Z",
		"2026-10-25T00:00:00.000Z",
		"2026-10-24T21:00:00.000-0300", // xDrip+ dateString
		"2026-10-24T21:00:00-03:00",
		"2026-10-25T00:00:00",
		"Sun, 25 Oct 2026 00:00:00 GMT",
	}
	for _, c := range cases {
		got, ok := Parse(c)
		if !ok || got != want {
			t.Errorf("Parse(%v) = %d %v, want %d", c, got, ok, want)
		}
	}
	for _, bad := range []any{"yesterday", "", json.Number("1.5e400"), true, nil} {
		if _, ok := Parse(bad); ok {
			t.Errorf("Parse(%v) accepted", bad)
		}
	}
}
