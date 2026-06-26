package log

import (
	"os"
	"path/filepath"
	"testing"
)

// TestFileSinkRotatesAndBoundsRetention drives the size rotator past several thresholds and asserts
// (1) the active file plus numbered backups exist and (2) retention is bounded by maxBackups — the
// oldest backup is dropped rather than growing without limit (issue #117 acceptance).
func TestFileSinkRotatesAndBoundsRetention(t *testing.T) {
	dir := t.TempDir()
	const maxBackups = 2
	s, err := openFileSink(dir, "client.log", 50 /*bytes*/, maxBackups)
	if err != nil {
		t.Fatalf("openFileSink: %v", err)
	}

	line := []byte("0123456789\n") // 11 bytes; ~5 lines fill a 50-byte file
	for i := 0; i < 40; i++ {
		if _, err := s.Write(line); err != nil {
			t.Fatalf("Write: %v", err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	base := filepath.Join(dir, "client.log")
	mustExist(t, base)
	mustExist(t, base+".1")
	mustExist(t, base+".2")
	// Retention is bounded: a third backup must never accumulate.
	if _, err := os.Stat(base + ".3"); !os.IsNotExist(err) {
		t.Fatalf("retention not bounded: client.log.3 exists (err=%v)", err)
	}

	// Every backup must respect the size cap (a rotation happened before the cap was blown past by a
	// lot — each file holds whole lines under ~maxSize).
	for _, name := range []string{base + ".1", base + ".2"} {
		info, err := os.Stat(name)
		if err != nil {
			t.Fatalf("stat %s: %v", name, err)
		}
		if info.Size() > 60 { // maxSize 50 + at most one line (11) of overshoot before the check
			t.Fatalf("%s is %d bytes, larger than the rotation cap", name, info.Size())
		}
	}
}

// TestFileSinkSeedsSizeFromExistingFile ensures a reopened sink continues toward the SAME rotation
// threshold instead of resetting its byte counter (so an append-mode restart still rotates on time).
func TestFileSinkSeedsSizeFromExistingFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "client.log")
	if err := os.WriteFile(path, make([]byte, 45), 0o644); err != nil {
		t.Fatalf("seed file: %v", err)
	}
	s, err := openFileSink(dir, "client.log", 50, 1)
	if err != nil {
		t.Fatalf("openFileSink: %v", err)
	}
	defer func() { _ = s.Close() }()

	// 45 existing + 11 > 50 ⇒ this write must rotate the seeded content aside first.
	if _, err := s.Write([]byte("0123456789\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	mustExist(t, path+".1")
}

func mustExist(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("expected %s to exist: %v", path, err)
	}
}
