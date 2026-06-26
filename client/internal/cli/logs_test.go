package cli

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

func seedLog(t *testing.T, dataDir, content string) {
	t.Helper()
	logsDir := filepath.Join(dataDir, "logs")
	if err := os.MkdirAll(logsDir, 0o755); err != nil {
		t.Fatalf("mkdir logs: %v", err)
	}
	if err := os.WriteFile(filepath.Join(logsDir, "client.log"), []byte(content), 0o644); err != nil {
		t.Fatalf("seed log: %v", err)
	}
}

func TestLogsTailPrintsLastLines(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, "l1\nl2\nl3\nl4\nl5\n")

	stdout, _, err := runRoot(t, "", "logs", "--tail", "2")
	if err != nil {
		t.Fatalf("logs: %v", err)
	}
	if got := strings.TrimSpace(stdout); got != "l4\nl5" {
		t.Fatalf("logs --tail 2 = %q, want last two lines", got)
	}
}

func TestLogsTailZeroPrintsAll(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, "a\nb\nc\n")

	stdout, _, err := runRoot(t, "", "logs", "--tail", "0")
	if err != nil {
		t.Fatalf("logs: %v", err)
	}
	if got := strings.TrimSpace(stdout); got != "a\nb\nc" {
		t.Fatalf("logs --tail 0 = %q, want all lines", got)
	}
}

func TestLogsNoFileIsFriendly(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)

	stdout, _, err := runRoot(t, "", "logs")
	if err != nil {
		t.Fatalf("logs on a fresh data dir must not error: %v", err)
	}
	if !strings.Contains(stdout, "no client log yet") {
		t.Fatalf("expected a friendly 'no log yet' note, got: %q", stdout)
	}
}

func TestDoctorReportsSpoolAndPaths(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")

	// Spool exactly one event (a regular event does no network and leaves one entry behind).
	if _, _, err := runRoot(t, `{"tool_name":"Bash"}`, "hook", "--event", "PostToolUse"); err != nil {
		t.Fatalf("seed hook: %v", err)
	}

	stdout, _, err := runRoot(t, "", "doctor")
	if err != nil {
		t.Fatalf("doctor: %v", err)
	}
	for _, want := range []string{"data dir:", "server url:", "log level:", "log file:", "spool pending:"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("doctor output missing %q:\n%s", want, stdout)
		}
	}
	if !regexp.MustCompile(`spool pending:\s+1\b`).MatchString(stdout) {
		t.Fatalf("doctor should report 1 pending spool entry:\n%s", stdout)
	}
	// The tail section should surface the captured event from this run.
	if !strings.Contains(stdout, "event captured") {
		t.Fatalf("doctor tail should include the captured event:\n%s", stdout)
	}
}
