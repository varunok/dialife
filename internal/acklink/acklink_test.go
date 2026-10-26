package acklink

import (
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

const secret = "0123456789abcdef0123456789abcdef-test"

func query(t *testing.T, link string) url.Values {
	t.Helper()
	u, err := url.Parse(link)
	if err != nil {
		t.Fatal(err)
	}
	return u.Query()
}

func TestRoundTripAndTampering(t *testing.T) {
	s, err := NewSigner(secret)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_792_886_400, 0)
	link := s.URL("https://dia.example", 12, 8, now.Add(6*time.Hour), ActHandle, 0)
	if !strings.HasPrefix(link, "https://dia.example/api/alarms/12/ack-link?") {
		t.Fatalf("link %s", link)
	}
	q := query(t, link)

	if d, act, m, err := s.Verify(12, q, now); err != nil || d != 8 || act != ActHandle || m != 0 {
		t.Fatalf("valid link: %d %s %d %v", d, act, m, err)
	}
	if _, _, _, err := s.Verify(13, q, now); err == nil {
		t.Fatal("link accepted for another alarm")
	}
	if _, _, _, err := s.Verify(12, q, now.Add(7*time.Hour)); err == nil {
		t.Fatal("expired link accepted")
	}

	forged := url.Values{"d": {"9"}, "exp": q["exp"], "sig": q["sig"]}
	if _, _, _, err := s.Verify(12, forged, now); err == nil {
		t.Fatal("link accepted for another person")
	}
	longer := url.Values{"d": q["d"], "exp": {"9999999999"}, "sig": q["sig"]}
	if _, _, _, err := s.Verify(12, longer, now); err == nil {
		t.Fatal("extended expiry accepted")
	}
	if _, _, _, err := s.Verify(12, url.Values{}, now); err == nil {
		t.Fatal("empty query accepted")
	}

	other, _ := NewSigner(secret + "x")
	if _, _, _, err := other.Verify(12, q, now); err == nil {
		t.Fatal("link accepted under another secret")
	}
}

func TestShortSecretRejected(t *testing.T) {
	if _, err := NewSigner("too-short"); err == nil {
		t.Fatal("short secret accepted")
	}
}

func TestActionAndMinutesAreSigned(t *testing.T) {
	s, _ := NewSigner(secret)
	now := time.Unix(1_792_886_400, 0)
	q := query(t, s.URL("https://dia.example", 12, 8, now.Add(time.Hour), ActHandle, 120))
	if d, act, m, err := s.Verify(12, q, now); err != nil || d != 8 || act != ActHandle || m != 120 {
		t.Fatalf("verify: %d %s %d %v", d, act, m, err)
	}
	edit := func(k, v string) url.Values {
		c := url.Values{}
		for kk, vv := range q {
			c[kk] = vv
		}
		if v == "" {
			c.Del(k)
		} else {
			c.Set(k, v)
		}
		return c
	}
	for name, bad := range map[string]url.Values{
		"longer quiet":    edit("s", "1440"),
		"quiet dropped":   edit("s", ""),
		"other action":    edit("a", ActHelp),
		"action dropped":  edit("a", ""),
		"unknown action":  edit("a", "resolve"),
		"zero minutes":    edit("s", "0"),
		"garbage minutes": edit("s", "abc"),
	} {
		if _, _, _, err := s.Verify(12, bad, now); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
	help := query(t, s.URL("https://dia.example", 12, 8, now.Add(time.Hour), ActHelp, 0))
	if _, act, _, err := s.Verify(12, help, now); err != nil || act != ActHelp {
		t.Fatalf("help link: %s %v", act, err)
	}
}

// Links in notifications sent before episodes existed must keep working.
func TestLegacyLinksStillVerify(t *testing.T) {
	s, _ := NewSigner(secret)
	now := time.Unix(1_792_886_400, 0)
	exp := now.Add(time.Hour).Unix()
	legacy := url.Values{"d": {"8"}, "exp": {strconv.FormatInt(exp, 10)}, "s": {"120"}, "sig": {s.mac(12, 8, exp, "", 120)}}
	if d, act, m, err := s.Verify(12, legacy, now); err != nil || d != 8 || act != ActHandle || m != 120 {
		t.Fatalf("legacy quiet link: %d %s %d %v", d, act, m, err)
	}
	plain := url.Values{"d": {"8"}, "exp": {strconv.FormatInt(exp, 10)}, "sig": {s.mac(12, 8, exp, "", 0)}}
	if _, act, m, err := s.Verify(12, plain, now); err != nil || act != ActHandle || m != 0 {
		t.Fatalf("legacy ack link: %s %d %v", act, m, err)
	}
}
