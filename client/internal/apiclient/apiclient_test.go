package apiclient

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
)

func sampleEvents(t *testing.T, n int) []hook.Payload {
	t.Helper()
	ws, _ := core.NewWorkspaceID("acme")
	proj, _ := core.NewProjectID("agent-memory")
	var out []hook.Payload
	for i := 0; i < n; i++ {
		out = append(out, hook.NewPayload("PostToolUse", core.NewSessionID(), ws, proj,
			time.Date(2026, 6, 25, 12, 0, i, 0, time.UTC)))
	}
	return out
}

func TestPostHookBatchMapsStatuses(t *testing.T) {
	cases := []struct {
		status int
		want   BatchOutcome
	}{
		{http.StatusAccepted, BatchAccepted},
		{http.StatusOK, BatchAccepted},
		{http.StatusTooManyRequests, BatchThrottled},
		{http.StatusBadRequest, BatchRejected},
		{http.StatusServiceUnavailable, BatchRejected},
	}
	for _, c := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/hook/batch" || r.Method != http.MethodPost {
				t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			}
			w.WriteHeader(c.status)
		}))
		client := New(srv.URL)
		res, err := client.PostHookBatch(context.Background(), sampleEvents(t, 2))
		srv.Close()
		if err != nil {
			t.Fatalf("status %d: unexpected error %v", c.status, err)
		}
		if res.Outcome != c.want {
			t.Errorf("status %d: outcome = %v, want %v", c.status, res.Outcome, c.want)
		}
		if res.Status != c.status {
			t.Errorf("raw status = %d, want %d", res.Status, c.status)
		}
	}
}

func TestPostHookBatchSendsJSONArrayAndToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer secret" {
			t.Errorf("Authorization = %q, want bearer token", got)
		}
		body, _ := io.ReadAll(r.Body)
		var arr []json.RawMessage
		if err := json.Unmarshal(body, &arr); err != nil {
			t.Errorf("body must be a JSON array: %v", err)
		}
		if len(arr) != 3 {
			t.Errorf("expected 3 events, got %d", len(arr))
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()

	client := New(srv.URL, WithToken("secret"))
	if _, err := client.PostHookBatch(context.Background(), sampleEvents(t, 3)); err != nil {
		t.Fatalf("post: %v", err)
	}
}

// batchServer answers /hook/batch with the given status and body (the server's per-item report).
func batchServer(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// TestPostHookBatchReportsPartiallyThrottledItems (#158): the server answers 202 as soon as one item
// was accepted, so the per-item report is the only place a saturated queue shows up. The throttled
// indexes must be surfaced so the drain keeps those events instead of deleting the whole batch.
func TestPostHookBatchReportsPartiallyThrottledItems(t *testing.T) {
	srv := batchServer(t, http.StatusAccepted, `{"total":4,"accepted":2,"throttled":2,"invalid":0,
		"results":[{"index":0,"status":"accepted"},{"index":1,"status":"throttled"},
		{"index":2,"status":"accepted"},{"index":3,"status":"throttled"}]}`)

	res, err := New(srv.URL).PostHookBatch(context.Background(), sampleEvents(t, 4))
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if res.Outcome != BatchPartial {
		t.Fatalf("outcome = %v, want BatchPartial", res.Outcome)
	}
	if len(res.Throttled) != 2 || res.Throttled[0] != 1 || res.Throttled[1] != 3 {
		t.Fatalf("throttled indexes = %v, want [1 3]", res.Throttled)
	}
}

// TestPostHookBatchAcceptedReportWithoutThrottlingIsAccepted: accepted and invalid items are both
// done (an invalid item will never be accepted as sent), so the whole batch is acknowledged.
func TestPostHookBatchAcceptedReportWithoutThrottlingIsAccepted(t *testing.T) {
	srv := batchServer(t, http.StatusAccepted, `{"total":2,"accepted":1,"throttled":0,"invalid":1,
		"results":[{"index":0,"status":"accepted"},{"index":1,"status":"invalid"}]}`)

	res, err := New(srv.URL).PostHookBatch(context.Background(), sampleEvents(t, 2))
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if res.Outcome != BatchAccepted || len(res.Throttled) != 0 {
		t.Fatalf("got %+v, want BatchAccepted with no throttled indexes", res)
	}
}

func TestPostHookBatchEmptyIsNoop(t *testing.T) {
	// No server needed: an empty batch returns accepted without making a request.
	client := New("http://127.0.0.1:1")
	res, err := client.PostHookBatch(context.Background(), nil)
	if err != nil || res.Outcome != BatchAccepted {
		t.Fatalf("empty batch should be a no-op success, got %+v err=%v", res, err)
	}
}

func TestPostHookBatchTransportErrorIsReturned(t *testing.T) {
	// Nothing listening ⇒ a transport error so the drain keeps the spool.
	client := New("http://127.0.0.1:1", WithHTTPClient(&http.Client{Timeout: 500 * time.Millisecond}))
	_, err := client.PostHookBatch(context.Background(), sampleEvents(t, 1))
	if err == nil {
		t.Fatal("expected a transport error against a dead server")
	}
}
