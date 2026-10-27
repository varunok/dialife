package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"dialife/internal/auth"
)

type sseEvent struct {
	ID   int64
	Type string // "ping" for keep-alive comments
	Data string
}

type sseClient struct {
	events chan sseEvent
	resp   *http.Response
	cancel context.CancelFunc
}

func openStream(t *testing.T, f *fixture, token string, hdr map[string]string) *sseClient {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, "GET", f.srv.URL+"/api/stream", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	c := &sseClient{events: make(chan sseEvent, 64), resp: resp, cancel: cancel}
	t.Cleanup(func() { cancel(); resp.Body.Close() })
	if resp.StatusCode != 200 {
		return c
	}
	go func() {
		defer close(c.events)
		sc := bufio.NewScanner(resp.Body)
		var ev sseEvent
		for sc.Scan() {
			line := sc.Text()
			switch {
			case line == "":
				if ev.Type != "" {
					c.events <- ev
				}
				ev = sseEvent{}
			case strings.HasPrefix(line, ": ping"):
				ev.Type = "ping"
			case strings.HasPrefix(line, "id: "):
				ev.ID, _ = strconv.ParseInt(line[4:], 10, 64)
			case strings.HasPrefix(line, "event: "):
				ev.Type = line[7:]
			case strings.HasPrefix(line, "data: "):
				ev.Data = line[6:]
			}
		}
	}()
	return c
}

// next returns the next event, skipping pings unless asked for one.
func (c *sseClient) next(t *testing.T, wantType string) sseEvent {
	t.Helper()
	timeout := time.After(2 * time.Second)
	for {
		select {
		case ev, ok := <-c.events:
			if !ok {
				t.Fatalf("stream closed while waiting for %q", wantType)
			}
			if ev.Type == "ping" && wantType != "ping" {
				continue
			}
			if ev.Type != wantType {
				t.Fatalf("got event %q (%s), want %q", ev.Type, ev.Data, wantType)
			}
			return ev
		case <-timeout:
			t.Fatalf("timeout waiting for %q", wantType)
		}
	}
}

func (c *sseClient) expectQuiet(t *testing.T) {
	t.Helper()
	select {
	case ev := <-c.events:
		if ev.Type != "ping" {
			t.Fatalf("unexpected event %q: %s", ev.Type, ev.Data)
		}
	case <-time.After(30 * time.Millisecond):
	}
}

func decodeInto(t *testing.T, data string, v any) {
	t.Helper()
	if err := json.Unmarshal([]byte(data), v); err != nil {
		t.Fatalf("bad event json %q: %v", data, err)
	}
}

func TestStreamLiveReading(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	_, dad := f.addDevice(t, "dad", auth.RoleCaregiver)
	upload(t, f, xd, testNow.Add(-6*time.Minute), "Flat", 120)

	c := openStream(t, f, dad, nil)
	if ct := c.resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("content-type %q", ct)
	}
	var st stateBody
	first := c.next(t, "state")
	decodeInto(t, first.Data, &st)
	if st.BG == nil || st.BG.SGV != 120 || st.DisplayUnits != "mg/dl" {
		t.Fatalf("initial state %+v", st)
	}

	upload(t, f, xd, testNow.Add(-time.Minute), "FortyFiveDown", 112)
	ev := c.next(t, "bg")
	if ev.ID <= first.ID {
		t.Fatalf("ids not increasing: %d then %d", first.ID, ev.ID)
	}
	var bg stateBody
	decodeInto(t, ev.Data, &bg)
	if bg.BG == nil || bg.BG.SGV != 112 || *bg.BG.Delta != -8 || bg.BG.Arrow != "↘" || bg.AR2 == nil {
		t.Fatalf("bg event %+v", bg.BG)
	}
	if strings.Contains(ev.Data, "display_units") {
		t.Fatal("shared bg event leaks a device's display_units")
	}

	// the same batch again (xDrip+ retry) changes nothing and must stay silent
	upload(t, f, xd, testNow.Add(-time.Minute), "FortyFiveDown", 112)
	c.expectQuiet(t)
}

func TestStreamBackfillIsOneHistoryEvent(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 150)
	c := openStream(t, f, xd, nil)
	c.next(t, "state")

	// 3 hours of transmitter backfill arriving after the live reading (like 00:13 on 2026-10-25)
	vals := make([]int, 36)
	for i := range vals {
		vals[i] = 200 - i
	}
	upload(t, f, xd, testNow.Add(-6*time.Minute), "Flat", vals...)

	var h struct{ From, To int64 }
	decodeInto(t, c.next(t, "history").Data, &h)
	wantFrom := testNow.Add(-6*time.Minute - 35*5*time.Minute).UnixMilli()
	if h.From != wantFrom || h.To != testNow.Add(-6*time.Minute).UnixMilli() {
		t.Fatalf("history range %d..%d, want %d..", h.From, h.To, wantFrom)
	}
	c.expectQuiet(t) // no bg: the live reading did not change
}

func TestStreamBackfillWithNewLatest(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	c := openStream(t, f, xd, nil)
	c.next(t, "state")

	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 130, 128, 125)
	c.next(t, "history")
	var bg stateBody
	decodeInto(t, c.next(t, "bg").Data, &bg)
	if bg.BG.SGV != 125 {
		t.Fatalf("bg %+v", bg.BG)
	}
}

func TestStreamTreatmentsAndUploader(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	c := openStream(t, f, xd, nil)
	c.next(t, "state")

	f.do(t, "PUT", "/api/v1/treatments", strings.NewReader(string(fixtureFile(t, "treatment_put.json"))), xdrip(xd))
	var tr struct {
		Op  string
		IDs []string
	}
	decodeInto(t, c.next(t, "treatment").Data, &tr)
	if tr.Op != "upsert" || len(tr.IDs) != 1 || tr.IDs[0] != "2f1c6c1e8b3a4d2e9f001a2b" {
		t.Fatalf("treatment event %+v", tr)
	}

	f.do(t, "DELETE", "/api/v1/treatments/2f1c6c1e8b3a4d2e9f001a2b", nil, xdrip(xd))
	decodeInto(t, c.next(t, "treatment").Data, &tr)
	if tr.Op != "delete" {
		t.Fatalf("delete event %+v", tr)
	}
	f.do(t, "DELETE", "/api/v1/treatments/2f1c6c1e8b3a4d2e9f001a2b", nil, xdrip(xd))
	c.expectQuiet(t) // deleting nothing is silent

	f.do(t, "POST", "/api/v1/devicestatus", strings.NewReader(string(fixtureFile(t, "devicestatus.json"))), xdrip(xd))
	var up struct{ Battery int }
	decodeInto(t, c.next(t, "uploader").Data, &up)
	if up.Battery != 63 {
		t.Fatalf("uploader event %+v", up)
	}
}

func TestStreamReconnectReplay(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	c := openStream(t, f, xd, nil)
	lastSeen := c.next(t, "state").ID
	c.cancel() // phone lost signal

	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 140, 135)
	f.do(t, "POST", "/api/v1/treatments", strings.NewReader(`{"eventType":"Note","created_at":"2026-10-25T00:30:00Z"}`), xdrip(xd))

	// fixture time is frozen, so received_at equals the first state id; replay from just before it
	c2 := openStream(t, f, xd, map[string]string{"Last-Event-ID": fmt.Sprint(lastSeen - 1)})
	var st stateBody
	decodeInto(t, c2.next(t, "state").Data, &st)
	if st.BG == nil || st.BG.SGV != 135 {
		t.Fatalf("state after reconnect %+v", st.BG)
	}
	var h struct{ From, To int64 }
	decodeInto(t, c2.next(t, "history").Data, &h)
	if h.To != testNow.Add(-time.Minute).UnixMilli() {
		t.Fatalf("replayed history %+v", h)
	}
	var tr struct{ Op string }
	decodeInto(t, c2.next(t, "treatment").Data, &tr)
	if tr.Op != "changed" {
		t.Fatalf("replayed treatment %+v", tr)
	}

	// a reconnect with an id newer than every change replays nothing
	c3 := openStream(t, f, xd, map[string]string{"Last-Event-ID": fmt.Sprint(testNow.Add(time.Hour).UnixMilli())})
	c3.next(t, "state")
	c3.expectQuiet(t)
}

func TestStreamPingAndLimits(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)

	if c := openStream(t, f, "nope", nil); c.resp.StatusCode != 401 {
		t.Fatalf("bad token: status %d", c.resp.StatusCode)
	}

	first := openStream(t, f, xd, nil)
	first.next(t, "state")
	first.next(t, "ping") // fixture pings every 50 ms

	for range 3 { // fixture bus allows 4 subscribers
		openStream(t, f, xd, nil).next(t, "state")
	}
	if c := openStream(t, f, xd, nil); c.resp.StatusCode != 503 {
		t.Fatalf("5th stream: status %d, want 503", c.resp.StatusCode)
	}
}

// a glucometer check on its own (Bluetooth meter → xDrip+) must reach the chart
func TestStreamMeterCheckIsHistoryEvent(t *testing.T) {
	f := newFixture(t)
	_, xd := f.addDevice(t, "xDrip", auth.RoleUploader)
	upload(t, f, xd, testNow.Add(-time.Minute), "Flat", 150)
	c := openStream(t, f, xd, nil)
	c.next(t, "state")

	at := testNow.Add(-30 * time.Second).UnixMilli()
	f.do(t, "POST", "/api/v1/entries", strings.NewReader(fmt.Sprintf(`{"type":"mbg","mbg":107,"date":%d,"device":"Bluetooth Glucose Meter"}`, at)), xdrip(xd))
	var h struct{ From, To int64 }
	decodeInto(t, c.next(t, "history").Data, &h)
	if h.From != at || h.To != at {
		t.Fatalf("history range %d..%d, want the check at %d", h.From, h.To, at)
	}
	c.expectQuiet(t)
}
