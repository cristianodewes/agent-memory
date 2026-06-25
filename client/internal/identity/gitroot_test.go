package identity

import (
	"os/exec"
	"path/filepath"
	"testing"
)

func TestMainRepoRootOrdinaryCheckout(t *testing.T) {
	root := t.TempDir()
	repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
	gitDir(t, repo)
	nested := mkdir(t, filepath.Join(repo, "a", "b", "c"))

	if got := MainRepoRoot(nested); got != repo {
		t.Fatalf("MainRepoRoot(%q) = %q, want %q", nested, got, repo)
	}
}

func TestMainRepoRootNoRepo(t *testing.T) {
	dir := mkdir(t, filepath.Join(t.TempDir(), "x", "y"))
	if got := MainRepoRoot(dir); got != "" {
		t.Fatalf("MainRepoRoot(%q) = %q, want empty (no repo)", dir, got)
	}
}

// TestMainRepoRootFabricatedWorktree builds the on-disk shape git uses for a linked worktree (a .git
// FILE pointing at <main>/.git/worktrees/<name>, plus that admin dir's commondir pointer) without
// invoking git, so the resolution logic is exercised hermetically in CI.
func TestMainRepoRootFabricatedWorktree(t *testing.T) {
	root := t.TempDir()
	mainRepo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
	mainGit := mkdir(t, filepath.Join(mainRepo, ".git"))

	// The per-worktree admin dir lives under <main>/.git/worktrees/<name> with a commondir pointer.
	adminDir := mkdir(t, filepath.Join(mainGit, "worktrees", "feature-x"))
	writeFile(t, filepath.Join(adminDir, "commondir"), "../..\n")

	// The linked worktree's working tree, with a .git FILE pointing at the admin dir.
	worktree := mkdir(t, filepath.Join(root, "worktrees-checkout", "feature-x"))
	writeFile(t, filepath.Join(worktree, ".git"), "gitdir: "+adminDir+"\n")
	nested := mkdir(t, filepath.Join(worktree, "internal", "pkg"))

	// Both the worktree root and a nested dir must resolve to the MAIN repo's working-tree root.
	if got := MainRepoRoot(worktree); got != mainRepo {
		t.Fatalf("MainRepoRoot(worktree) = %q, want %q", got, mainRepo)
	}
	if got := MainRepoRoot(nested); got != mainRepo {
		t.Fatalf("MainRepoRoot(nested worktree) = %q, want %q", got, mainRepo)
	}

	// And the full resolver gives the MAIN repo's identity for the worktree (issue #32 acceptance).
	id := Resolve(nested)
	if id.Workspace != "acme" || id.Project != "billing-service" {
		t.Fatalf("worktree identity = (%q,%q), want (acme,billing-service)", id.Workspace, id.Project)
	}
}

// TestMainRepoRootRealWorktree uses the actual git CLI to create a worktree and asserts it resolves
// to the main repo identity. Skipped when git is unavailable (kept out of the hermetic guarantee but
// valuable where git exists, including CI's ubuntu runner).
func TestMainRepoRootRealWorktree(t *testing.T) {
	git, err := exec.LookPath("git")
	if err != nil {
		t.Skip("git not on PATH; covered hermetically by TestMainRepoRootFabricatedWorktree")
	}

	root := t.TempDir()
	mainRepo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
	runGit(t, git, mainRepo, "init", "-q")
	runGit(t, git, mainRepo, "-c", "user.email=t@t", "-c", "user.name=t",
		"commit", "-q", "--allow-empty", "-m", "root")

	worktree := filepath.Join(root, "wt", "feature-x")
	mkdir(t, filepath.Dir(worktree))
	runGit(t, git, mainRepo, "worktree", "add", "-q", "-b", "feature-x", worktree)

	got := MainRepoRoot(worktree)
	// Resolve symlinks on both sides: macOS /var -> /private/var can differ between the two paths.
	if realPath(t, got) != realPath(t, mainRepo) {
		t.Fatalf("MainRepoRoot(real worktree) = %q, want %q", got, mainRepo)
	}

	id := Resolve(worktree)
	if id.Project != "billing-service" {
		t.Fatalf("real worktree project = %q, want billing-service", id.Project)
	}
}

func runGit(t *testing.T, git, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command(git, args...)
	cmd.Dir = dir
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, out)
	}
}

func realPath(t *testing.T, p string) string {
	t.Helper()
	if rp, err := filepath.EvalSymlinks(p); err == nil {
		return rp
	}
	return p
}
