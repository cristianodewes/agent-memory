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

func TestRootHasTimeTravelCommands(t *testing.T) {
	want := map[string]bool{
		"checkpoints": false, "restore-page": false,
		"backup": false, "restore": false, "bootstrap": false,
	}
	for _, c := range newRootCmd().Commands() {
		if _, ok := want[c.Name()]; ok {
			want[c.Name()] = true
		}
	}
	for name, found := range want {
		if !found {
			t.Fatalf("expected a %q subcommand on the root command", name)
		}
	}
}

func TestRestorePageRequiresFlags(t *testing.T) {
	cmd := newRestorePageCmd()
	cmd.SetArgs([]string{"--workspace", "w", "--project", "p", "--path", "a.md"}) // missing --from
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--from") {
		t.Fatalf("expected a missing --from error, got %v", err)
	}
}

func TestRestoreRequiresYes(t *testing.T) {
	cmd := newRestoreCmd()
	cmd.SetArgs([]string{"--in", "/tmp/b.tar.gz"}) // no --yes
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--yes") {
		t.Fatalf("expected a confirmation (--yes) error, got %v", err)
	}
}

func TestBackupRequiresOut(t *testing.T) {
	cmd := newBackupCmd()
	cmd.SetArgs([]string{})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--out") {
		t.Fatalf("expected a missing --out error, got %v", err)
	}
}

func TestRestorePagePostsExpectedBody(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/restore-page" {
			http.Error(w, "wrong path", http.StatusNotFound)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &got)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"path":"concepts/recall.md","changed":true}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := postLifecycle(&out, srv.URL, "/restore-page", map[string]any{
		"workspace": "w", "project": "p", "path": "concepts/recall.md", "from": "HEAD~1"})
	if err != nil {
		t.Fatalf("postLifecycle errored: %v", err)
	}
	if got["workspace"] != "w" || got["path"] != "concepts/recall.md" || got["from"] != "HEAD~1" {
		t.Fatalf("server received wrong body: %+v", got)
	}
	if !strings.Contains(out.String(), "\"changed\": true") {
		t.Fatalf("response not pretty-printed: %q", out.String())
	}
}

func TestCheckpointsGetsLimitQuery(t *testing.T) {
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"count":0,"checkpoints":[]}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	if err := getJSON(&out, srv.URL+"/checkpoints?limit=5"); err != nil {
		t.Fatalf("getJSON errored: %v", err)
	}
	if gotPath != "/checkpoints" {
		t.Fatalf("expected GET /checkpoints, got %q", gotPath)
	}
	if !strings.Contains(gotQuery, "limit=5") {
		t.Fatalf("expected limit=5 query, got %q", gotQuery)
	}
	if !strings.Contains(out.String(), "\"count\": 0") {
		t.Fatalf("response not pretty-printed: %q", out.String())
	}
}

func TestGetJSONSurfacesServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, `{"status":"unavailable"}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := getJSON(&out, srv.URL+"/checkpoints?limit=20")
	if err == nil || !strings.Contains(err.Error(), "unavailable") {
		t.Fatalf("expected a surfaced 503 error, got %v", err)
	}
}
