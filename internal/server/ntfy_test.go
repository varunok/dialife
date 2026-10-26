package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/auth"
	"dialife/internal/notify"
)

type fakeNtfy struct {
	mu       sync.Mutex
	got      []notify.Message
	auth     []string
	cleared  []string // PUT /<topic>/<sequence id>/clear paths
	failNext int
	srv      *httptest.Server
}

func newFakeNtfy(t *testing.T) *fakeNtfy {
	f := &fakeNtfy{}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.failNext > 0 {
			f.failNext--
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		if r.Method == http.MethodPut {
			f.cleared = append(f.cleared, r.URL.Path)
			return
		}
		var m notify.Message
		body, _ := io.ReadAll(r.Body)
		json.Unmarshal(body, &m)
		f.got = append(f.got, m)
		f.auth = append(f.auth, r.Header.Get("Authorization"))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

// wait returns all messages once at least n arrived.
func (f *fakeNtfy) wait(t *testing.T, n int) []notify.Message {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		f.mu.Lock()
		if len(f.got) >= n {
			out := append([]notify.Message(nil), f.got...)
			f.mu.Unlock()
			return out
		}
		f.mu.Unlock()
		time.Sleep(10 * time.Millisecond)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	t.Fatalf("ntfy got %d messages, want %d: %+v", len(f.got), n, f.got)
	return nil
}

func (f *fakeNtfy) clears() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.cleared...)
}

// settle waits a moment and returns how many messages arrived in total.
func (f *fakeNtfy) settle() int {
	time.Sleep(60 * time.Millisecond)
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.got)
}

func startNtfy(t *testing.T, f *fixture, fake *fakeNtfy, targets []notify.Target) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	n := &notify.Ntfy{URL: fake.srv.URL, Token: "tk_test", Targets: targets, PublicURL: f.srv.URL, Signer: f.links,
		Bus: f.bus, Loc: time.UTC, Now: f.now, Client: fake.srv.Client(),
		LinkTTL: 12 * time.Hour, Retries: []time.Duration{10 * time.Millisecond}}
	go n.Run(ctx)
	deadline := time.Now().Add(time.Second)
	for f.bus.Subscribers() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
}

func tap(t *testing.T, a notify.Action) {
	t.Helper()
	resp, err := http.Post(a.URL, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("tap %q: status %d", a.Label, resp.StatusCode)
	}
}

func labels(m notify.Message) []string {
	var out []string
	for _, a := range m.Actions {
		out = append(out, a.Label)
	}
	return out
}

// The whole hypo episode through push notifications only, as a parent sees it.
func TestNtfyHypoEpisodeFromPushButtons(t *testing.T) {
	f := newFixture(t)
	fake := newFakeNtfy(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, _ := f.addDevice(t, "Тато", auth.RoleCaregiver)
	momID, _ := f.addDevice(t, "Мама", auth.RoleCaregiver)
	startNtfy(t, f, fake, []notify.Target{{Topic: "dialife-dad", DeviceID: dadID}, {Topic: "dialife-mom", DeviceID: momID}})

	upload(t, f, xd, f.now().Add(-time.Minute), "SingleDown", 52)
	msgs := fake.wait(t, 2)
	alarm := msgs[0]
	if alarm.Topic != "dialife-dad" || msgs[1].Topic != "dialife-mom" || alarm.Priority != 5 ||
		alarm.Title != "Низький цукор: 52 mg/dL" || fake.auth[0] != "Bearer tk_test" {
		t.Fatalf("alarm push %+v", msgs)
	}
	if got := labels(alarm); len(got) != 1 || got[0] != "Я займаюсь" {
		t.Fatalf("urgent low must offer only \"I'm on it\", got %v", got)
	}

	tap(t, alarm.Actions[0]) // dad
	msgs = fake.wait(t, 3)
	if fake.settle() != 3 {
		t.Fatalf("\"is on it\" must reach only mom: %+v", fake.got[2:])
	}
	if m := msgs[2]; m.Topic != "dialife-mom" || m.Title != "Тато займається" || m.Priority != 2 {
		t.Fatalf("handled push %+v", m)
	}
	sid := alarm.SequenceID
	if sid == "" || msgs[1].SequenceID != sid {
		t.Fatalf("alarm without a shared sequence id: %q %q", sid, msgs[1].SequenceID)
	}
	// taken: the ringing message goes away on both phones, the taker's too
	if got := fake.clears(); len(got) != 2 || got[0] != "/dialife-dad/"+sid+"/clear" || got[1] != "/dialife-mom/"+sid+"/clear" {
		t.Fatalf("clears after handle %v", got)
	}

	// 20 minutes later, barely better: a quiet check-in for dad only
	for _, v := range []int{53, 54, 55, 56} {
		f.advance(5 * time.Minute)
		upload(t, f, xd, f.now(), "Flat", v)
	}
	msgs = fake.wait(t, 4)
	if fake.settle() != 4 {
		t.Fatalf("check-in must reach only dad: %+v", fake.got[3:])
	}
	checkin := msgs[3]
	if checkin.Topic != "dialife-dad" || checkin.Priority != 3 || checkin.Title != "Все під контролем?" ||
		!strings.Contains(checkin.Message, "52 → 56") {
		t.Fatalf("check-in %+v", checkin)
	}
	if got := labels(checkin); len(got) != 2 || got[1] != "Потрібна допомога" {
		t.Fatalf("check-in buttons %v", got)
	}

	tap(t, checkin.Actions[1]) // help
	msgs = fake.wait(t, 5)
	if fake.settle() != 5 {
		t.Fatalf("help must reach only mom: %+v", fake.got[4:])
	}
	if m := msgs[4]; m.Topic != "dialife-mom" || m.Priority != 5 || m.Title != "Тато просить допомоги" {
		t.Fatalf("help push %+v", m)
	}
	if got := labels(msgs[4]); len(got) != 1 || got[0] != "Іду" {
		t.Fatalf("help buttons %v", got)
	}

	tap(t, msgs[4].Actions[0]) // mom: on my way
	msgs = fake.wait(t, 6)
	if fake.settle() != 6 {
		t.Fatalf("\"coming\" must reach only dad: %+v", fake.got[5:])
	}
	if m := msgs[5]; m.Topic != "dialife-dad" || m.Priority != 3 || m.Title != "Мама йде" {
		t.Fatalf("coming push %+v", m)
	}
	if msgs[4].SequenceID != sid {
		t.Fatalf("help must replace the alarm message: %q", msgs[4].SequenceID)
	}
	// help cleared dad's check-in question; "coming" cleared mom's help request
	got := fake.clears()
	if !slices.Contains(got, "/dialife-dad/"+checkin.SequenceID+"/clear") || !slices.Contains(got, "/dialife-mom/"+sid+"/clear") {
		t.Fatalf("clears after help and coming %v", got)
	}

	// recovers: two readings past 75 end it, quietly, for both
	for _, v := range []int{70, 79, 88} {
		f.advance(5 * time.Minute)
		upload(t, f, xd, f.now(), "SingleUp", v)
	}
	msgs = fake.wait(t, 8)
	for _, m := range msgs[6:8] {
		if m.Title != "Відбій: цукор піднявся" || m.Priority != 2 || !strings.Contains(m.Message, "вів(ла) Тато") ||
			!strings.Contains(m.Message, "Зараз 88") {
			t.Fatalf("clear push %+v", m)
		}
	}

	tap(t, alarm.Actions[0]) // an old button after the episode ended: just dismisses
}

func TestNtfyNoResponseWakesMom(t *testing.T) {
	f := newFixture(t)
	fake := newFakeNtfy(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, _ := f.addDevice(t, "Тато", auth.RoleCaregiver)
	momID, _ := f.addDevice(t, "Мама", auth.RoleCaregiver)
	startNtfy(t, f, fake, []notify.Target{{Topic: "dialife-dad", DeviceID: dadID}, {Topic: "dialife-mom", DeviceID: momID}})

	upload(t, f, xd, f.now().Add(-time.Minute), "Flat", 54)
	tap(t, fake.wait(t, 2)[0].Actions[0])
	fake.wait(t, 3)
	for _, v := range []int{54, 54, 55, 55, 55} { // check-in at 20 min, no answer by 25
		f.advance(5 * time.Minute)
		upload(t, f, xd, f.now(), "Flat", v)
	}
	msgs := fake.wait(t, 6) // check-in (dad) + alarm to both
	for _, m := range msgs[4:6] {
		if m.Priority != 5 || m.Message != "Тато не відповідає на нагадування." {
			t.Fatalf("no-response push %+v", m)
		}
	}
}

func TestQuietTwoHoursButtonForWarn(t *testing.T) {
	f := newFixture(t)
	fake := newFakeNtfy(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, _ := f.addDevice(t, "Тато", auth.RoleCaregiver)
	momID, _ := f.addDevice(t, "Мама", auth.RoleCaregiver)
	startNtfy(t, f, fake, []notify.Target{{Topic: "dialife-dad", DeviceID: dadID}, {Topic: "dialife-mom", DeviceID: momID}})

	upload(t, f, xd, f.now().Add(-time.Minute), "Flat", 65) // warn low (default thresholds)
	alarm := fake.wait(t, 2)[1]                             // mom's copy
	if got := labels(alarm); len(got) != 2 || got[1] != "Тиша 2 год" {
		t.Fatalf("warn buttons %v", got)
	}
	tap(t, alarm.Actions[1]) // mom: quiet 2 h
	m := fake.wait(t, 3)[2]
	if m.Topic != "dialife-dad" || m.Title != "Мама займається" || !strings.Contains(m.Message, "Тиша до 03:00") {
		t.Fatalf("quiet handled push %+v", m)
	}
}

func TestAckLinkRejectsForgeryAndWrongRoles(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, _ := f.addDevice(t, "Тато", auth.RoleCaregiver)
	panelID, _ := f.addDevice(t, "panel", auth.RoleReader)
	s := openStream(t, f, xd, nil)
	s.next(t, "state")
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 50)
	s.next(t, "bg")
	var a alarmPayload
	decodeInto(t, s.next(t, "alarm").Data, &a)

	exp := testNow.Add(time.Hour)
	good := f.links.URL(f.srv.URL, a.Alarm.ID, dadID, exp, acklink.ActHandle, 0)
	cases := map[string]string{
		"tampered signature": strings.Replace(good, "sig=", "sig=x", 1),
		"other person":       strings.Replace(good, "d="+jsonInt(dadID), "d="+jsonInt(panelID), 1),
		"other action":       strings.Replace(good, "a=handle", "a=help", 1),
		"reader device":      f.links.URL(f.srv.URL, a.Alarm.ID, panelID, exp, acklink.ActHandle, 0),
		"expired":            f.links.URL(f.srv.URL, a.Alarm.ID, dadID, testNow.Add(-time.Minute), acklink.ActHandle, 0),
		"other alarm":        strings.Replace(good, "/alarms/"+jsonInt(a.Alarm.ID)+"/", "/alarms/999/", 1),
	}
	for name, link := range cases {
		resp, err := http.Post(link, "", nil)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != 403 {
			t.Errorf("%s: status %d, want 403", name, resp.StatusCode)
		}
	}
	s.expectQuiet(t) // nothing got taken
}

func TestNtfyRetriesOnFailure(t *testing.T) {
	f := newFixture(t)
	fake := newFakeNtfy(t)
	fake.failNext = 1
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	dadID, _ := f.addDevice(t, "Тато", auth.RoleCaregiver)
	startNtfy(t, f, fake, []notify.Target{{Topic: "dialife-dad", DeviceID: dadID}})

	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 260)
	if m := fake.wait(t, 1)[0]; m.Title != "Високий цукор: 260 mg/dL" {
		t.Fatalf("after retry %+v", m)
	}
}
