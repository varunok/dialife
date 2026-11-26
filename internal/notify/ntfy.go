// Package notify pushes alarm episodes to the parents' phones through a
// self-hosted ntfy server, one topic per caregiver. Buttons are signed
// one-alarm links (no API token in the message).
package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/bus"
	"dialife/internal/questions"
	"dialife/internal/recheck"
	"dialife/internal/sensorless"
)

// Target is one caregiver's phone: their ntfy topic and their DiaLife device,
// in whose name the buttons act.
type Target struct {
	Topic    string
	DeviceID int64
}

type Ntfy struct {
	URL       string // ntfy server, e.g. http://127.0.0.1:2586
	Token     string // ntfy access token with write access to the topics
	Targets   []Target
	PublicURL string // where phones reach DiaLife, for button links
	Uploader  string // what the family calls the xDrip+ phone; empty: a neutral name
	Signer    *acklink.Signer
	Bus       *bus.Bus
	Loc       *time.Location // for "since 01:35"
	Now       func() time.Time
	Client    *http.Client
	LinkTTL   time.Duration
	Retries   []time.Duration // backoff between attempts
}

// Message is the JSON body ntfy accepts on POST /.
type Message struct {
	Topic string `json:"topic"`
	// SequenceID ties messages about one episode together: a newer one
	// replaces the older on the phone, and a "clear" dismisses it everywhere.
	SequenceID string   `json:"sequence_id,omitempty"`
	Title      string   `json:"title"`
	Message    string   `json:"message"`
	Priority   int      `json:"priority"`
	Tags       []string `json:"tags,omitempty"`
	Actions    []Action `json:"actions,omitempty"`
}

type Action struct {
	Action string `json:"action"`
	Label  string `json:"label"`
	URL    string `json:"url"`
	Method string `json:"method,omitempty"`
	Clear  bool   `json:"clear,omitempty"`
}

// ntfy priorities
const (
	prioQuiet  = 2 // no sound by default: "Dad is on it", "all clear"
	prioNormal = 3 // check-in: a gentle buzz for the handler
	prioHigh   = 4
	prioMax    = 5
)

const quietMin = 120 // the "quiet 2 h" button

// Run forwards alarm events until ctx ends, resubscribing if the bus drops it.
func (n *Ntfy) Run(ctx context.Context) {
	for ctx.Err() == nil {
		events, cancel, err := n.Bus.Subscribe()
		if errors.Is(err, bus.ErrClosed) {
			return
		}
		if err != nil {
			slog.Error("ntfy subscribe", "err", err)
			sleep(ctx, 5*time.Second)
			continue
		}
		n.forward(ctx, events)
		cancel()
		sleep(ctx, time.Second) // dropped as too slow or bus closing; retry
	}
}

func (n *Ntfy) forward(ctx context.Context, events <-chan bus.Event) {
	for {
		select {
		case <-ctx.Done():
			return
		case ev, ok := <-events:
			if !ok {
				return
			}
			if ev.Type == questions.EventQuestion {
				n.planned(ctx, ev.Data)
				continue
			}
			if ev.Type == sensorless.EventType {
				n.sensorless(ctx, ev.Data)
				continue
			}
			if ev.Type == recheck.EventType {
				n.recheck(ctx, ev.Data)
				continue
			}
			switch ev.Type {
			case alarms.EventAlarm, alarms.EventHandled, alarms.EventCheckin, alarms.EventHelp, alarms.EventComing,
				alarms.EventCheckinOK, alarms.EventClear:
			default:
				continue
			}
			var e alarms.Event
			if err := json.Unmarshal(ev.Data, &e); err != nil {
				slog.Error("ntfy decode", "err", err)
				continue
			}
			for _, t := range n.Targets {
				for _, sid := range Dismiss(ev.Type, e, t) {
					n.clear(ctx, t.Topic, sid)
				}
				if m, ok := n.Build(ev.Type, e, t); ok {
					n.send(ctx, m)
				}
			}
		}
	}
}

func sidPlanned(id int64) string { return fmt.Sprintf("planned-%d", id) }

// planned pushes "was the dose given?" to whoever has not settled it, again at
// each reminder, and takes it off the phones once it is settled. The other
// questions the app asks by itself.
func (n *Ntfy) planned(ctx context.Context, data []byte) {
	var q questions.View
	if err := json.Unmarshal(data, &q); err != nil || q.Kind != "planned" {
		return
	}
	for _, t := range n.Targets {
		if q.State != "open" {
			n.clear(ctx, t.Topic, sidPlanned(q.ID))
			continue
		}
		if !q.Remind || !slices.Contains(q.WaitingIDs, t.DeviceID) {
			continue
		}
		m := PlannedMessage(q, t, n.clock(q.From))
		n.send(ctx, m)
	}
}

// PlannedMessage is «Кололи до їжі о 12:40?» with the dose and what to press.
func PlannedMessage(q questions.View, t Target, at string) Message {
	dose := strconv.FormatFloat(q.Dose, 'f', -1, 64)
	return Message{Topic: t.Topic, SequenceID: sidPlanned(q.ID), Title: "Кололи до їжі о " + at + "?",
		Message:  "Очікувана доза " + strings.ReplaceAll(dose, ".", ",") + " од ще не підтверджена. У DiaLife: «Вкололи» або «Не кололи».",
		Priority: prioNormal, Tags: []string{"syringe"}}
}

const (
	sidRemind     = "sensorless-remind"
	sidSensorBack = "sensorless-back"
)

// sensorless: in the "no sensor" mode, the reminder to measure (once, taken
// off the phones by the next check or the mode's end), and the news that a
// sensor sends again and the alarms are back.
func (n *Ntfy) sensorless(ctx context.Context, data []byte) {
	var e sensorless.Event
	if err := json.Unmarshal(data, &e); err != nil {
		slog.Error("ntfy decode", "err", err)
		return
	}
	for _, t := range n.Targets {
		switch e.Kind {
		case "remind":
			n.send(ctx, RemindMessage(e, t, n.clock))
		case "remind_over":
			n.clear(ctx, t.Topic, sidRemind)
		case "off":
			n.clear(ctx, t.Topic, sidRemind)
			if e.Period.End == "sensor" {
				n.send(ctx, SensorBackMessage(t))
			}
		}
	}
}

// RemindMessage is «Час виміряти цукор з пальця» with the last check, if any.
func RemindMessage(e sensorless.Event, t Target, clock func(int64) string) Message {
	msg := "Режим без сенсора: з його початку замірів з пальця не було."
	if c := e.LastCheck; c != nil && c.T >= e.Period.From {
		msg = fmt.Sprintf("Режим без сенсора: останній замір %d mg/dL о %s.", c.MG, clock(c.T))
	}
	return Message{Topic: t.Topic, SequenceID: sidRemind, Title: "Час виміряти цукор з пальця",
		Message: msg, Priority: prioNormal, Tags: []string{"drop_of_blood"}}
}

const (
	sidRecheckDue   = "recheck-due"
	sidRecheckStill = "recheck-still"
)

// recheck: carbs were given for a low; 15 minutes on, every phone is asked to
// measure again, and told when that check is still low. Over: both go away.
func (n *Ntfy) recheck(ctx context.Context, data []byte) {
	var e recheck.Event
	if err := json.Unmarshal(data, &e); err != nil {
		slog.Error("ntfy decode", "err", err)
		return
	}
	for _, t := range n.Targets {
		switch e.Kind {
		case "due":
			n.send(ctx, RecheckDueMessage(e.Recheck, t, n.clock))
		case "still_low":
			n.clear(ctx, t.Topic, sidRecheckDue)
			n.send(ctx, RecheckStillLowMessage(e.Recheck, t))
		case "over":
			n.clear(ctx, t.Topic, sidRecheckDue)
			n.clear(ctx, t.Topic, sidRecheckStill)
		}
	}
}

// RecheckDueMessage is «Перевірте цукор глюкометром» with the carbs given.
func RecheckDueMessage(r recheck.Recheck, t Target, clock func(int64) string) Message {
	return Message{Topic: t.Topic, SequenceID: sidRecheckDue, Title: "Перевірте цукор глюкометром",
		Message:  fmt.Sprintf("О %s дали %s г вуглеводів на низький цукор. Минуло 15 хв: виміряйте з пальця.", clock(r.CarbsT), grams(r.Carbs)),
		Priority: prioHigh, Tags: []string{"drop_of_blood"}}
}

// RecheckStillLowMessage says the recheck is still below 70.
func RecheckStillLowMessage(r recheck.Recheck, t Target) Message {
	return Message{Topic: t.Topic, SequenceID: sidRecheckStill, Title: fmt.Sprintf("Досі низько: %d mg/dL", r.CheckMG),
		Message:  "Дайте ще швидких вуглеводів за вашим планом і виміряйте знову через 15 хв.",
		Priority: prioHigh, Tags: []string{"warning"}}
}

func grams(g float64) string {
	if g == float64(int(g)) {
		return fmt.Sprintf("%d", int(g))
	}
	return strings.Replace(fmt.Sprintf("%.1f", g), ".", ",", 1)
}

// SensorBackMessage says the mode ended by itself and the alarms work again.
func SensorBackMessage(t Target) Message {
	return Message{Topic: t.Topic, SequenceID: sidSensorBack, Title: "Сенсор знову надсилає дані",
		Message: "Режим без сенсора вимкнено, тривоги знову працюють.", Priority: prioNormal, Tags: []string{"satellite"}}
}

func (n *Ntfy) send(ctx context.Context, m Message) {
	body, _ := json.Marshal(m)
	var err error
	for attempt := 0; attempt <= len(n.Retries); attempt++ {
		if attempt > 0 {
			sleep(ctx, n.Retries[attempt-1])
		}
		if err = n.post(ctx, body); err == nil {
			slog.Info("ntfy sent", "topic", m.Topic, "priority", m.Priority, "title", m.Title)
			return
		}
		if ctx.Err() != nil {
			break
		}
	}
	slog.Error("ntfy send failed", "topic", m.Topic, "title", m.Title, "err", err)
}

// Sequence IDs per episode: the ringing message, the check-in question and
// the quiet notes ("Dad is on it", "all clear") each replace their own kind.
func sidAlarm(id int64) string   { return fmt.Sprintf("alarm-%d", id) }
func sidCheckin(id int64) string { return fmt.Sprintf("checkin-%d", id) }
func sidInfo(id int64) string    { return fmt.Sprintf("info-%d", id) }

// Dismiss lists the earlier messages this event makes obsolete on a phone:
// once someone took the alarm (here or in the app) nobody should still see it ringing.
func Dismiss(typ string, e alarms.Event, t Target) []string {
	a := e.Alarm
	isHandler := a.HandledByID != 0 && a.HandledByID == t.DeviceID
	switch typ {
	case alarms.EventHandled, alarms.EventComing:
		return []string{sidAlarm(a.ID)}
	case alarms.EventHelp, alarms.EventCheckinOK:
		if isHandler {
			return []string{sidCheckin(a.ID)}
		}
	case alarms.EventClear:
		return []string{sidAlarm(a.ID), sidCheckin(a.ID)}
	}
	return nil
}

// clear dismisses a message on the phones (ntfy 2.16+, Android app 1.22.2+).
// Best effort: a message that was never sent there is not an error worth retrying.
func (n *Ntfy) clear(ctx context.Context, topic, sid string) {
	url := strings.TrimRight(n.URL, "/") + "/" + topic + "/" + sid + "/clear"
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, url, nil)
	if err != nil {
		return
	}
	req.Header.Set("Authorization", "Bearer "+n.Token)
	resp, err := n.Client.Do(req)
	if err != nil {
		slog.Warn("ntfy clear failed", "topic", topic, "sid", sid, "err", err)
		return
	}
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		slog.Warn("ntfy clear", "topic", topic, "sid", sid, "status", resp.StatusCode)
	}
}

func (n *Ntfy) post(ctx context.Context, body []byte) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(n.URL, "/")+"/", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+n.Token)
	resp, err := n.Client.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("ntfy status %d", resp.StatusCode)
	}
	return nil
}

var kindText = map[alarms.Kind]string{
	alarms.KindLow:           "Низький цукор",
	alarms.KindHigh:          "Високий цукор",
	alarms.KindPredictedLow:  "Прогноз: гіпо за ~20 хв",
	alarms.KindPredictedHigh: "Прогноз: гіпер за ~20 хв",
	alarms.KindStale:         "Немає даних від сенсора",
}

var clearText = map[alarms.Kind]string{
	alarms.KindLow:           "Відбій: цукор піднявся",
	alarms.KindHigh:          "Відбій: цукор знизився",
	alarms.KindPredictedLow:  "Відбій прогнозу гіпо",
	alarms.KindPredictedHigh: "Відбій прогнозу гіпер",
	alarms.KindStale:         "Дані знову надходять",
}

// Build turns an alarm event into the message for one caregiver, or false
// when this caregiver should not get it (check-ins go to the handler only,
// "is on it" and help requests to everyone but the handler).
func (n *Ntfy) Build(typ string, e alarms.Event, t Target) (Message, bool) {
	note, ok := map[string]func(Message, alarms.Event, Target, string, bool) (Message, bool){
		alarms.EventAlarm:   n.alarmNote,
		alarms.EventHandled: n.handledNote,
		alarms.EventCheckin: n.checkinNote,
		alarms.EventHelp:    n.helpNote,
		alarms.EventComing:  n.comingNote,
		alarms.EventClear:   n.clearNote,
	}[typ]
	if !ok {
		return Message{}, false
	}
	a := e.Alarm
	what := kindText[a.Kind]
	if what == "" {
		what = string(a.Kind)
	}
	isHandler := a.HandledByID != 0 && a.HandledByID == t.DeviceID
	m, send := note(Message{Topic: t.Topic}, e, t, what, isHandler)
	if !send {
		return Message{}, false
	}
	switch typ {
	case alarms.EventAlarm, alarms.EventHelp:
		m.SequenceID = sidAlarm(a.ID)
	case alarms.EventCheckin:
		m.SequenceID = sidCheckin(a.ID)
	default:
		m.SequenceID = sidInfo(a.ID)
	}
	return m, true
}

// alarmNote: an alarm rings: what and how much, why, "I'm on it" and (unless it is an urgent low) "quiet".
func (n *Ntfy) alarmNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	m.Title = what + n.value(a)
	m.Message = n.alarmBody(e)
	m.Priority, m.Tags = prioHigh, []string{"warning"}
	if a.Level == alarms.Urgent {
		m.Priority, m.Tags = prioMax, []string{"rotating_light"}
	}
	m.Actions = []Action{n.button("Я займаюсь", a.ID, t, acklink.ActHandle, 0)}
	if !(a.Kind == alarms.KindLow && a.Level == alarms.Urgent) {
		m.Actions = append(m.Actions, n.button("Тиша 2 год", a.ID, t, acklink.ActHandle, quietMin))
	}
	return m, true
}

// handledNote: someone took it: to everyone else, quietly.
func (n *Ntfy) handledNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	if isHandler {
		return m, false
	}
	m.Title = e.By + " займається"
	m.Message = what + n.value(a) + "."
	if a.QuietUntil != nil {
		m.Message += " Тиша до " + n.clock(*a.QuietUntil) + "."
	}
	m.Priority, m.Tags = prioQuiet, []string{"white_check_mark"}
	return m, true
}

// checkinNote: "all under control?": to the one handling it only.
func (n *Ntfy) checkinNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	if !isHandler {
		return m, false
	}
	m.Title = "Все під контролем?"
	m.Message = what + ": " + n.progress(a) + "."
	m.Priority, m.Tags = prioNormal, []string{"hourglass"}
	m.Actions = []Action{
		n.button("Так, ще 15 хв", a.ID, t, acklink.ActOK, 0),
		n.button("Потрібна допомога", a.ID, t, acklink.ActHelp, 0),
	}
	return m, true
}

// helpNote: the one handling it asks for help: to everyone else, as loud as it gets.
func (n *Ntfy) helpNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	if isHandler {
		return m, false
	}
	m.Title = e.By + " просить допомоги"
	m.Message = what + n.value(a) + "."
	m.Priority, m.Tags = prioMax, []string{"sos"}
	m.Actions = []Action{n.button("Іду", a.ID, t, acklink.ActComing, 0)}
	return m, true
}

// comingNote: someone is coming: to everyone but them; the one who asked gets a gentle buzz.
func (n *Ntfy) comingNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	if a.ComingByID == t.DeviceID {
		return m, false
	}
	m.Title = e.By + " йде"
	m.Message = what + n.value(a) + "."
	m.Priority, m.Tags = prioQuiet, []string{"running"}
	if isHandler { // the one who asked: a gentle buzz, help is on the way
		m.Priority = prioNormal
	}
	return m, true
}

// clearNote: it is over (or ended by hand), how long it lasted and who handled it.
func (n *Ntfy) clearNote(m Message, e alarms.Event, t Target, what string, isHandler bool) (Message, bool) {
	a := e.Alarm
	m.Title = clearText[a.Kind]
	if m.Title == "" {
		m.Title = "Відбій: " + what
	}
	if e.Summary != nil && e.Summary.Manual { // ended by hand: the sensor did not say it is over
		m.Title = what + ": завершено вручну"
	}
	if e.Summary != nil && e.Summary.Sensorless { // nothing recovered: there is no sensor to alarm about
		m.Title = what + ": знято, режим без сенсора"
	}
	var parts []string
	if a.SGV != nil && (e.Summary == nil || !e.Summary.Sensorless) { // no sensor: no "now"
		parts = append(parts, fmt.Sprintf("Зараз %d mg/dL", *a.SGV))
	}
	if s := e.Summary; s != nil {
		parts = append(parts, fmt.Sprintf("тривало %d хв", s.DurationMin))
		switch {
		case s.Manual:
			parts = append(parts, "завершив(ла) вручну "+s.ResolvedBy)
		case s.HandledBy != "":
			parts = append(parts, "вів(ла) "+s.HandledBy)
		}
	}
	m.Message = strings.Join(parts, " · ") + "."
	m.Priority, m.Tags = prioQuiet, []string{"white_check_mark"}
	return m, true
}

func (n *Ntfy) alarmBody(e alarms.Event) string {
	a := e.Alarm
	was := e.WasHandledBy
	if was == "" {
		was = "той, хто займався,"
	}
	switch e.Reason {
	case alarms.ReasonRepeat:
		return "Повтор: ніхто ще не взяв. З " + n.clock(a.StartedAt) + "."
	case alarms.ReasonEscalated:
		return "Стало терміново. З " + n.clock(a.StartedAt) + "."
	case alarms.ReasonWorse:
		return "Гірше, ніж коли " + was + " взяв(ла) тривогу. Потрібні всі."
	case alarms.ReasonCeiling:
		return fmt.Sprintf("Терміново вже %d хв, хоча %s займається. Потрібні всі.", int(n.Now().Sub(time.UnixMilli(a.StartedAt)).Minutes()), was)
	case alarms.ReasonNoResponse:
		return was + " не відповідає на нагадування."
	case alarms.ReasonQuietOver:
		return "Тиша закінчилась, а тривога досі є."
	}
	if a.Kind == alarms.KindStale {
		msg := "Понад 15 хв без значень."
		if a.Level == alarms.Urgent {
			msg = "Понад 30 хв без значень."
		}
		if a.SGV != nil {
			msg += fmt.Sprintf(" Останнє значення %d mg/dL.", *a.SGV)
		}
		return msg + " Перевірте " + n.uploader() + " і сенсор."
	}
	return "з " + n.clock(a.StartedAt)
}

// progress: "22 хв, 54 → 58 mg/dL" for a check-in.
func (n *Ntfy) progress(a alarms.View) string {
	s := ""
	if a.HandledAt != nil {
		s = fmt.Sprintf("%d хв", int(n.Now().Sub(time.UnixMilli(*a.HandledAt)).Minutes()))
	}
	if a.HandledSGV != nil && a.SGV != nil {
		s += fmt.Sprintf(", %d → %d mg/dL", *a.HandledSGV, *a.SGV)
	}
	return s
}

func (n *Ntfy) value(a alarms.View) string {
	if a.SGV == nil || a.Kind == alarms.KindStale {
		return ""
	}
	return fmt.Sprintf(": %d mg/dL", *a.SGV)
}

func (n *Ntfy) button(label string, alarmID int64, t Target, action string, minutes int) Action {
	return Action{Action: "http", Label: label, Method: "POST", Clear: true,
		URL: n.Signer.URL(n.PublicURL, alarmID, t.DeviceID, n.Now().Add(n.LinkTTL), action, minutes)}
}

func (n *Ntfy) clock(ms int64) string {
	return time.UnixMilli(ms).In(n.Loc).Format("15:04")
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

// ParseTargets reads "topic:deviceID,topic:deviceID".
func ParseTargets(s string) ([]Target, error) {
	var out []Target
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		topic, id, ok := strings.Cut(part, ":")
		var dev int64
		if _, err := fmt.Sscan(id, &dev); !ok || err != nil || topic == "" || dev <= 0 {
			return nil, fmt.Errorf("bad ntfy target %q (want topic:deviceID)", part)
		}
		out = append(out, Target{Topic: topic, DeviceID: dev})
	}
	if len(out) == 0 {
		return nil, errors.New("no ntfy targets")
	}
	return out, nil
}

// SendTest pushes a test message to every target and reports the first failure.
func (n *Ntfy) SendTest(ctx context.Context) error {
	for _, t := range n.Targets {
		body, _ := json.Marshal(Message{Topic: t.Topic, Title: "DiaLife: тестове сповіщення",
			Message: "Якщо ви це бачите, тривоги доходитимуть на цей телефон.", Priority: prioHigh, Tags: []string{"test_tube"}})
		if err := n.post(ctx, body); err != nil {
			return fmt.Errorf("topic %s: %w", t.Topic, err)
		}
	}
	return nil
}

// uploader names the phone with xDrip+ inside a sentence.
func (n *Ntfy) uploader() string {
	if n.Uploader != "" {
		return "«" + n.Uploader + "»"
	}
	return "телефон із xDrip+"
}
