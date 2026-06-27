package apiclient

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	applog "github.com/cristianodewes/agent-memory/client/internal/log"
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

// roundTripFunc adapts a function to http.RoundTripper for transport-level unit tests (no real socket).
type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// TestResponseBodyLoggingOffByDefault proves the #126 opt-in is OFF unless enabled: without
// WithResponseBodyLogging (here even with it explicitly false) the transport logs method/path/status/
// latency but NEVER the response body — the pre-#126 behaviour, unchanged.
func TestResponseBodyLoggingOffByDefault(t *testing.T) {
	const marker = "recall-body-marker-must-not-be-logged"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"text":"`+marker+`","hits":1}`)
	}))
	defer srv.Close()

	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	client := New(srv.URL, WithLogger(logger), WithResponseBodyLogging(false))

	if _, err := client.InjectRecall(context.Background(), "", "", "hi"); err != nil {
		t.Fatalf("inject: %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, `"msg":"http request"`) {
		t.Fatalf("expected the request trace, got:\n%s", out)
	}
	if strings.Contains(out, marker) || strings.Contains(out, `"body"`) {
		t.Fatalf("response body leaked into the log with body logging OFF:\n%s", out)
	}
}

// TestResponseBodyLoggingLogsAndTees proves that with the opt-in ON the full response body is logged
// AND tee'd: the typed InjectRecall still parses the body, so the tee did not consume it (#126).
func TestResponseBodyLoggingLogsAndTees(t *testing.T) {
	const want = "recall-XYZ-123"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"text":"`+want+`","hits":2}`)
	}))
	defer srv.Close()

	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	client := New(srv.URL, WithLogger(logger), WithResponseBodyLogging(true))

	got, err := client.InjectRecall(context.Background(), "", "", "hi")
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	if got != want {
		t.Fatalf("InjectRecall text = %q, want %q — the tee consumed the body", got, want)
	}
	out := buf.String()
	if !strings.Contains(out, `"body":`) || !strings.Contains(out, want) {
		t.Fatalf("expected the response body in the log, got:\n%s", out)
	}
}

// TestResponseBodyLoggingTruncatesLargeBody proves a body beyond maxLoggedBodyBytes is truncated in the
// LOG (with a note) while the FULL body is still restored to resp.Body for the typed parser (#126).
func TestResponseBodyLoggingTruncatesLargeBody(t *testing.T) {
	full := bytes.Repeat([]byte("A"), maxLoggedBodyBytes+4096)
	stub := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(bytes.NewReader(full)),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})
	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	rt := &loggingRoundTripper{base: stub, log: logger, logBodies: true}

	req, _ := http.NewRequest(http.MethodGet, "http://example.test/x", nil)
	resp, err := rt.RoundTrip(req)
	if err != nil {
		t.Fatalf("roundtrip: %v", err)
	}
	restored, _ := io.ReadAll(resp.Body)
	if len(restored) != len(full) {
		t.Fatalf("restored body = %d bytes, want the full %d (the tee must not truncate the parsed body)",
			len(restored), len(full))
	}
	if !strings.Contains(buf.String(), `"bodyTruncated":true`) {
		t.Fatal(`expected a truncation note ("bodyTruncated":true) for an oversized body`)
	}
}

// TestResponseBodyLoggingRedactsSecrets proves that even with body logging ON, a known secret embedded
// in the response body is masked by the logger's redaction boundary (the same ReplaceAttr production
// installs) — the token/Authorization guarantee holds in this mode (#126).
func TestResponseBodyLoggingRedactsSecrets(t *testing.T) {
	const secret = "sk-leak-9f8e7d6c5b4a"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"text":"ok","leaked":"token=`+secret+`"}`)
	}))
	defer srv.Close()

	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{
		Level:       slog.LevelDebug,
		ReplaceAttr: applog.ReplaceAttr,
	}))
	client := New(srv.URL, WithLogger(logger), WithResponseBodyLogging(true))

	got, err := client.InjectRecall(context.Background(), "", "", "hi")
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	if got != "ok" {
		t.Fatalf("InjectRecall text = %q, want \"ok\"", got)
	}
	out := buf.String()
	if strings.Contains(out, secret) {
		t.Fatalf("secret leaked into the body log despite redaction:\n%s", out)
	}
	if !strings.Contains(out, applog.Redacted) {
		t.Fatalf("expected the redaction marker in the logged body, got:\n%s", out)
	}
}
