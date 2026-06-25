package apiclient

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetScentReturnsMapOn200(t *testing.T) {
	var gotPath, gotMethod, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod, gotQuery = r.URL.Path, r.Method, r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"scope":{"workspace":"acme","project":"p"},
			"folders":[{"folder":"concepts","pages":12},{"folder":"decisions","pages":8}],
			"hubs":[{"path":"concepts/recall.md","title":"Recall","inbound":5}]}`)
	}))
	defer srv.Close()

	s, err := New(srv.URL).GetScent(context.Background(), "acme", "p", 8, 6)
	if err != nil {
		t.Fatalf("GetScent errored: %v", err)
	}
	if gotMethod != http.MethodGet {
		t.Fatalf("expected GET, got %s", gotMethod)
	}
	if gotPath != "/api/v1/workspaces/acme/projects/p/scent" {
		t.Fatalf("unexpected path: %s", gotPath)
	}
	if gotQuery != "folders=8&hubs=6" {
		t.Fatalf("expected folders=8&hubs=6 query, got %q", gotQuery)
	}
	if len(s.Folders) != 2 || s.Folders[0].Folder != "concepts" || s.Folders[0].Pages != 12 ||
		s.Folders[1].Folder != "decisions" || s.Folders[1].Pages != 8 {
		t.Fatalf("folders decoded wrong: %+v", s.Folders)
	}
	if len(s.Hubs) != 1 || s.Hubs[0].Path != "concepts/recall.md" ||
		s.Hubs[0].Title != "Recall" || s.Hubs[0].Inbound != 5 {
		t.Fatalf("hubs decoded wrong: %+v", s.Hubs)
	}
}

func TestGetScentErrorsOnUnexpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // /api/v1 unwired (no datasource)
	}))
	defer srv.Close()

	s, err := New(srv.URL).GetScent(context.Background(), "acme", "p", 8, 6)
	if err == nil {
		t.Fatal("expected an error for a 503")
	}
	if s != nil {
		t.Fatalf("expected nil scent on error, got %+v", s)
	}
}

func TestGetScentErrorsOnTransportFailure(t *testing.T) {
	// Nothing is listening → transport error (advisory; the caller omits the section).
	s, err := New("http://127.0.0.1:1").GetScent(context.Background(), "acme", "p", 8, 6)
	if err == nil {
		t.Fatal("expected a transport error")
	}
	if s != nil {
		t.Fatalf("expected nil scent on transport error, got %+v", s)
	}
}

func TestGetScentSendsBearerTokenAndOmitsCapsWhenZero(t *testing.T) {
	var gotAuth, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotQuery = r.URL.RawQuery
		_, _ = io.WriteString(w, `{"scope":{"workspace":"acme","project":"p"}}`)
	}))
	defer srv.Close()

	if _, err := New(srv.URL, WithToken("secret")).GetScent(
		context.Background(), "acme", "p", 0, 0); err != nil {
		t.Fatalf("GetScent errored: %v", err)
	}
	if gotAuth != "Bearer secret" {
		t.Fatalf("expected bearer auth header, got %q", gotAuth)
	}
	if gotQuery != "" {
		t.Fatalf("expected no query string when folders=hubs=0, got %q", gotQuery)
	}
}
