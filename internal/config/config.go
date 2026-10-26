// Package config reads runtime settings from the environment.
package config

import "os"

type Config struct {
	Addr   string // listen address; Caddy proxies to it
	DBPath string // SQLite database file

	// Secret signs notification ack links; empty disables them (and ntfy).
	Secret    string
	PublicURL string // how phones reach DiaLife, e.g. https://dialife.example.com; needed with Secret
	TZ        string // the family's time zone: times in notifications, the stats day

	// UploaderLabel names the phone running xDrip+ in texts, e.g. "Телефон Олі";
	// empty: a neutral default.
	UploaderLabel string

	NtfyURL     string // ntfy server; empty disables push
	NtfyToken   string // ntfy access token allowed to publish to the topics
	NtfyTargets string // "topic:deviceID,topic:deviceID"
}

func FromEnv() Config {
	return Config{
		Addr:          getenv("DIALIFE_ADDR", "127.0.0.1:8090"),
		DBPath:        getenv("DIALIFE_DB", "data/dialife.db"),
		Secret:        os.Getenv("DIALIFE_SECRET"),
		PublicURL:     os.Getenv("DIALIFE_PUBLIC_URL"),
		TZ:            getenv("DIALIFE_TZ", "UTC"),
		UploaderLabel: os.Getenv("DIALIFE_UPLOADER_LABEL"),
		NtfyURL:       os.Getenv("DIALIFE_NTFY_URL"),
		NtfyToken:     os.Getenv("DIALIFE_NTFY_TOKEN"),
		NtfyTargets:   os.Getenv("DIALIFE_NTFY_TARGETS"),
	}
}

// Thresholds are alarm limits in mg/dL. Defaults until the settings table arrives (M4).
type Thresholds struct {
	UrgentLow  int `json:"bgLow"`
	Low        int `json:"bgTargetBottom"`
	High       int `json:"bgTargetTop"`
	UrgentHigh int `json:"bgHigh"`
}

var DefaultThresholds = Thresholds{UrgentLow: 55, Low: 70, High: 180, UrgentHigh: 250}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
