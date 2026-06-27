// Package config loads the single agent-memory client configuration (server URL, token, data dir).
// This is the MINIMAL resolver the native hook + drain (#10) need; the full configuration surface
// (file precedence, identity overrides via .agent-memory.toml) lands with #2/#32. Kept deliberately
// small and env-driven so the capture path stays fast and dependency-free.
package config

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Defaults and env var names. These mirror the server's conventions (loopback bind, ~/.agent-memory
// data root) so a default client talks to a default server with no configuration.
const (
	// EnvServerURL overrides the server base URL.
	EnvServerURL = "AGENT_MEMORY_SERVER_URL"
	// EnvToken sets the bearer token for protected routes (DD-007).
	EnvToken = "AGENT_MEMORY_TOKEN"
	// EnvDataDir overrides the on-disk data directory root.
	EnvDataDir = "AGENT_MEMORY_DATA_DIR"
	// EnvLogLevel sets the client log level (debug|info|warn|error). See internal/log.Resolve for how
	// it composes with the -v/--verbose flag and EnvDebug (#117).
	EnvLogLevel = "AGENT_MEMORY_LOG_LEVEL"
	// EnvDebug, when truthy (1|true|yes|on), forces debug-level logging — a shorthand for
	// EnvLogLevel=debug (#117).
	EnvDebug = "AGENT_MEMORY_DEBUG"
	// EnvRecallTimeout overrides the proactive recall-inject deadline (#84, #125). It accepts a Go
	// duration ("12s", "1500ms") or a bare positive integer count of seconds ("12"); empty or
	// unparseable values fall back to DefaultRecallTimeout. See ResolveRecallTimeout.
	EnvRecallTimeout = "AGENT_MEMORY_RECALL_TIMEOUT"
	// EnvLogResponseBodies, when truthy (1|true|yes|on), makes the client log the FULL body of every
	// server response at debug — an OPT-IN debugging aid, OFF by default (#126).
	//
	// DATA-LEAK WARNING: this writes memory CONTENT (recall/inject, briefing, handoff, scent payloads)
	// to the durable client log. The bearer token / `Authorization` stays redacted (the log's secret
	// boundary still runs), but everything else the server returns is exposed in plaintext. Enable it
	// only for deliberate debugging, never as a default.
	EnvLogResponseBodies = "AGENT_MEMORY_LOG_RESPONSE_BODIES"

	defaultServerURL   = "http://127.0.0.1:8080"
	defaultDataDirName = ".agent-memory"
	// spoolSubdir is the spool's location under the data dir (ARCHITECTURE §4.1 keeps client spool
	// state under the data root).
	spoolSubdir = "spool"
	// logsSubdir is the client log's location under the data dir, mirroring the server's rotating
	// tracing dir (ARCHITECTURE §4.1: `<data_dir>/logs`) on the client side (#117).
	logsSubdir = "logs"
)

// Config is the resolved client configuration.
type Config struct {
	// ServerURL is the base URL of the agent-memory server (e.g. http://127.0.0.1:8080).
	ServerURL string
	// Token is the bearer token, or "" for loopback/no-auth.
	Token string
	// DataDir is the canonical absolute data directory root.
	DataDir string
	// LogLevel is the raw AGENT_MEMORY_LOG_LEVEL value ("" when unset). It is resolved to an effective
	// slog level by internal/log.Resolve, which also weighs the -v/--verbose flag and Debug (#117).
	LogLevel string
	// Debug is AGENT_MEMORY_DEBUG read as a boolean: a shorthand that forces debug-level logging.
	Debug bool
	// LogResponseBodies is AGENT_MEMORY_LOG_RESPONSE_BODIES read as a boolean: the opt-in mode that
	// logs full server response bodies at debug. DATA-LEAK risk — see EnvLogResponseBodies. Default
	// false (#126).
	LogResponseBodies bool
}

// Load resolves the client configuration from the environment, applying defaults. It performs no IO
// beyond reading env vars and the user home dir, so it is safe and fast on the capture path. A
// relative or ~-prefixed data dir is expanded to an absolute path.
func Load() Config {
	server := strings.TrimSpace(os.Getenv(EnvServerURL))
	if server == "" {
		server = defaultServerURL
	}
	return Config{
		ServerURL:         server,
		Token:             strings.TrimSpace(os.Getenv(EnvToken)),
		DataDir:           resolveDataDir(os.Getenv(EnvDataDir)),
		LogLevel:          strings.TrimSpace(os.Getenv(EnvLogLevel)),
		Debug:             truthy(os.Getenv(EnvDebug)),
		LogResponseBodies: truthy(os.Getenv(EnvLogResponseBodies)),
	}
}

// SpoolDir returns the spool directory under the data dir (<data_dir>/spool).
func (c Config) SpoolDir() string {
	return filepath.Join(c.DataDir, spoolSubdir)
}

// LogsDir returns the client log directory under the data dir (<data_dir>/logs), where the rotating
// client.log lives (#117). It mirrors the server's tracing dir location on the client side.
func (c Config) LogsDir() string {
	return filepath.Join(c.DataDir, logsSubdir)
}

// DefaultRecallTimeout is the fallback deadline for the proactive recall-inject call (#84, #125) when
// AGENT_MEMORY_RECALL_TIMEOUT is unset or unparseable. It is deliberately generous: the server runs two
// sequential LLM calls (query-expansion + rerank) whose latency swings with the provider/model and load,
// and the call is advisory — a deadline only skips injection, it never blocks the prompt.
const DefaultRecallTimeout = 15 * time.Second

// recallHTTPHeadroom is the slack added on top of the resolved recall deadline to derive the HTTP client
// timeout, so the context deadline (not the transport) stays the authoritative cap and never races the
// apiclient's own default timeout (#125).
const recallHTTPHeadroom = 5 * time.Second

// ResolveRecallTimeout resolves the proactive recall-inject deadline from AGENT_MEMORY_RECALL_TIMEOUT
// (#125). The value may be a Go duration ("12s", "1500ms") or a bare positive integer count of seconds
// ("12"). Empty, non-positive, or unparseable input falls back to DefaultRecallTimeout. It reads only the
// environment, so it is safe and fast on the prompt path. This is the authoritative context deadline; the
// HTTP transport gets a small headroom over it (see RecallHTTPTimeout).
func ResolveRecallTimeout() time.Duration {
	raw := strings.TrimSpace(os.Getenv(EnvRecallTimeout))
	if raw == "" {
		return DefaultRecallTimeout
	}
	if d, err := time.ParseDuration(raw); err == nil && d > 0 {
		return d
	}
	if secs, err := strconv.Atoi(raw); err == nil && secs > 0 {
		return time.Duration(secs) * time.Second
	}
	return DefaultRecallTimeout
}

// RecallHTTPTimeout derives the recall HTTP client timeout from the resolved deadline by adding a fixed
// headroom, keeping the context deadline the authoritative cap so the apiclient default never races it
// (#125).
func RecallHTTPTimeout(deadline time.Duration) time.Duration {
	return deadline + recallHTTPHeadroom
}

// truthy reports whether an env value reads as "on": 1|true|yes|on (case-insensitive). Anything else
// — including "", "0", "false" — is false, so AGENT_MEMORY_DEBUG must be set deliberately to engage.
func truthy(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

// WithIdentityOverrides returns a copy of c with the server URL and/or token replaced by the
// non-empty values a .agent-memory.toml marker supplied (see internal/identity). An empty argument
// leaves the corresponding field untouched, so a marker that pins only identity (no server fields)
// keeps the environment-derived endpoint. This is how a per-tree marker can also point a project at
// a different server without environment variables.
func (c Config) WithIdentityOverrides(serverURL, token string) Config {
	if s := strings.TrimSpace(serverURL); s != "" {
		c.ServerURL = s
	}
	if t := strings.TrimSpace(token); t != "" {
		c.Token = t
	}
	return c
}

// ResolveDataDir expands and absolutizes an explicitly supplied data dir (e.g. an `auth --data-dir`
// flag), applying the same ~ expansion and default as Load. An empty argument yields the default
// ~/.agent-memory root (it does NOT consult the environment — Load already does that for the no-flag
// case).
func ResolveDataDir(configured string) string {
	return resolveDataDir(configured)
}

// resolveDataDir expands and absolutizes the configured data dir, defaulting to ~/.agent-memory.
func resolveDataDir(configured string) string {
	configured = strings.TrimSpace(configured)
	if configured == "" {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, defaultDataDirName)
		}
		// No home dir (rare/sandboxed): fall back to a relative dir so capture still works.
		return defaultDataDirName
	}
	configured = expandHome(configured)
	if abs, err := filepath.Abs(configured); err == nil {
		return abs
	}
	return configured
}

// expandHome expands a leading ~ or ~/... to the user home; other ~ are literal.
func expandHome(path string) string {
	if path == "~" {
		if home, err := os.UserHomeDir(); err == nil {
			return home
		}
		return path
	}
	if strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, path[2:])
		}
	}
	return path
}
