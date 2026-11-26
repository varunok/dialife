package meter

import (
	"context"
	"slices"
	"time"

	"dialife/internal/glucose"
	"dialife/internal/store"
	"dialife/internal/trust"
)

// Data is what the comparison reads: every glucometer check (repeats and
// checks after a shower dropped, calibrations apart), the sensor around each
// check, sensor starts and calibrations, all oldest first.
type Data struct {
	Checks []Point
	SGV    []Point // valid readings only
	Lows   []int64 // readings the sensor reported as LOW (its error code 39)
	Starts []int64
	Cals   []int64
}

// Bounds are where a stretch of one sensor's behaviour begins: starts and calibrations.
func (d Data) Bounds() []int64 {
	return slices.Sorted(slices.Values(append(slices.Clone(d.Starts), d.Cals...)))
}

// lowCode is how xDrip+ uploads a Dexcom "LOW" reading.
const lowCode = 39

// Load reads Data from the store.
func Load(ctx context.Context, st *store.Store) (Data, error) {
	var d Data
	es, err := st.QueryEntries(ctx, store.EntryQuery{Type: "mbg", Count: meterRows, Asc: true})
	if err != nil {
		return d, err
	}
	for _, e := range es {
		if IsCalibration(e.Device) {
			d.Cals = append(d.Cals, e.Date)
			continue
		}
		if e.MBG != nil && *e.MBG > 0 {
			d.Checks = append(d.Checks, Point{T: e.Date, V: *e.MBG, Src: Source(e.Device)})
		}
	}
	ts, err := st.ListTreatments(ctx, store.TreatmentQuery{Count: meterRows})
	if err != nil {
		return d, err
	}
	var untrusted []trust.Window // a check after a shower says nothing about the sensor
	for _, t := range ts {
		if w, ok := trust.Of(t); ok {
			untrusted = append(untrusted, w)
		}
		if v := CheckOf(t.Raw); v != nil {
			d.Checks = append(d.Checks, Point{T: t.CreatedAt, V: *v, Src: t.EnteredBy})
		}
		if t.EventType == "Sensor Start" || t.EventType == "Sensor Change" {
			d.Starts = append(d.Starts, t.CreatedAt)
		}
	}
	slices.Sort(d.Starts)
	d.Checks = trust.Drop(Dedup(d.Checks), func(p Point) int64 { return p.T }, untrusted)

	// the sensor around each check only, not years of readings: from 15 minutes
	// before (how steady it was) to the longest delay tried and a reading beyond
	reach := (15 * time.Minute).Milliseconds()
	last := int64(-1)
	for _, c := range d.Checks {
		from, to := c.T-reach, c.T+(maxLag*time.Minute).Milliseconds()+reach
		from = max(from, last+1) // overlaps the previous check's window
		if from > to {
			continue
		}
		rs, err := st.QueryEntries(ctx, store.EntryQuery{Type: "sgv", Gte: &from, Lte: &to, Count: 50, Asc: true})
		if err != nil {
			return d, err
		}
		for _, e := range rs {
			last = max(last, e.Date)
			switch {
			case e.SGV == nil:
			case *e.SGV > glucose.MaxErrorCode:
				d.SGV = append(d.SGV, Point{T: e.Date, V: *e.SGV})
			case *e.SGV == lowCode:
				d.Lows = append(d.Lows, e.Date)
			}
		}
		last = max(last, to)
	}
	return d, nil
}
