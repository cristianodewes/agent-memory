package openaioauth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// TestLoginEndToEnd drives the full device + authorization-code flow against a stub OpenAI auth host:
// request a user code, poll (pending then success), exchange the code, and harvest the refresh token +
// account id (decoded from the id_token).
func TestLoginEndToEnd(t *testing.T) {
	var polls atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("/api/accounts/deviceauth/usercode", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"device_auth_id": "dev-123",
			"user_code":      "WXYZ-1234",
			"interval":       "1",
		})
	})
	mux.HandleFunc("/api/accounts/deviceauth/token", func(w http.ResponseWriter, _ *http.Request) {
		// First poll is still pending (404), second poll returns the authorization code + verifier.
		if polls.Add(1) < 2 {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"authorization_code": "auth-code-abc",
			"code_verifier":      "verifier-xyz",
		})
	})
	mux.HandleFunc("/oauth/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.FormValue("grant_type") != "authorization_code" ||
			r.FormValue("code") != "auth-code-abc" ||
			r.FormValue("code_verifier") != "verifier-xyz" ||
			r.FormValue("client_id") != CodexClientID {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad exchange"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"access_token":  "access-tok",
			"refresh_token": "refresh-tok",
			"id_token":      testJWT(map[string]any{"chatgpt_account_id": "acct-42"}),
			"expires_in":    3600,
		})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	frozen := time.Unix(1_700_000_000, 0)
	client := NewClient(
		WithBaseURL(srv.URL),
		WithHTTPClient(srv.Client()),
		WithSleeper(func(ctx context.Context, _ time.Duration) error { return ctx.Err() }),
		WithClock(func() time.Time { return frozen }),
		WithOutput(io.Discard),
	)

	tok, err := client.Login(context.Background(), 30*time.Second)
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	if tok.Access != "access-tok" || tok.Refresh != "refresh-tok" {
		t.Fatalf("unexpected tokens: %+v", tok)
	}
	if tok.AccountID != "acct-42" {
		t.Fatalf("expected account id from id_token, got %q", tok.AccountID)
	}
	if want := frozen.UnixMilli() + 3600*1000; tok.Expires != want {
		t.Fatalf("expires = %d, want %d", tok.Expires, want)
	}
}

func TestLoginRequiresRefreshToken(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/accounts/deviceauth/usercode", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"device_auth_id": "d", "user_code": "C", "interval": "1"})
	})
	mux.HandleFunc("/api/accounts/deviceauth/token", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"authorization_code": "c", "code_verifier": "v"})
	})
	mux.HandleFunc("/oauth/token", func(w http.ResponseWriter, _ *http.Request) {
		// Access token but NO refresh token -> the login must fail clearly.
		writeJSON(w, http.StatusOK, map[string]any{"access_token": "a", "expires_in": 3600})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	client := NewClient(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()),
		WithSleeper(func(ctx context.Context, _ time.Duration) error { return ctx.Err() }),
		WithOutput(io.Discard))
	_, err := client.Login(context.Background(), 30*time.Second)
	if err == nil || !strings.Contains(err.Error(), "refresh_token") {
		t.Fatalf("expected a missing refresh_token error, got %v", err)
	}
}

func TestAccountIDFromJWTFallbacks(t *testing.T) {
	cases := []struct {
		name    string
		payload map[string]any
		want    string
	}{
		{"top-level", map[string]any{"chatgpt_account_id": "top"}, "top"},
		{"namespaced", map[string]any{
			"https://api.openai.com/auth": map[string]any{"chatgpt_account_id": "nested"}}, "nested"},
		{"organizations", map[string]any{
			"organizations": []map[string]any{{"id": "org-1"}}}, "org-1"},
		{"absent", map[string]any{"sub": "x"}, ""},
	}
	for _, tc := range cases {
		if got := accountIDFromJWT(testJWT(tc.payload)); got != tc.want {
			t.Fatalf("%s: accountIDFromJWT = %q, want %q", tc.name, got, tc.want)
		}
	}
	if got := accountIDFromJWT("not-a-jwt"); got != "" {
		t.Fatalf("non-JWT should yield no account id, got %q", got)
	}
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// testJWT builds a display-only JWT (header.payload.sig) with the given claims (no signing).
func testJWT(claims map[string]any) string {
	payload, _ := json.Marshal(claims)
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	return fmt.Sprintf("%s.%s.sig", header, base64.RawURLEncoding.EncodeToString(payload))
}
