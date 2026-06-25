package cli

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

// recallServer records the paths/bodies the hook hits and scripts the /recall/inject response for the
// UserPromptSubmit proactive-recall tests (#84).
type recallServer struct {
	mu           sync.Mutex
	paths        []string
	bodies       []string
	injectStatus int
	injectBody   string
}

func (s *recallServer) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		s.mu.Lock()
		s.paths = append(s.paths, r.URL.Path)
		s.bodies = append(s.bodies, string(raw))
		s.mu.Unlock()
		switch r.URL.Path {
		case "/recall/inject":
			status := s.injectStatus
			if status == 0 {
				status = http.StatusOK
			}
			w.WriteHeader(status)
			if s.injectBody != "" {
				_, _ = w.Write([]byte(s.injectBody))
			}
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}
}

func (s *recallServer) hit(path string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, p := range s.paths {
		if p == path {
			return true
		}
	}
	return false
}

// bodyContains reports whether any recorded request body contains substr (used to assert the prompt
// reached /recall/inject).
func (s *recallServer) bodyContains(substr string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, b := range s.bodies {
		if strings.Contains(b, substr) {
			return true
		}
	}
	return false
}

// runUserPrompt runs the real `hook --event UserPromptSubmit` command against serverURL, capturing
// stdout and stderr SEPARATELY (the recall injection is written to stdout). Returns (stdout, stderr,
// err).
func runUserPrompt(t *testing.T, serverURL, stdin string) (string, string, error) {
	t.Helper()
	t.Setenv(config.EnvDataDir, t.TempDir())
	t.Setenv(config.EnvServerURL, serverURL)

	root := newRootCmd()
	root.SetArgs([]string{"hook", "--event", "UserPromptSubmit"})
	root.SetIn(strings.NewReader(stdin))
	var outBuf, errBuf bytes.Buffer
	root.SetOut(&outBuf)
	root.SetErr(&errBuf)
	err := root.Execute()
	return outBuf.String(), errBuf.String(), err
}

const userPromptStdin = `{"session_id":"0190b3e2-1d00-7a00-8000-000000000003",` +
	`"prompt":"how does recall ranking work?"}`

// TestUserPromptSubmitInjectsRecallBlock is the headline acceptance (#84): on a user prompt, the
// client POSTs the prompt to /recall/inject and injects the returned block as Claude Code
// UserPromptSubmit additionalContext on stdout — proactive recall, no agent action needed.
func TestUserPromptSubmitInjectsRecallBlock(t *testing.T) {
	rs := &recallServer{
		injectStatus: http.StatusOK,
		injectBody: `{"scope":{"workspace":"acme","project":"p"},"hits":1,` +
			`"text":"## Relevant memory\nRecall fuses FTS + link-graph with RRF."}`,
	}
	srv := httptest.NewServer(rs.handler())
	defer srv.Close()

	stdout, stderr, err := runUserPrompt(t, srv.URL, userPromptStdin)
	if err != nil {
		t.Fatalf("user-prompt must exit 0, got %v (stderr: %s)", err, stderr)
	}
	if !rs.hit("/recall/inject") {
		t.Fatal("expected the hook to POST /recall/inject")
	}
	// A non-boundary event must NOT drain the spool over the network.
	if rs.hit("/hook/batch") {
		t.Error("UserPromptSubmit must not drain the spool (no /hook/batch)")
	}
	if !rs.bodyContains("how does recall ranking work?") {
		t.Error("the prompt text must reach /recall/inject")
	}
	for _, want := range []string{
		`"hookEventName":"UserPromptSubmit"`,
		`"additionalContext"`,
		"Recall fuses FTS + link-graph with RRF.",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("stdout missing %q\n--- stdout ---\n%s", want, stdout)
		}
	}
}

// TestUserPromptSubmitEmptyRecallIsNoOp: the server returns an empty block (low-signal prompt) → the
// endpoint is still called, but nothing is injected on stdout, exit 0.
func TestUserPromptSubmitEmptyRecallIsNoOp(t *testing.T) {
	rs := &recallServer{
		injectStatus: http.StatusOK,
		injectBody:   `{"scope":{"workspace":"acme","project":"p"},"hits":0,"text":""}`,
	}
	srv := httptest.NewServer(rs.handler())
	defer srv.Close()

	stdout, stderr, err := runUserPrompt(t, srv.URL, userPromptStdin)
	if err != nil {
		t.Fatalf("empty-recall user-prompt must exit 0, got %v (stderr: %s)", err, stderr)
	}
	if !rs.hit("/recall/inject") {
		t.Fatal("expected the hook to still POST /recall/inject")
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("an empty recall block must emit NOTHING on stdout, got %q", stdout)
	}
}

// TestUserPromptSubmitServerDownIsResilient: with the server unreachable, the prompt still exits 0 and
// injects nothing (a missing server must never block or delay the prompt).
func TestUserPromptSubmitServerDownIsResilient(t *testing.T) {
	// http://127.0.0.1:1 — nothing listening.
	stdout, _, err := runUserPrompt(t, "http://127.0.0.1:1", userPromptStdin)
	if err != nil {
		t.Fatalf("server-down user-prompt must still exit 0, got %v", err)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("server-down must emit no injection on stdout, got %q", stdout)
	}
}

// TestUserPromptSubmitUnwiredIsNoOp: server up but recall unwired (503) → advisory, no stdout, exit 0.
func TestUserPromptSubmitUnwiredIsNoOp(t *testing.T) {
	rs := &recallServer{injectStatus: http.StatusServiceUnavailable}
	srv := httptest.NewServer(rs.handler())
	defer srv.Close()

	stdout, _, err := runUserPrompt(t, srv.URL, userPromptStdin)
	if err != nil {
		t.Fatalf("user-prompt must exit 0 even when recall is unwired, got %v", err)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("a 503 must emit no injection, got %q", stdout)
	}
}

// TestUserPromptSubmitBlankPromptSkipsCall: a prompt event with no body skips the network call
// entirely (no point asking recall about nothing) and injects nothing, exit 0.
func TestUserPromptSubmitBlankPromptSkipsCall(t *testing.T) {
	rs := &recallServer{injectStatus: http.StatusOK, injectBody: `{"text":"should not be used"}`}
	srv := httptest.NewServer(rs.handler())
	defer srv.Close()

	stdout, _, err := runUserPrompt(t, srv.URL,
		`{"session_id":"0190b3e2-1d00-7a00-8000-000000000004"}`)
	if err != nil {
		t.Fatalf("blank-prompt user-prompt must exit 0, got %v", err)
	}
	if rs.hit("/recall/inject") {
		t.Error("a blank prompt must NOT call /recall/inject")
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("a blank prompt must emit no injection, got %q", stdout)
	}
}
