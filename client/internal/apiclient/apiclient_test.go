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
