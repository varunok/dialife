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

	// Carb estimates from language models; no key (or URL) set: none, and
	// nothing about food leaves the server. Models are comma-separated: each
	// one is asked, at the same time, and their answers combined.
	GeminiKey    string
	GeminiModels string // default "gemini-3.5-flash-lite"
	OpenAIURL    string // any OpenAI-compatible API: OpenAI, OpenRouter, a local Ollama
	OpenAIKey    string
	OpenAIModels string
	LLMTimeout   string // per model, a Go duration; default 10s
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
		GeminiKey:     os.Getenv("DIALIFE_GEMINI_KEY"),
		GeminiModels:  getenv("DIALIFE_GEMINI_MODEL", "gemini-3.5-flash-lite"),
		OpenAIURL:     os.Getenv("DIALIFE_OPENAI_URL"),
		OpenAIKey:     os.Getenv("DIALIFE_OPENAI_KEY"),
		OpenAIModels:  os.Getenv("DIALIFE_OPENAI_MODEL"),
		LLMTimeout:    os.Getenv("DIALIFE_LLM_TIMEOUT"),
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
