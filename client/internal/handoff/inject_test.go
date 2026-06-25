package handoff

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
)

// stubAccepter is a hand-rolled accepter so the fetcher can be tested without a server.
type stubAccepter struct {
	handoff *apiclient.Handoff
	err     error
	gotWS   string
	gotProj string
	calls   int
}

func (s *stubAccepter) AcceptHandoff(_ context.Context, ws, proj string) (*apiclient.Handoff, error) {
	s.calls++
	s.gotWS, s.gotProj = ws, proj
	return s.handoff, s.err
}

func TestFetcherStoresAcceptedHandoffAndRenders(t *testing.T) {
	stub := &stubAccepter{handoff: &apiclient.Handoff{
		Summary:       "Refactored the recall pipeline.",
		OpenQuestions: []string{"Should RRF weight vector higher?"},
		NextSteps:     []string{"Wire the reranker", "Add a benchmark"},
	}}
	f := NewFetcher(stub, "acme", "agent-memory")

	if err := f.FetchHandoff(context.Background()); err != nil {
		t.Fatalf("FetchHandoff errored: %v", err)
	}
	if stub.calls != 1 || stub.gotWS != "acme" || stub.gotProj != "agent-memory" {
		t.Fatalf("accept called wrong: calls=%d ws=%q proj=%q", stub.calls, stub.gotWS, stub.gotProj)
	}
	if f.Handoff() == nil {
		t.Fatal("expected the accepted handoff to be stored")
	}
	out := f.Rendered()
	for _, want := range []string{
		"Handoff from your previous session",
		"Refactored the recall pipeline.",
		"## Open questions",
		"Should RRF weight vector higher?",
		"## Next steps",
		"- Wire the reranker",
		"- Add a benchmark",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("rendered block missing %q\n--- got ---\n%s", want, out)
		}
	}
}

func TestFetcherNoOpenHandoffRendersEmpty(t *testing.T) {
	stub := &stubAccepter{handoff: nil} // HTTP 204 → nil, nil
	f := NewFetcher(stub, "acme", "proj")
	if err := f.FetchHandoff(context.Background()); err != nil {
		t.Fatalf("FetchHandoff errored: %v", err)
	}
	if f.Handoff() != nil {
		t.Fatal("expected no handoff")
	}
	if f.Rendered() != "" {
		t.Fatalf("expected empty render for no handoff, got %q", f.Rendered())
	}
}

func TestFetcherErrorIsReturnedAndRendersEmpty(t *testing.T) {
	stub := &stubAccepter{err: errors.New("server down")}
	f := NewFetcher(stub, "acme", "proj")
	err := f.FetchHandoff(context.Background())
	if err == nil || !strings.Contains(err.Error(), "server down") {
		t.Fatalf("expected the accept error to propagate, got %v", err)
	}
	// A failed fetch must not produce an injection block.
	if f.Rendered() != "" {
		t.Fatalf("expected empty render after a fetch error, got %q", f.Rendered())
	}
}

func TestRenderSummaryOnly(t *testing.T) {
	out := Render(&apiclient.Handoff{Summary: "Just a summary."})
	if !strings.Contains(out, "## Summary") || !strings.Contains(out, "Just a summary.") {
		t.Fatalf("summary-only render wrong: %q", out)
	}
	if strings.Contains(out, "## Open questions") || strings.Contains(out, "## Next steps") {
		t.Fatalf("summary-only render should omit empty sections: %q", out)
	}
}

func TestRenderEmptyHandoffIsEmpty(t *testing.T) {
	// A handoff with nothing useful (blank summary, blank list entries) renders "" so the caller no-ops.
	out := Render(&apiclient.Handoff{Summary: "  ", OpenQuestions: []string{" ", ""}, NextSteps: nil})
	if out != "" {
		t.Fatalf("expected empty render for an empty handoff, got %q", out)
	}
	if Render(nil) != "" {
		t.Fatal("Render(nil) should be empty")
	}
}
