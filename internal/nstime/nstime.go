// Package nstime parses the time formats Nightscout clients send.
package nstime

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

var timeLayouts = []string{
	time.RFC3339Nano,
	"2006-01-02T15:04:05.000Z0700", // xDrip+ sysTime/dateString: 2026-10-24T21:00:00.000-0300
	"2006-01-02T15:04:05Z0700",
	"2006-01-02T15:04:05.000",
	"2006-01-02T15:04:05",
	time.RFC1123Z,
	time.RFC1123,
}

// Parse reads unix ms (number or numeric string) or one of the ISO-ish
// layouts Nightscout clients send. Times without a zone are taken as UTC.
func Parse(v any) (int64, bool) {
	switch t := v.(type) {
	case json.Number:
		if ms, err := t.Int64(); err == nil {
			return ms, true
		}
		if f, err := t.Float64(); err == nil {
			return int64(f), true
		}
	case string:
		t = strings.TrimSpace(t)
		var ms int64
		if _, err := fmt.Sscan(t, &ms); err == nil && fmt.Sprint(ms) == t {
			return ms, true
		}
		for _, layout := range timeLayouts {
			if tm, err := time.Parse(layout, t); err == nil {
				return tm.UnixMilli(), true
			}
		}
	}
	return 0, false
}
