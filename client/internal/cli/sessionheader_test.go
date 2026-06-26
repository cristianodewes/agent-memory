package cli

import (
	"bytes"
	"os"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/capturesession"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
)

// runSessionHeaderCmd runs `mcp-session-header` with the given args and EnvDataDir, returning stdout
// (the headers JSON object), stderr, and the error. stdout is kept separate because headersHelper
// parses exactly that stream.
func runSessionHeaderCmd(t *testing.T, dataDir string, args ...string) (string, string, error) {
	t.Helper()
	t.Setenv(config.EnvDataDir, dataDir)
	root := newRootCmd()
	root.SetArgs(append([]string{"mcp-session-header"}, args...))
	var out, errBuf bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&errBuf)
	err := root.Execute()
	return strings.TrimSpace(out.String()), errBuf.String(), err
}

// TestSessionStartHookRecordsCaptureSession is the end-to-end client guard for #87: a SessionStart hook
// writes this project's current capture session id where the MCP header command can read it back.
func TestSessionStartHookRecordsCaptureSession(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1") // boundary drain hits a refused connection
	const sessionID = "0190b3e2-1d00-7a00-8000-0000000000aa"

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "SessionStart"})
	root.SetIn(strings.NewReader(`{"session_id":"` + sessionID + `"}`))
	var out bytes.Buffer
	root.SetErr(&out)
	root.SetOut(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("SessionStart hook must exit 0, got %v (stderr: %s)", err, out.String())
	}

	ws, proj := identityForTest(t)
	got, rerr := capturesession.Read(dataDir, ws, proj)
	if rerr != nil {
		t.Fatalf("read recorded session: %v", rerr)
	}
	if got != sessionID {
		t.Fatalf("recorded session = %q, want %q", got, sessionID)
	}
}

// TestSessionHeaderEmitsRecordedSession: with a session recorded for the project, the command prints
// the X-Agent-Memory-Session header as a JSON object — exactly what headersHelper feeds Claude Code.
func TestSessionHeaderEmitsRecordedSession(t *testing.T) {
	dataDir := t.TempDir()
	const sessionID = "0190b3e2-1d00-7a00-8000-0000000000bb"
	if err := capturesession.Write(dataDir, "acme", "alpha", sessionID); err != nil {
		t.Fatal(err)
	}
	out, _, err := runSessionHeaderCmd(t, dataDir, "--workspace", "acme", "--project", "alpha")
	if err != nil {
		t.Fatalf("command error: %v", err)
	}
	want := `{"` + capturesession.HeaderName + `":"` + sessionID + `"}`
	if out != want {
		t.Fatalf("stdout = %q, want %q", out, want)
	}
}

// TestSessionHeaderDerivesIdentityFromCwd is the runtime guarantee the user-scope (global) install
// relies on (#116): the global headersHelper command bakes NO --workspace/--project, so
// `mcp-session-header` must derive the project from its cwd and still read back the recorded session.
func TestSessionHeaderDerivesIdentityFromCwd(t *testing.T) {
	dataDir := t.TempDir()
	ws, proj := identityForTest(t)
	const sessionID = "0190b3e2-1d00-7a00-8000-0000000000cc"
	if err := capturesession.Write(dataDir, ws, proj, sessionID); err != nil {
		t.Fatal(err)
	}
	// No identity flags — exactly how the global wiring invokes it; identity comes from cwd.
	out, _, err := runSessionHeaderCmd(t, dataDir)
	if err != nil {
		t.Fatalf("command error: %v", err)
	}
	want := `{"` + capturesession.HeaderName + `":"` + sessionID + `"}`
	if out != want {
		t.Fatalf("cwd-derived header = %q, want %q", out, want)
	}
}

// TestSessionHeaderEmitsEmptyObjectWhenNoSession: no recorded session → an empty JSON object, so no
// header is added and the fail-closed server rejects a no-scope session_aware call (never a leak).
func TestSessionHeaderEmitsEmptyObjectWhenNoSession(t *testing.T) {
	out, _, err := runSessionHeaderCmd(t, t.TempDir(), "--workspace", "acme", "--project", "never")
	if err != nil {
		t.Fatalf("command error: %v", err)
	}
	if out != "{}" {
		t.Fatalf("stdout = %q, want {}", out)
	}
}

// TestSessionHeaderIgnoresCorruptSessionFile: a non-UUID in the file is not put on the wire — the
// command emits an empty object rather than a junk header value.
func TestSessionHeaderIgnoresCorruptSessionFile(t *testing.T) {
	dataDir := t.TempDir()
	if err := capturesession.Write(dataDir, "acme", "alpha", "not-a-uuid"); err != nil {
		t.Fatal(err)
	}
	out, _, err := runSessionHeaderCmd(t, dataDir, "--workspace", "acme", "--project", "alpha")
	if err != nil {
		t.Fatalf("command error: %v", err)
	}
	if out != "{}" {
		t.Fatalf("stdout = %q, want {} for a corrupt session file", out)
	}
}

// identityForTest computes the normalized (workspace, project) for the test's working directory the
// same way production code does, so a test can locate the file the hook wrote without hardcoding the
// derived repo identity.
func identityForTest(t *testing.T) (string, string) {
	t.Helper()
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	id := identity.Resolve(cwd)
	ws, err := core.NewWorkspaceID(id.Workspace)
	if err != nil {
		t.Fatalf("normalize workspace %q: %v", id.Workspace, err)
	}
	proj, err := core.NewProjectID(id.Project)
	if err != nil {
		t.Fatalf("normalize project %q: %v", id.Project, err)
	}
	return ws.String(), proj.String()
}
