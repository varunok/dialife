package nsv1

import (
	"strings"
	"testing"
)

func TestUUIDToIDMatchesXDrip(t *testing.T) {
	cases := map[string]string{
		"xdrip:abc123":                         "c81e53fd8189239191e51892", // contains ":" → md5
		"short-uuid":                           "88417f6a3577189320e95155", // < 24 → md5
		"0123456789abcdef01234567":             "0123456789abcdef01234567", // already an id
		"2f1c6c1e-8b3a-4d2e-9f00-1a2b3c4d5e6f": "2f1c6c1e8b3a4d2e9f001a2b", // dashes stripped, first 24
	}
	for in, want := range cases {
		if got := uuidToID(in); got != want {
			t.Errorf("uuidToID(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestReadDocs(t *testing.T) {
	one, err := readDocs(strings.NewReader(` {"a":1} `))
	if err != nil || len(one) != 1 {
		t.Fatalf("object: %v %v", one, err)
	}
	many, err := readDocs(strings.NewReader(`[{"a":1},{"b":2}]`))
	if err != nil || len(many) != 2 {
		t.Fatalf("array: %v %v", many, err)
	}
	for _, bad := range []string{"", "[1,2]", "null", "[null]", "{", `"x"`} {
		if _, err := readDocs(strings.NewReader(bad)); err == nil {
			t.Errorf("readDocs(%q) accepted", bad)
		}
	}
}

func TestRawKeepsNumbersExact(t *testing.T) {
	d, err := readDocs(strings.NewReader(`{"delta":-1.234,"filtered":123456789012.5,"date":1792886400000}`))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(d[0].marshal()), `{"date":1792886400000,"delta":-1.234,"filtered":123456789012.5}`; got != want {
		t.Fatalf("raw = %s, want %s", got, want)
	}
}
