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

func TestRootHasUserCommand(t *testing.T) {
	for _, c := range newRootCmd().Commands() {
		if c.Name() != "user" {
			continue
		}
		// Verify the expected subcommands hang off `user`.
		want := map[string]bool{
			"add": false, "list": false, "expire": false, "revive": false, "rotate-token": false,
		}
		for _, sub := range c.Commands() {
			if _, ok := want[sub.Name()]; ok {
				want[sub.Name()] = true
			}
		}
		for name, found := range want {
			if !found {
				t.Fatalf("expected a %q subcommand under `user`", name)
			}
		}
		return
	}
	t.Fatal("expected a `user` subcommand on the root command")
}

func TestUserAddRequiresUsername(t *testing.T) {
	cmd := newUserAddCmd()
	cmd.SetArgs([]string{}) // missing --username
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--username") {
		t.Fatalf("expected a missing --username error, got %v", err)
	}
}

func TestPostUserAttachesBearerTokenAndBody(t *testing.T) {
	var gotAuth string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"username":"alice","token":"issued-token"}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := postUser(&out, http.MethodPost, srv.URL, "/users/add", "root-token",
		map[string]any{"username": "alice"})
	if err != nil {
		t.Fatalf("postUser errored: %v", err)
	}
	if gotAuth != "Bearer root-token" {
		t.Fatalf("expected the root bearer token to be attached, got %q", gotAuth)
	}
	if gotBody["username"] != "alice" {
		t.Fatalf("server received wrong body: %+v", gotBody)
	}
	if !strings.Contains(out.String(), "\"token\": \"issued-token\"") {
		t.Fatalf("response not pretty-printed with the issued token: %q", out.String())
	}
}

func TestPostUserListSendsNoBodyButAuth(t *testing.T) {
	var gotAuth, gotMethod string
	var bodyLen int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotMethod = r.Method
		raw, _ := io.ReadAll(r.Body)
		bodyLen = len(raw)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"users":[]}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	if err := postUser(&out, http.MethodGet, srv.URL, "/users/list", "root-token", nil); err != nil {
		t.Fatalf("postUser(list) errored: %v", err)
	}
	if gotMethod != http.MethodGet {
		t.Fatalf("expected GET, got %s", gotMethod)
	}
	if gotAuth != "Bearer root-token" {
		t.Fatalf("expected bearer auth on list, got %q", gotAuth)
	}
	if bodyLen != 0 {
		t.Fatalf("expected no request body on GET, got %d bytes", bodyLen)
	}
}

func TestPostUserSurfacesForbidden(t *testing.T) {
	// A per-user (non-root) caller hitting an admin route gets 403; the CLI must surface it.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `{"status":"forbidden"}`)
	}))
	defer srv.Close()

	var out bytes.Buffer
	err := postUser(&out, http.MethodPost, srv.URL, "/users/add", "a-user-token",
		map[string]any{"username": "x"})
	if err == nil || !strings.Contains(err.Error(), "403") {
		t.Fatalf("expected a 403 error to surface, got %v", err)
	}
}

func TestResolveTokenPrefersFlagOverEnv(t *testing.T) {
	t.Setenv("AGENT_MEMORY_TOKEN", "env-token")
	if got := resolveToken("flag-token"); got != "flag-token" {
		t.Fatalf("expected the flag token to win, got %q", got)
	}
	if got := resolveToken(""); got != "env-token" {
		t.Fatalf("expected the env token as fallback, got %q", got)
	}
}
