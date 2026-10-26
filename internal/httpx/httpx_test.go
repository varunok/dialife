package httpx

import (
	"net/http/httptest"
	"testing"
)

func TestClientIP(t *testing.T) {
	cases := []struct {
		name, remote, xff, want string
	}{
		{"direct client", "203.0.113.7:5555", "", "203.0.113.7"},
		{"direct client cannot spoof xff", "203.0.113.7:5555", "1.2.3.4", "203.0.113.7"},
		{"behind caddy", "127.0.0.1:40000", "198.51.100.9", "198.51.100.9"},
		{"behind caddy, spoofed prefix ignored", "127.0.0.1:40000", "1.2.3.4, 198.51.100.9", "198.51.100.9"},
		{"loopback without xff", "[::1]:40000", "", "::1"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := httptest.NewRequest("GET", "/", nil)
			r.RemoteAddr = c.remote
			if c.xff != "" {
				r.Header.Set("X-Forwarded-For", c.xff)
			}
			if got := ClientIP(r); got != c.want {
				t.Fatalf("ClientIP = %q, want %q", got, c.want)
			}
		})
	}
}
