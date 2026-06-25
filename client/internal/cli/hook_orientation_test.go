package cli

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// orientServer scripts the SessionStart endpoints the orientation path hits (#85): the backlog drain
// (/hook/batch), the handoff (/handoff/accept), and the briefing (GET .../briefing). A blank handoffBody
// means "no open handoff" (204); a blank briefingBody means "briefing unwired" (503).
type orientServer struct {
	mu           sync.Mutex
	paths        []string
	handoffBody  string
	briefingBody string
}

func (s *orientServer) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		s.mu.Lock()
		s.paths = append(s.paths, r.URL.Path)
		s.mu.Unlock()
		switch {
		case r.URL.Path == "/hook/batch":
			w.WriteHeader(http.StatusAccepted)
		case r.URL.Path == "/handoff/accept":
			if s.handoffBody == "" {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			_, _ = io.WriteString(w, s.handoffBody)
		case strings.HasSuffix(r.URL.Path, "/briefing"):
			if s.briefingBody == "" {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
			_, _ = io.WriteString(w, s.briefingBody)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}
}

func (s *orientServer) hitSuffix(suffix string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, p := range s.paths {
		if strings.HasSuffix(p, suffix) {
			return true
		}
	}
	return false
}

const orientBriefingBody = `{"scope":{"workspace":"acme","project":"p"},
	"pages":12,"observations":40,"sessions":3,"links":7,"dependents":2,
	"observationsLast7Days":5,"observationsLast30Days":18,
	"rules":["_rules/style.md"],"slots":["_slots/identity.md"],
	"recent":[{"path":"concepts/recall.md","title":"Recall","updatedAt":"2026-06-25T12:00:00Z"}]}`

const orientHandoffBody = `{"id":"id1","workspace":"acme","project":"p","status":"accepted",` +
	`"summary":"Left off mid-refactor of recall.","openQuestions":[],"nextSteps":[],` +
	`"createdAt":"2026-06-25T12:00:00Z"}`

// TestSessionStartInjectsHandoffAndBriefing is the #85 headline: at session start the injected
// additionalContext now carries the handoff FIRST and then the bounded project briefing orientation.
func TestSessionStartInjectsHandoffAndBriefing(t *testing.T) {
	osv := &orientServer{handoffBody: orientHandoffBody, briefingBody: orientBriefingBody}
	srv := httptest.NewServer(osv.handler())
	defer srv.Close()

	stdout, stderr, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("session-start must exit 0, got %v (stderr: %s)", err, stderr)
	}
	if !osv.hitSuffix("/handoff/accept") || !osv.hitSuffix("/briefing") {
		t.Fatalf("expected both /handoff/accept and /briefing to be hit, paths: %v", osv.paths)
	}
	for _, want := range []string{
		`"hookEventName":"SessionStart"`,
		"Left off mid-refactor of recall.", // handoff
		"Project memory orientation",       // briefing section
		"Pages: 12",
		"concepts/recall.md",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("stdout missing %q\n--- stdout ---\n%s", want, stdout)
		}
	}
	if strings.Index(stdout, "Left off mid-refactor") > strings.Index(stdout, "Project memory orientation") {
		t.Errorf("the handoff must precede the briefing:\n%s", stdout)
	}
}

// TestSessionStartInjectsBriefingWithoutHandoff: no open handoff (204) but a briefing is available → the
// orientation block still injects (briefing only). A handoff failing must not suppress orientation.
func TestSessionStartInjectsBriefingWithoutHandoff(t *testing.T) {
	osv := &orientServer{handoffBody: "", briefingBody: orientBriefingBody}
	srv := httptest.NewServer(osv.handler())
	defer srv.Close()

	stdout, stderr, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("session-start must exit 0, got %v (stderr: %s)", err, stderr)
	}
	if strings.Contains(stdout, "Handoff from your previous session") {
		t.Errorf("did not expect a handoff section:\n%s", stdout)
	}
	for _, want := range []string{"Project memory orientation", "Pages: 12", "concepts/recall.md"} {
		if !strings.Contains(stdout, want) {
			t.Errorf("stdout missing %q\n--- stdout ---\n%s", want, stdout)
		}
	}
}

// TestSessionStartNoHandoffNoBriefingIsCleanNoOp: nothing available (204 handoff + 503 briefing) → no
// stdout, exit 0.
func TestSessionStartNoHandoffNoBriefingIsCleanNoOp(t *testing.T) {
	osv := &orientServer{handoffBody: "", briefingBody: ""}
	srv := httptest.NewServer(osv.handler())
	defer srv.Close()

	stdout, _, err := runSessionStart(t, srv, sessionStartStdin)
	if err != nil {
		t.Fatalf("session-start must exit 0, got %v", err)
	}
	if strings.TrimSpace(stdout) != "" {
		t.Fatalf("nothing to orient → no stdout, got %q", stdout)
	}
}
