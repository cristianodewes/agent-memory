package install

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstallInstructionsCreatesThenIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CLAUDE.md")

	ch, err := Instructions(path)
	if err != nil || ch != Created {
		t.Fatalf("first install: change=%v err=%v", ch, err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, SelfRoutingBegin) || !strings.Contains(s, SelfRoutingEnd) {
		t.Fatalf("missing markers:\n%s", s)
	}
	if !strings.Contains(s, "Project memory (agent-memory)") {
		t.Fatalf("missing body:\n%s", s)
	}

	ch2, err := Instructions(path)
	if err != nil || ch2 != Unchanged {
		t.Fatalf("second install: change=%v err=%v", ch2, err)
	}
	data2, _ := os.ReadFile(path)
	if string(data2) != s {
		t.Fatalf("idempotent install changed bytes")
	}
}

func TestInstallInstructionsPreservesExistingContent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CLAUDE.md")
	orig := "# My project\n\nSome guidance.\n"
	if err := os.WriteFile(path, []byte(orig), 0o644); err != nil {
		t.Fatal(err)
	}

	if _, err := Instructions(path); err != nil {
		t.Fatal(err)
	}
	s, _ := os.ReadFile(path)
	if !strings.HasPrefix(string(s), orig) {
		t.Fatalf("original content not preserved at top:\n%s", s)
	}
	if !strings.Contains(string(s), SelfRoutingBegin) {
		t.Fatalf("snippet not appended:\n%s", s)
	}
}

func TestInstallInstructionsReplacesInPlace(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CLAUDE.md")
	stale := "# Top\n\n" + SelfRoutingBegin + "\nOLD BODY\n" + SelfRoutingEnd + "\n\n## After\nkeep me\n"
	if err := os.WriteFile(path, []byte(stale), 0o644); err != nil {
		t.Fatal(err)
	}

	ch, err := Instructions(path)
	if err != nil || ch != Updated {
		t.Fatalf("expected Updated, got %v err=%v", ch, err)
	}
	s, _ := os.ReadFile(path)
	str := string(s)
	if strings.Contains(str, "OLD BODY") {
		t.Fatalf("stale body not replaced:\n%s", str)
	}
	if !strings.Contains(str, "Project memory (agent-memory)") {
		t.Fatalf("fresh body missing:\n%s", str)
	}
	if !strings.Contains(str, "## After\nkeep me") {
		t.Fatalf("content after the block was lost:\n%s", str)
	}
	if !strings.HasPrefix(str, "# Top") {
		t.Fatalf("content before the block was lost:\n%s", str)
	}
}

func TestUninstallInstructions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CLAUDE.md")

	if ch, err := UninstallInstructions(path); err != nil || ch != Absent {
		t.Fatalf("missing file: change=%v err=%v", ch, err)
	}

	if err := os.WriteFile(path, []byte("# Keep\n\nprose\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Instructions(path); err != nil {
		t.Fatal(err)
	}
	ch, err := UninstallInstructions(path)
	if err != nil || ch != Removed {
		t.Fatalf("uninstall: change=%v err=%v", ch, err)
	}
	data, _ := os.ReadFile(path)
	if strings.Contains(string(data), SelfRoutingBegin) {
		t.Fatalf("block still present after uninstall:\n%s", data)
	}
	if !strings.Contains(string(data), "prose") {
		t.Fatalf("surrounding content lost:\n%s", data)
	}
	if ch, _ := UninstallInstructions(path); ch != Absent {
		t.Fatalf("second uninstall should be Absent, got %v", ch)
	}
}

func TestUninstallInstructionsRemovesFileWhenOnlyBlock(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CLAUDE.md")
	if _, err := Instructions(path); err != nil {
		t.Fatal(err)
	}
	if ch, err := UninstallInstructions(path); err != nil || ch != Removed {
		t.Fatalf("change=%v err=%v", ch, err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("file should have been removed, stat err=%v", err)
	}
}
