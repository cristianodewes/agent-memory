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

func TestRootHasLifecycleCommands(t *testing.T) {
	want := map[string]bool{
		"rename-project": false, "move-project": false, "purge-project": false, "reset": false,
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

func TestRenameProjectRequiresFlags(t *testing.T) {
	cmd := newRenameProjectCmd()
	cmd.SetArgs([]string{"--workspace", "w", "--project", "p"}) // missing --to
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--to") {
		t.Fatalf("expected a missing --to error, got %v", err)
	}
}

func TestPurgeRequiresYes(t *testing.T) {
	cmd := newPurgeProjectCmd()
	cmd.SetArgs([]string{"--workspace", "w", "--project", "p"}) // no --yes
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--yes") {
		t.Fatalf("expected a confirmation (--yes) error, got %v", err)
	}
}

func TestResetRequiresYes(t *testing.T) {
	cmd := newResetCmd()
	cmd.SetArgs([]string{}) // no --yes
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--yes") {
		t.Fatalf("expected a confirmation (--yes) error, got %v", err)
	}
}

func TestRenameProjectPostsExpectedBody(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/projects/rename" {
			http.Error(w, "wrong path", http.StatusNotFound)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &got)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"op":"rename","pagesAffected":2}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := postLifecycle(&out, srv.URL, "/projects/rename",
		map[string]any{"workspace": "w", "project": "old", "newProject": "new"})
	if err != nil {
		t.Fatalf("postLifecycle errored: %v", err)
	}
	if got["workspace"] != "w" || got["project"] != "old" || got["newProject"] != "new" {
		t.Fatalf("server received wrong body: %+v", got)
	}
	if !strings.Contains(out.String(), "\"op\": \"rename\"") {
		t.Fatalf("response not pretty-printed: %q", out.String())
	}
}

func TestPostLifecycleSurfacesConflictRefusal(t *testing.T) {
	// A 409 (e.g. reset refused because a live process holds the data dir) must surface as an error
	// carrying the server's reason, not be swallowed.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = io.WriteString(w,
			`{"performed":false,"reason":"refused: a live agent-memory process (pid 4242) ..."}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := postLifecycle(&out, srv.URL, "/reset", map[string]any{"force": false})
	if err == nil {
		t.Fatal("expected an error for a 409 refusal")
	}
	if !strings.Contains(err.Error(), "live agent-memory process") {
		t.Fatalf("error should surface the refusal reason, got: %v", err)
	}
}

func TestResolveServerURLForLifecycleHonorsFlag(t *testing.T) {
	t.Setenv("AGENT_MEMORY_SERVER", "")
	if got := resolveServerURL("http://host:9/"); got != "http://host:9" {
		t.Fatalf("expected trimmed flag URL, got %q", got)
	}
}
