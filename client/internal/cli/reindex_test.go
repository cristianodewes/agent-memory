package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRootHasReindexCommand(t *testing.T) {
	root := newRootCmd()
	for _, c := range root.Commands() {
		if c.Name() == "reindex" {
			return
		}
	}
	t.Fatal("expected a 'reindex' subcommand on the root command")
}

func TestReindexFullAndIncrementalAreMutuallyExclusive(t *testing.T) {
	cmd := newReindexCmd()
	cmd.SetArgs([]string{"--full", "--incremental"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected an error when both --full and --incremental are set")
	}
}

func TestReindexSinceRequiresIncremental(t *testing.T) {
	cmd := newReindexCmd()
	cmd.SetArgs([]string{"--since", "HEAD~3"}) // default mode is full
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected an error when --since is used without --incremental")
	}
}

func TestRunReindexPostsRequestAndRendersReport(t *testing.T) {
	var gotBody reindexRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/reindex" {
			http.Error(w, "unexpected", http.StatusNotFound)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"mode":"incremental","filesScanned":3,"pagesIndexed":2,`+
			`"pagesDeleted":1,"linksWritten":4,"linksResolved":1,"skipped":[]}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := runReindex(&out, adminTarget{baseURL: srv.URL}, reindexRequest{Mode: "incremental", Since: "HEAD~2", Reembed: true})
	if err != nil {
		t.Fatalf("runReindex returned error: %v", err)
	}

	// The request was encoded as the server expects.
	if gotBody.Mode != "incremental" || gotBody.Since != "HEAD~2" || !gotBody.Reembed {
		t.Fatalf("server received wrong request: %+v", gotBody)
	}
	// The report was rendered for a human.
	s := out.String()
	if !strings.Contains(s, "reindex incremental") || !strings.Contains(s, "indexed=2") ||
		!strings.Contains(s, "deleted=1") || !strings.Contains(s, "resolved 1") {
		t.Fatalf("report not rendered as expected: %q", s)
	}
}

func TestRunReindexReportsServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, `{"status":"unavailable","reason":"reindex not configured"}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := runReindex(&out, adminTarget{baseURL: srv.URL}, reindexRequest{Mode: "full"})
	if err == nil {
		t.Fatal("expected an error for a non-200 response")
	}
	if !strings.Contains(err.Error(), "not configured") {
		t.Fatalf("error should surface the server reason, got: %v", err)
	}
}

func TestRenderReportFallsBackToRawOnUnexpectedShape(t *testing.T) {
	var out bytes.Buffer
	if err := renderReport(&out, []byte("not json at all")); err != nil {
		t.Fatalf("renderReport should not error on unexpected body: %v", err)
	}
	if !strings.Contains(out.String(), "not json at all") {
		t.Fatalf("expected raw body echo, got %q", out.String())
	}
}
