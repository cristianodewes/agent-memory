package cli

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/oidc"
	"github.com/cristianodewes/agent-memory/client/internal/openaioauth"
	"github.com/spf13/cobra"
)

func TestRootHasAuthCommand(t *testing.T) {
	auth := findSubcommand(newRootCmd(), "auth")
	if auth == nil {
		t.Fatal("expected an `auth` subcommand on the root command")
	}
	// auth login (carrying the oidc-device grant), auth status, auth logout.
	for _, name := range []string{"login", "status", "logout"} {
		if findSubcommand(auth, name) == nil {
			t.Fatalf("expected an `auth %s` subcommand", name)
		}
	}
	login := findSubcommand(auth, "login")
	if findSubcommand(login, "oidc-device") == nil {
		t.Fatal("expected `auth login oidc-device`")
	}
	if findSubcommand(login, "openai-oauth") == nil {
		t.Fatal("expected `auth login openai-oauth`")
	}
}

// TestAuthLoginOpenAIOAuthEndToEnd runs the ChatGPT/Codex device login through the cobra command
// against a stub OpenAI auth host and proves the token file the server reads (<data-dir>/auth.json)
// was written with the obtained credential.
func TestAuthLoginOpenAIOAuthEndToEnd(t *testing.T) {
	idp := startMockOpenAIAuth(t)
	dir := t.TempDir()

	prev := newOpenAIOAuthClient
	newOpenAIOAuthClient = func(opts ...openaioauth.Option) *openaioauth.Client {
		return openaioauth.NewClient(append(opts,
			openaioauth.WithBaseURL(idp.URL),
			openaioauth.WithHTTPClient(idp.Client()),
			openaioauth.WithSleeper(func(ctx context.Context, _ time.Duration) error { return ctx.Err() }),
		)...)
	}
	t.Cleanup(func() { newOpenAIOAuthClient = prev })

	var out bytes.Buffer
	cmd := newAuthLoginOpenAIOAuthCmd()
	cmd.SetArgs([]string{"--data-dir", dir})
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err != nil {
		t.Fatalf("openai-oauth login errored: %v", err)
	}
	if !strings.Contains(out.String(), "logged in") || !strings.Contains(out.String(), "acct-7") {
		t.Fatalf("unexpected confirmation: %q", out.String())
	}

	// The token the server reads + refreshes is the one just obtained.
	tok, ok, err := openaioauth.Load(dir)
	if err != nil || !ok {
		t.Fatalf("expected a persisted token: ok=%v err=%v", ok, err)
	}
	if tok.Refresh != "refresh-tok" || tok.AccountID != "acct-7" {
		t.Fatalf("unexpected stored token: %+v", tok)
	}
}

// startMockOpenAIAuth serves OpenAI's device-authorization + token endpoints, issuing a refresh token
// and an id_token carrying a ChatGPT account id on the first poll.
func startMockOpenAIAuth(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/api/accounts/deviceauth/usercode", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"device_auth_id": "dev-1", "user_code": "ABCD-1234", "interval": "1",
		})
	})
	mux.HandleFunc("/api/accounts/deviceauth/token", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"authorization_code": "code-1", "code_verifier": "verifier-1",
		})
	})
	mux.HandleFunc("/oauth/token", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"access_token":  "access-tok",
			"refresh_token": "refresh-tok",
			"id_token":      accountJWT("acct-7"),
			"expires_in":    3600,
		})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// accountJWT builds a display-only JWT carrying a chatgpt_account_id claim (the client never verifies it).
func accountJWT(accountID string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	payload := base64.RawURLEncoding.EncodeToString(
		[]byte(fmt.Sprintf(`{"chatgpt_account_id":%q}`, accountID)))
	return header + "." + payload + ".sig"
}

// findSubcommand returns the direct child command with the given name, or nil.
func findSubcommand(parent *cobra.Command, name string) *cobra.Command {
	for _, c := range parent.Commands() {
		if c.Name() == name {
			return c
		}
	}
	return nil
}

func TestAuthLoginDeviceRequiresIssuerAndClientID(t *testing.T) {
	cmd := newAuthLoginDeviceCmd()
	cmd.SetArgs([]string{"--client-id", "c"}) // missing --issuer
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "--issuer") {
		t.Fatalf("expected a missing --issuer error, got %v", err)
	}
}

// TestAuthLoginDeviceEndToEnd runs the real device flow (oidc.Client) against a mock IdP through the
// cobra command, then proves the credential was persisted where the native hook's fallback reads it
// (oidc.AccessToken) — i.e. obtained, stored, and attachable.
func TestAuthLoginDeviceEndToEnd(t *testing.T) {
	idp := startMockIDP(t)
	dir := t.TempDir()

	// Inject the mock IdP's HTTP client + a no-op poll sleeper into the command's device client.
	prev := newOidcClient
	newOidcClient = func(opts ...oidc.Option) *oidc.Client {
		return oidc.NewClient(append(opts,
			oidc.WithHTTPClient(idp.Client()),
			oidc.WithSleeper(func(ctx context.Context, _ time.Duration) error { return ctx.Err() }),
		)...)
	}
	t.Cleanup(func() { newOidcClient = prev })

	var out bytes.Buffer
	cmd := newAuthLoginDeviceCmd()
	cmd.SetArgs([]string{
		"--issuer", idp.URL, "--client-id", "device-client", "--data-dir", dir,
	})
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err != nil {
		t.Fatalf("oidc-device login errored: %v", err)
	}
	if !strings.Contains(out.String(), "alice@oidc.example") {
		t.Fatalf("expected the logged-in subject in the confirmation, got %q", out.String())
	}

	// The token the hook will attach (config.DataDir → oidc.AccessToken) is the one just obtained.
	if tok := oidc.AccessToken(dir); tok == "" {
		t.Fatal("expected a persisted, attachable access token after login")
	}

	// `auth status` reflects the stored identity.
	var status bytes.Buffer
	scmd := newAuthStatusCmd()
	scmd.SetArgs([]string{"--data-dir", dir})
	scmd.SetOut(&status)
	if err := scmd.Execute(); err != nil {
		t.Fatalf("auth status errored: %v", err)
	}
	if !strings.Contains(status.String(), "alice@oidc.example") ||
		!strings.Contains(status.String(), "valid") {
		t.Fatalf("auth status did not reflect the stored credential: %q", status.String())
	}

	// `auth logout` removes it; the hook fallback then finds nothing.
	lcmd := newAuthLogoutCmd()
	lcmd.SetArgs([]string{"--data-dir", dir})
	lcmd.SetOut(io.Discard)
	if err := lcmd.Execute(); err != nil {
		t.Fatalf("auth logout errored: %v", err)
	}
	if tok := oidc.AccessToken(dir); tok != "" {
		t.Fatal("expected no token after logout")
	}
}

func TestAuthStatusWhenNotLoggedIn(t *testing.T) {
	var out bytes.Buffer
	cmd := newAuthStatusCmd()
	cmd.SetArgs([]string{"--data-dir", t.TempDir()})
	cmd.SetOut(&out)
	if err := cmd.Execute(); err != nil {
		t.Fatalf("auth status errored: %v", err)
	}
	if !strings.Contains(out.String(), "Not logged in") {
		t.Fatalf("expected a not-logged-in message, got %q", out.String())
	}
}

// --- mock IdP ------------------------------------------------------------------------------------

// startMockIDP serves a minimal RFC 8628 device grant that issues a token on the first token poll.
func startMockIDP(t *testing.T) *httptest.Server {
	t.Helper()
	var base string
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"issuer":                        base,
			"device_authorization_endpoint": base + "/device",
			"token_endpoint":                base + "/token",
		})
	})
	mux.HandleFunc("/device", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"device_code":      "dev-code",
			"user_code":        "WDJB-MJHT",
			"verification_uri": base + "/activate",
			"expires_in":       600,
			"interval":         1,
		})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, _ *http.Request) {
		writeJSONResp(w, http.StatusOK, map[string]any{
			"access_token": deviceTestJWT("alice@oidc.example"),
			"token_type":   "Bearer",
			"expires_in":   3600,
			"scope":        "openid",
		})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	base = srv.URL
	return srv
}

func writeJSONResp(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// deviceTestJWT builds a display-only JWT carrying a `sub` claim (the client never verifies it).
func deviceTestJWT(subject string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	payload := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"sub":%q}`, subject)))
	return header + "." + payload + "." + base64.RawURLEncoding.EncodeToString([]byte("sig"))
}
