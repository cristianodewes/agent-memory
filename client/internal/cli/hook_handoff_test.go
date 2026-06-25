package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

// hookServer records the paths the hook hits and lets a test script the /handoff/accept response.
type hookServer struct {
	mu           sync.Mutex
	paths        []string
	acceptStatus int
	acceptBody   string
}

func (h *hookServer) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		h.paths = append(h.paths, r.URL.Path)
		h.mu.Unlock()
		switch r.URL.Path {
		case "/hook/batch":
			w.WriteHeader(http.StatusAccepted) // drain ack
		case "/handoff/accept":
			if h.acceptStatus == 0 {
				h.acceptStatus = http.StatusNoContent
			}
			w.WriteHeader(h.acceptStatus)
			if h.acceptBody != "" {
				_, _ = w.Write([]byte(h.acceptBody))
			}
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}
}

func (h *hookServer) hit(path string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, p := range h.paths {
		if p == path {
			return true
		}
	}
	return false
}

// runSessionStart runs the `hook --event SessionStart` command against srv, capturing stdout and
// stderr SEPARATELY (the handoff injection is written to stdout). Returns (stdout, stderr, err).
func runSessionStart(t *testing.T, srv *httptest.Server, stdin string) (string, string, error) {
	t.Helper()
	t.Setenv(config.EnvDataDir, t.TempDir())
	t.Setenv(config.EnvServerURL, srv.URL)

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "SessionStart"})
	root.SetIn(strings.NewReader(stdin))
	var outBuf, errBuf bytes.Buffer
	root.SetOut(&outBuf)
	root.SetErr(&errBuf)
	err := root.Execute()
	return outBuf.String(), errBuf.String(), err
}

const sessionStartStdin = `{"session_id":"0190b3e2-1d00-7a00-8000-000000000002"}`

// TestSessionStartInjectsOpenHandoff is the headline acceptance: on a fresh session in a project with
// an open handoff, the "where you left off" block is injected at session start (as Claude Code
// SessionStart additionalContext on stdout), AND the drain ran before the handoff fetch.
func TestSessionStartInjectsOpenHandoff(t *testing.T) {
	hs := &hookServer{
		acceptStatus: http.StatusOK,
		acceptBody: `{"id":"id1","workspace":"acme","project":"p","fromSession":"s1",` +
			`"status":"accepted","summary":"Left off mid-refactor of recall.",` +
			`"openQuestions":["Weight vector higher?"],"nextSteps":["Wire reranker"],` +
			`"createdAt":"2026-06-25T12:00:00Z","acceptedAt":"2026-06-25T12:05:00Z"}`,
	}
	srv := httptest.NewServer(hs.handler())
	defer srv.Close()

	stdout, stderr, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("session-start must exit 0, got %v (stderr: %s)", err, stderr)
	}
	// The handoff was fetched+consumed via the accept endpoint (single-use), after the backlog drain.
	if !hs.hit("/handoff/accept") {
		t.Fatal("expected the hook to POST /handoff/accept")
	}
	// The injected block is valid Claude Code SessionStart output on stdout.
	for _, want := range []string{
		`"hookEventName":"SessionStart"`,
		`"additionalContext"`,
		"Left off mid-refactor of recall.",
		"Weight vector higher?",
		"Wire reranker",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("stdout missing %q\n--- stdout ---\n%s", want, stdout)
		}
	}
}

// TestSessionStartNoHandoffIsCleanNoOp: no open handoff (204) → no stdout output, exit 0.
func TestSessionStartNoHandoffIsCleanNoOp(t *testing.T) {
	hs := &hookServer{acceptStatus: http.StatusNoContent}
	srv := httptest.NewServer(hs.handler())
	defer srv.Close()

	stdout, stderr, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("no-handoff session-start must exit 0, got %v (stderr: %s)", err, stderr)
	}
	if !hs.hit("/handoff/accept") {
		t.Fatal("expected the hook to still POST /handoff/accept")
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("no open handoff must emit NOTHING on stdout, got %q", stdout)
	}
}

// TestSessionStartServerDownIsResilient: with the server unreachable, session start still exits 0 and
// emits no injection (a missing server must not break session start).
func TestSessionStartServerDownIsResilient(t *testing.T) {
	t.Setenv(config.EnvDataDir, t.TempDir())
	t.Setenv(config.EnvServerURL, "http://127.0.0.1:1") // nothing listening

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "SessionStart"})
	root.SetIn(strings.NewReader(sessionStartStdin))
	var outBuf, errBuf bytes.Buffer
	root.SetOut(&outBuf)
	root.SetErr(&errBuf)

	if err := root.Execute(); err != nil {
		t.Fatalf("server-down session-start must still exit 0, got %v", err)
	}
	if strings.TrimSpace(outBuf.String()) != "" {
		t.Fatalf("server-down must emit no injection on stdout, got %q", outBuf.String())
	}
}

// TestSessionStartHandoffUnwiredIsNoOp: server up but handoffs unwired (503) → advisory stderr note,
// no stdout, exit 0.
func TestSessionStartHandoffUnwiredIsNoOp(t *testing.T) {
	hs := &hookServer{acceptStatus: http.StatusServiceUnavailable}
	srv := httptest.NewServer(hs.handler())
	defer srv.Close()

	stdout, _, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("session-start must exit 0 even when handoffs are unwired, got %v", err)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("a 503 must emit no injection, got %q", stdout)
	}
}
