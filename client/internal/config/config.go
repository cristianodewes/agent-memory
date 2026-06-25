// Package config loads the single agent-memory client configuration (server URL, token, data dir).
// This is the MINIMAL resolver the native hook + drain (#10) need; the full configuration surface
// (file precedence, identity overrides via .agent-memory.toml) lands with #2/#32. Kept deliberately
// small and env-driven so the capture path stays fast and dependency-free.
package config

import (
	"os"
	"path/filepath"
	"strings"
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

	defaultServerURL   = "http://127.0.0.1:8080"
	defaultDataDirName = ".agent-memory"
	// spoolSubdir is the spool's location under the data dir (ARCHITECTURE §4.1 keeps client spool
	// state under the data root).
	spoolSubdir = "spool"
)

// Config is the resolved client configuration.
type Config struct {
	// ServerURL is the base URL of the agent-memory server (e.g. http://127.0.0.1:8080).
	ServerURL string
	// Token is the bearer token, or "" for loopback/no-auth.
	Token string
	// DataDir is the canonical absolute data directory root.
	DataDir string
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
		ServerURL: server,
		Token:     strings.TrimSpace(os.Getenv(EnvToken)),
		DataDir:   resolveDataDir(os.Getenv(EnvDataDir)),
	}
}

// SpoolDir returns the spool directory under the data dir (<data_dir>/spool).
func (c Config) SpoolDir() string {
	return filepath.Join(c.DataDir, spoolSubdir)
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
