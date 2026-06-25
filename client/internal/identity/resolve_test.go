package identity

import (
	"os"
	"path/filepath"
	"testing"
)

// mkdir is a t.Fatal-on-error MkdirAll.
func mkdir(t *testing.T, path string) string {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", path, err)
	}
	return path
}

// writeFile is a t.Fatal-on-error WriteFile that creates parent dirs.
func writeFile(t *testing.T, path, content string) {
	t.Helper()
	mkdir(t, filepath.Dir(path))
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// gitDir marks dir as an ordinary git repo root by creating an (empty) .git directory.
func gitDir(t *testing.T, dir string) {
	t.Helper()
	mkdir(t, filepath.Join(dir, ".git"))
}

func TestResolve(t *testing.T) {
	t.Run("git repo without marker: project=repo basename, workspace=parent", func(t *testing.T) {
		root := t.TempDir()
		repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
		gitDir(t, repo)
		nested := mkdir(t, filepath.Join(repo, "cmd", "server"))

		got := Resolve(nested)

		if got.Workspace != "acme" || got.Project != "billing-service" {
			t.Fatalf("identity = (%q,%q), want (acme,billing-service)", got.Workspace, got.Project)
		}
		if got.Source != SourceGitRoot {
			t.Fatalf("source = %q, want git-root", got.Source)
		}
		if got.RepoRoot != repo {
			t.Fatalf("repoRoot = %q, want %q", got.RepoRoot, repo)
		}
	})

	t.Run("no git, no marker: falls back to cwd basename", func(t *testing.T) {
		root := t.TempDir()
		dir := mkdir(t, filepath.Join(root, "loose-dir"))

		got := Resolve(dir)

		if got.Workspace != "loose-dir" || got.Project != "loose-dir" {
			t.Fatalf("identity = (%q,%q), want (loose-dir,loose-dir)", got.Workspace, got.Project)
		}
		if got.Source != SourceCwd {
			t.Fatalf("source = %q, want cwd", got.Source)
		}
	})

	t.Run("empty cwd resolves to unknown", func(t *testing.T) {
		got := Resolve("")
		if got.Workspace != "unknown" || got.Project != "unknown" {
			t.Fatalf("identity = (%q,%q), want (unknown,unknown)", got.Workspace, got.Project)
		}
	})

	t.Run("marker overrides git derivation (nearest ancestor wins)", func(t *testing.T) {
		root := t.TempDir()
		repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
		gitDir(t, repo)
		// A marker at the repo root pins a different identity than the directory names imply.
		writeFile(t, filepath.Join(repo, MarkerFileName),
			"workspace = \"personal\"\nproject = \"side-project\"\n")
		nested := mkdir(t, filepath.Join(repo, "internal", "pkg"))

		got := Resolve(nested)

		if got.Workspace != "personal" || got.Project != "side-project" {
			t.Fatalf("identity = (%q,%q), want (personal,side-project)", got.Workspace, got.Project)
		}
		if got.Source != SourceMarker {
			t.Fatalf("source = %q, want marker", got.Source)
		}
		if got.MarkerPath != filepath.Join(repo, MarkerFileName) {
			t.Fatalf("markerPath = %q, want repo marker", got.MarkerPath)
		}
	})

	t.Run("nearest marker wins over a farther one", func(t *testing.T) {
		root := t.TempDir()
		outer := mkdir(t, filepath.Join(root, "outer"))
		writeFile(t, filepath.Join(outer, MarkerFileName),
			"workspace = \"outer-ws\"\nproject = \"outer-proj\"\n")
		inner := mkdir(t, filepath.Join(outer, "sub", "inner"))
		writeFile(t, filepath.Join(inner, MarkerFileName),
			"workspace = \"inner-ws\"\nproject = \"inner-proj\"\n")
		deep := mkdir(t, filepath.Join(inner, "deeper"))

		got := Resolve(deep)

		if got.Workspace != "inner-ws" || got.Project != "inner-proj" {
			t.Fatalf("identity = (%q,%q), want (inner-ws,inner-proj)", got.Workspace, got.Project)
		}
		if got.MarkerPath != filepath.Join(inner, MarkerFileName) {
			t.Fatalf("markerPath = %q, want inner marker", got.MarkerPath)
		}
	})

	t.Run("marker with only server_url keeps derived identity but carries override", func(t *testing.T) {
		root := t.TempDir()
		repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
		gitDir(t, repo)
		writeFile(t, filepath.Join(repo, MarkerFileName),
			"server_url = \"http://10.0.0.5:8080\"\ntoken = \"sekret\"\n")

		got := Resolve(repo)

		// Identity still derived from git (marker set neither workspace nor project).
		if got.Workspace != "acme" || got.Project != "billing-service" {
			t.Fatalf("identity = (%q,%q), want (acme,billing-service)", got.Workspace, got.Project)
		}
		if got.Source != SourceGitRoot {
			t.Fatalf("source = %q, want git-root (marker pinned no identity)", got.Source)
		}
		if got.ServerURL != "http://10.0.0.5:8080" || got.Token != "sekret" {
			t.Fatalf("overrides = (%q,%q), want (http://10.0.0.5:8080,sekret)", got.ServerURL, got.Token)
		}
	})

	t.Run("marker with only project overrides project, derives workspace", func(t *testing.T) {
		root := t.TempDir()
		repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
		gitDir(t, repo)
		writeFile(t, filepath.Join(repo, MarkerFileName), "project = \"renamed\"\n")

		got := Resolve(repo)

		if got.Workspace != "acme" || got.Project != "renamed" {
			t.Fatalf("identity = (%q,%q), want (acme,renamed)", got.Workspace, got.Project)
		}
		if got.Source != SourceMarker {
			t.Fatalf("source = %q, want marker", got.Source)
		}
	})

	t.Run("malformed marker is skipped, derivation still applies", func(t *testing.T) {
		root := t.TempDir()
		repo := mkdir(t, filepath.Join(root, "acme", "billing-service"))
		gitDir(t, repo)
		// Not valid TOML — must be ignored, not fatal.
		writeFile(t, filepath.Join(repo, MarkerFileName), "this is = = not toml [[[")

		got := Resolve(repo)

		if got.Workspace != "acme" || got.Project != "billing-service" {
			t.Fatalf("identity = (%q,%q), want git derivation despite bad marker", got.Workspace, got.Project)
		}
		if got.Source != SourceGitRoot {
			t.Fatalf("source = %q, want git-root", got.Source)
		}
	})

	t.Run("a broken nearer marker does not strand a valid farther one", func(t *testing.T) {
		root := t.TempDir()
		outer := mkdir(t, filepath.Join(root, "outer"))
		writeFile(t, filepath.Join(outer, MarkerFileName),
			"workspace = \"outer-ws\"\nproject = \"outer-proj\"\n")
		inner := mkdir(t, filepath.Join(outer, "inner"))
		writeFile(t, filepath.Join(inner, MarkerFileName), "= = broken [[[")

		got := Resolve(inner)

		// The nearer (broken) marker is skipped; the valid outer one applies.
		if got.Workspace != "outer-ws" || got.Project != "outer-proj" {
			t.Fatalf("identity = (%q,%q), want outer marker", got.Workspace, got.Project)
		}
		if got.MarkerPath != filepath.Join(outer, MarkerFileName) {
			t.Fatalf("markerPath = %q, want outer marker", got.MarkerPath)
		}
	})
}
