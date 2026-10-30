package app

import (
	"strings"
	"testing"

	"dialife/internal/bus"
	"dialife/internal/questions"
	"dialife/internal/weblogin"
)

func TestForStream(t *testing.T) {
	logout := bus.Event{ID: 1, Type: weblogin.EventLogout, Data: []byte(`{"session":5}`)}
	if _, v := forStream(logout, true, 5); v != logOut {
		t.Errorf("this browser's logout ends its stream: %v", v)
	}
	if _, v := forStream(logout, true, 6); v != skipEvent {
		t.Errorf("another browser's logout: %v", v)
	}
	if _, v := forStream(logout, false, 0); v != skipEvent {
		t.Errorf("a phone ignores browser logouts: %v", v)
	}

	q := bus.Event{Type: questions.EventQuestion, Data: []byte(`{}`)}
	if _, v := forStream(q, true, 5); v != skipEvent {
		t.Errorf("the web viewer gets no questions: %v", v)
	}
	if _, v := forStream(q, false, 0); v != sendEvent {
		t.Errorf("a phone gets questions: %v", v)
	}

	st := bus.Event{Type: EventState, Data: []byte(`{"questions":[{"id":1}],"now":1}`)}
	if out, v := forStream(st, true, 5); v != sendEvent || strings.Contains(string(out.Data), "questions") {
		t.Errorf("the web viewer's state without questions: %v %s", v, out.Data)
	}
	if out, _ := forStream(st, false, 0); !strings.Contains(string(out.Data), "questions") {
		t.Errorf("a phone's state keeps them: %s", out.Data)
	}
}
