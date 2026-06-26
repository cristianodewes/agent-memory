package apiclient

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestWithLoggerTracesRequestsWithoutSecrets proves the transport-level injection (issue #117: logger
// injected into internal/apiclient) records each call AND that the bearer token can never leak through
// this seam — the logger here has NO redaction, yet the token is absent because the transport logs
// only method/path/status, never headers.
func TestWithLoggerTracesRequestsWithoutSecrets(t *testing.T) {
	const secret = "secret-bearer-xyz-123"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer "+secret {
			t.Errorf("authorization header = %q, want the bearer token to be sent", got)
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()

	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	client := New(srv.URL, WithToken(secret), WithLogger(logger))

	if _, err := client.PostHookBatch(context.Background(), sampleEvents(t, 1)); err != nil {
		t.Fatalf("post: %v", err)
	}

	out := buf.String()
	if !strings.Contains(out, `"msg":"http request"`) || !strings.Contains(out, "/hook/batch") {
		t.Fatalf("expected a traced request for /hook/batch, got:\n%s", out)
	}
	if !strings.Contains(out, `"status":202`) {
		t.Fatalf("expected status 202 in the trace, got:\n%s", out)
	}
	if strings.Contains(out, secret) {
		t.Fatalf("bearer token leaked into the transport trace:\n%s", out)
	}
}

// TestWithLoggerNilIsNoop ensures WithLogger(nil) leaves the client usable (no transport wrap, no
// panic), so callers can pass a maybe-nil logger unconditionally.
func TestWithLoggerNilIsNoop(t *testing.T) {
	c := New("http://127.0.0.1:1", WithLogger(nil))
	if c.http == nil {
		t.Fatal("client http unexpectedly nil after WithLogger(nil)")
	}
}
