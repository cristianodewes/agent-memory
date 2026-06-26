// Package openaioauth implements the client side of the OpenAI ChatGPT/Codex OAuth login
// (issue #113): `agent-memory auth login openai-oauth` runs OpenAI's device-authorization +
// authorization-code flow and writes the resulting token into the shared auth token file. The
// agent-memory SERVER reads and refreshes that file (DD-001 — the server holds long-lived state and
// is the only component that calls the LLM), so the client only mints the initial credential.
//
// The token file format matches the ai-memory reference and the server's reader: a single JSON object
// keyed by provider, with the `openai` entry holding the OAuth token.
package openaioauth

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// tokenFileName is the on-disk name of the shared provider token file under the data dir. Written
// 0600 — it holds a bearer credential.
const tokenFileName = "auth.json"

// entryKey is the provider key under which the OpenAI OAuth token lives in the shared file.
const entryKey = "openai"

// entryType marks an OAuth entry (vs, e.g., a static-key entry under the same provider).
const entryType = "oauth"

// Token is the OpenAI OAuth credential. Expires is the absolute access-token expiry in epoch
// milliseconds (the server refreshes a margin before it); AccountID is the optional ChatGPT
// account/workspace id the server sends on the `chatgpt-account-id` header.
type Token struct {
	Access    string
	Refresh   string
	Expires   int64
	AccountID string
}

// entry is the on-disk JSON shape of the `openai` token-file entry.
type entry struct {
	Type      string `json:"type"`
	Access    string `json:"access"`
	Refresh   string `json:"refresh"`
	Expires   int64  `json:"expires"`
	AccountID string `json:"accountId,omitempty"`
}

// TokenPath returns the absolute path of the shared token file under dataDir.
func TokenPath(dataDir string) string {
	return filepath.Join(dataDir, tokenFileName)
}

// Save writes tok as the `openai` entry in <dataDir>/auth.json, preserving any other providers'
// entries. The write is atomic (temp file in the same dir, fsync, rename) and 0600.
func Save(dataDir string, tok Token) error {
	root, err := readRoot(dataDir)
	if err != nil {
		return err
	}
	e := entry{Type: entryType, Access: tok.Access, Refresh: tok.Refresh, Expires: tok.Expires, AccountID: tok.AccountID}
	raw, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("openai-oauth: marshal entry: %w", err)
	}
	root[entryKey] = raw
	return writeRoot(dataDir, root)
}

// Load reads the stored OpenAI OAuth token. The boolean is false (with a nil error) when the file or
// the `openai` oauth entry is absent — the common "not logged in" case.
func Load(dataDir string) (Token, bool, error) {
	root, err := readRoot(dataDir)
	if err != nil {
		return Token{}, false, err
	}
	raw, ok := root[entryKey]
	if !ok {
		return Token{}, false, nil
	}
	var e entry
	if err := json.Unmarshal(raw, &e); err != nil {
		return Token{}, false, fmt.Errorf("openai-oauth: parse entry: %w", err)
	}
	if e.Type != entryType || e.Access == "" {
		return Token{}, false, nil
	}
	return Token{Access: e.Access, Refresh: e.Refresh, Expires: e.Expires, AccountID: e.AccountID}, true, nil
}

// Delete removes the `openai` entry (used by `auth logout openai-oauth`), preserving other entries.
// Removing an absent entry is a success — logout is idempotent.
func Delete(dataDir string) error {
	root, err := readRoot(dataDir)
	if err != nil {
		return err
	}
	if _, ok := root[entryKey]; !ok {
		return nil
	}
	delete(root, entryKey)
	if len(root) == 0 {
		err := os.Remove(TokenPath(dataDir))
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("openai-oauth: remove token file: %w", err)
		}
		return nil
	}
	return writeRoot(dataDir, root)
}

// readRoot loads the token file as a map of provider -> raw JSON entry, preserving entries this
// package does not understand. A missing file is an empty map (not an error).
func readRoot(dataDir string) (map[string]json.RawMessage, error) {
	data, err := os.ReadFile(TokenPath(dataDir))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return map[string]json.RawMessage{}, nil
		}
		return nil, fmt.Errorf("openai-oauth: read token file: %w", err)
	}
	root := map[string]json.RawMessage{}
	if len(data) > 0 {
		if err := json.Unmarshal(data, &root); err != nil {
			return nil, fmt.Errorf("openai-oauth: parse token file: %w", err)
		}
	}
	return root, nil
}

// writeRoot atomically writes root to <dataDir>/auth.json with 0600 permissions.
func writeRoot(dataDir string, root map[string]json.RawMessage) error {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return fmt.Errorf("openai-oauth: create data dir: %w", err)
	}
	data, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return fmt.Errorf("openai-oauth: marshal token file: %w", err)
	}

	tmp, err := os.CreateTemp(dataDir, tokenFileName+".*.tmp")
	if err != nil {
		return fmt.Errorf("openai-oauth: create temp: %w", err)
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
		return fmt.Errorf("openai-oauth: write temp: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("openai-oauth: fsync temp: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("openai-oauth: close temp: %w", err)
	}
	if err := os.Rename(tmpName, TokenPath(dataDir)); err != nil {
		return fmt.Errorf("openai-oauth: commit rename: %w", err)
	}
	committed = true
	return nil
}
