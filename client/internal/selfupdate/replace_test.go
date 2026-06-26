package selfupdate

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// stageReplace writes a "current" binary (execPath) and a "new" staged binary (newPath)
// in a fresh temp dir and returns their paths.
func stageReplace(t *testing.T) (execPath, newPath string) {
	t.Helper()
	dir := t.TempDir()
	execPath = filepath.Join(dir, "agent-memory")
	newPath = filepath.Join(dir, "agent-memory-update.tmp")
	mustWrite(t, execPath, "OLD-BINARY")
	mustWrite(t, newPath, "NEW-BINARY")
	return execPath, newPath
}

func mustWrite(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o755); err != nil {
		t.Fatalf("writing %s: %v", path, err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading %s: %v", path, err)
	}
	return string(b)
}

// failRename makes renameFunc fail only for the exact src→dst rename, delegating every
// other rename (notably the rollback's old→exec) to the real os.Rename. It restores the
// original on cleanup. Used to deterministically trigger the "install new binary"
// failure without also breaking the rollback that must follow it.
func failRename(t *testing.T, src, dst string) {
	t.Helper()
	prev := renameFunc
	renameFunc = func(oldpath, newpath string) error {
		if oldpath == src && newpath == dst {
			return os.ErrPermission
		}
		return prev(oldpath, newpath)
	}
	t.Cleanup(func() { renameFunc = prev })
}

func TestReplaceUnixHappy(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("replaceUnix relies on rename-over-existing, which Windows forbids; covered on Unix CI")
	}
	execPath, newPath := stageReplace(t)
	if err := replaceUnix(execPath, newPath); err != nil {
		t.Fatalf("replaceUnix: %v", err)
	}
	if got := readFile(t, execPath); got != "NEW-BINARY" {
		t.Fatalf("exec content = %q, want NEW-BINARY", got)
	}
	if _, err := os.Stat(newPath); !os.IsNotExist(err) {
		t.Fatalf("staged binary should be gone after a successful rename, stat err = %v", err)
	}
}

func TestReplaceUnixFailureKeepsOriginal(t *testing.T) {
	execPath, newPath := stageReplace(t)
	failRename(t, newPath, execPath) // the rename of new → exec fails

	err := replaceUnix(execPath, newPath)
	if err == nil {
		t.Fatal("expected an error when the rename fails")
	}
	if got := readFile(t, execPath); got != "OLD-BINARY" {
		t.Fatalf("original binary must be untouched on failure, got %q", got)
	}
	if _, statErr := os.Stat(newPath); !os.IsNotExist(statErr) {
		t.Fatalf("staged binary should be cleaned up on failure, stat err = %v", statErr)
	}
}

func TestReplaceWindowsHappy(t *testing.T) {
	execPath, newPath := stageReplace(t)
	if err := replaceWindows(execPath, newPath); err != nil {
		t.Fatalf("replaceWindows: %v", err)
	}
	if got := readFile(t, execPath); got != "NEW-BINARY" {
		t.Fatalf("exec content = %q, want NEW-BINARY", got)
	}
	if _, err := os.Stat(newPath); !os.IsNotExist(err) {
		t.Fatalf("staged binary should be gone after the swap, stat err = %v", err)
	}
	// Best-effort cleanup removes the .old sidecar when it is not locked (the test
	// process never executes it, so on every platform it should be gone here).
	if _, err := os.Stat(execPath + ".old"); !os.IsNotExist(err) {
		t.Fatalf(".old sidecar should be cleaned up, stat err = %v", err)
	}
}

func TestReplaceWindowsRollback(t *testing.T) {
	execPath, newPath := stageReplace(t)
	failRename(t, newPath, execPath) // moving the new binary into place fails; must roll back

	err := replaceWindows(execPath, newPath)
	if err == nil {
		t.Fatal("expected an error when installing the new binary fails")
	}
	// Rollback must restore the original binary at its original path.
	if got := readFile(t, execPath); got != "OLD-BINARY" {
		t.Fatalf("rollback must restore the original binary, got %q", got)
	}
	if _, statErr := os.Stat(execPath + ".old"); !os.IsNotExist(statErr) {
		t.Fatalf(".old sidecar should be renamed back, stat err = %v", statErr)
	}
}
