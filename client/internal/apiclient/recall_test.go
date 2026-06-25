package apiclient

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestInjectRecallReturnsBlockOn200(t *testing.T) {
	var gotPath, gotMethod string
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.Path, r.Method
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		// The RecallInjectionController body shape: {scope, hits, text}.
		_, _ = io.WriteString(w, `{"scope":{"workspace":"acme","project":"p"},`+
			`"hits":2,"text":"## Relevant memory\n- recall fuses FTS + links with RRF"}`)
	}))
	defer srv.Close()

	block, err := New(srv.URL).InjectRecall(
		context.Background(), "acme", "p", "how does recall work?")
	if err != nil {
		t.Fatalf("InjectRecall errored: %v", err)
	}
	if gotMethod != http.MethodPost || gotPath != "/recall/inject" {
		t.Fatalf("expected POST /recall/inject, got %s %s", gotMethod, gotPath)
	}
	if gotBody["prompt"] != "how does recall work?" ||
		gotBody["workspace"] != "acme" || gotBody["project"] != "p" {
		t.Fatalf("server received wrong body: %+v", gotBody)
	}
	if block != "## Relevant memory\n- recall fuses FTS + links with RRF" {
		t.Fatalf("decoded block wrong: %q", block)
	}
}

func TestInjectRecallEmptyTextIsNoInjection(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// 200 with an empty block: nothing cleared the relevance gate (a low-signal prompt).
		_, _ = io.WriteString(w, `{"scope":{"workspace":"acme","project":"p"},"hits":0,"text":""}`)
	}))
	defer srv.Close()

	block, err := New(srv.URL).InjectRecall(context.Background(), "acme", "p", "hi")
	if err != nil {
		t.Fatalf("empty recall should be a clean no-op, got error %v", err)
	}
	if block != "" {
		t.Fatalf("empty text must yield an empty block, got %q", block)
	}
}

func TestInjectRecallErrorsOnUnexpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // recall unwired (no datasource)
	}))
	defer srv.Close()

	block, err := New(srv.URL).InjectRecall(context.Background(), "acme", "p", "hi")
	if err == nil {
		t.Fatal("expected an error for a 503")
	}
	if block != "" {
		t.Fatalf("expected an empty block on error, got %q", block)
	}
}

func TestInjectRecallErrorsOnTransportFailure(t *testing.T) {
	// Nothing is listening here → transport error (advisory; the caller swallows it).
	block, err := New("http://127.0.0.1:1").InjectRecall(context.Background(), "acme", "p", "hi")
	if err == nil {
		t.Fatal("expected a transport error")
	}
	if block != "" {
		t.Fatalf("expected an empty block on transport error, got %q", block)
	}
}

func TestInjectRecallSendsBearerToken(t *testing.T) {
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		_, _ = io.WriteString(w, `{"text":""}`)
	}))
	defer srv.Close()

	if _, err := New(srv.URL, WithToken("secret")).InjectRecall(
		context.Background(), "acme", "p", "hi"); err != nil {
		t.Fatalf("InjectRecall errored: %v", err)
	}
	if gotAuth != "Bearer secret" {
		t.Fatalf("expected bearer auth header, got %q", gotAuth)
	}
}
