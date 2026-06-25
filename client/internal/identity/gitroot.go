package identity

import (
	"os"
	"path/filepath"
	"strings"
)

// MainRepoRoot walks up from dir to the git repository and returns the working-tree root of the
// MAIN repository, or "" if dir is not inside a repo. For an ordinary checkout this is the directory
// that contains the .git directory. For a linked worktree (where .git is a FILE pointing at
// <main>/.git/worktrees/<name>), it resolves through that to the main repo's working-tree root, so a
// worktree shares the main repo's identity (issue #32 acceptance) rather than being treated as its
// own project.
func MainRepoRoot(dir string) string {
	gitPath, container := findGitEntry(dir)
	if gitPath == "" {
		return ""
	}

	info, err := os.Stat(gitPath)
	if err != nil {
		return ""
	}
	if info.IsDir() {
		// Ordinary repo (or the main worktree itself): the container holds the .git directory.
		return container
	}

	// Linked worktree: .git is a file "gitdir: <path-to>/.git/worktrees/<name>". Resolve to the main
	// working-tree root. If anything about the file is unexpected, fall back to the container so we
	// still return a usable root rather than nothing.
	if root := mainRootFromWorktreeGitFile(gitPath); root != "" {
		return root
	}
	return container
}

// findGitEntry walks up from dir, returning the path of the first ".git" entry found and the
// directory that contains it. Returns ("", "") if none is found before the filesystem root.
func findGitEntry(dir string) (gitPath, container string) {
	for {
		candidate := filepath.Join(dir, ".git")
		if _, err := os.Lstat(candidate); err == nil {
			return candidate, dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", ""
		}
		dir = parent
	}
}

// mainRootFromWorktreeGitFile reads a worktree's ".git" file and returns the main repository's
// working-tree root, or "" if it cannot be determined. The file's "gitdir:" points at the per-
// worktree admin dir (<commondir>/worktrees/<name>); the main .git directory is found via that
// admin dir's "commondir" file (falling back to stripping "/worktrees/<name>"), and the main
// working-tree root is that .git directory's parent.
func mainRootFromWorktreeGitFile(gitFile string) string {
	data, err := os.ReadFile(gitFile)
	if err != nil {
		return ""
	}
	worktreeAdminDir := parseGitdirLine(string(data), filepath.Dir(gitFile))
	if worktreeAdminDir == "" {
		return ""
	}

	commonGitDir := resolveCommonDir(worktreeAdminDir)
	if commonGitDir == "" {
		return ""
	}
	// The main working-tree root is the parent of the main ".git" directory. Guard the rare case
	// where the common dir is not named ".git" (bare-ish layouts) by still returning its parent.
	return filepath.Dir(commonGitDir)
}

// parseGitdirLine extracts the path from a "gitdir: <path>" line, resolving a relative path against
// baseDir (the directory containing the .git file). Returns "" if no gitdir line is present.
func parseGitdirLine(content, baseDir string) string {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		rest, ok := strings.CutPrefix(line, "gitdir:")
		if !ok {
			continue
		}
		p := strings.TrimSpace(rest)
		if p == "" {
			return ""
		}
		if !filepath.IsAbs(p) {
			p = filepath.Join(baseDir, p)
		}
		return filepath.Clean(p)
	}
	return ""
}

// resolveCommonDir returns the main repository's shared ".git" directory given a per-worktree admin
// dir (<commondir>/worktrees/<name>). It prefers the admin dir's "commondir" file (git's own
// pointer, usually "../.."); if that is absent it falls back to stripping a trailing
// "worktrees/<name>" path. Returns "" if neither yields a directory.
func resolveCommonDir(worktreeAdminDir string) string {
	if data, err := os.ReadFile(filepath.Join(worktreeAdminDir, "commondir")); err == nil {
		rel := strings.TrimSpace(string(data))
		if rel != "" {
			common := rel
			if !filepath.IsAbs(common) {
				common = filepath.Join(worktreeAdminDir, common)
			}
			return filepath.Clean(common)
		}
	}
	// Fallback: <commondir>/worktrees/<name> -> <commondir>.
	parent := filepath.Dir(worktreeAdminDir) // .../worktrees
	if filepath.Base(parent) == "worktrees" {
		return filepath.Dir(parent)
	}
	return ""
}
