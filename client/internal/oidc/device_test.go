package oidc

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// mockIdP is a minimal RFC 8628 device-grant IdP for tests: a discovery document, a device-auth
// endpoint, and a token endpoint whose first calls return authorization_pending / slow_down before it
// finally issues a token — exercising the full poll state machine.
type mockIdP struct {
	server   *httptest.Server
	clientID string

	mu             sync.Mutex
	tokenCalls     int
	pendingCalls   int // how many authorization_pending to return before success
	slowDownCalls  int // how many slow_down to return (after the pending ones)
	gotScope       string
	gotGrantType   string
	gotDeviceCode  string
	gotClientIDTok string
	accessToken    string
}

func newMockIdP(t *testing.T, pending, slowDown int) *mockIdP {
	t.Helper()
	idp := &mockIdP{
		clientID:      "device-client",
		pendingCalls:  pending,
		slowDownCalls: slowDown,
		accessToken:   fakeJWT("alice@oidc.example"),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"issuer":                        idp.server.URL,
			"device_authorization_endpoint": idp.server.URL + "/device",
			"token_endpoint":                idp.server.URL + "/token",
		})
	})
	mux.HandleFunc("/device", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		idp.mu.Lock()
		idp.gotScope = r.Form.Get("scope")
		idp.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]any{
			"device_code":      "dev-code-123",
			"user_code":        "WDJB-MJHT",
			"verification_uri": idp.server.URL + "/activate",
			"expires_in":       600,
			"interval":         5,
		})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		idp.mu.Lock()
		idp.tokenCalls++
		n := idp.tokenCalls
		idp.gotGrantType = r.Form.Get("grant_type")
		idp.gotDeviceCode = r.Form.Get("device_code")
		idp.gotClientIDTok = r.Form.Get("client_id")
		pending, slow := idp.pendingCalls, idp.slowDownCalls
		token := idp.accessToken
		idp.mu.Unlock()

		switch {
		case n <= pending:
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "authorization_pending"})
		case n <= pending+slow:
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "slow_down"})
		default:
			writeJSON(w, http.StatusOK, map[string]any{
				"access_token": token,
				"token_type":   "Bearer",
				"expires_in":   3600,
				"scope":        "openid",
			})
		}
	})
	idp.server = httptest.NewServer(mux)
	t.Cleanup(idp.server.Close)
	return idp
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// fakeJWT builds an UNSIGNED-for-display JWT (valid base64url segments, a `sub` claim) so the client's
// display-only subject peek works. The client never verifies it — that is the server's job.
func fakeJWT(subject string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	payload := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"sub":%q}`, subject)))
	sig := base64.RawURLEncoding.EncodeToString([]byte("not-a-real-signature"))
	return header + "." + payload + "." + sig
}

// noSleep is an injected sleeper that returns immediately (respecting cancellation) so the poll loop
// runs at full speed under test.
func noSleep(ctx context.Context, _ time.Duration) error {
	return ctx.Err()
}

func TestLoginDeviceGrantHappyPath(t *testing.T) {
	idp := newMockIdP(t, 2, 1) // 2 pending, then 1 slow_down, then success
	var prompt strings.Builder
	client := NewClient(
		WithHTTPClient(idp.server.Client()),
		WithSleeper(noSleep),
		WithOutput(&prompt),
	)

	creds, err := client.Login(context.Background(), LoginRequest{
		Issuer:   idp.server.URL,
		ClientID: "device-client",
		Scope:    "profile",
	})
	if err != nil {
		t.Fatalf("Login errored: %v", err)
	}

	if creds.AccessToken != idp.accessToken {
		t.Fatalf("unexpected access token: %q", creds.AccessToken)
	}
	if creds.Subject != "alice@oidc.example" {
		t.Fatalf("expected the subject peeked from the token, got %q", creds.Subject)
	}
	if creds.Issuer != idp.server.URL {
		t.Fatalf("issuer not recorded: %q", creds.Issuer)
	}
	if creds.ExpiresAt.IsZero() {
		t.Fatal("expected ExpiresAt to be derived from expires_in")
	}
	if !creds.Valid(time.Now()) {
		t.Fatal("freshly issued credential should be valid")
	}

	// The token endpoint was polled until success (2 pending + 1 slow_down + 1 success = 4 calls).
	if idp.tokenCalls != 4 {
		t.Fatalf("expected 4 token polls, got %d", idp.tokenCalls)
	}
	if idp.gotGrantType != deviceGrantType {
		t.Fatalf("token poll used wrong grant_type: %q", idp.gotGrantType)
	}
	if idp.gotDeviceCode != "dev-code-123" {
		t.Fatalf("token poll sent wrong device_code: %q", idp.gotDeviceCode)
	}
	// The OIDC `openid` scope must always be requested, alongside the caller's scope.
	if !strings.Contains(idp.gotScope, "openid") || !strings.Contains(idp.gotScope, "profile") {
		t.Fatalf("device request scope %q missing openid/profile", idp.gotScope)
	}
	// The user-facing prompt carried the verification URL and user code.
	if !strings.Contains(prompt.String(), "WDJB-MJHT") ||
		!strings.Contains(prompt.String(), "/activate") {
		t.Fatalf("prompt missing user code / verification URL: %q", prompt.String())
	}
}

func TestLoginUsesExplicitEndpointsWithoutDiscovery(t *testing.T) {
	idp := newMockIdP(t, 0, 0)
	// Point discovery at a dead URL to prove it is never consulted when endpoints are pinned.
	client := NewClient(WithHTTPClient(idp.server.Client()), WithSleeper(noSleep), WithOutput(io.Discard))
	creds, err := client.Login(context.Background(), LoginRequest{
		Issuer:             "http://127.0.0.1:1/should-not-be-fetched",
		ClientID:           "device-client",
		DeviceAuthEndpoint: idp.server.URL + "/device",
		TokenEndpoint:      idp.server.URL + "/token",
	})
	if err != nil {
		t.Fatalf("Login with explicit endpoints errored: %v", err)
	}
	if creds.AccessToken == "" {
		t.Fatal("expected an access token via explicit endpoints")
	}
}

func TestLoginPropagatesAuthorizationFailure(t *testing.T) {
	// A token endpoint that denies the grant must surface as an error, not hang.
	mux := http.NewServeMux()
	var base string
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"device_authorization_endpoint": base + "/device",
			"token_endpoint":                base + "/token",
		})
	})
	mux.HandleFunc("/device", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"device_code": "d", "user_code": "U-CODE", "verification_uri": base + "/a",
			"expires_in": 600, "interval": 1,
		})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": "access_denied", "error_description": "user declined",
		})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	base = srv.URL

	client := NewClient(WithHTTPClient(srv.Client()), WithSleeper(noSleep), WithOutput(io.Discard))
	_, err := client.Login(context.Background(), LoginRequest{Issuer: base, ClientID: "c"})
	if err == nil || !strings.Contains(err.Error(), "access_denied") {
		t.Fatalf("expected an access_denied error, got %v", err)
	}
}

func TestLoginRequiresIssuerAndClientID(t *testing.T) {
	client := NewClient()
	if _, err := client.Login(context.Background(), LoginRequest{ClientID: "c"}); err == nil {
		t.Fatal("expected an error when issuer is missing")
	}
	if _, err := client.Login(context.Background(), LoginRequest{Issuer: "https://idp"}); err == nil {
		t.Fatal("expected an error when client-id is missing")
	}
}

func TestWithOpenIDScope(t *testing.T) {
	cases := map[string]string{
		"":                "openid",
		"openid":          "openid",
		"profile":         "openid profile",
		"openid email":    "openid email",
		"email openid":    "email openid",
		" profile email ": "openid profile email",
	}
	for in, want := range cases {
		if got := withOpenID(in); got != want {
			t.Errorf("withOpenID(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestUnverifiedSubject(t *testing.T) {
	if got := unverifiedSubject(fakeJWT("bob@x")); got != "bob@x" {
		t.Fatalf("expected bob@x, got %q", got)
	}
	if got := unverifiedSubject("opaque-token"); got != "" {
		t.Fatalf("expected empty subject for a non-JWT, got %q", got)
	}
}
