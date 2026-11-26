package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dialife/internal/auth"
)

func TestAppUpdate(t *testing.T) {
	f := newFixture(t)
	_, dad := f.addDevice(t, "Тато", auth.RoleCaregiver)
	hdr := map[string]string{"Authorization": "Bearer " + dad}
	state := func() map[string]any {
		var s map[string]any
		json.NewDecoder(f.get(t, "/api/state", hdr).Body).Decode(&s)
		return s
	}
	// nothing pushed: no update, no file
	if _, ok := state()["app"]; ok {
		t.Fatal("no update yet")
	}
	if st := f.get(t, "/api/app/apk", hdr).StatusCode; st != 404 {
		t.Fatalf("no apk: %d", st)
	}

	apk := []byte("PK\x03\x04 not really an apk")
	sum := sha256.Sum256(apk)
	if err := os.WriteFile(filepath.Join(f.appDir, "dialife-0.7.1.apk"), apk, 0o644); err != nil {
		t.Fatal(err)
	}
	meta := `{"name":"0.7.1","code":70100,"sha256":"` + hex.EncodeToString(sum[:]) + `","size":24,"notes":"Що нового","at":1,"file":"dialife-0.7.1.apk"}`
	if err := os.WriteFile(filepath.Join(f.appDir, "latest.json"), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
	app, ok := state()["app"].(map[string]any)
	if !ok || app["name"] != "0.7.1" || app["code"] != float64(70100) || app["notes"] != "Що нового" {
		t.Fatalf("state app %+v", state()["app"])
	}
	resp := f.get(t, "/api/app/apk", hdr)
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || string(body) != string(apk) || resp.Header.Get("Content-Type") != "application/vnd.android.package-archive" {
		t.Fatalf("apk %d %q %s", resp.StatusCode, body, resp.Header.Get("Content-Type"))
	}
	if st := f.get(t, "/api/app/apk", nil).StatusCode; st != 401 {
		t.Fatalf("no token: %d", st)
	}

	// a file name that could leave the folder is refused
	bad := strings.Replace(meta, "dialife-0.7.1.apk", "../dialife.db", 1)
	if err := os.WriteFile(filepath.Join(f.appDir, "latest.json"), []byte(bad+" "), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := state()["app"]; ok {
		t.Fatal("a name outside the folder must not be served")
	}
}
