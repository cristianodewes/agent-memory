package apiclient

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetBriefingReturnsSnapshotOn200(t *testing.T) {
	var gotPath, gotMethod, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod, gotQuery = r.URL.Path, r.Method, r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"scope":{"workspace":"acme","project":"p"},
			"pages":12,"observations":40,"sessions":3,"links":7,"dependents":2,
			"observationsLast7Days":5,"observationsLast30Days":18,
			"rules":["_rules/style.md"],"slots":["_slots/identity.md"],
			"recent":[{"path":"concepts/recall.md","title":"Recall","layer":"active",
				"latest":true,"accessCount":3,"createdAt":"2026-06-01T00:00:00Z",
				"updatedAt":"2026-06-25T12:00:00Z"}]}`)
	}))
	defer srv.Close()

	b, err := New(srv.URL).GetBriefing(context.Background(), "acme", "p", 8)
	if err != nil {
		t.Fatalf("GetBriefing errored: %v", err)
	}
	if gotMethod != http.MethodGet {
		t.Fatalf("expected GET, got %s", gotMethod)
	}
	if gotPath != "/api/v1/workspaces/acme/projects/p/briefing" {
		t.Fatalf("unexpected path: %s", gotPath)
	}
	if gotQuery != "limit=8" {
		t.Fatalf("expected limit=8 query, got %q", gotQuery)
	}
	if b.Pages != 12 || b.Observations != 40 || b.Sessions != 3 || b.Links != 7 ||
		b.ObservationsLast7Days != 5 || b.ObservationsLast30Days != 18 {
		t.Fatalf("counts decoded wrong: %+v", b)
	}
	if len(b.Rules) != 1 || b.Rules[0] != "_rules/style.md" ||
		len(b.Slots) != 1 || b.Slots[0] != "_slots/identity.md" {
		t.Fatalf("rules/slots wrong: %+v / %+v", b.Rules, b.Slots)
	}
	if len(b.Recent) != 1 || b.Recent[0].Path != "concepts/recall.md" ||
		b.Recent[0].Title != "Recall" {
		t.Fatalf("recent decoded wrong: %+v", b.Recent)
	}
}

func TestGetBriefingErrorsOnUnexpectedStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // /api/v1 unwired (no datasource)
	}))
	defer srv.Close()

	b, err := New(srv.URL).GetBriefing(context.Background(), "acme", "p", 8)
	if err == nil {
		t.Fatal("expected an error for a 503")
	}
	if b != nil {
		t.Fatalf("expected nil briefing on error, got %+v", b)
	}
}

func TestGetBriefingErrorsOnTransportFailure(t *testing.T) {
	// Nothing is listening → transport error (advisory; the caller omits the section).
	b, err := New("http://127.0.0.1:1").GetBriefing(context.Background(), "acme", "p", 8)
	if err == nil {
		t.Fatal("expected a transport error")
	}
	if b != nil {
		t.Fatalf("expected nil briefing on transport error, got %+v", b)
	}
}

func TestGetBriefingSendsBearerTokenAndOmitsLimitWhenZero(t *testing.T) {
	var gotAuth, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotQuery = r.URL.RawQuery
		_, _ = io.WriteString(w, `{"scope":{"workspace":"acme","project":"p"}}`)
	}))
	defer srv.Close()

	if _, err := New(srv.URL, WithToken("secret")).GetBriefing(
		context.Background(), "acme", "p", 0); err != nil {
		t.Fatalf("GetBriefing errored: %v", err)
	}
	if gotAuth != "Bearer secret" {
		t.Fatalf("expected bearer auth header, got %q", gotAuth)
	}
	if gotQuery != "" {
		t.Fatalf("expected no query string when recentLimit=0, got %q", gotQuery)
	}
}
