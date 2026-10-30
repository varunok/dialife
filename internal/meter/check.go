package meter

import (
	"encoding/json"
	"math"
	"strings"

	"dialife/internal/glucose"
)

// CheckOf is a glucometer reading entered as a treatment ("BG Check"), in mg/dL.
func CheckOf(raw []byte) *int {
	var d struct {
		Glucose *float64 `json:"glucose"`
		Units   string   `json:"units"`
	}
	if json.Unmarshal(raw, &d) != nil || d.Glucose == nil || *d.Glucose <= 0 {
		return nil
	}
	v := *d.Glucose
	if strings.HasPrefix(strings.ToLower(d.Units), "mmol") {
		v *= glucose.MgdlPerMmol
	}
	mg := int(math.Round(v))
	return &mg
}
