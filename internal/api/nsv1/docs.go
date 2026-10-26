package nsv1

import (
	"bytes"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"strings"

	"dialife/internal/nstime"
)

// doc is one uploaded JSON object, decoded with json.Number so the original
// values survive a round trip into the raw column.
type doc map[string]any

// readDocs accepts either a single object or an array of objects,
// as Nightscout does (xDrip+ sends devicestatus and PUT treatments as objects).
func readDocs(r io.Reader) ([]doc, error) {
	body, err := io.ReadAll(r)
	if err != nil {
		return nil, err
	}
	body = bytes.TrimSpace(body)
	if len(body) == 0 {
		return nil, errors.New("empty body")
	}

	var raws []json.RawMessage
	if body[0] == '[' {
		if err := json.Unmarshal(body, &raws); err != nil {
			return nil, err
		}
	} else {
		raws = []json.RawMessage{body}
	}

	docs := make([]doc, 0, len(raws))
	for _, raw := range raws {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var d doc
		if err := dec.Decode(&d); err != nil {
			return nil, err
		}
		if d == nil {
			return nil, errors.New("null document")
		}
		docs = append(docs, d)
	}
	return docs, nil
}

func (d doc) str(key string) string {
	switch v := d[key].(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	}
	return ""
}

func (d doc) num(key string) (float64, bool) {
	var f float64
	var err error
	switch v := d[key].(type) {
	case json.Number:
		f, err = v.Float64()
	case string:
		_, err = fmt.Sscan(v, &f)
	default:
		return 0, false
	}
	if err != nil || math.IsNaN(f) || math.IsInf(f, 0) {
		return 0, false
	}
	return f, true
}

func (d doc) intVal(key string) (int, bool) {
	f, ok := d.num(key)
	if !ok {
		return 0, false
	}
	return int(math.Round(f)), true
}

func (d doc) intPtr(key string) *int {
	if v, ok := d.intVal(key); ok {
		return &v
	}
	return nil
}

func (d doc) floatPtr(key string) *float64 {
	if v, ok := d.num(key); ok {
		return &v
	}
	return nil
}

func (d doc) sub(key string) doc {
	if m, ok := d[key].(map[string]any); ok {
		return doc(m)
	}
	return nil
}

func (d doc) marshal() []byte {
	b, _ := json.Marshal(d) // values came from JSON, cannot fail
	return b
}

func isHexID(s string) bool {
	if len(s) != 24 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

// uuidToID mirrors xDrip+'s NightscoutUploader.uuid_to_id so DELETE by _id
// finds what PUT stored.
func uuidToID(uuid string) string {
	md5ID := func() string {
		sum := md5.Sum([]byte(uuid))
		return hex.EncodeToString(sum[:])[:24]
	}
	switch {
	case strings.Contains(uuid, ":"):
		return md5ID()
	case len(uuid) == 24:
		return uuid
	case len(uuid) < 24:
		return md5ID()
	}
	id := strings.ReplaceAll(uuid, "-", "")
	if len(id) < 24 {
		return md5ID()
	}
	return id[:24]
}

// stableID derives a repeatable 24-hex id, so a retried upload keeps its _id.
func stableID(parts ...string) string {
	sum := sha1.Sum([]byte(strings.Join(parts, "\x00")))
	return hex.EncodeToString(sum[:])[:24]
}

func randomID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// firstTime returns the first parseable time among keys.
func (d doc) firstTime(keys ...string) (int64, bool) {
	for _, k := range keys {
		if v, ok := d[k]; ok {
			if ms, ok := nstime.Parse(v); ok {
				return ms, true
			}
		}
	}
	return 0, false
}
