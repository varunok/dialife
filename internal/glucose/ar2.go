package glucose

import "math"

// AR2 constants from Nightscout's plugins/ar2.js.
const (
	ar2Ref   = 140.0 // central tendency, mg/dL
	ar2Min   = 36
	ar2Max   = 400
	ar2Prev  = -0.723
	ar2Curr  = 1.716
	ar2Steps = 6 // 30 minutes

	warnLoss   = 0.05
	urgentLoss = 0.10

	DefaultConeFactor = 2.0
)

// coneSteps widen the uncertainty band per 5-minute step (65 minutes total).
var coneSteps = []float64{0.020, 0.041, 0.061, 0.081, 0.099, 0.116, 0.132, 0.146, 0.159, 0.171, 0.182, 0.192, 0.201}

type Point struct {
	T   int64 `json:"t"`
	SGV int   `json:"sgv"`
}

type ConePoint struct {
	T  int64 `json:"t"`
	Lo int   `json:"lo"`
	Hi int   `json:"hi"`
}

type Level string

const (
	LevelNone   Level = ""
	LevelWarn   Level = "warn"
	LevelUrgent Level = "urgent"
)

type Forecast struct {
	Predicted []Point     `json:"predicted"` // +5..+30 min
	Cone      []ConePoint `json:"cone"`      // +5..+65 min
	AvgLoss   float64     `json:"avg_loss"`
	Level     Level       `json:"level,omitempty"`
	Event     string      `json:"event,omitempty"` // "low" | "high" when Level is set
}

// AR2 forecasts from the newest bucket mean and the (possibly interpolated)
// mean 5 minutes earlier. Returns false when Nightscout would not forecast.
func AR2(mean, mean5MinsAgo float64, from int64, targetLow, targetHigh int, coneFactor float64) (Forecast, bool) {
	if mean < ar2Min || mean5MinsAgo <= 0 || math.IsNaN(mean5MinsAgo) {
		return Forecast{}, false
	}
	initPrev, initCurr := math.Log(mean5MinsAgo/ar2Ref), math.Log(mean/ar2Ref)

	var f Forecast
	prev, curr, t := initPrev, initCurr, from
	for range ar2Steps {
		prev, curr, t = curr, ar2Prev*prev+ar2Curr*curr, t+bucketMs
		f.Predicted = append(f.Predicted, Point{T: t, SGV: toMgdl(curr, 0)})
	}

	prev, curr, t = initPrev, initCurr, from
	for _, step := range coneSteps {
		prev, curr, t = curr, ar2Prev*prev+ar2Curr*curr, t+bucketMs
		f.Cone = append(f.Cone, ConePoint{T: t, Lo: toMgdl(curr, -coneFactor*step), Hi: toMgdl(curr, coneFactor*step)})
	}

	// Nightscout sums 6 terms but weights each by 1/5; kept as is so alarms match.
	size := min(len(f.Predicted)-1, 6)
	for j := 0; j <= size; j++ {
		f.AvgLoss += 1 / float64(size) * math.Pow(math.Log10(float64(f.Predicted[j].SGV)/120), 2)
	}

	switch {
	case f.AvgLoss > urgentLoss:
		f.Level = LevelUrgent
	case f.AvgLoss > warnLoss:
		f.Level = LevelWarn
	}
	if f.Level != LevelNone {
		in20 := f.Predicted[3].SGV
		switch {
		case in20 > targetHigh:
			f.Event = "high"
		case in20 < targetLow:
			f.Event = "low"
		}
	}
	return f, true
}

func toMgdl(logValue, cone float64) int {
	v := int(math.Round(ar2Ref * math.Exp(logValue+cone)))
	return max(ar2Min, min(ar2Max, v))
}
