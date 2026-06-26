package log

import (
	"bufio"
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseLevel(t *testing.T) {
	cases := map[string]struct {
		want slog.Level
		ok   bool
	}{
		"debug":   {slog.LevelDebug, true},
		"DEBUG":   {slog.LevelDebug, true},
		"info":    {slog.LevelInfo, true},
		" warn ":  {slog.LevelWarn, true},
		"warning": {slog.LevelWarn, true},
		"error":   {slog.LevelError, true},
		"":        {slog.LevelInfo, false},
		"bogus":   {slog.LevelInfo, false},
	}
	for in, want := range cases {
		got, ok := ParseLevel(in)
		if got != want.want || ok != want.ok {
			t.Fatalf("ParseLevel(%q) = (%v,%v), want (%v,%v)", in, got, ok, want.want, want.ok)
		}
	}
}

// TestResolve covers the precedence: -v flag > AGENT_MEMORY_LOG_LEVEL > AGENT_MEMORY_DEBUG > info.
func TestResolve(t *testing.T) {
	cases := []struct {
		name     string
		verbose  int
		envLevel string
		debugEnv bool
		want     slog.Level
	}{
		{"default is info", 0, "", false, slog.LevelInfo},
		{"verbose forces debug", 1, "error", false, slog.LevelDebug},
		{"env level honored", 0, "warn", false, slog.LevelWarn},
		{"debug env when no level", 0, "", true, slog.LevelDebug},
		{"env level beats debug env", 0, "error", true, slog.LevelError},
		{"invalid env falls through to debug env", 0, "nope", true, slog.LevelDebug},
		{"invalid env falls through to info", 0, "nope", false, slog.LevelInfo},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Resolve(c.verbose, c.envLevel, c.debugEnv); got != c.want {
				t.Fatalf("Resolve(%d,%q,%v) = %v, want %v", c.verbose, c.envLevel, c.debugEnv, got, c.want)
			}
		})
	}
}

// TestLoggerWritesRedactedJSON is the end-to-end redaction + format proof: a real file logger emits
// valid JSON per line, never writes token/Authorization, and masks secrets embedded in values.
func TestLoggerWritesRedactedJSON(t *testing.T) {
	dir := t.TempDir()
	lg, err := New(Options{Dir: dir, Level: slog.LevelDebug})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	lg.Info("event captured",
		"kind", "PostToolUse",
		"authorization", "Bearer abc.def.ghi",
		"token", "supersecret-token",
	)
	lg.Debug("event payload", "bodyPreview", "Authorization: Bearer zzz-leaky")
	if err := lg.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	body := readFile(t, filepath.Join(dir, "client.log"))
	for _, secret := range []string{"abc.def.ghi", "supersecret-token", "zzz-leaky"} {
		if strings.Contains(body, secret) {
			t.Fatalf("secret %q leaked into the log:\n%s", secret, body)
		}
	}
	if !strings.Contains(body, Redacted) {
		t.Fatalf("expected redaction marker in log:\n%s", body)
	}
	if !strings.Contains(body, "event captured") || !strings.Contains(body, "PostToolUse") {
		t.Fatalf("expected the non-secret content to survive:\n%s", body)
	}
	// Every line must be valid JSON (structured output).
	sc := bufio.NewScanner(strings.NewReader(body))
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal(line, &m); err != nil {
			t.Fatalf("log line is not valid JSON: %q (%v)", line, err)
		}
	}
}

// TestLoggerLevelGating proves the level actually filters: at warn, info/debug records are dropped.
func TestLoggerLevelGating(t *testing.T) {
	dir := t.TempDir()
	lg, err := New(Options{Dir: dir, Level: slog.LevelWarn})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	lg.Debug("a debug line")
	lg.Info("an info line")
	lg.Warn("a warn line")
	if err := lg.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	body := readFile(t, filepath.Join(dir, "client.log"))
	if strings.Contains(body, "a debug line") || strings.Contains(body, "an info line") {
		t.Fatalf("sub-threshold records were written:\n%s", body)
	}
	if !strings.Contains(body, "a warn line") {
		t.Fatalf("warn record missing:\n%s", body)
	}
}

func TestNopLoggerIsSafe(t *testing.T) {
	lg := Nop()
	lg.Info("ignored", "token", "secret") // must not panic or write anywhere
	if lg.Path() != "" {
		t.Fatalf("Nop logger should have no path, got %q", lg.Path())
	}
	if err := lg.Close(); err != nil {
		t.Fatalf("Nop Close: %v", err)
	}
	if lg.Dropped() != 0 {
		t.Fatalf("Nop Dropped = %d, want 0", lg.Dropped())
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(b)
}
