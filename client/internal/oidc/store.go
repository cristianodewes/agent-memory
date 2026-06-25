// Package oidc implements the native-hook side of the RFC 8628 OAuth 2.0 Device Authorization Grant
// (issue #39 PR2). `agent-memory auth login oidc-device` runs the device flow against a configurable
// IdP, and the resulting access token is persisted here and attached as the bearer on the capture /
// drain path when no explicit token is configured. The server (OidcJwtAuthenticator) is the authority
// that verifies the token's signature, issuer, audience and expiry against the IdP's JWKS — this client
// only obtains, stores and presents it.
//
// The grant is deliberately dependency-light: plain net/http form posts, no oauth2/jwt library, so the
// client stays small and the capture path keeps no heavy imports.
package oidc

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// credentialsFile is the on-disk name of the stored device-grant credentials, under the client data
// dir (the same ~/.agent-memory root the spool lives under). Written 0600 — it holds a bearer token.
const credentialsFile = "credentials.json"

// Credentials is the persisted result of a successful device grant: the access token attached to
// server requests plus the metadata needed to display the identity (`auth status`) and, later, refresh
// it. Verification is the server's job; nothing here is trusted for authorization, only for transport.
type Credentials struct {
	Issuer       string    `json:"issuer"`
	ClientID     string    `json:"client_id"`
	Subject      string    `json:"subject,omitempty"` // unverified `sub`, for display only (server verifies)
	TokenType    string    `json:"token_type,omitempty"`
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token,omitempty"`
	IDToken      string    `json:"id_token,omitempty"`
	Scope        string    `json:"scope,omitempty"`
	ObtainedAt   time.Time `json:"obtained_at"`
	ExpiresAt    time.Time `json:"expires_at,omitempty"` // zero ⇒ unknown lifetime (server still enforces expiry)
}

// Valid reports whether the credentials carry an access token that is not known to be expired. A zero
// ExpiresAt means the IdP gave no lifetime, so it is treated as still-usable — the server validates
// the real `exp` claim and rejects a stale token, which is the authoritative check.
func (c Credentials) Valid(now time.Time) bool {
	if c.AccessToken == "" {
		return false
	}
	return c.ExpiresAt.IsZero() || now.Before(c.ExpiresAt)
}

// CredentialsPath returns the absolute path of the credentials file under dataDir.
func CredentialsPath(dataDir string) string {
	return filepath.Join(dataDir, credentialsFile)
}

// Save writes creds to <dataDir>/credentials.json with 0600 permissions, creating dataDir if needed.
// The write is atomic (temp file in the same dir, fsync, rename) so a crash never leaves a partially
// written credential. os.CreateTemp makes the temp file 0600, which the rename preserves.
func Save(dataDir string, creds Credentials) error {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return fmt.Errorf("oidc: create data dir: %w", err)
	}
	data, err := json.MarshalIndent(creds, "", "  ")
	if err != nil {
		return fmt.Errorf("oidc: marshal credentials: %w", err)
	}

	tmp, err := os.CreateTemp(dataDir, credentialsFile+".*.tmp")
	if err != nil {
		return fmt.Errorf("oidc: create temp: %w", err)
	}
	tmpName := tmp.Name()
	committed := false
	defer func() {
		if !committed {
			_ = tmp.Close()
			_ = os.Remove(tmpName)
		}
	}()

	if _, err := tmp.Write(data); err != nil {
		return fmt.Errorf("oidc: write temp: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("oidc: fsync temp: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("oidc: close temp: %w", err)
	}
	if err := os.Rename(tmpName, CredentialsPath(dataDir)); err != nil {
		return fmt.Errorf("oidc: commit rename: %w", err)
	}
	committed = true
	return nil
}

// Load reads the stored credentials. The boolean is false (with a nil error) when no credential file
// exists yet — the common "not logged in" case, which callers treat as "no OIDC token", not an error.
func Load(dataDir string) (Credentials, bool, error) {
	data, err := os.ReadFile(CredentialsPath(dataDir))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return Credentials{}, false, nil
		}
		return Credentials{}, false, fmt.Errorf("oidc: read credentials: %w", err)
	}
	var creds Credentials
	if err := json.Unmarshal(data, &creds); err != nil {
		return Credentials{}, false, fmt.Errorf("oidc: parse credentials: %w", err)
	}
	return creds, true, nil
}

// Delete removes the stored credentials (used by `auth logout`). Removing an already-absent file is a
// success — logout is idempotent.
func Delete(dataDir string) error {
	err := os.Remove(CredentialsPath(dataDir))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("oidc: delete credentials: %w", err)
	}
	return nil
}

// AccessTokenStatus returns a stored, unexpired OIDC access token for dataDir and a flag telling the
// caller why a token is absent so it can guide the user:
//
//   - token != ""             → a valid credential; attach it.
//   - token == "" && !expired → no credential at all (never logged in / unreadable); stay silent.
//   - token == "" && expired  → a credential exists but is past its expiry; the caller should print a
//     clear "re-login" hint rather than silently sending no token and looping on 401s.
//
// It does a single small file read and never errors out — a broken credential simply yields no token,
// leaving the request unauthenticated rather than failing the hook.
func AccessTokenStatus(dataDir string) (token string, expired bool) {
	creds, ok, err := Load(dataDir)
	if err != nil || !ok {
		return "", false
	}
	if creds.Valid(time.Now()) {
		return creds.AccessToken, false
	}
	return "", true // a credential is present but expired — re-login needed
}

// AccessToken returns a stored, unexpired OIDC access token for dataDir, or "" when there is none
// (not logged in, unreadable, or expired). This is the capture path's fallback bearer; the env/marker
// token always takes precedence over it (see the hook command).
func AccessToken(dataDir string) string {
	token, _ := AccessTokenStatus(dataDir)
	return token
}
