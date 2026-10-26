// Package glucose computes the current BG state from CGM readings: 5-minute
// bucket means, delta, trend direction and the AR2 forecast. It is a port of
// Nightscout's bgnow, direction and ar2 plugins (see .local/notes/nightscout-algorithms.md).
// All values are mg/dL, all times unix ms.
package glucose

import (
	"math"
	"strings"
)

const (
	minuteMs = int64(60_000)
	bucketMs = 5 * minuteMs
	// buckets are offset by half a period so the newest reading sits mid-bucket
	bucketOffsetMs = bucketMs / 2
	bucketCount    = 4

	// Dexcom reports sensor errors as small codes; anything at or below is not glucose.
	MaxErrorCode = 39
	// a gap longer than this between buckets is interpolated back to 5 minutes
	interpolateAfterMins = 9
)

type Reading struct {
	Date      int64
	SGV       int
	Direction string // as uploaded by xDrip+, may be empty
	Noise     *int
	Device    string
}

type Bucket struct {
	From, To int64
	Mean     float64 // mean of valid readings
	Last     int     // newest valid reading
	Mills    int64   // time of newest valid reading
	Valid    int     // number of valid readings
	Errors   int     // number of error codes
}

func (b Bucket) empty() bool { return b.Valid == 0 }

type Delta struct {
	MgDL         int     // rounded, as Nightscout displays it
	Exact        float64 // unrounded per-5-minute delta
	Absolute     float64 // raw difference between bucket means
	ElapsedMins  float64
	Interpolated bool
	Mean5MinsAgo float64
}

type State struct {
	Last      Reading // newest reading, error codes included
	Error     bool    // newest reading is a sensor error code
	Mean      float64 // mean of the newest valid bucket
	Mills     int64   // time of the newest valid reading
	Delta     *Delta  // nil without two non-empty buckets
	Direction string  // computed from our own readings (Dexcom rate bands)
	Arrow     string
	// UploaderDirection is what xDrip+ sent, kept for reference only.
	UploaderDirection string
	Buckets           []Bucket
}

// Compute builds the state from recent readings in any order.
// Readings after now are ignored, as in Nightscout.
func Compute(readings []Reading, now int64) (State, bool) {
	var last *Reading
	for i := range readings {
		r := &readings[i]
		if r.Date > now {
			continue
		}
		if last == nil || r.Date > last.Date {
			last = r
		}
	}
	if last == nil {
		return State{}, false
	}

	st := State{Last: *last, Error: last.SGV <= MaxErrorCode}
	st.Buckets = fillBuckets(readings, last.Date, now)

	recent, previous := -1, -1
	for i, b := range st.Buckets {
		if b.empty() {
			continue
		}
		if recent < 0 {
			recent = i
		} else if b.Mills < st.Buckets[recent].Mills {
			previous = i
			break
		}
	}
	if recent >= 0 {
		st.Mean = st.Buckets[recent].Mean
		st.Mills = st.Buckets[recent].Mills
	}
	if recent >= 0 && previous >= 0 {
		st.Delta = calcDelta(st.Buckets[recent], st.Buckets[previous])
	}

	// Our own trend: xDrip+ in native G6/ONE mode uploads "Flat" for every
	// reading (seen 2026-10-25 at +12 mg/dL per 5 min), so its direction
	// is only a fallback when there is too little data to compute one.
	st.UploaderDirection = NormalizeDirection(last.Direction)
	switch rate, ok := trendRate(st.Buckets); {
	case st.Error:
		st.Direction = "NOT COMPUTABLE"
	case ok:
		st.Direction = DirectionFromRate(rate)
	default:
		st.Direction = st.UploaderDirection
	}
	st.Arrow = Arrow(st.Direction)
	return st, true
}

func fillBuckets(readings []Reading, lastMills, now int64) []Bucket {
	bs := make([]Bucket, bucketCount)
	for i := range bs {
		bs[i].From = lastMills - bucketOffsetMs - int64(i)*bucketMs
		bs[i].To = bs[i].From + bucketMs
	}
	sums := make([]float64, bucketCount)
	for _, r := range readings {
		if r.Date > now {
			continue
		}
		for i := range bs {
			b := &bs[i]
			if r.Date < b.From || r.Date > b.To {
				continue
			}
			if r.SGV <= MaxErrorCode {
				b.Errors++
			} else {
				b.Valid++
				sums[i] += float64(r.SGV)
				if r.Date > b.Mills {
					b.Mills, b.Last = r.Date, r.SGV
				}
			}
			break // first (newest) matching bucket wins, like Nightscout's find()
		}
	}
	for i := range bs {
		if bs[i].Valid > 0 {
			bs[i].Mean = sums[i] / float64(bs[i].Valid)
		}
	}
	return bs
}

func calcDelta(recent, previous Bucket) *Delta {
	d := &Delta{
		Absolute:    recent.Mean - previous.Mean,
		ElapsedMins: float64(recent.Mills-previous.Mills) / float64(minuteMs),
	}
	d.Interpolated = d.ElapsedMins > interpolateAfterMins
	if d.Interpolated {
		d.Mean5MinsAgo = recent.Mean - d.Absolute/d.ElapsedMins*5
	} else {
		d.Mean5MinsAgo = recent.Mean - d.Absolute
	}
	d.Exact = recent.Mean - d.Mean5MinsAgo
	d.MgDL = int(math.Round(d.Exact))
	return d
}

var directionAliases = map[string]string{
	"none": "NONE", "tripleup": "TripleUp", "doubleup": "DoubleUp", "singleup": "SingleUp",
	"fortyfiveup": "FortyFiveUp", "flat": "Flat", "fortyfivedown": "FortyFiveDown",
	"singledown": "SingleDown", "doubledown": "DoubleDown", "tripledown": "TripleDown",
	"notcomputable": "NOT COMPUTABLE", "rateoutofrange": "RATE OUT OF RANGE",
	"up": "SingleUp", "down": "SingleDown", "slideup": "FortyFiveUp", "slidedown": "FortyFiveDown",
	"slightup": "FortyFiveUp", "slightdown": "FortyFiveDown",
}

// NormalizeDirection maps uploader spellings to Nightscout's canonical names
// (port of direction-normalizer.js). Unknown values pass through unchanged.
func NormalizeDirection(dir string) string {
	key := strings.ToLower(strings.NewReplacer(" ", "", "_", "", "-", "", "\t", "").Replace(strings.TrimSpace(dir)))
	if key == "" {
		return ""
	}
	if canon, ok := directionAliases[key]; ok {
		return canon
	}
	return dir
}

var arrows = map[string]string{
	"NONE": "⇼", "TripleUp": "⤊", "DoubleUp": "⇈", "SingleUp": "↑", "FortyFiveUp": "↗",
	"Flat": "→", "FortyFiveDown": "↘", "SingleDown": "↓", "DoubleDown": "⇊", "TripleDown": "⤋",
	"NOT COMPUTABLE": "-", "RATE OUT OF RANGE": "⇕",
}

func Arrow(dir string) string { return arrows[dir] }

// trendRate is mg/dL per minute over the longest span the buckets cover
// (up to ~15 minutes), smoothing single-reading noise like Dexcom's arrows.
func trendRate(bs []Bucket) (float64, bool) {
	newest, oldest := -1, -1
	for i, b := range bs {
		if b.empty() {
			continue
		}
		if newest < 0 {
			newest = i
		}
		oldest = i
	}
	if newest < 0 || oldest == newest {
		return 0, false
	}
	mins := float64(bs[newest].Mills-bs[oldest].Mills) / float64(minuteMs)
	if mins < 4 {
		return 0, false
	}
	return (bs[newest].Mean - bs[oldest].Mean) / mins, true
}

// directionFromRate maps mg/dL per minute onto Dexcom's arrow bands.
func DirectionFromRate(rate float64) string {
	switch {
	case rate > 3:
		return "DoubleUp"
	case rate > 2:
		return "SingleUp"
	case rate > 1:
		return "FortyFiveUp"
	case rate >= -1:
		return "Flat"
	case rate >= -2:
		return "FortyFiveDown"
	case rate >= -3:
		return "SingleDown"
	default:
		return "DoubleDown"
	}
}

// directionFromDelta maps a per-5-minute delta onto the same bands.
func directionFromDelta(per5 float64) string { return DirectionFromRate(per5 / 5) }

// MgdlPerMmol converts mmol/L to mg/dL.
const MgdlPerMmol = 18.0182
