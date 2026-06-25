// Package capturesession records, per project, the id of the capture session currently active in that
// project, so the MCP transport can tell the server WHICH session a no-scope tool call belongs to
// (issue #87, auto_scope=session_aware).
//
// The problem it solves: Claude Code does not send its session id to a remote MCP server, and the MCP
// `headersHelper` that could add one runs in an unspecified working directory with almost no
// environment (only CLAUDE_CODE_MCP_SERVER_NAME/URL). So the native hook — which DOES know the session
// id and runs in the project tree — writes that id here at SessionStart, and the `mcp-session-header`
// command (invoked by headersHelper, with the project identity baked into its arguments at install
// time) reads it back to emit the `X-Agent-Memory-Session` header.
//
// Keying: the file lives at <data_dir>/sessions/<workspace>/<project>, so two sessions of the same
// user in DIFFERENT projects write DIFFERENT files and stay isolated — the client-side counterpart of
// the server's per-session default scope. Two sessions in the SAME project share one file (last writer
// wins); that is a deliberate no-op, because the same project resolves to the same default scope
// either way (documented limitation, see DD-007 auto_scope). workspace and project are
// already-normalized slugs (core.WorkspaceID/ProjectID — single lower-case ASCII segment), hence safe
// as path components.
//
// Safety: this is best-effort transport. The server is fail-closed (session_aware never falls back to a
// global scope), so a missing or stale file degrades to a clear "no session" error on the server, never
// a cross-session leak.
package capturesession

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// HeaderName is the HTTP header that carries the capture session id to the server. It MUST match the
// server's com.agentmemory.core.CaptureSessionResolver.SESSION_HEADER — the server reads exactly this
// name in CaptureSessionHeaderFilter under auto_scope=session_aware.
const HeaderName = "X-Agent-Memory-Session"

// subdir is the location under the data dir that holds the per-project current-session files.
const subdir = "sessions"

// Dir returns the directory that holds the per-project session files (<data_dir>/sessions).
func Dir(dataDir string) string {
	return filepath.Join(dataDir, subdir)
}

// Path returns the file that records the current capture session for (workspace, project):
// <data_dir>/sessions/<workspace>/<project>. workspace and project must be normalized slugs.
func Path(dataDir, workspace, project string) string {
	return filepath.Join(Dir(dataDir), workspace, project)
}

// Write atomically records sessionID as the current capture session for (workspace, project). The
// parent directory is created 0700 and the file written 0600 (the id is a per-session capability in
// session_aware mode — keep it readable only by the owner, mirroring the OIDC credentials store). The
// write is atomic (temp file + rename) so a concurrent Read never sees a torn value.
func Write(dataDir, workspace, project, sessionID string) error {
	if strings.TrimSpace(sessionID) == "" {
		return fmt.Errorf("capturesession: empty session id")
	}
	path := Path(dataDir, workspace, project)
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("capturesession: create %s: %w", dir, err)
	}
	tmp, err := os.CreateTemp(dir, ".session-*")
	if err != nil {
		return fmt.Errorf("capturesession: temp file in %s: %w", dir, err)
	}
	tmpName := tmp.Name()
	// Best-effort cleanup if we fail before the rename; after a successful rename this is a no-op.
	defer os.Remove(tmpName)

	if _, err := tmp.WriteString(strings.TrimSpace(sessionID) + "\n"); err != nil {
		tmp.Close()
		return fmt.Errorf("capturesession: write %s: %w", tmpName, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("capturesession: close %s: %w", tmpName, err)
	}
	if err := os.Chmod(tmpName, 0o600); err != nil {
		return fmt.Errorf("capturesession: chmod %s: %w", tmpName, err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("capturesession: rename into %s: %w", path, err)
	}
	return nil
}

// Read returns the current capture session id for (workspace, project), or "" when no session has been
// recorded yet (the file is absent). A genuinely unreadable file (permissions, IO) is returned as an
// error; an absent file is the normal "no session bound" case and is NOT an error, so the caller emits
// no header and lets the fail-closed server reject a no-scope session_aware call.
func Read(dataDir, workspace, project string) (string, error) {
	data, err := os.ReadFile(Path(dataDir, workspace, project))
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", fmt.Errorf("capturesession: read: %w", err)
	}
	return strings.TrimSpace(string(data)), nil
}
