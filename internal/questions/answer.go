package questions

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"time"

	"dialife/internal/store"
)

func (e *Engine) Answer(ctx context.Context, id int64, by store.Device, answer, treatmentID string) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	q, err := e.open(ctx, id)
	if err != nil {
		return View{}, err
	}
	valid := []string{"food", "food_unknown", "insulin", "nothing"}
	switch q.Kind {
	case "double":
		valid = []string{"one", "two"}
	case "rise":
		valid = append(valid, "asleep")
	case "planned":
		valid = nil // settled by confirming or skipping the dose itself
	}
	if !slices.Contains(valid, answer) {
		return View{}, ErrAnswer
	}
	now := e.Now()
	if answer == "asleep" {
		// one parent who knows she was asleep settles it
		if err := e.Store.InsertQuestionAnswer(ctx, store.QuestionAnswer{
			QuestionID: q.ID, Round: q.Round, Person: by.Person, DeviceID: by.ID, Answer: answer, At: now.UnixMilli(),
		}); err != nil {
			return View{}, err
		}
		q.State, q.Outcome, q.ClosedBy = "answered", "asleep", by.Person
		q.ClosedAt = ptr(now.UnixMilli())
		slog.Info("question answered", "id", q.ID, "outcome", "asleep")
		if err := e.save(ctx, q, now, false); err != nil {
			return View{}, err
		}
		return e.View(ctx, q, now)
	}
	if err := e.vote(ctx, q, by, answer, treatmentID, now); err != nil {
		return View{}, err
	}
	q, err = e.Store.Question(ctx, id)
	if err != nil {
		return View{}, err
	}
	return e.View(ctx, q, now)
}

// Dismiss clears a suspect mark (a question nobody answered in time).
func (e *Engine) Dismiss(ctx context.Context, id int64, by store.Device) (View, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	q, err := e.Store.Question(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return View{}, ErrNotFound
	}
	if err != nil {
		return View{}, err
	}
	if q.State != "unknown" {
		return View{}, ErrClosed
	}
	now := e.Now()
	q.State, q.ClosedBy = "dismissed", by.Person
	q.ClosedAt = ptr(now.UnixMilli())
	if err := e.save(ctx, q, now, false); err != nil {
		return View{}, err
	}
	return e.View(ctx, q, now)
}

func (e *Engine) vote(ctx context.Context, q store.Question, by store.Device, answer, treatmentID string, now time.Time) error {
	if err := e.Store.InsertQuestionAnswer(ctx, store.QuestionAnswer{
		QuestionID: q.ID, Round: q.Round, Person: by.Person, DeviceID: by.ID, Answer: answer, TreatmentID: treatmentID, At: now.UnixMilli(),
	}); err != nil {
		return err
	}
	if treatmentID != "" {
		q.TreatmentID = treatmentID
	}
	devs, err := e.Store.ListDevices(ctx)
	if err != nil {
		return err
	}
	as, err := e.Store.QuestionAnswers(ctx, q.ID)
	if err != nil {
		return err
	}
	voters := people(devs)
	latest := map[string]string{}
	for _, a := range as {
		if a.Round == q.Round {
			latest[a.Person] = a.Answer
		}
	}
	for _, p := range voters {
		if _, ok := latest[p]; !ok {
			return e.save(ctx, q, now, false) // still waiting for someone
		}
	}
	outcome, agree := agreed(latest, q.TreatmentID != "")
	if agree {
		q.State, q.Outcome = "answered", outcome
		q.ClosedAt = ptr(now.UnixMilli())
		slog.Info("question answered", "id", q.ID, "outcome", outcome)
		if q.Kind == "double" && outcome == "one" {
			if err := e.dropDuplicate(ctx, q.TreatmentID, now); err != nil {
				return err
			}
		}
		return e.save(ctx, q, now, false)
	}
	// they disagree: everyone answers again, seeing what the others said
	q.Round++
	q.RemindedAt = ptr(now.UnixMilli())
	slog.Info("question disagreement", "id", q.ID, "round", q.Round)
	return e.save(ctx, q, now, false)
}

// agreed: everyone said the same thing. "food" and "food, amount unknown" agree
// that she ate; with an entry made for it, the answer is "food".
func agreed(latest map[string]string, hasEntry bool) (string, bool) {
	seen := map[string]bool{}
	for _, a := range latest {
		if a == "food_unknown" {
			a = "food"
		}
		seen[a] = true
	}
	if len(seen) != 1 {
		return "", false
	}
	for a := range seen {
		if a == "food" && !hasEntry {
			for _, raw := range latest {
				if raw == "food_unknown" {
					return "food_unknown", true
				}
			}
		}
		return a, true
	}
	return "", false
}

// dropDuplicate: "one injection" removes the second entry of it, from the chart and the stats.
func (e *Engine) dropDuplicate(ctx context.Context, id string, now time.Time) error {
	deleted, err := e.Store.DeleteTreatment(ctx, id, now)
	if err != nil || !deleted {
		return err
	}
	slog.Info("treatment delete", "id", id, "by", "double-dose answer")
	// the same event the treatments API sends (app.EventTreatment)
	e.Bus.Publish("treatment", map[string]any{"op": "delete", "ids": []string{id}}, now)
	return nil
}

func (e *Engine) open(ctx context.Context, id int64) (store.Question, error) {
	q, err := e.Store.Question(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return q, ErrNotFound
	}
	if err != nil {
		return q, err
	}
	if q.State != "open" {
		return q, ErrClosed
	}
	return q, nil
}

func (e *Engine) save(ctx context.Context, q store.Question, now time.Time, remind bool) error {
	if err := e.Store.UpdateQuestion(ctx, q); err != nil {
		return err
	}
	return e.publish(ctx, q, now, remind)
}

func (e *Engine) publish(ctx context.Context, q store.Question, now time.Time, remind bool) error {
	v, err := e.View(ctx, q, now)
	if err != nil {
		return err
	}
	v.Remind = remind
	e.Bus.Publish(EventQuestion, v, now)
	return nil
}

// View is a question as the app shows it, with who is still to answer.
