package capturesession

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestWriteThenReadRoundTrips(t *testing.T) {
	dir := t.TempDir()
	const id = "018f3a2b-1c2d-7e3f-8a9b-0c1d2e3f4a5b"
	if err := Write(dir, "acme", "alpha", id); err != nil {
		t.Fatalf("Write: %v", err)
	}
	got, err := Read(dir, "acme", "alpha")
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if got != id {
		t.Fatalf("round-trip = %q, want %q", got, id)
	}
}

func TestReadAbsentIsEmptyNotError(t *testing.T) {
	// The normal "no session bound yet" case: a missing file is "" with no error, so the header
	// command emits nothing and the fail-closed server rejects a no-scope session_aware call.
	got, err := Read(t.TempDir(), "acme", "never-written")
	if err != nil {
		t.Fatalf("Read of absent file: unexpected error %v", err)
	}
	if got != "" {
		t.Fatalf("Read of absent file = %q, want empty", got)
	}
}

func TestDifferentProjectsAreIsolated(t *testing.T) {
	// The core client-side guarantee: two sessions of the SAME workspace but DIFFERENT projects do not
	// clobber each other — each project keeps its own current session.
	dir := t.TempDir()
	if err := Write(dir, "acme", "alpha", "id-alpha"); err != nil {
		t.Fatalf("Write alpha: %v", err)
	}
	if err := Write(dir, "acme", "beta", "id-beta"); err != nil {
		t.Fatalf("Write beta: %v", err)
	}
	if got, _ := Read(dir, "acme", "alpha"); got != "id-alpha" {
		t.Fatalf("alpha = %q, want id-alpha (beta clobbered it)", got)
	}
	if got, _ := Read(dir, "acme", "beta"); got != "id-beta" {
		t.Fatalf("beta = %q, want id-beta", got)
	}
}

func TestWriteOverwritesPreviousSession(t *testing.T) {
	// Same project, a new session: last writer wins (the file holds the most recent session).
	dir := t.TempDir()
	mustWrite(t, dir, "acme", "alpha", "first")
	mustWrite(t, dir, "acme", "alpha", "second")
	if got, _ := Read(dir, "acme", "alpha"); got != "second" {
		t.Fatalf("after overwrite = %q, want second", got)
	}
}

func TestWriteTrimsAndReadTrims(t *testing.T) {
	dir := t.TempDir()
	if err := Write(dir, "acme", "alpha", "  padded-id  "); err != nil {
		t.Fatalf("Write: %v", err)
	}
	if got, _ := Read(dir, "acme", "alpha"); got != "padded-id" {
		t.Fatalf("trimmed round-trip = %q, want padded-id", got)
	}
}

func TestWriteRejectsEmptySessionID(t *testing.T) {
	if err := Write(t.TempDir(), "acme", "alpha", "   "); err == nil {
		t.Fatal("Write of a blank session id: expected an error, got nil")
	}
}

func TestPathLayout(t *testing.T) {
	got := Path("/data", "acme", "alpha")
	want := filepath.Join("/data", "sessions", "acme", "alpha")
	if got != want {
		t.Fatalf("Path = %q, want %q", got, want)
	}
}

func TestWrittenFileIsOwnerOnly(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unix file mode bits are not meaningful on Windows")
	}
	dir := t.TempDir()
	if err := Write(dir, "acme", "alpha", "id"); err != nil {
		t.Fatalf("Write: %v", err)
	}
	info, err := os.Stat(Path(dir, "acme", "alpha"))
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("file mode = %o, want 600", perm)
	}
}

func mustWrite(t *testing.T, dataDir, ws, proj, id string) {
	t.Helper()
	if err := Write(dataDir, ws, proj, id); err != nil {
		t.Fatalf("Write(%s/%s): %v", ws, proj, err)
	}
}
