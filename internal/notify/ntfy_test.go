package notify

import (
	"net/url"
	"strings"
	"testing"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/questions"
)

var (
	start  = time.Date(2026, 10, 25, 4, 5, 0, 0, time.UTC) // 01:05 local
	loc, _ = time.LoadLocation("Etc/GMT+3")
	tato   = Target{Topic: "dialife-dad", DeviceID: 8}
	mama   = Target{Topic: "dialife-mom", DeviceID: 9}
)

func testNtfy(t *testing.T) *Ntfy {
	t.Helper()
	s, err := acklink.NewSigner("0123456789abcdef0123456789abcdef!")
	if err != nil {
		t.Fatal(err)
	}
	return &Ntfy{PublicURL: "https://dia.example", Signer: s, Loc: loc, LinkTTL: time.Hour,
		Now: func() time.Time { return start.Add(22 * time.Minute) }}
}

func ptr[T any](v T) *T { return &v }

func verify(t *testing.T, n *Ntfy, a Action, alarmID int64) (int64, string, int) {
	t.Helper()
	u, _ := url.Parse(a.URL)
	dev, act, m, err := n.Signer.Verify(alarmID, u.Query(), n.Now())
	if err != nil {
		t.Fatalf("button %q link invalid: %v", a.Label, err)
	}
	return dev, act, m
}

func TestAlarmMessages(t *testing.T) {
	n := testNtfy(t)
	a := alarms.View{ID: 12, Kind: alarms.KindLow, Level: alarms.Urgent, StartedAt: start.UnixMilli(), SGV: ptr(52)}

	m, ok := n.Build(alarms.EventAlarm, alarms.Event{Alarm: a, Reason: alarms.ReasonNew}, tato)
	if !ok || m.Title != "Низький цукор: 52 mg/dL" || m.Message != "з 01:05" || m.Priority != 5 || m.Tags[0] != "rotating_light" {
		t.Fatalf("urgent low %+v", m)
	}
	if len(m.Actions) != 1 || m.Actions[0].Label != "Я займаюсь" || !m.Actions[0].Clear {
		t.Fatalf("urgent low must offer only \"I'm on it\": %+v", m.Actions)
	}
	if dev, act, mins := verify(t, n, m.Actions[0], 12); dev != 8 || act != acklink.ActHandle || mins != 0 {
		t.Fatalf("handle link %d %s %d", dev, act, mins)
	}

	a.Level = alarms.Warn
	m, _ = n.Build(alarms.EventAlarm, alarms.Event{Alarm: a, Reason: alarms.ReasonRepeat}, mama)
	if m.Priority != 4 || !strings.Contains(m.Message, "ніхто ще не взяв") || len(m.Actions) != 2 || m.Actions[1].Label != "Тиша 2 год" {
		t.Fatalf("warn repeat %+v", m)
	}
	if dev, act, mins := verify(t, n, m.Actions[1], 12); dev != 9 || act != acklink.ActHandle || mins != 120 {
		t.Fatalf("quiet link %d %s %d", dev, act, mins)
	}

	reasons := map[string]string{
		alarms.ReasonWorse:      "Гірше, ніж коли Тато взяв(ла) тривогу",
		alarms.ReasonNoResponse: "Тато не відповідає на нагадування.",
		alarms.ReasonCeiling:    "Терміново вже 22 хв, хоча Тато займається",
		alarms.ReasonQuietOver:  "Тиша закінчилась",
		alarms.ReasonEscalated:  "Стало терміново",
	}
	for reason, want := range reasons {
		m, _ := n.Build(alarms.EventAlarm, alarms.Event{Alarm: a, Reason: reason, WasHandledBy: "Тато"}, mama)
		if !strings.Contains(m.Message, want) {
			t.Errorf("%s: %q, want it to contain %q", reason, m.Message, want)
		}
	}
}

func TestStaleMessage(t *testing.T) {
	n := testNtfy(t)
	a := alarms.View{ID: 3, Kind: alarms.KindStale, Level: alarms.Urgent, StartedAt: start.UnixMilli(), SGV: ptr(140)}
	m, _ := n.Build(alarms.EventAlarm, alarms.Event{Alarm: a, Reason: alarms.ReasonNew}, tato)
	if m.Title != "Немає даних від сенсора" || !strings.Contains(m.Message, "Понад 30 хв") ||
		!strings.Contains(m.Message, "140 mg/dL") || !strings.Contains(m.Message, "телефон із xDrip+") || len(m.Actions) != 2 {
		t.Fatalf("stale %+v", m)
	}
}

func TestEpisodeRouting(t *testing.T) {
	n := testNtfy(t)
	handled := alarms.View{ID: 12, Kind: alarms.KindLow, Level: alarms.Urgent, StartedAt: start.UnixMilli(), SGV: ptr(56),
		State: "handled", HandledBy: "Тато", HandledByID: 8, HandledAt: ptr(start.UnixMilli()), HandledSGV: ptr(52)}

	if _, ok := n.Build(alarms.EventHandled, alarms.Event{Alarm: handled, By: "Тато"}, tato); ok {
		t.Fatal("\"is on it\" sent to the handler himself")
	}
	m, ok := n.Build(alarms.EventHandled, alarms.Event{Alarm: handled, By: "Тато"}, mama)
	if !ok || m.Title != "Тато займається" || m.Priority != 2 || m.Message != "Низький цукор: 56 mg/dL." {
		t.Fatalf("handled for mom %+v", m)
	}

	if _, ok := n.Build(alarms.EventCheckin, alarms.Event{Alarm: handled}, mama); ok {
		t.Fatal("check-in sent to someone who is not handling it")
	}
	m, ok = n.Build(alarms.EventCheckin, alarms.Event{Alarm: handled}, tato)
	if !ok || m.Title != "Все під контролем?" || m.Priority != 3 || m.Message != "Низький цукор: 22 хв, 52 → 56 mg/dL." {
		t.Fatalf("check-in %+v", m)
	}
	if _, act, _ := verify(t, n, m.Actions[0], 12); act != acklink.ActOK {
		t.Fatalf("first check-in button %s", act)
	}
	if _, act, _ := verify(t, n, m.Actions[1], 12); act != acklink.ActHelp {
		t.Fatalf("second check-in button %s", act)
	}

	if _, ok := n.Build(alarms.EventHelp, alarms.Event{Alarm: handled, By: "Тато"}, tato); ok {
		t.Fatal("help request sent back to the one asking")
	}
	m, _ = n.Build(alarms.EventHelp, alarms.Event{Alarm: handled, By: "Тато"}, mama)
	if m.Title != "Тато просить допомоги" || m.Priority != 5 {
		t.Fatalf("help %+v", m)
	}
}

func TestClearMessages(t *testing.T) {
	n := testNtfy(t)
	a := alarms.View{ID: 12, Kind: alarms.KindLow, Level: alarms.Warn, SGV: ptr(88)}
	m, _ := n.Build(alarms.EventClear, alarms.Event{Alarm: a, Summary: &alarms.Summary{DurationMin: 24, HandledBy: "Тато"}}, mama)
	if m.Title != "Відбій: цукор піднявся" || m.Message != "Зараз 88 mg/dL · тривало 24 хв · вів(ла) Тато." || m.Priority != 2 {
		t.Fatalf("clear %+v", m)
	}
	m, _ = n.Build(alarms.EventClear, alarms.Event{Alarm: a, Summary: &alarms.Summary{DurationMin: 6, Manual: true, ResolvedBy: "Мама"}}, tato)
	if m.Title != "Низький цукор: завершено вручну" || !strings.Contains(m.Message, "завершив(ла) вручну Мама") {
		t.Fatalf("manual clear %+v", m)
	}
}

func TestParseTargets(t *testing.T) {
	got, err := ParseTargets(" dialife-dad:8, dialife-mom:9 ")
	if err != nil || len(got) != 2 || got[1] != (Target{Topic: "dialife-mom", DeviceID: 9}) {
		t.Fatalf("%v %v", got, err)
	}
	for _, bad := range []string{"", "x", "x:", ":8", "x:abc", "x:-1"} {
		if _, err := ParseTargets(bad); err == nil {
			t.Errorf("ParseTargets(%q) accepted", bad)
		}
	}
}

func TestPlannedMessage(t *testing.T) {
	m := PlannedMessage(questions.View{ID: 7, Kind: "planned", Dose: 2.5}, Target{Topic: "mom"}, "12:40")
	if m.Topic != "mom" || m.SequenceID != "planned-7" || m.Title != "Кололи до їжі о 12:40?" || !strings.Contains(m.Message, "2,5 од") {
		t.Fatalf("%+v", m)
	}
}
