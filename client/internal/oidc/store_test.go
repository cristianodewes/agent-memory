package oidc

import (
	"os"
	"runtime"
	"testing"
	"time"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	want := Credentials{
		Issuer:       "https://idp.example.com",
		ClientID:     "device-client",
		Subject:      "alice@oidc.example",
		TokenType:    "Bearer",
		AccessToken:  "the-access-token",
		RefreshToken: "the-refresh-token",
		Scope:        "openid profile",
		ObtainedAt:   time.Now().UTC().Truncate(time.Second),
		ExpiresAt:    time.Now().UTC().Add(time.Hour).Truncate(time.Second),
	}
	if err := Save(dir, want); err != nil {
		t.Fatalf("Save errored: %v", err)
	}

	got, ok, err := Load(dir)
	if err != nil || !ok {
		t.Fatalf("Load failed: ok=%v err=%v", ok, err)
	}
	if got.AccessToken != want.AccessToken || got.Issuer != want.Issuer ||
		got.Subject != want.Subject || got.RefreshToken != want.RefreshToken {
		t.Fatalf("round-trip mismatch:\n got %+v\nwant %+v", got, want)
	}
	if !got.ExpiresAt.Equal(want.ExpiresAt) {
		t.Fatalf("ExpiresAt mismatch: got %v want %v", got.ExpiresAt, want.ExpiresAt)
	}
}

func TestSaveUsesOwnerOnlyPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX file-mode bits are not enforced on Windows")
	}
	dir := t.TempDir()
	if err := Save(dir, Credentials{AccessToken: "x"}); err != nil {
		t.Fatalf("Save errored: %v", err)
	}
	info, err := os.Stat(CredentialsPath(dir))
	if err != nil {
		t.Fatalf("stat errored: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("credential file mode = %o, want 600 (it holds a bearer token)", perm)
	}
}

func TestLoadMissingFileIsNotAnError(t *testing.T) {
	creds, ok, err := Load(t.TempDir())
	if err != nil {
		t.Fatalf("expected no error for a missing credential, got %v", err)
	}
	if ok {
		t.Fatal("expected ok=false when no credential exists")
	}
	if creds.AccessToken != "" {
		t.Fatal("expected zero credentials when none exist")
	}
}

func TestDeleteIsIdempotent(t *testing.T) {
	dir := t.TempDir()
	// Deleting when nothing is stored is a success.
	if err := Delete(dir); err != nil {
		t.Fatalf("Delete on empty dir errored: %v", err)
	}
	if err := Save(dir, Credentials{AccessToken: "x"}); err != nil {
		t.Fatalf("Save errored: %v", err)
	}
	if err := Delete(dir); err != nil {
		t.Fatalf("Delete errored: %v", err)
	}
	if _, ok, _ := Load(dir); ok {
		t.Fatal("credential should be gone after Delete")
	}
}

func TestCredentialsValid(t *testing.T) {
	now := time.Now()
	cases := []struct {
		name  string
		creds Credentials
		want  bool
	}{
		{"valid with future expiry", Credentials{AccessToken: "t", ExpiresAt: now.Add(time.Hour)}, true},
		{"expired", Credentials{AccessToken: "t", ExpiresAt: now.Add(-time.Minute)}, false},
		{"no expiry is treated as usable", Credentials{AccessToken: "t"}, true},
		{"empty token is never valid", Credentials{ExpiresAt: now.Add(time.Hour)}, false},
	}
	for _, tc := range cases {
		if got := tc.creds.Valid(now); got != tc.want {
			t.Errorf("%s: Valid()=%v want %v", tc.name, got, tc.want)
		}
	}
}

func TestAccessTokenFallback(t *testing.T) {
	dir := t.TempDir()
	// No credential yet → empty.
	if got := AccessToken(dir); got != "" {
		t.Fatalf("expected empty token when not logged in, got %q", got)
	}
	// A valid credential → its access token.
	_ = Save(dir, Credentials{AccessToken: "live-token", ExpiresAt: time.Now().Add(time.Hour)})
	if got := AccessToken(dir); got != "live-token" {
		t.Fatalf("expected the stored token, got %q", got)
	}
	// An expired credential → empty (the hook stays unauthenticated rather than sending a dead token).
	_ = Save(dir, Credentials{AccessToken: "dead-token", ExpiresAt: time.Now().Add(-time.Hour)})
	if got := AccessToken(dir); got != "" {
		t.Fatalf("expected empty token for an expired credential, got %q", got)
	}
}

func TestAccessTokenStatusDistinguishesExpiredFromAbsent(t *testing.T) {
	dir := t.TempDir()
	// No credential at all → not expired (stay silent, the user simply isn't logged in).
	if tok, expired := AccessTokenStatus(dir); tok != "" || expired {
		t.Fatalf("no credential: got (%q, %v), want (\"\", false)", tok, expired)
	}
	// A valid credential → the token, not expired.
	_ = Save(dir, Credentials{AccessToken: "live", ExpiresAt: time.Now().Add(time.Hour)})
	if tok, expired := AccessTokenStatus(dir); tok != "live" || expired {
		t.Fatalf("valid credential: got (%q, %v), want (\"live\", false)", tok, expired)
	}
	// An expired credential → no token but expired=true, so the caller can prompt a re-login.
	_ = Save(dir, Credentials{AccessToken: "dead", ExpiresAt: time.Now().Add(-time.Hour)})
	if tok, expired := AccessTokenStatus(dir); tok != "" || !expired {
		t.Fatalf("expired credential: got (%q, %v), want (\"\", true)", tok, expired)
	}
}
