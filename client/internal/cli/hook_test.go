package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

// runHookCmd executes the `hook` subcommand with the given args and stdin, isolating the data dir to
// a temp dir (so the spool lands there) and pointing the server at an unused port (so any boundary
// drain hits a refused connection). Returns the captured stderr and the command error.
func runHookCmd(t *testing.T, stdin string, args ...string) (string, error) {
	t.Helper()
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	// Point at a port nothing is listening on, so a drain attempt fails fast (server down).
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")

	root := newRootCmd()
	root.SetArgs(append([]string{"hook"}, args...))
	root.SetIn(strings.NewReader(stdin))
	var errBuf bytes.Buffer
	root.SetErr(&errBuf)
	root.SetOut(&errBuf)
	err := root.Execute()
	return errBuf.String(), err
}

func spoolFiles(t *testing.T, dataDir string) []string {
	t.Helper()
	dir := filepath.Join(dataDir, "spool")
	dirents, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var names []string
	for _, de := range dirents {
		if !de.IsDir() && strings.HasSuffix(de.Name(), ".json") {
			names = append(names, de.Name())
		}
	}
	return names
}

// TestCaptureWritesSpoolAndExitsZero is the core acceptance: a regular event is captured to the
// spool and the command exits 0 (no error), with no network involved on the hot path.
func TestCaptureWritesSpoolAndExitsZero(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1")

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "UserPromptSubmit"})
	root.SetIn(strings.NewReader(`{"prompt":"why is recall slow?"}`))
	var out bytes.Buffer
	root.SetErr(&out)
	root.SetOut(&out)

	if err := root.Execute(); err != nil {
		t.Fatalf("capture must exit 0, got error: %v (stderr: %s)", err, out.String())
	}
	if files := spoolFiles(t, dataDir); len(files) != 1 {
		t.Fatalf("expected exactly 1 spooled event, got %d", len(files))
	}
}

// TestCaptureSucceedsWithServerDownAtSessionEnd: even at a session boundary (where the drain runs),
// a server-down does NOT fail the command — the event is already durably spooled, which is the
// guarantee. The drain error is reported on stderr, not via exit code.
func TestCaptureSucceedsWithServerDownAtSessionEnd(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1") // nothing listening

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "SessionEnd"})
	root.SetIn(strings.NewReader(`{"session_id":"0190b3e2-1d00-7a00-8000-000000000002"}`))
	var out bytes.Buffer
	root.SetErr(&out)
	root.SetOut(&out)

	if err := root.Execute(); err != nil {
		t.Fatalf("server-down at session-end must still exit 0, got: %v", err)
	}
	// The session-end event itself remains spooled (the drain to a down server left it intact).
	if files := spoolFiles(t, dataDir); len(files) == 0 {
		t.Fatal("the session-end event must remain spooled when the server is down")
	}
}

func TestCaptureReadsPayloadFlagLiteral(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)

	root := newRootCmd()
	root.SetArgs([]string{
		"hook", "--event", "Stop", "--payload", `{"session_id":"x"}`,
	})
	root.SetIn(strings.NewReader("")) // stdin unused when --payload is a literal
	var out bytes.Buffer
	root.SetErr(&out)
	root.SetOut(&out)

	if err := root.Execute(); err != nil {
		t.Fatalf("literal --payload should work, got: %v", err)
	}
	if files := spoolFiles(t, dataDir); len(files) != 1 {
		t.Fatalf("expected 1 spooled event, got %d", len(files))
	}
}

func TestCaptureMissingEventIsAnError(t *testing.T) {
	// No --event and no hook_event_name ⇒ capture cannot classify the event: it is a usage error.
	_, err := runHookCmd(t, `{"prompt":"hi"}`)
	if err == nil {
		t.Fatal("expected an error when no event is provided")
	}
}
