// Command dialife is the DiaLife API server and its admin CLI.
//
//	dialife serve                               run the API (applies migrations first)
//	dialife migrate                             apply migrations only
//	dialife device add -name NAME -role ROLE    create a device, print its token once
//	dialife device list
//	dialife device revoke -id ID
//	dialife device rotate -id ID                new token for the same device
//	dialife ntfy-test                           push a test notification to every caregiver
//	dialife backup -dir DIR                      snapshot the database, keep 14 daily + 12 monthly
//	dialife alarms show                         current alarm thresholds
//	dialife alarms set high=250 high_for_min=30  change them (live within a minute)
//	dialife alarms live                         alarms firing right now
//	dialife readings [-n 12]                    newest CGM readings
//	dialife foods import FILE|-                  load a carbohydrate table (replaces the same source)
//	dialife foods show                          imported tables
//	dialife version
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"text/tabwriter"
	"time"

	"dialife/internal/acklink"
	"dialife/internal/alarms"
	"dialife/internal/auth"
	"dialife/internal/bus"
	"dialife/internal/carbs"
	"dialife/internal/config"
	"dialife/internal/glucose"
	"dialife/internal/notify"
	"dialife/internal/server"
	"dialife/internal/store"
)

// version is set at build time: -ldflags "-X main.version=..."
var version = "dev"

// maxStreams caps concurrent SSE connections (a family needs a handful).
const maxStreams = 64

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stderr, nil)))

	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cfg := config.FromEnv()

	var err error
	switch os.Args[1] {
	case "serve":
		err = serve(cfg)
	case "migrate":
		err = migrate(cfg)
	case "device":
		err = device(cfg, os.Args[2:])
	case "ntfy-test":
		err = ntfyTest(cfg)
	case "backup":
		err = backup(cfg, os.Args[2:])
	case "alarms":
		err = alarmSettings(cfg, os.Args[2:])
	case "readings":
		err = readings(cfg, os.Args[2:])
	case "foods":
		err = foods(cfg, os.Args[2:])
	case "version":
		fmt.Println(version)
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: dialife serve | migrate | device add|list|revoke | alarms show|set|live | readings | foods import|show | backup | ntfy-test | version")
}

func openMigrated(cfg config.Config) (*store.Store, error) {
	s, err := store.Open(cfg.DBPath)
	if err != nil {
		return nil, err
	}
	n, err := s.Migrate(context.Background())
	if err != nil {
		s.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	if n > 0 {
		slog.Info("migrations applied", "count", n)
	}
	return s, nil
}

func serve(cfg config.Config) error {
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()

	var links *acklink.Signer
	if cfg.Secret != "" {
		if cfg.PublicURL == "" {
			return errors.New("DIALIFE_PUBLIC_URL is required with DIALIFE_SECRET: notification buttons link to it")
		}
		if links, err = acklink.NewSigner(cfg.Secret); err != nil {
			return fmt.Errorf("DIALIFE_SECRET: %w", err)
		}
	}

	llmTimeout := time.Duration(0)
	if cfg.LLMTimeout != "" {
		if llmTimeout, err = time.ParseDuration(cfg.LLMTimeout); err != nil {
			return fmt.Errorf("DIALIFE_LLM_TIMEOUT: %w", err)
		}
	}
	models := carbs.FromConfig(cfg.GeminiKey, cfg.GeminiModels, cfg.OpenAIURL, cfg.OpenAIKey, cfg.OpenAIModels)
	names := make([]string, len(models))
	for i, m := range models {
		names[i] = m.Name()
	}
	slog.Info("carb estimates", "models", names) // none: only the family's own numbers
	var embed carbs.Embedder
	if cfg.GeminiKey != "" && cfg.GeminiEmbedModel != "" && cfg.GeminiEmbedModel != "none" {
		embed = &carbs.GeminiEmbed{Key: cfg.GeminiKey, Model: cfg.GeminiEmbedModel}
		slog.Info("food names by meaning", "model", embed.Name())
	}
	similar := 0.0
	if cfg.FoodSimilar != "" {
		if similar, err = strconv.ParseFloat(cfg.FoodSimilar, 64); err != nil || similar <= 0 || similar >= 1 {
			return fmt.Errorf("DIALIFE_FOOD_SIMILAR: want a number between 0 and 1, got %q", cfg.FoodSimilar)
		}
	}

	events := bus.New(maxStreams)
	wired := server.New(server.Deps{
		Carbs:     &carbs.Service{Store: s, Models: models, Embed: embed, Similar: float32(similar), Timeout: llmTimeout, Now: time.Now},
		Links:     links,
		Store:     s,
		Auth:      auth.NewAuthenticator(s, time.Now),
		Bus:       events,
		Version:   version,
		Now:       time.Now,
		PingEvery: 25 * time.Second,
		Loc:       loc(cfg),
		Uploader:  cfg.UploaderLabel,
	})
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           wired.Handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       120 * time.Second,
		// no WriteTimeout: SSE streams (M3) stay open; handlers set their own deadlines
	}

	// open SSE streams never finish on their own; closing the bus ends them
	srv.RegisterOnShutdown(events.Close)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// the watchdog raises "no data" alarms, repeats and snooze expiry
	go wired.Alarms.Run(ctx)
	// questions time out and re-ask without new readings too
	go wired.Questions.Run(ctx)

	if cfg.NtfyURL != "" {
		n, err := newNtfy(ctx, cfg, s, events, links)
		if err != nil {
			return err
		}
		go n.Run(ctx)
	}

	errc := make(chan error, 1)
	go func() {
		slog.Info("listening", "addr", cfg.Addr, "version", version)
		errc <- srv.ListenAndServe()
	}()

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}

	slog.Info("shutting down")
	shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func newNtfy(ctx context.Context, cfg config.Config, s *store.Store, events *bus.Bus, links *acklink.Signer) (*notify.Ntfy, error) {
	if links == nil {
		return nil, errors.New("DIALIFE_NTFY_URL needs DIALIFE_SECRET for ack links")
	}
	targets, err := notify.ParseTargets(cfg.NtfyTargets)
	if err != nil {
		return nil, err
	}
	for _, t := range targets {
		d, err := s.ActiveDeviceByID(ctx, t.DeviceID)
		if err != nil || auth.Role(d.Role) != auth.RoleCaregiver {
			return nil, fmt.Errorf("ntfy topic %s: device %d is not an active caregiver", t.Topic, t.DeviceID)
		}
		slog.Info("ntfy target", "topic", t.Topic, "device", d.Name)
	}
	loc, err := time.LoadLocation(cfg.TZ)
	if err != nil {
		return nil, fmt.Errorf("DIALIFE_TZ: %w", err)
	}
	return &notify.Ntfy{
		URL:       cfg.NtfyURL,
		Token:     cfg.NtfyToken,
		Targets:   targets,
		PublicURL: cfg.PublicURL,
		Uploader:  cfg.UploaderLabel,
		Signer:    links,
		Bus:       events,
		Loc:       loc,
		Now:       time.Now,
		Client:    &http.Client{Timeout: 10 * time.Second},
		LinkTTL:   12 * time.Hour,
		Retries:   []time.Duration{time.Second, 5 * time.Second, 15 * time.Second},
	}, nil
}

// ntfyTest sends a test push to every configured caregiver.
func ntfyTest(cfg config.Config) error {
	if cfg.NtfyURL == "" {
		return errors.New("DIALIFE_NTFY_URL is not set")
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	links, err := acklink.NewSigner(cfg.Secret)
	if err != nil {
		return fmt.Errorf("DIALIFE_SECRET: %w", err)
	}
	ctx := context.Background()
	n, err := newNtfy(ctx, cfg, s, bus.New(1), links)
	if err != nil {
		return err
	}
	if err := n.SendTest(ctx); err != nil {
		return err
	}
	fmt.Printf("test push sent to %d topic(s)\n", len(n.Targets))
	return nil
}

func backup(cfg config.Config, args []string) error {
	fs := flag.NewFlagSet("backup", flag.ContinueOnError)
	dir := fs.String("dir", "data/backups", "backup directory")
	keepDaily := fs.Int("keep-daily", 14, "newest daily backups to keep")
	keepMonthly := fs.Int("keep-monthly", 12, "first-of-month backups to keep")
	if err := fs.Parse(args); err != nil {
		return err
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()

	path, err := s.Backup(context.Background(), *dir, time.Now())
	if err != nil {
		return err
	}
	info, _ := os.Stat(path)
	deleted, err := store.PruneBackups(*dir, *keepDaily, *keepMonthly)
	if err != nil {
		return err
	}
	slog.Info("backup done", "path", path, "bytes", info.Size(), "pruned", deleted)
	return nil
}

func alarmSettings(cfg config.Config, args []string) error {
	if len(args) > 0 && args[0] == "live" {
		return liveAlarms(cfg)
	}
	if len(args) == 0 || (args[0] != "show" && args[0] != "set") {
		return errors.New("alarms: want show, live or set key=value ...")
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	ctx := context.Background()

	rules, err := alarms.LoadRules(ctx, s, alarms.DefaultRules)
	if err != nil {
		return err
	}
	st := alarms.SettingsOf(rules)
	if args[0] == "set" {
		if len(args) == 1 {
			return errors.New("alarms set: nothing to set")
		}
		if st, err = alarms.ParseAssignments(st, args[1:]); err != nil {
			return err
		}
		if err := alarms.SaveSettings(ctx, s, st, time.Now()); err != nil {
			return err
		}
		fmt.Println("saved; the server applies it within a minute")
	}
	onOff := map[bool]string{true: "on", false: "off"}
	fmt.Printf("urgent_low     < %d mg/dL (immediate)\n", st.UrgentLow)
	fmt.Printf("low            < %d mg/dL (immediate)\n", st.Low)
	fmt.Printf("high           > %d mg/dL for %d min\n", st.High, st.HighForMin)
	fmt.Printf("urgent_high    > %d mg/dL for %d min\n", st.UrgentHigh, st.HighForMin)
	fmt.Printf("predicted_low  %s\n", onOff[st.PredictedLow])
	fmt.Printf("predicted_high %s\n", onOff[st.PredictedHigh])
	fmt.Println()
	fmt.Println("episodes (warn: repeats ×3, check-ins ×2):")
	fmt.Printf("repeat_min          %d   nobody took it: ring everyone again\n", st.RepeatMin)
	fmt.Printf("worse_by            %d   mg/dL past the value when taken: ring everyone\n", st.WorseBy)
	fmt.Printf("checkin_first_min   %d   quiet \"all under control?\" to the handler\n", st.CheckinFirstMin)
	fmt.Printf("checkin_every_min   %d\n", st.CheckinEveryMin)
	fmt.Printf("checkin_timeout_min %d   unanswered: ring everyone\n", st.CheckinTimeoutMin)
	fmt.Printf("ceiling_min         %d   urgent and taken this long: ring everyone\n", st.CeilingMin)
	return nil
}

func loc(cfg config.Config) *time.Location {
	l, err := time.LoadLocation(cfg.TZ)
	if err != nil {
		return time.UTC
	}
	return l
}

func liveAlarms(cfg config.Config) error {
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	live, err := s.ActiveAlarms(context.Background())
	if err != nil {
		return err
	}
	if len(live) == 0 {
		fmt.Println("no live alarms")
		return nil
	}
	l := loc(cfg)
	hm := func(ms *int64) string {
		if ms == nil {
			return "-"
		}
		return time.UnixMilli(*ms).In(l).Format("15:04")
	}
	tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tKIND\tLEVEL\tSINCE\tSGV\tSTATE\tHANDLED BY\tQUIET UNTIL\tNEXT CHECK-IN")
	for _, a := range live {
		sgv, state, by := "-", "firing", "-"
		if a.LastSGV != nil {
			sgv = fmt.Sprint(*a.LastSGV)
		}
		if a.HandledBy != nil {
			state, by = "handled", a.HandledByName
			if a.CheckinSentAt != nil {
				state = "check-in pending"
			}
			if a.HelpAt != nil {
				state = "help requested"
			}
		}
		fmt.Fprintf(tw, "%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", a.ID, a.Kind, a.Level,
			time.UnixMilli(a.StartedAt).In(l).Format("01-02 15:04"), sgv, state, by, hm(a.QuietUntil), hm(a.NextCheckinAt))
	}
	return tw.Flush()
}

func readings(cfg config.Config, args []string) error {
	fs := flag.NewFlagSet("readings", flag.ContinueOnError)
	n := fs.Int("n", 12, "how many")
	if err := fs.Parse(args); err != nil {
		return err
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	want := max(1, min(*n, 500))
	// a few extra older rows so the last shown ones still get a 15-minute trend
	es, err := s.QueryEntries(context.Background(), store.EntryQuery{Type: "sgv", Count: want + 4})
	if err != nil {
		return err
	}
	var rows []store.Entry
	for _, e := range es {
		if e.SGV != nil && *e.SGV > glucose.MaxErrorCode {
			rows = append(rows, e)
		}
	}
	if len(rows) == 0 {
		fmt.Println("no readings yet")
		return nil
	}
	l := loc(cfg)
	fmt.Printf("newest %s ago · delta and trend computed from the readings (xDrip+ sends Flat for everything)\n",
		time.Since(time.UnixMilli(rows[0].Date)).Round(time.Second))
	tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "TIME\tSGV\tMMOL\tTREND\tΔ/5 MIN")
	for i := 0; i < len(rows) && i < want; i++ {
		e := rows[i]
		sgv := *e.SGV
		delta, trend := "-", "-"
		if i+1 < len(rows) {
			p := rows[i+1]
			per5 := float64(sgv-*p.SGV) * 5 / (float64(e.Date-p.Date) / 60000)
			delta = fmt.Sprintf("%+.0f", per5)
			rate := per5 / 5
			for j := i + 1; j < len(rows); j++ { // prefer ~15 minutes, like the server
				mins := float64(e.Date-rows[j].Date) / 60000
				if mins >= 12.5 && mins <= 17.5 {
					rate = float64(sgv-*rows[j].SGV) / mins
					break
				}
				if mins > 17.5 {
					break
				}
			}
			dir := glucose.DirectionFromRate(rate)
			trend = glucose.Arrow(dir) + " " + dir
		}
		fmt.Fprintf(tw, "%s\t%d\t%.1f\t%s\t%s\n", time.UnixMilli(e.Date).In(l).Format("01-02 15:04"), sgv, float64(sgv)/18, trend, delta)
	}
	return tw.Flush()
}

func migrate(cfg config.Config) error {
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	return s.Close()
}

func device(cfg config.Config, args []string) error {
	if len(args) == 0 {
		return errors.New("device: want add, list, rotate, person or revoke")
	}
	s, err := openMigrated(cfg)
	if err != nil {
		return err
	}
	defer s.Close()
	ctx := context.Background()

	switch args[0] {
	case "add":
		fs := flag.NewFlagSet("device add", flag.ContinueOnError)
		name := fs.String("name", "", "device name, e.g. \"xDrip\"")
		roleStr := fs.String("role", "", "uploader | caregiver | reader")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		if *name == "" {
			return errors.New("device add: -name is required")
		}
		role, err := auth.ParseRole(*roleStr)
		if err != nil {
			return err
		}
		token, err := auth.NewToken()
		if err != nil {
			return err
		}
		d, err := s.CreateDevice(ctx, *name, string(role), auth.Hash(token), time.Now())
		if err != nil {
			return err
		}
		fmt.Printf("device #%d %q (%s) created\n", d.ID, d.Name, d.Role)
		fmt.Printf("token (shown once, store it now):\n%s\n", token)
		return nil

	case "list":
		devs, err := s.ListDevices(ctx)
		if err != nil {
			return err
		}
		tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(tw, "ID\tNAME\tPERSON\tROLE\tUNITS\tCREATED\tLAST SEEN\tREVOKED")
		for _, d := range devs {
			fmt.Fprintf(tw, "%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
				d.ID, d.Name, d.Person, d.Role, d.DisplayUnits, fmtTime(&d.CreatedAt), fmtTime(d.LastSeenAt), fmtTime(d.RevokedAt))
		}
		return tw.Flush()

	case "rotate":
		fs := flag.NewFlagSet("device rotate", flag.ContinueOnError)
		id := fs.Int64("id", 0, "device id from 'device list'")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		token, err := auth.NewToken()
		if err != nil {
			return err
		}
		d, err := s.RotateToken(ctx, *id, auth.Hash(token))
		if errors.Is(err, store.ErrNotFound) {
			return fmt.Errorf("no active device with id %d", *id)
		}
		if err != nil {
			return err
		}
		fmt.Printf("device #%d %q (%s): new token, the old one no longer works\n", d.ID, d.Name, d.Role)
		fmt.Printf("token (shown once, store it now):\n%s\n", token)
		return nil

	case "person":
		// phones of one person answer questions as one voice
		fs := flag.NewFlagSet("device person", flag.ContinueOnError)
		id := fs.Int64("id", 0, "device id from 'device list'")
		name := fs.String("name", "", "who holds it, e.g. \"Тато\"; empty: the device name")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		if err := s.SetPerson(ctx, *id, *name); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				return fmt.Errorf("no device with id %d", *id)
			}
			return err
		}
		fmt.Printf("device #%d belongs to %q\n", *id, *name)
		return nil

	case "revoke":
		fs := flag.NewFlagSet("device revoke", flag.ContinueOnError)
		id := fs.Int64("id", 0, "device id from 'device list'")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		if err := s.RevokeDevice(ctx, *id, time.Now()); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				return fmt.Errorf("no active device with id %d", *id)
			}
			return err
		}
		fmt.Printf("device #%d revoked\n", *id)
		return nil
	}
	return fmt.Errorf("device: unknown command %q", args[0])
}

func fmtTime(t *time.Time) string {
	if t == nil {
		return "-"
	}
	return t.Local().Format("2006-01-02 15:04")
}
