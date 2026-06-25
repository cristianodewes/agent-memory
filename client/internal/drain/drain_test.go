package drain

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
)

// fakePoster drives the drain's 202/429/transport-error paths deterministically. outcomes is a queue
// of canned responses; each call pops the next (the last repeats once exhausted). A non-nil err on a
// call simulates a transport failure (server down).
type fakePoster struct {
	calls    int
	totalIn  int
	outcomes []apiclient.BatchOutcome
	err      error
}

func (f *fakePoster) PostHookBatch(_ context.Context, events []hook.Payload) (apiclient.BatchResult, error) {
	f.calls++
	f.totalIn += len(events)
	if f.err != nil {
		return apiclient.BatchResult{}, f.err
	}
	out := apiclient.BatchAccepted
	if len(f.outcomes) > 0 {
		idx := f.calls - 1
		if idx >= len(f.outcomes) {
			idx = len(f.outcomes) - 1
		}
		out = f.outcomes[idx]
	}
	status := 202
	switch out {
	case apiclient.BatchThrottled:
		status = 429
	case apiclient.BatchRejected:
		status = 400
	}
	return apiclient.BatchResult{Outcome: out, Status: status}, nil
}

// noSleep is an injected backoff that never actually waits, so the 429 tests are instant.
func noSleep(_ context.Context, _ time.Duration) error { return nil }

func newSpool(t *testing.T) *spool.Spool {
	t.Helper()
	s, err := spool.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func appendN(t *testing.T, s *spool.Spool, n int) {
	t.Helper()
	ws, _ := core.NewWorkspaceID("acme")
	proj, _ := core.NewProjectID("agent-memory")
	for i := 0; i < n; i++ {
		id := "evt-" + string(rune('a'+i))
		p := hook.NewPayload("PostToolUse", core.NewSessionID(), ws, proj,
			time.Date(2026, 6, 25, 12, 0, i, 0, time.UTC))
		p.ClientEventID = &id
		if _, err := s.Append(p); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDrainShipsAndClearsSpoolOnAccept(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 5)
	poster := &fakePoster{}
	d := New(s, poster, Options{Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if res.Sent != 5 || res.Remaining != 0 || res.Quarantined != 0 {
		t.Fatalf("unexpected result: %+v", res)
	}
	if poster.totalIn != 5 {
		t.Fatalf("expected 5 events posted, got %d", poster.totalIn)
	}
	if entries, _ := s.List(); len(entries) != 0 {
		t.Fatalf("spool must be cleared on ack, %d remain", len(entries))
	}
}

func TestDrainBatchesBySize(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 5)
	poster := &fakePoster{}
	d := New(s, poster, Options{BatchSize: 2, Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if res.Sent != 5 {
		t.Fatalf("expected 5 sent, got %d", res.Sent)
	}
	// 5 events at batch size 2 ⇒ 3 POSTs (2,2,1).
	if poster.calls != 3 {
		t.Fatalf("expected 3 batch POSTs, got %d", poster.calls)
	}
}

// TestDrainQuarantinesCorruptEntryAndShipsRest is the regression guard for the prior-art
// "batch-drain stalls on one bad event" bug: a single unparseable spool file is quarantined and the
// remaining valid events still drain.
func TestDrainQuarantinesCorruptEntryAndShipsRest(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 2)
	// Inject a corrupt entry whose name sorts in the middle.
	bad := filepath.Join(s.Dir(), "evt-aa.json")
	if err := os.WriteFile(bad, []byte("{ corrupt"), 0o644); err != nil {
		t.Fatal(err)
	}

	poster := &fakePoster{}
	d := New(s, poster, Options{Sleep: noSleep})
	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain must not fail on a corrupt entry: %v", err)
	}
	if res.Quarantined != 1 {
		t.Fatalf("expected 1 quarantined, got %d", res.Quarantined)
	}
	if res.Sent != 2 {
		t.Fatalf("the 2 valid events must still ship, got Sent=%d", res.Sent)
	}
	if res.Remaining != 0 {
		t.Fatalf("valid events must be cleared, %d remain", res.Remaining)
	}
	if n, _ := s.QuarantinedCount(); n != 1 {
		t.Fatalf("expected 1 file in quarantine, got %d", n)
	}
}

// TestDrainRetriesAfter429ThenSucceeds: a 429 then a 202 ⇒ the batch is retried with backoff and
// ultimately shipped; nothing is dropped.
func TestDrainRetriesAfter429ThenSucceeds(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 3)
	poster := &fakePoster{outcomes: []apiclient.BatchOutcome{
		apiclient.BatchThrottled, // first attempt: 429
		apiclient.BatchAccepted,  // retry: 202
	}}
	d := New(s, poster, Options{Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if res.Sent != 3 || res.Throttled {
		t.Fatalf("expected all shipped after retry, got %+v", res)
	}
	if poster.calls != 2 {
		t.Fatalf("expected 2 attempts (429 then 202), got %d", poster.calls)
	}
	if entries, _ := s.List(); len(entries) != 0 {
		t.Fatalf("spool must be cleared after successful retry, %d remain", len(entries))
	}
}

// TestDrainStaysThrottledLeavesSpoolIntact: a server that stays 429 through the whole budget ⇒ the
// drain stops with ErrThrottled and NOTHING is dropped (the spool is intact for the next boundary).
func TestDrainStaysThrottledLeavesSpoolIntact(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 3)
	poster := &fakePoster{outcomes: []apiclient.BatchOutcome{apiclient.BatchThrottled}}
	d := New(s, poster, Options{MaxRetries: 2, Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if !errors.Is(err, ErrThrottled) {
		t.Fatalf("expected ErrThrottled, got %v", err)
	}
	if !res.Throttled || res.Sent != 0 {
		t.Fatalf("expected throttled with nothing sent, got %+v", res)
	}
	if res.Remaining != 3 {
		t.Fatalf("all 3 events must remain spooled, got Remaining=%d", res.Remaining)
	}
	if entries, _ := s.List(); len(entries) != 3 {
		t.Fatalf("spool must be intact, %d remain", len(entries))
	}
	// initial attempt + 2 retries = 3 calls.
	if poster.calls != 3 {
		t.Fatalf("expected 3 attempts (1 + MaxRetries 2), got %d", poster.calls)
	}
}

// TestDrainTransportErrorLeavesSpoolIntact: server down ⇒ drain returns the error and keeps the
// spool for the next boundary; nothing lost.
func TestDrainTransportErrorLeavesSpoolIntact(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 4)
	poster := &fakePoster{err: errors.New("connection refused")}
	d := New(s, poster, Options{Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if err == nil {
		t.Fatal("expected a transport error")
	}
	if res.Sent != 0 || res.Remaining != 4 {
		t.Fatalf("nothing should ship and all should remain, got %+v", res)
	}
	if entries, _ := s.List(); len(entries) != 4 {
		t.Fatalf("spool must be intact after server-down, %d remain", len(entries))
	}
}

func TestEmptySpoolDrainIsNoop(t *testing.T) {
	s := newSpool(t)
	poster := &fakePoster{}
	d := New(s, poster, Options{Sleep: noSleep})
	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if res.Sent != 0 || res.Remaining != 0 || poster.calls != 0 {
		t.Fatalf("empty drain should do nothing, got %+v calls=%d", res, poster.calls)
	}
}

func TestOnSessionStartDrainsThenFetchesHandoff(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 2)
	poster := &fakePoster{}
	d := New(s, poster, Options{Sleep: noSleep})

	handoffCalled := false
	fetcher := HandoffFetcherFunc(func(_ context.Context) error {
		// The backlog drain must have run before the handoff fetch.
		if poster.calls == 0 {
			t.Error("handoff fetched before backlog drain")
		}
		handoffCalled = true
		return nil
	})

	res, drainErr, handoffErr := d.OnSessionStart(context.Background(), fetcher)
	if drainErr != nil || handoffErr != nil {
		t.Fatalf("unexpected errors: drain=%v handoff=%v", drainErr, handoffErr)
	}
	if res.Sent != 2 {
		t.Fatalf("backlog drain should ship 2, got %d", res.Sent)
	}
	if !handoffCalled {
		t.Fatal("handoff fetcher seam was not invoked")
	}
}
