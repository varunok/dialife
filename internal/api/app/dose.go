package app

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"slices"

	"dialife/internal/auth"
	"dialife/internal/httpx"
	"dialife/internal/store"
)

// The family's dose calculation: one for every phone, kept in settings.
const doseKey = "dose"

type doseSettings struct {
	Ratio    float64    `json:"ratio"` // 1 unit per this many grams of carbohydrate
	Rule     string     `json:"rule"`  // "nearest" | "halfDown" | "down" | "up"
	Step     float64    `json:"step"`  // 1 or 0.5 unit
	FreshMin int        `json:"fresh_min"`
	MaxRapid float64    `json:"max_rapid"`
	MaxBasal float64    `json:"max_basal"`
	MaxCarbs int        `json:"max_carbs"`
	Table    []doseStep `json:"table"`
	// the physiological forecast: mg/dL one unit lowers (0: from the table),
	// how long rapid acts, how long a meal takes to absorb
	ISF     float64 `json:"isf"`
	DIAMin  int     `json:"dia_min"`
	CarbMin int     `json:"carb_min"`
	// when to eat after a rapid dose (a suggestion): minutes to wait with the
	// sugar in range and above WaitHighFrom; below WaitLowBelow (now or by the
	// trend in 15 min) eat at once
	WaitMin      int    `json:"wait_min"`
	WaitHighMin  int    `json:"wait_high_min"`
	WaitHighFrom int    `json:"wait_high_from"`
	WaitLowBelow int    `json:"wait_low_below"`
	UpdatedAt    int64  `json:"updated_at"`
	UpdatedBy    string `json:"updated_by"`
}

// doseStep: from this glucometer value (mg/dL) up, add this many units.
type doseStep struct {
	MG int     `json:"mg"`
	U  float64 `json:"u"`
}

var defaultDose = doseSettings{
	Ratio: 20, Rule: "halfDown", Step: 1, FreshMin: 15, MaxRapid: 10, MaxBasal: 20, MaxCarbs: 150,
	Table:  []doseStep{{140, 1}, {180, 2}, {220, 3}, {260, 4}, {300, 5}},
	DIAMin: 240, CarbMin: 180,
	WaitMin: 15, WaitHighMin: 20, WaitHighFrom: 180, WaitLowBelow: 80,
}

// withDefaults: settings saved before a field existed, or sent by an older
// app, keep the default for it.
func (d doseSettings) withDefaults() doseSettings {
	if d.DIAMin == 0 {
		d.DIAMin = defaultDose.DIAMin
	}
	if d.CarbMin == 0 {
		d.CarbMin = defaultDose.CarbMin
	}
	if d.WaitMin == 0 && d.WaitHighMin == 0 && d.WaitHighFrom == 0 && d.WaitLowBelow == 0 {
		d.WaitMin, d.WaitHighMin, d.WaitHighFrom, d.WaitLowBelow = defaultDose.WaitMin, defaultDose.WaitHighMin, defaultDose.WaitHighFrom, defaultDose.WaitLowBelow
	}
	return d
}

// isf: the one set, or the table's own: the typical mg/dL between its rows
// per unit added; 40 with no table to read it from.
func (d doseSettings) isf() float64 {
	if d.ISF > 0 {
		return d.ISF
	}
	var steps []float64
	t := slices.Clone(d.Table)
	slices.SortFunc(t, func(a, b doseStep) int { return a.MG - b.MG })
	for i := 1; i < len(t); i++ {
		if du := t[i].U - t[i-1].U; du > 0 {
			steps = append(steps, float64(t[i].MG-t[i-1].MG)/du)
		}
	}
	if len(steps) == 0 {
		return 40
	}
	slices.Sort(steps)
	return steps[len(steps)/2]
}

func (h *Handlers) dose(ctx context.Context) doseSettings {
	raw, err := h.Store.Setting(ctx, doseKey)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			slog.Error("dose settings", "err", err)
		}
		return defaultDose
	}
	var d doseSettings
	err = json.Unmarshal(raw, &d)
	d = d.withDefaults()
	if err != nil || d.valid() != nil {
		slog.Error("dose settings: stored value unreadable, using defaults")
		return defaultDose
	}
	return d
}

var errDose = errors.New("dose settings out of range")

func (d doseSettings) valid() error {
	ok := d.Ratio >= 1 && d.Ratio <= 100 &&
		slices.Contains([]string{"nearest", "halfDown", "down", "up"}, d.Rule) &&
		(d.Step == 1 || d.Step == 0.5) &&
		d.FreshMin >= 1 && d.FreshMin <= 60 &&
		d.MaxRapid >= 0.5 && d.MaxRapid <= 100 &&
		d.MaxBasal >= 0.5 && d.MaxBasal <= 200 &&
		d.MaxCarbs >= 20 && d.MaxCarbs <= 500 &&
		(d.ISF == 0 || d.ISF >= 5 && d.ISF <= 400) &&
		d.DIAMin >= 120 && d.DIAMin <= 480 &&
		d.CarbMin >= 60 && d.CarbMin <= 360 &&
		d.WaitMin >= 0 && d.WaitMin <= 45 && d.WaitHighMin >= d.WaitMin && d.WaitHighMin <= 60 &&
		d.WaitLowBelow >= 60 && d.WaitLowBelow <= 120 && d.WaitHighFrom > d.WaitLowBelow && d.WaitHighFrom <= 300 &&
		len(d.Table) <= 20
	if !ok {
		return errDose
	}
	seen := map[int]bool{}
	for _, s := range d.Table {
		if s.MG < 40 || s.MG > 600 || s.U < 0 || s.U > 50 || seen[s.MG] {
			return errDose
		}
		seen[s.MG] = true
	}
	return nil
}

// PutDose serves PUT /api/dose: a parent changed the calculation; every phone
// gets it with the next state.
func (h *Handlers) PutDose(w http.ResponseWriter, r *http.Request) {
	var d doseSettings
	if !decodeOptional(w, r, &d) {
		return
	}
	d = d.withDefaults()
	if d.valid() != nil {
		httpx.Error(w, http.StatusBadRequest)
		return
	}
	slices.SortFunc(d.Table, func(a, b doseStep) int { return a.MG - b.MG })
	if d.Table == nil {
		d.Table = []doseStep{}
	}
	dev, _ := auth.DeviceFrom(r.Context())
	now := h.Now()
	d.UpdatedAt, d.UpdatedBy = now.UnixMilli(), dev.Person
	if err := h.Store.SetSetting(r.Context(), doseKey, mustJSON(d), now); err != nil {
		fail(w, "dose save", err)
		return
	}
	slog.Info("dose settings", "by", dev.Person)
	h.PublishState(r.Context())
	httpx.WriteJSON(w, http.StatusOK, d)
}

// PublishState sends every phone a fresh snapshot: something in it changed
// that is not a reading (the dose settings, a planned dose). It goes out as a
// "bg" event, which the phones take as the whole state.
func (h *Handlers) PublishState(ctx context.Context) {
	now := h.Now()
	snap, err := h.snapshot(ctx, now)
	if err != nil {
		slog.Error("state publish", "err", err)
		return
	}
	h.Bus.Publish(EventBG, snap, now)
}
