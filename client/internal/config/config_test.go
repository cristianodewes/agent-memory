package config

import (
	"path/filepath"
	"testing"
	"time"
)

func TestLogsDir(t *testing.T) {
	c := Config{DataDir: filepath.FromSlash("/data")}
	want := filepath.Join(filepath.FromSlash("/data"), "logs")
	if got := c.LogsDir(); got != want {
		t.Fatalf("LogsDir() = %q, want %q", got, want)
	}
}

func TestLoadReadsLogEnv(t *testing.T) {
	t.Setenv(EnvDataDir, t.TempDir())
	t.Setenv(EnvLogLevel, "  debug ")
	t.Setenv(EnvDebug, "1")
	cfg := Load()
	if cfg.LogLevel != "debug" {
		t.Fatalf("LogLevel = %q, want trimmed %q", cfg.LogLevel, "debug")
	}
	if !cfg.Debug {
		t.Fatalf("Debug = false, want true for AGENT_MEMORY_DEBUG=1")
	}
}

func TestLoadDebugDefaultsFalse(t *testing.T) {
	t.Setenv(EnvDataDir, t.TempDir())
	// EnvDebug unset/empty ⇒ Debug false, LogLevel empty.
	cfg := Load()
	if cfg.Debug {
		t.Fatal("Debug should default to false when AGENT_MEMORY_DEBUG is unset")
	}
	if cfg.LogLevel != "" {
		t.Fatalf("LogLevel should default to empty, got %q", cfg.LogLevel)
	}
}

func TestTruthy(t *testing.T) {
	for _, v := range []string{"1", "true", "TRUE", " yes ", "on", "On"} {
		if !truthy(v) {
			t.Fatalf("truthy(%q) = false, want true", v)
		}
	}
	for _, v := range []string{"", "0", "false", "no", "off", "2", "enabled"} {
		if truthy(v) {
			t.Fatalf("truthy(%q) = true, want false", v)
		}
	}
}

func TestResolveRecallTimeout(t *testing.T) {
	t.Run("go duration in seconds", func(t *testing.T) {
		t.Setenv(EnvRecallTimeout, "12s")
		if got := ResolveRecallTimeout(); got != 12*time.Second {
			t.Fatalf("ResolveRecallTimeout() = %v, want 12s", got)
		}
	})

	t.Run("go duration in milliseconds", func(t *testing.T) {
		t.Setenv(EnvRecallTimeout, "1500ms")
		if got := ResolveRecallTimeout(); got != 1500*time.Millisecond {
			t.Fatalf("ResolveRecallTimeout() = %v, want 1500ms", got)
		}
	})

	t.Run("bare integer is parsed as seconds", func(t *testing.T) {
		t.Setenv(EnvRecallTimeout, "  20 ")
		if got := ResolveRecallTimeout(); got != 20*time.Second {
			t.Fatalf("ResolveRecallTimeout() = %v, want 20s", got)
		}
	})

	t.Run("empty falls back to default", func(t *testing.T) {
		t.Setenv(EnvRecallTimeout, "")
		if got := ResolveRecallTimeout(); got != DefaultRecallTimeout {
			t.Fatalf("ResolveRecallTimeout() = %v, want default %v", got, DefaultRecallTimeout)
		}
	})

	t.Run("invalid falls back to default", func(t *testing.T) {
		t.Setenv(EnvRecallTimeout, "soon")
		if got := ResolveRecallTimeout(); got != DefaultRecallTimeout {
			t.Fatalf("ResolveRecallTimeout() = %v, want default %v", got, DefaultRecallTimeout)
		}
	})

	t.Run("non-positive falls back to default", func(t *testing.T) {
		for _, v := range []string{"0", "-5s", "-3"} {
			t.Setenv(EnvRecallTimeout, v)
			if got := ResolveRecallTimeout(); got != DefaultRecallTimeout {
				t.Fatalf("ResolveRecallTimeout(%q) = %v, want default %v", v, got, DefaultRecallTimeout)
			}
		}
	})
}

// TestDefaultRecallTimeoutFloor pins the #125 requirement that the default be at least 15s.
func TestDefaultRecallTimeoutFloor(t *testing.T) {
	if DefaultRecallTimeout < 15*time.Second {
		t.Fatalf("DefaultRecallTimeout = %v, must be >= 15s per #125", DefaultRecallTimeout)
	}
}

// TestRecallHTTPTimeout asserts the HTTP client timeout is derived as deadline + fixed headroom, so the
// context deadline stays the authoritative cap (#125).
func TestRecallHTTPTimeout(t *testing.T) {
	if got := RecallHTTPTimeout(12 * time.Second); got != 17*time.Second {
		t.Fatalf("RecallHTTPTimeout(12s) = %v, want 17s (deadline + 5s headroom)", got)
	}
	if got := RecallHTTPTimeout(DefaultRecallTimeout); got != DefaultRecallTimeout+5*time.Second {
		t.Fatalf("RecallHTTPTimeout(default) = %v, want %v", got, DefaultRecallTimeout+5*time.Second)
	}
}

func TestWithIdentityOverrides(t *testing.T) {
	base := Config{ServerURL: "http://127.0.0.1:8080", Token: "env-token", DataDir: "/data"}

	t.Run("non-empty values override", func(t *testing.T) {
		got := base.WithIdentityOverrides("http://10.0.0.5:8080", "marker-token")
		if got.ServerURL != "http://10.0.0.5:8080" {
			t.Fatalf("serverURL = %q, want overridden", got.ServerURL)
		}
		if got.Token != "marker-token" {
			t.Fatalf("token = %q, want overridden", got.Token)
		}
		if got.DataDir != "/data" {
			t.Fatalf("dataDir = %q, want unchanged", got.DataDir)
		}
	})

	t.Run("empty values leave fields untouched", func(t *testing.T) {
		got := base.WithIdentityOverrides("   ", "")
		if got.ServerURL != base.ServerURL || got.Token != base.Token {
			t.Fatalf("blank overrides changed config: %+v", got)
		}
	})

	t.Run("only server url overridden, token kept", func(t *testing.T) {
		got := base.WithIdentityOverrides("http://h:9", "")
		if got.ServerURL != "http://h:9" {
			t.Fatalf("serverURL = %q, want overridden", got.ServerURL)
		}
		if got.Token != "env-token" {
			t.Fatalf("token = %q, want env-token kept", got.Token)
		}
	})
}
