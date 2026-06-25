package apiclient

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAcceptHandoffReturnsHandoffOn200(t *testing.T) {
	var gotPath, gotMethod string
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.Path, r.Method
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		// The flat HandoffController.toBody shape.
		_, _ = io.WriteString(w, `{
			"id":"0190b3e2-1d00-7a00-8000-000000000002",
			"workspace":"acme","project":"agent-memory",
			"fromSession":"0190b3e2-1d00-7a00-8000-000000000001",
			"status":"accepted","summary":"Left off mid-refactor.",
			"openQuestions":["Q1"],"nextSteps":["S1","S2"],
			"createdAt":"2026-06-25T12:00:00Z","acceptedAt":"2026-06-25T12:05:00Z"}`)
	}))
	defer srv.Close()

	h, err := New(srv.URL).AcceptHandoff(context.Background(), "acme", "agent-memory")
	if err != nil {
		t.Fatalf("AcceptHandoff errored: %v", err)
	}
	if gotMethod != http.MethodPost || gotPath != "/handoff/accept" {
		t.Fatalf("expected POST /handoff/accept, got %s %s", gotMethod, gotPath)
	}
	if gotBody["workspace"] != "acme" || gotBody["project"] != "agent-memory" {
		t.Fatalf("server received wrong body: %+v", gotBody)
	}
	if h == nil || h.Summary != "Left off mid-refactor." || h.Status != "accepted" {
		t.Fatalf("decoded handoff wrong: %+v", h)
	}
	if len(h.OpenQuestions) != 1 || len(h.NextSteps) != 2 {
		t.Fatalf("decoded lists wrong: %+v", h)
	}
}

func TestAcceptHandoffReturnsNilOn204(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	h, err := New(srv.URL).AcceptHandoff(context.Background(), "acme", "proj")
	if err != nil {
		t.Fatalf("204 should be a clean no-op, got error %v", err)
	}
	if h != nil {
		t.Fatalf("204 should yield a nil handoff, got %+v", h)
	}
}

func TestAcceptHandoffErrorsOnUnexpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // handoffs unwired
	}))
	defer srv.Close()

	h, err := New(srv.URL).AcceptHandoff(context.Background(), "acme", "proj")
	if err == nil {
		t.Fatal("expected an error for a 503")
	}
	if h != nil {
		t.Fatalf("expected nil handoff on error, got %+v", h)
	}
}

func TestAcceptHandoffErrorsOnTransportFailure(t *testing.T) {
	// Nothing is listening here → transport error (advisory; the caller swallows it).
	h, err := New("http://127.0.0.1:1").AcceptHandoff(context.Background(), "acme", "proj")
	if err == nil {
		t.Fatal("expected a transport error")
	}
	if h != nil {
		t.Fatalf("expected nil handoff on transport error, got %+v", h)
	}
}
