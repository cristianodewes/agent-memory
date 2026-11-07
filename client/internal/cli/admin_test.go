package cli

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/oidc"
)

// recordedRequest is what a stand-in server saw of one admin request.
type recordedRequest struct {
	method, path, auth string
}

// adminRecorder is a stand-in agent-memory server that answers every request with an empty JSON
// object and records the method, path and Authorization header it received.
type adminRecorder struct {
	srv  *httptest.Server
	mu   sync.Mutex
	reqs []recordedRequest
}

func newAdminRecorder(t *testing.T) *adminRecorder {
	t.Helper()
	rec := &adminRecorder{}
	rec.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.mu.Lock()
		rec.reqs = append(rec.reqs, recordedRequest{r.Method, r.URL.Path, r.Header.Get("Authorization")})
		rec.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{}`)
	}))
	t.Cleanup(rec.srv.Close)
	return rec
}

func (r *adminRecorder) requests() []recordedRequest {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]recordedRequest(nil), r.reqs...)
}

// isolateAdminEnv clears every server/token source an admin command could read (the environment, a
// .agent-memory.toml marker above the working directory, an OIDC login in the data dir) and returns
// the fresh working directory, so each test configures exactly the sources it is about.
func isolateAdminEnv(t *testing.T) string {
	t.Helper()
	t.Setenv("AGENT_MEMORY_SERVER_URL", "")
	t.Setenv("AGENT_MEMORY_SERVER", "")
	t.Setenv("AGENT_MEMORY_TOKEN", "")
	t.Setenv("AGENT_MEMORY_DATA_DIR", t.TempDir())
	dir := t.TempDir()
	prev, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(prev) })
	return dir
}

// writeMarker writes a .agent-memory.toml pointing at serverURL with token into dir.
func writeMarker(t *testing.T, dir, serverURL, token string) {
	t.Helper()
	body := "workspace = \"w\"\nproject = \"p\"\nserver_url = \"" + serverURL + "\"\ntoken = \"" + token + "\"\n"
	if err := os.WriteFile(filepath.Join(dir, ".agent-memory.toml"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

// runAdmin executes the root command with args, as a user would from the shell.
func runAdmin(t *testing.T, args ...string) error {
	t.Helper()
	root := newRootCmd()
	root.SetArgs(args)
	root.SetOut(&bytes.Buffer{})
	root.SetErr(io.Discard)
	return root.Execute()
}

// assertOnlyRequest fails unless rec received exactly one request, with the given method, path and
// Authorization header.
func assertOnlyRequest(t *testing.T, rec *adminRecorder, want recordedRequest) {
	t.Helper()
	got := rec.requests()
	if len(got) != 1 || got[0] != want {
		t.Fatalf("server received %+v, want exactly [%+v]", got, want)
	}
}

// The admin commands must reach the server the rest of the client uses (AGENT_MEMORY_SERVER_URL) and
// authenticate with AGENT_MEMORY_TOKEN. Before this, they read a different variable and fell back to
// http://127.0.0.1:8080 without a token, so on a machine where another program owns that port a
// purge-project answered "404 page not found" and never reached agent-memory. Each command must also
// honor its own --server and --token flags over that configuration.
func TestAdminCommandsSendToTheConfiguredServerWithTheToken(t *testing.T) {
	cases := []struct {
		args         []string
		method, path string
	}{
		{[]string{"purge-project", "--workspace", "w", "--project", "p", "--yes"}, http.MethodPost, "/projects/purge"},
		{[]string{"reset", "--yes"}, http.MethodPost, "/reset"},
		{[]string{"rename-project", "--workspace", "w", "--project", "p", "--to", "q"}, http.MethodPost, "/projects/rename"},
		{[]string{"move-project", "--workspace", "w", "--project", "p", "--to-workspace", "v"}, http.MethodPost, "/projects/move"},
		{[]string{"reindex"}, http.MethodPost, "/reindex"},
		{[]string{"checkpoints"}, http.MethodGet, "/checkpoints"},
		{[]string{"restore-page", "--workspace", "w", "--project", "p", "--path", "a.md", "--from", "HEAD"}, http.MethodPost, "/restore-page"},
		{[]string{"backup", "--out", "/srv/b.tar.gz"}, http.MethodPost, "/backup"},
		{[]string{"restore", "--in", "/srv/b.tar.gz", "--yes"}, http.MethodPost, "/restore"},
		{[]string{"bootstrap", "--workspace", "w", "--project", "p", "--repo", "/srv/repo"}, http.MethodPost, "/bootstrap"},
		{[]string{"user", "add", "--username", "u"}, http.MethodPost, "/users/add"},
		{[]string{"user", "list"}, http.MethodGet, "/users/list"},
		{[]string{"user", "expire", "--username", "u"}, http.MethodPost, "/users/expire"},
		{[]string{"user", "revive", "--username", "u"}, http.MethodPost, "/users/revive"},
		{[]string{"user", "rotate-token", "--username", "u"}, http.MethodPost, "/users/rotate-token"},
	}
	for _, tc := range cases {
		name := strings.Join(tc.args, " ")
		t.Run(name+" (environment)", func(t *testing.T) {
			isolateAdminEnv(t)
			rec := newAdminRecorder(t)
			t.Setenv("AGENT_MEMORY_SERVER_URL", rec.srv.URL)
			t.Setenv("AGENT_MEMORY_TOKEN", "env-token")

			if err := runAdmin(t, tc.args...); err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			assertOnlyRequest(t, rec, recordedRequest{tc.method, tc.path, "Bearer env-token"})
		})
		t.Run(name+" (flags)", func(t *testing.T) {
			isolateAdminEnv(t)
			fromFlag, fromEnv := newAdminRecorder(t), newAdminRecorder(t)
			t.Setenv("AGENT_MEMORY_SERVER_URL", fromEnv.srv.URL)
			t.Setenv("AGENT_MEMORY_TOKEN", "env-token")

			args := append(append([]string{}, tc.args...), "--server", fromFlag.srv.URL, "--token", "flag-token")
			if err := runAdmin(t, args...); err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			assertOnlyRequest(t, fromFlag, recordedRequest{tc.method, tc.path, "Bearer flag-token"})
			if got := fromEnv.requests(); len(got) != 0 {
				t.Fatalf("the environment's server should not be called, got %+v", got)
			}
		})
	}
}

// A .agent-memory.toml marker that names a server and token wins over the environment, exactly as
// it does for the hooks and the drain.
func TestAdminCommandMarkerWinsOverEnvironment(t *testing.T) {
	dir := isolateAdminEnv(t)
	fromMarker, fromEnv := newAdminRecorder(t), newAdminRecorder(t)
	writeMarker(t, dir, fromMarker.srv.URL, "marker-token")
	t.Setenv("AGENT_MEMORY_SERVER_URL", fromEnv.srv.URL)
	t.Setenv("AGENT_MEMORY_TOKEN", "env-token")

	if err := runAdmin(t, "checkpoints"); err != nil {
		t.Fatal(err)
	}
	assertOnlyRequest(t, fromMarker, recordedRequest{http.MethodGet, "/checkpoints", "Bearer marker-token"})
	if got := fromEnv.requests(); len(got) != 0 {
		t.Fatalf("the environment's server should not be called, got %+v", got)
	}
}

// --server and --token win over the marker and the environment, and a trailing slash on --server
// does not end up doubled in the request path.
func TestAdminCommandFlagsWinOverMarkerAndEnvironment(t *testing.T) {
	dir := isolateAdminEnv(t)
	fromFlag, fromMarker, fromEnv := newAdminRecorder(t), newAdminRecorder(t), newAdminRecorder(t)
	writeMarker(t, dir, fromMarker.srv.URL, "marker-token")
	t.Setenv("AGENT_MEMORY_SERVER_URL", fromEnv.srv.URL)
	t.Setenv("AGENT_MEMORY_TOKEN", "env-token")

	if err := runAdmin(t, "reindex", "--server", fromFlag.srv.URL+"/", "--token", "flag-token"); err != nil {
		t.Fatal(err)
	}
	assertOnlyRequest(t, fromFlag, recordedRequest{http.MethodPost, "/reindex", "Bearer flag-token"})
	if n := len(fromMarker.requests()) + len(fromEnv.requests()); n != 0 {
		t.Fatalf("only the --server target should be called, the others got %d request(s)", n)
	}
}

// With nothing configured, an admin command targets a server on the default loopback bind, with no
// token (a local server without auth).
func TestAdminTargetDefaultsToLoopbackServerWithoutToken(t *testing.T) {
	isolateAdminEnv(t)
	got := resolveAdminTarget("", "")
	if want := (adminTarget{baseURL: "http://127.0.0.1:8080"}); got != want {
		t.Fatalf("resolveAdminTarget() = %+v, want %+v", got, want)
	}
}

// The old AGENT_MEMORY_SERVER name keeps working when AGENT_MEMORY_SERVER_URL is not set.
func TestAdminCommandFallsBackToLegacyServerVariable(t *testing.T) {
	isolateAdminEnv(t)
	rec := newAdminRecorder(t)
	t.Setenv("AGENT_MEMORY_SERVER", rec.srv.URL)
	t.Setenv("AGENT_MEMORY_TOKEN", "env-token")

	if err := runAdmin(t, "purge-project", "--workspace", "w", "--project", "p", "--yes"); err != nil {
		t.Fatal(err)
	}
	assertOnlyRequest(t, rec, recordedRequest{http.MethodPost, "/projects/purge", "Bearer env-token"})
}

// When both names are set, AGENT_MEMORY_SERVER_URL (the one the hooks use) wins over the old one.
func TestAdminCommandServerURLWinsOverLegacyServerVariable(t *testing.T) {
	isolateAdminEnv(t)
	current, legacy := newAdminRecorder(t), newAdminRecorder(t)
	t.Setenv("AGENT_MEMORY_SERVER_URL", current.srv.URL)
	t.Setenv("AGENT_MEMORY_SERVER", legacy.srv.URL)

	if err := runAdmin(t, "purge-project", "--workspace", "w", "--project", "p", "--yes"); err != nil {
		t.Fatal(err)
	}
	assertOnlyRequest(t, current, recordedRequest{http.MethodPost, "/projects/purge", ""})
	if got := legacy.requests(); len(got) != 0 {
		t.Fatalf("the legacy variable's server should not be called, got %+v", got)
	}
}

// With no token configured, the OIDC device login is the fallback (as for the hooks); a configured
// token always wins over it.
func TestAdminCommandTokenFallsBackToOIDCLogin(t *testing.T) {
	cases := []struct {
		name, envToken, wantAuth string
	}{
		{"no configured token uses the OIDC login", "", "Bearer oidc-token"},
		{"a configured token wins over the OIDC login", "env-token", "Bearer env-token"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			isolateAdminEnv(t)
			rec := newAdminRecorder(t)
			t.Setenv("AGENT_MEMORY_SERVER_URL", rec.srv.URL)
			t.Setenv("AGENT_MEMORY_TOKEN", tc.envToken)
			now := time.Now()
			if err := oidc.Save(os.Getenv("AGENT_MEMORY_DATA_DIR"), oidc.Credentials{
				Issuer: "https://idp.example", ClientID: "agent-memory", AccessToken: "oidc-token",
				ObtainedAt: now, ExpiresAt: now.Add(time.Hour),
			}); err != nil {
				t.Fatal(err)
			}

			if err := runAdmin(t, "reset", "--yes"); err != nil {
				t.Fatal(err)
			}
			assertOnlyRequest(t, rec, recordedRequest{http.MethodPost, "/reset", tc.wantAuth})
		})
	}
}
