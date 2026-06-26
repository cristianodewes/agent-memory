package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

// runRoot executes a root subcommand with SEPARATE stdout/stderr buffers, so a test can assert the
// hook's stdout (additional-context channel) stays clean independently of stderr.
func runRoot(t *testing.T, stdin string, args ...string) (stdout, stderr string, err error) {
	t.Helper()
	root := newRootCmd()
	root.SetArgs(args)
	root.SetIn(strings.NewReader(stdin))
	var outBuf, errBuf bytes.Buffer
	root.SetOut(&outBuf)
	root.SetErr(&errBuf)
	err = root.Execute()
	return outBuf.String(), errBuf.String(), err
}

func readClientLog(t *testing.T, dataDir string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dataDir, "logs", "client.log"))
	if err != nil {
		if os.IsNotExist(err) {
			return ""
		}
		t.Fatalf("read client log: %v", err)
	}
	return string(b)
}

// TestHookWritesStructuredLog: a captured event leaves an "event captured" JSON record in the durable
// log, and the hook's stdout stays clean (no additional-context for a regular event).
func TestHookWritesStructuredLog(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")

	stdout, _, err := runRoot(t, `{"tool_name":"Bash"}`, "hook", "--event", "PostToolUse")
	if err != nil {
		t.Fatalf("hook returned error: %v", err)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("stdout must stay clean for a regular event, got: %q", stdout)
	}
	log := readClientLog(t, dataDir)
	if !strings.Contains(log, `"msg":"event captured"`) {
		t.Fatalf("expected an 'event captured' record, got:\n%s", log)
	}
	if !strings.Contains(log, `"event":"PostToolUse"`) {
		t.Fatalf("expected the raw event name in the record, got:\n%s", log)
	}
}

// TestHookHotPathNoNetworkWithDebug is the hot-path-budget acceptance at the command level: even with
// debug logging ON and the server pointed at a black hole, capturing a regular event does NO network
// and returns well within budget (a network call would hit the multi-second drain/recall timeouts).
func TestHookHotPathNoNetworkWithDebug(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")
	t.Setenv(config.EnvDebug, "1")

	start := time.Now()
	stdout, _, err := runRoot(t, `{"tool_name":"Bash"}`, "hook", "--event", "PostToolUse")
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("hook returned error: %v", err)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("hot path took %v with debug logging — a blocking network/IO call slipped in", elapsed)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("stdout must stay clean, got: %q", stdout)
	}
	if readClientLog(t, dataDir) == "" {
		t.Fatal("expected debug logging to produce a non-empty client log")
	}
}

// TestHookLogLevelWarnSuppressesInfo: AGENT_MEMORY_LOG_LEVEL=warn drops the info-level capture record,
// proving the level is honored from the environment.
func TestHookLogLevelWarnSuppressesInfo(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")
	t.Setenv(config.EnvLogLevel, "warn")

	if _, _, err := runRoot(t, `{"tool_name":"Bash"}`, "hook", "--event", "PostToolUse"); err != nil {
		t.Fatalf("hook returned error: %v", err)
	}
	if log := readClientLog(t, dataDir); strings.Contains(log, "event captured") {
		t.Fatalf("info record must be suppressed at warn level, got:\n%s", log)
	}
}

// TestHookVerboseFlagEnablesDebug: the persistent -v flag forces debug, surfacing the debug-only
// "regular event" record that the default info level omits.
func TestHookVerboseFlagEnablesDebug(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")

	if _, _, err := runRoot(t, `{"tool_name":"Bash"}`, "hook", "--event", "PostToolUse", "-v"); err != nil {
		t.Fatalf("hook -v returned error: %v", err)
	}
	if log := readClientLog(t, dataDir); !strings.Contains(log, "regular event") {
		t.Fatalf("expected the debug-level regular-event record under -v, got:\n%s", log)
	}
}

// TestHookTokenNeverLogged: with a configured bearer token and a boundary drain that hits a black-hole
// server at debug level, the token must NEVER appear in the durable log (issue #117 / invariant #6).
func TestHookTokenNeverLogged(t *testing.T) {
	const secret = "supersecret-bearer-tok-987654"
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")
	t.Setenv(config.EnvToken, secret)
	t.Setenv(config.EnvDebug, "1")

	if _, _, err := runRoot(t, `{"session_id":"0190b3e2-1d00-7a00-8000-000000000002"}`,
		"hook", "--event", "SessionEnd"); err != nil {
		t.Fatalf("hook returned error: %v", err)
	}
	log := readClientLog(t, dataDir)
	if strings.Contains(log, secret) {
		t.Fatalf("bearer token leaked into the log:\n%s", log)
	}
	// Sanity: the drain cycle WAS logged (so the absence of the token is meaningful, not an empty file).
	if !strings.Contains(log, "drain complete") {
		t.Fatalf("expected the drain cycle to be logged, got:\n%s", log)
	}
}
