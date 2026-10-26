// Package insulin tells long-acting from rapid in a stored treatment document.
package insulin

import (
	"encoding/json"
	"strings"
)

// Kind reads the app's insulinType/insulinName from a stored treatment.
// xDrip+ only names the insulin from its own profile list ("Lantus 18" in
// insulinInjections, or a note): that gives the type, while the name is left to
// each phone's settings. Without either, insulin counts as rapid (a bolus).
func Kind(raw []byte) (typ, name string) {
	var d struct {
		Type       string `json:"insulinType"`
		Name       string `json:"insulinName"`
		EventType  string `json:"eventType"`
		Notes      string `json:"notes"`
		Injections string `json:"insulinInjections"` // xDrip+: a JSON array inside a string
	}
	_ = json.Unmarshal(raw, &d)
	if d.Type != "" {
		return d.Type, d.Name
	}
	if d.EventType == "Basal Injection" {
		return "basal", ""
	}
	var inj []struct {
		Insulin string  `json:"insulin"`
		Units   float64 `json:"units"`
	}
	_ = json.Unmarshal([]byte(d.Injections), &inj)
	for _, i := range inj {
		if isBasalName(i.Insulin) {
			return "basal", ""
		}
	}
	if len(inj) > 0 {
		return "rapid", ""
	}
	if isBasalName(d.Notes) {
		return "basal", ""
	}
	return "", ""
}

// long-acting insulins, originals and biosimilars, as people write them
var basalNames = []string{
	"lantus", "toujeo", "basalog", "abasaglar", "basaglar", "semglee", "rezvoglar",
	"glargin", "tresiba", "degludec", "levemir", "detemir", "protaphane", "insulatard",
	"humulin n", "insuman basal", "nph",
}

func isBasalName(s string) bool {
	s = strings.ToLower(s)
	for _, n := range basalNames {
		if strings.Contains(s, n) {
			return true
		}
	}
	return false
}
