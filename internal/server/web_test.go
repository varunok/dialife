package server

import (
	"encoding/json"
	"io"
	"net/url"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

func TestWebLoginByQR(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	browser := map[string]string{"Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"}

	var start struct{ ID, Secret string }
	json.NewDecoder(f.do(t, "POST", "/api/web/login", nil, browser).Body).Decode(&start)
	if start.ID == "" || start.Secret == "" {
		t.Fatal("no login code")
	}
	// the QR picture carries the code; read it back the way the app's scanner would
	resp := f.get(t, "/api/web/login/"+start.ID+"/qr.svg", nil)
	svg, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.HasPrefix(string(svg), "<svg") {
		t.Fatalf("qr %d", resp.StatusCode)
	}
	code := f.app.webCode(t, start.ID)

	poll := func(secret string) (int, map[string]any) {
		r := f.do(t, "POST", "/api/web/login/"+start.ID+"/poll", strings.NewReader(`{"secret":"`+secret+`"}`), browser)
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		return r.StatusCode, m
	}
	if st, m := poll(start.Secret); st != 200 || m["state"] != "pending" {
		t.Fatalf("pending: %d %v", st, m)
	}
	if st, _ := poll("someone-elses-secret"); st != 404 {
		t.Fatalf("another browser polling: %d", st)
	}

	dadHdr := map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json"}
	body := `{"id":"` + start.ID + `","code":"` + code + `"}`
	var info struct {
		Browser, OS string
		Same        bool `json:"same_network"`
	}
	json.NewDecoder(f.do(t, "POST", "/api/web/inspect", strings.NewReader(body), dadHdr).Body).Decode(&info)
	if info.Browser != "Firefox" || info.OS != "Linux" || !info.Same { // the test's browser and phone share 127.0.0.1
		t.Fatalf("inspect %+v", info)
	}
	// scanned: the browser keeps the code and learns it now lives longer
	if st, m := poll(start.Secret); st != 200 || m["state"] != "scanned" {
		t.Fatalf("after inspect: %d %v", st, m)
	}
	// the parent takes their time: past the first two minutes, still valid
	f.advance(3 * time.Minute)
	approve := `{"id":"` + start.ID + `","code":"` + code + `","minutes":90}`
	if st := f.do(t, "POST", "/api/web/approve", strings.NewReader(approve), dadHdr).StatusCode; st != 204 {
		t.Fatalf("approve %d", st)
	}
	if st := f.do(t, "POST", "/api/web/approve", strings.NewReader(approve), dadHdr).StatusCode; st != 410 {
		t.Fatalf("second approve %d", st)
	}
	_, m := poll(start.Secret)
	token, _ := m["token"].(string)
	if m["state"] != "approved" || !strings.HasPrefix(token, "dlw_") || m["by"] != "Тато" {
		t.Fatalf("approved: %v", m)
	}
	if _, m := poll(start.Secret); m["state"] != "expired" {
		t.Fatalf("a code gives one session: %v", m)
	}

	web := map[string]string{"Authorization": "Bearer " + token}
	if st := f.get(t, "/api/state", web).StatusCode; st != 200 {
		t.Fatalf("web reads state: %d", st)
	}
	from := url.QueryEscape(f.now().Add(-time.Hour).Format(time.RFC3339))
	if st := f.get(t, "/api/history?from="+from, web).StatusCode; st != 200 {
		t.Fatalf("web reads history: %d", st)
	}
	if st := f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Note","notes":"x"}`), web).StatusCode; st != 403 {
		t.Fatalf("web must not write: %d", st)
	}
	if st := f.get(t, "/api/web/sessions", web).StatusCode; st != 403 {
		t.Fatalf("web must not list sessions: %d", st)
	}

	var ss []struct {
		ID      int64
		Browser string
		By      string
	}
	json.NewDecoder(f.get(t, "/api/web/sessions", dadHdr).Body).Decode(&ss)
	if len(ss) != 1 || ss[0].Browser != "Firefox" || ss[0].By != "Тато" {
		t.Fatalf("sessions %+v", ss)
	}
	if st := f.do(t, "POST", "/api/web/logout", nil, web).StatusCode; st != 204 {
		t.Fatalf("logout %d", st)
	}
	if st := f.get(t, "/api/state", web).StatusCode; st != 401 {
		t.Fatalf("after logout: %d", st)
	}
}

func TestWebSessionExpires(t *testing.T) {
	f := newFixture(t)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	var start struct{ ID, Secret string }
	json.NewDecoder(f.do(t, "POST", "/api/web/login", nil, nil).Body).Decode(&start)
	code := f.app.webCode(t, start.ID)
	hdr := map[string]string{"Authorization": "Bearer " + mom, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/web/approve", strings.NewReader(`{"id":"`+start.ID+`","code":"`+code+`","minutes":30}`), hdr)
	var m map[string]any
	json.NewDecoder(f.do(t, "POST", "/api/web/login/"+start.ID+"/poll", strings.NewReader(`{"secret":"`+start.Secret+`"}`), nil).Body).Decode(&m)
	web := map[string]string{"Authorization": "Bearer " + m["token"].(string)}
	f.advance(31 * time.Minute)
	if st := f.get(t, "/api/state", web).StatusCode; st != 401 {
		t.Fatalf("expired session: %d", st)
	}
}

func (a *App) webCode(t *testing.T, id string) string {
	t.Helper()
	c := a.api.WebCode(id)
	if c == "" {
		t.Fatalf("no code for %s", id)
	}
	return c
}

// webToken logs a browser in the way the page does, approved by a parent's token.
func webToken(t *testing.T, f *fixture, parent string) string {
	t.Helper()
	var start struct{ ID, Secret string }
	json.NewDecoder(f.do(t, "POST", "/api/web/login", nil, nil).Body).Decode(&start)
	code := f.app.webCode(t, start.ID)
	hdr := map[string]string{"Authorization": "Bearer " + parent, "Content-Type": "application/json"}
	f.do(t, "POST", "/api/web/approve", strings.NewReader(`{"id":"`+start.ID+`","code":"`+code+`","minutes":60}`), hdr)
	var m map[string]any
	json.NewDecoder(f.do(t, "POST", "/api/web/login/"+start.ID+"/poll", strings.NewReader(`{"secret":"`+start.Secret+`"}`), nil).Body).Decode(&m)
	return m["token"].(string)
}

// collect gathers the stream's events for a moment, pings left out.
func collect(c *sseClient, d time.Duration) []sseEvent {
	var out []sseEvent
	timeout := time.After(d)
	for {
		select {
		case ev, ok := <-c.events:
			if !ok {
				return out
			}
			if ev.Type != "ping" {
				out = append(out, ev)
			}
		case <-timeout:
			return out
		}
	}
}

func TestWebGetsNoQuestions(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, mom := f.addDevice(t, "Мама", auth.RoleCaregiver)
	web := webToken(t, f, mom)

	webStream, momStream := openStream(t, f, web, nil), openStream(t, f, mom, nil)
	webStream.next(t, "state")
	momStream.next(t, "state")
	// a rise with no food: the parents are asked
	upload(t, f, xd, testNow.Add(-time.Minute), "SingleUp", 120, 118, 119, 125, 136, 148, 160, 171)

	has := func(evs []sseEvent, typ string) bool {
		for _, e := range evs {
			if e.Type == typ {
				return true
			}
		}
		return false
	}
	momEvs, webEvs := collect(momStream, 500*time.Millisecond), collect(webStream, 500*time.Millisecond)
	if !has(momEvs, "question") {
		t.Fatalf("the parent must be asked: %v", momEvs)
	}
	if has(webEvs, "question") || !has(webEvs, "bg") {
		t.Fatalf("web events %v", webEvs)
	}
	for _, e := range webEvs {
		if strings.Contains(e.Data, `"questions"`) {
			t.Fatalf("questions leak to the web in %s: %s", e.Type, e.Data)
		}
	}
	raw, _ := io.ReadAll(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + web}).Body)
	if strings.Contains(string(raw), `"questions"`) {
		t.Fatalf("web state has questions: %s", raw)
	}
	momRaw, _ := io.ReadAll(f.get(t, "/api/state", map[string]string{"Authorization": "Bearer " + mom}).Body)
	if !strings.Contains(string(momRaw), `"questions"`) {
		t.Fatal("the parent's state lost its questions")
	}
}

func TestWebLoginFromAnotherNetwork(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	var start struct{ ID, Secret string }
	json.NewDecoder(f.do(t, "POST", "/api/web/login", nil, map[string]string{"X-Forwarded-For": "198.51.100.9"}).Body).Decode(&start)
	code := f.app.webCode(t, start.ID)
	var info struct {
		IP   string
		Same bool `json:"same_network"`
	}
	json.NewDecoder(f.do(t, "POST", "/api/web/inspect", strings.NewReader(`{"id":"`+start.ID+`","code":"`+code+`"}`),
		map[string]string{"Authorization": "Bearer " + dad, "Content-Type": "application/json", "X-Forwarded-For": "203.0.113.4"}).Body).Decode(&info)
	if info.Same || info.IP != "198.51.100.9" {
		t.Fatalf("a browser elsewhere: %+v", info)
	}
}
