// Package identity resolves the (workspace, project) coordinate the client attributes captured
// events to. Correct project attribution is everything in a shared memory (ARCHITECTURE §2.1,
// Survey §2.7), so resolution lives in exactly one place: every caller — the native hook (#10), the
// CLI subcommands, and handoff fetch (#23) — goes through Resolve, never re-deriving ad hoc.
//
// Resolution, in precedence order:
//
//  1. The nearest .agent-memory.toml marker file walking up from cwd. It sets explicit
//     workspace/project (and optionally a server URL / token), overriding derivation — this is what
//     lets consultancies, work/personal splits and monorepos pin identity. Nearest ancestor wins.
//  2. Otherwise, the main git repository: project = basename(repoRoot), workspace =
//     basename(parent(repoRoot)). A git worktree resolves to its MAIN repo's identity, so a feature
//     worktree shares the parent repo's memory rather than spawning a new project.
//  3. Otherwise (no marker, no git), fall back to basename(cwd) for both so capture still works
//     outside a repo.
package identity

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/BurntSushi/toml"
)

// MarkerFileName is the per-tree override file the client looks for, nearest ancestor winning.
const MarkerFileName = ".agent-memory.toml"

// Source records how an identity was resolved, for diagnostics and tests.
type Source string

const (
	// SourceMarker means a .agent-memory.toml provided workspace/project.
	SourceMarker Source = "marker"
	// SourceGitRoot means identity was derived from the main git repository root.
	SourceGitRoot Source = "git-root"
	// SourceCwd means there was no marker and no git root; identity fell back to the cwd name.
	SourceCwd Source = "cwd"
)

// Resolved is the outcome of resolution: the identity coordinates plus optional server overrides a
// marker may carry, and provenance. Workspace and Project are raw (un-normalized) strings — the
// hook payload builder normalizes them through core.NewWorkspaceID/NewProjectID, the single place
// slug rules are applied.
type Resolved struct {
	// Workspace and Project are the resolved coordinates (raw; normalized downstream).
	Workspace string
	Project   string
	// Source records how the identity was determined.
	Source Source
	// RepoRoot is the main git repository root used for derivation, or "" when none was found.
	RepoRoot string
	// MarkerPath is the absolute path of the marker that won, or "" when none applied.
	MarkerPath string
	// ServerURL and Token are optional overrides a marker supplied (empty when unset). Callers may
	// layer these over their environment-derived config.
	ServerURL string
	Token     string
}

// marker is the on-disk shape of a .agent-memory.toml file. Every field is optional; an empty file
// (or one setting only server_url) leaves derivation to fill workspace/project.
type marker struct {
	Workspace string `toml:"workspace"`
	Project   string `toml:"project"`
	ServerURL string `toml:"server_url"`
	Token     string `toml:"token"`
}

// Resolve determines the identity for cwd. It performs only filesystem reads (stat + reading any
// marker file) and never fails: malformed or unreadable markers are skipped so capture is never
// blocked by a bad override. An empty cwd resolves to the "unknown" project so an event is still
// attributable.
func Resolve(cwd string) Resolved {
	if strings.TrimSpace(cwd) == "" {
		return Resolved{Workspace: "unknown", Project: "unknown", Source: SourceCwd}
	}
	abs := absOrSelf(cwd)

	repoRoot := MainRepoRoot(abs)
	res := deriveFromGit(abs, repoRoot)

	// A marker (nearest ancestor) overrides whatever was derived. Search up to the filesystem root.
	if m, path, ok := findMarker(abs); ok {
		applyMarker(&res, m, path)
	}
	return res
}

// deriveFromGit fills Resolved from the main repo root (project = basename(root), workspace =
// basename(parent(root))), or from basename(cwd) when there is no git root.
func deriveFromGit(cwd, repoRoot string) Resolved {
	if repoRoot != "" {
		return Resolved{
			Workspace: filepath.Base(filepath.Dir(repoRoot)),
			Project:   filepath.Base(repoRoot),
			Source:    SourceGitRoot,
			RepoRoot:  repoRoot,
		}
	}
	base := filepath.Base(cwd)
	return Resolved{Workspace: base, Project: base, Source: SourceCwd}
}

// applyMarker overlays a marker's explicit fields onto res. Workspace/project from the marker (when
// both present) override derivation and flip the source to SourceMarker; server URL / token are
// carried through regardless. A marker that sets neither workspace nor project (e.g. only
// server_url) leaves the derived identity intact but still contributes its overrides.
func applyMarker(res *Resolved, m marker, path string) {
	ws := strings.TrimSpace(m.Workspace)
	proj := strings.TrimSpace(m.Project)
	res.MarkerPath = path
	if ws != "" {
		res.Workspace = ws
	}
	if proj != "" {
		res.Project = proj
	}
	if ws != "" || proj != "" {
		res.Source = SourceMarker
	}
	if s := strings.TrimSpace(m.ServerURL); s != "" {
		res.ServerURL = s
	}
	if t := strings.TrimSpace(m.Token); t != "" {
		res.Token = t
	}
}

// findMarker walks up from dir looking for a readable, parseable MarkerFileName, returning the
// nearest one (closest ancestor wins). A marker that cannot be read or parsed is skipped (not fatal)
// and the walk continues upward, so one broken file does not strand a valid one above it.
func findMarker(dir string) (marker, string, bool) {
	for {
		path := filepath.Join(dir, MarkerFileName)
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			var m marker
			if _, err := toml.DecodeFile(path, &m); err == nil {
				return m, path, true
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return marker{}, "", false
		}
		dir = parent
	}
}

// absOrSelf returns the absolute form of p, or p unchanged when it cannot be absolutized.
func absOrSelf(p string) string {
	if abs, err := filepath.Abs(p); err == nil {
		return abs
	}
	return p
}
