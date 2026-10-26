// Package acklink signs one-shot "acknowledge this alarm as this person"
// links for notification buttons, so push messages never carry an API token.
package acklink

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"time"
)

var ErrInvalid = errors.New("invalid ack link")

type Signer struct {
	key []byte
}

// NewSigner derives a dedicated key from the server secret.
func NewSigner(secret string) (*Signer, error) {
	if len(secret) < 32 {
		return nil, errors.New("secret must be at least 32 characters")
	}
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte("dialife ack link v1"))
	return &Signer{key: m.Sum(nil)}, nil
}

// Actions a link can carry.
const (
	ActHandle = "handle" // "I'm on it" (minutes > 0: and keep quiet that long)
	ActOK     = "ok"     // check-in answer: all under control
	ActHelp   = "help"   // check-in answer: need help
	ActComing = "coming" // answer to a help request: on my way
)

// mac covers action and minutes, so a "quiet 2 h" button cannot be edited
// into a day or into another action. Links without an action are the older
// form (ack + optional snooze) and stay valid.
func (s *Signer) mac(alarmID, deviceID, exp int64, action string, minutes int) string {
	m := hmac.New(sha256.New, s.key)
	switch {
	case action != "":
		fmt.Fprintf(m, "v2|%d|%d|%d|%s|%d", alarmID, deviceID, exp, action, minutes)
	case minutes > 0:
		fmt.Fprintf(m, "%d|%d|%d|%d", alarmID, deviceID, exp, minutes)
	default:
		fmt.Fprintf(m, "%d|%d|%d", alarmID, deviceID, exp)
	}
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// URL returns base + /api/alarms/{id}/ack-link?d=&exp=&a=[&s=]&sig=.
func (s *Signer) URL(base string, alarmID, deviceID int64, expires time.Time, action string, minutes int) string {
	exp := expires.Unix()
	q := url.Values{}
	q.Set("d", strconv.FormatInt(deviceID, 10))
	q.Set("exp", strconv.FormatInt(exp, 10))
	if action != "" {
		q.Set("a", action)
	}
	if minutes > 0 {
		q.Set("s", strconv.Itoa(minutes))
	}
	q.Set("sig", s.mac(alarmID, deviceID, exp, action, minutes))
	return fmt.Sprintf("%s/api/alarms/%d/ack-link?%s", base, alarmID, q.Encode())
}

// Verify checks a link's query for alarmID and returns who it acts as, the
// action (older links: ActHandle) and its minutes (0: none).
func (s *Signer) Verify(alarmID int64, q url.Values, now time.Time) (deviceID int64, action string, minutes int, err error) {
	deviceID, err1 := strconv.ParseInt(q.Get("d"), 10, 64)
	exp, err2 := strconv.ParseInt(q.Get("exp"), 10, 64)
	if err1 != nil || err2 != nil {
		return 0, "", 0, ErrInvalid
	}
	if v := q.Get("s"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 {
			return 0, "", 0, ErrInvalid
		}
		minutes = n
	}
	action = q.Get("a")
	switch action {
	case "", ActHandle, ActOK, ActHelp, ActComing:
	default:
		return 0, "", 0, ErrInvalid
	}
	want := s.mac(alarmID, deviceID, exp, action, minutes)
	if !hmac.Equal([]byte(want), []byte(q.Get("sig"))) {
		return 0, "", 0, ErrInvalid
	}
	if now.Unix() > exp {
		return 0, "", 0, ErrInvalid
	}
	if action == "" {
		action = ActHandle
	}
	return deviceID, action, minutes, nil
}
