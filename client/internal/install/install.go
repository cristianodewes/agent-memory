// Package install implements the idempotent agent-memory installers (issue #40): wiring the native
// lifecycle hooks, the MCP server, and the self-routing instructions snippet into a real agent (Claude
// Code) — and unwiring them again. Each operation edits a config file in place, preserving anything it
// does not own, and is safe to run repeatedly: a second run reports Unchanged.
//
// The three surfaces, at project scope, are:
//   - hooks        → <root>/.claude/settings.json  (the `hooks` map)
//   - MCP server   → <root>/.mcp.json              (the `mcpServers` map)
//   - instructions → <root>/CLAUDE.md (or AGENTS.md) — the self-routing block between stable markers
//
// All edits go through atomicWrite (temp file + rename) so a crash never leaves a half-written config.
package install

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/cristianodewes/agent-memory/client/internal/identity"
)

// Change describes what an installer op did to a target, for human-readable output and idempotency
// assertions (a re-run yields Unchanged / Absent).
type Change string

const (
	// Created means the managed content was added to a file that lacked it (file possibly created).
	Created Change = "created"
	// Updated means existing managed content was replaced with new content.
	Updated Change = "updated"
	// Unchanged means the target already held exactly the desired content.
	Unchanged Change = "unchanged"
	// Removed means managed content was taken out of the file.
	Removed Change = "removed"
	// Absent means there was nothing to remove (uninstall on a clean target).
	Absent Change = "absent"
)

// ProjectRoot resolves the directory an installer writes project-scoped config into: the main git
// repository root for cwd, or — when cwd is not in a repo — cwd itself. This matches where Claude Code
// looks for .claude/settings.json and .mcp.json.
func ProjectRoot(cwd string) string {
	res := identity.Resolve(cwd)
	if res.RepoRoot != "" {
		return res.RepoRoot
	}
	if abs, err := filepath.Abs(cwd); err == nil {
		return abs
	}
	return cwd
}

// SettingsPath is <root>/.claude/settings.json — Claude Code's project hook settings.
func SettingsPath(root string) string {
	return filepath.Join(root, ".claude", "settings.json")
}

// McpPath is <root>/.mcp.json — Claude Code's project-scoped MCP server registry.
func McpPath(root string) string {
	return filepath.Join(root, ".mcp.json")
}

// readFileOrEmpty returns the file's bytes, or (nil, false, nil) when it does not exist. A missing file
// is not an error for an installer — it just means "nothing managed yet".
func readFileOrEmpty(path string) ([]byte, bool, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, fmt.Errorf("install: read %s: %w", path, err)
	}
	return data, true, nil
}

// atomicWrite writes data to path via a temp file in the same directory plus an atomic rename, creating
// parent directories as needed. A crash mid-write leaves either the old file or the new one, never a
// truncated mix.
func atomicWrite(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("install: mkdir %s: %w", dir, err)
	}
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".*.tmp")
	if err != nil {
		return fmt.Errorf("install: create temp in %s: %w", dir, err)
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
		return fmt.Errorf("install: write temp: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("install: fsync temp: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("install: close temp: %w", err)
	}
	if err := os.Chmod(tmpName, perm); err != nil {
		return fmt.Errorf("install: chmod temp: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("install: commit rename: %w", err)
	}
	committed = true
	return nil
}
