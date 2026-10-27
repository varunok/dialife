package questions

import (
	"cmp"
	"slices"
	"time"
)

// Two doses of one insulin this close together are most likely one injection
// logged twice, or two parents injecting at once. The app asks before saving;
// xDrip+ cannot, so a pair with an xDrip+ dose in it becomes a question.
const (
	DoubleRapid  = 20 * time.Minute // a correction comes later than this
	RecentRapid  = 3 * time.Hour    // the app only notes the previous dose up to here
	DoubleBasal  = 16 * time.Hour   // long-acting is once a day
	doubleRecent = 12 * time.Hour   // pairs whose newer dose is older than this are left alone
	// after "two doses": how long everyone is told to watch for a low
	watchRapid = 3 * time.Hour
	watchBasal = 24 * time.Hour
)

// Dose is one insulin entry, as the double-dose check needs it.
type Dose struct {
	ID      string
	T       int64 // unix ms
	Units   float64
	Basal   bool
	XDrip   bool   // came from the uploader, not from a parent's app
	Checked string // the app saved it after warning about this other dose: no question then
}

func doubleWindow(basal bool) time.Duration {
	if basal {
		return DoubleBasal
	}
	return DoubleRapid
}

func watchFor(basal bool) time.Duration {
	if basal {
		return watchBasal
	}
	return watchRapid
}

// Doubles finds pairs worth asking about: one insulin, closer than its window,
// at least one dose from xDrip+, the newer one recent. The first of each pair
// is the dose "one injection" would remove: the xDrip+ one, the newer if both are.
func Doubles(ds []Dose, now time.Time) [][2]Dose {
	ds = slices.Clone(ds)
	slices.SortFunc(ds, func(a, b Dose) int {
		if a.T != b.T {
			return cmp.Compare(a.T, b.T)
		}
		return cmp.Compare(a.ID, b.ID)
	})
	var out [][2]Dose
	for j, b := range ds {
		if now.UnixMilli()-b.T > doubleRecent.Milliseconds() {
			continue
		}
		for _, a := range ds[:j] {
			if a.Basal != b.Basal || b.T-a.T >= doubleWindow(b.Basal).Milliseconds() {
				continue
			}
			if !a.XDrip && !b.XDrip || a.Checked == b.ID || b.Checked == a.ID {
				continue
			}
			if b.XDrip {
				out = append(out, [2]Dose{b, a})
			} else {
				out = append(out, [2]Dose{a, b})
			}
		}
	}
	return out
}
