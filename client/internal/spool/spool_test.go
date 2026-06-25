package spool

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
)

func testPayload(t *testing.T, clientEventID string) hook.Payload {
	t.Helper()
	ws, err := core.NewWorkspaceID("acme")
	if err != nil {
		t.Fatal(err)
	}
	proj, err := core.NewProjectID("agent-memory")
	if err != nil {
		t.Fatal(err)
	}
	p := hook.NewPayload("UserPromptSubmit", core.NewSessionID(), ws, proj,
		time.Date(2026, 6, 25, 12, 0, 0, 0, time.UTC))
	if clientEventID != "" {
		p.ClientEventID = &clientEventID
	}
	return p
}

func TestAppendThenListAndRead(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	name, err := s.Append(testPayload(t, "evt-1"))
	if err != nil {
		t.Fatalf("append: %v", err)
	}
	entries, err := s.List()
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(entries) != 1 || entries[0].Name != name {
		t.Fatalf("expected one entry named %q, got %+v", name, entries)
	}
	got, err := s.Read(name)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if got.Event != "UserPromptSubmit" || got.Kind != core.KindUserPrompt {
		t.Fatalf("round-trip mismatch: %+v", got)
	}
}

func TestListReturnsCaptureOrder(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	// Names derive from ClientEventID; pick ids whose sort order is known.
	for _, id := range []string{"a-003", "a-001", "a-002"} {
		if _, err := s.Append(testPayload(t, id)); err != nil {
			t.Fatal(err)
		}
	}
	entries, err := s.List()
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name)
	}
	want := []string{"a-001.json", "a-002.json", "a-003.json"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Fatalf("order: got %v want %v", names, want)
	}
}

func TestRemoveClearsEntryAndIsIdempotent(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	name, _ := s.Append(testPayload(t, "evt-x"))
	if err := s.Remove(name); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if entries, _ := s.List(); len(entries) != 0 {
		t.Fatalf("expected empty spool after remove, got %d", len(entries))
	}
	// Removing again is a no-op (idempotent re-drain).
	if err := s.Remove(name); err != nil {
		t.Fatalf("second remove must be a no-op, got %v", err)
	}
}

func TestQuarantineMovesEntryAndKeepsReason(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	// Write a corrupt entry directly into the spool (not valid JSON).
	bad := filepath.Join(dir, "bad-1"+eventExt)
	if err := os.WriteFile(bad, []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := s.Quarantine("bad-1"+eventExt, "parse error"); err != nil {
		t.Fatalf("quarantine: %v", err)
	}
	if entries, _ := s.List(); len(entries) != 0 {
		t.Fatalf("quarantined entry must leave the active spool, got %d", len(entries))
	}
	n, err := s.QuarantinedCount()
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("expected 1 quarantined, got %d", n)
	}
	// The reason breadcrumb exists.
	if _, err := os.Stat(filepath.Join(dir, quarantineDir, "bad-1"+eventExt+".reason")); err != nil {
		t.Fatalf("expected reason file: %v", err)
	}
}

func TestAppendIsAtomicNoTmpLeftOnSuccess(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Append(testPayload(t, "evt-atomic")); err != nil {
		t.Fatal(err)
	}
	dirents, _ := os.ReadDir(dir)
	for _, de := range dirents {
		if strings.HasSuffix(de.Name(), tmpExt) {
			t.Fatalf("a committed append must leave no .tmp file, found %q", de.Name())
		}
	}
}

func TestSanitizeSegmentNeverEscapesDir(t *testing.T) {
	// A hostile ClientEventID with path separators must not escape the spool dir.
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	name, err := s.Append(testPayload(t, "../../etc/passwd"))
	if err != nil {
		t.Fatalf("append: %v", err)
	}
	if strings.ContainsAny(name, `/\`) {
		t.Fatalf("file name must be a single safe segment, got %q", name)
	}
	// The file lives directly under dir.
	if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
		t.Fatalf("expected file under spool dir: %v", err)
	}
}

// TestWindowsExtendedLengthPath is the regression guard for the documented prior-art bug where a
// native spool died under a `\\?\C:\...` extended-length data dir. On Windows we build a real
// extended-length path; on other OSes we still exercise an unusual deep/odd dir so the test is
// cross-platform (the issue requires a cross-platform path test incl. Windows).
func TestWindowsExtendedLengthPath(t *testing.T) {
	base := t.TempDir()

	var spoolDir string
	if runtime.GOOS == "windows" {
		// Extended-length form of an absolute Windows path: prefix with \\?\.
		abs, err := filepath.Abs(filepath.Join(base, "data", "spool"))
		if err != nil {
			t.Fatal(err)
		}
		spoolDir = `\\?\` + abs
	} else {
		spoolDir = filepath.Join(base, "data", "spool")
	}

	s, err := Open(spoolDir)
	if err != nil {
		t.Fatalf("open under extended-length path %q: %v", spoolDir, err)
	}
	name, err := s.Append(testPayload(t, "evt-winpath"))
	if err != nil {
		t.Fatalf("append under extended-length path: %v", err)
	}
	entries, err := s.List()
	if err != nil {
		t.Fatalf("list under extended-length path: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected 1 entry under extended-length path, got %d", len(entries))
	}
	got, err := s.Read(name)
	if err != nil {
		t.Fatalf("read under extended-length path: %v", err)
	}
	// Sanity: it is the event we wrote.
	var probe map[string]any
	b, _ := json.Marshal(got)
	_ = json.Unmarshal(b, &probe)
	if probe["event"] != "UserPromptSubmit" {
		t.Fatalf("unexpected event read back: %v", probe["event"])
	}
	if err := s.Remove(name); err != nil {
		t.Fatalf("remove under extended-length path: %v", err)
	}
}
