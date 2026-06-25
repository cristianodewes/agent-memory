package core

import (
	"strings"
	"testing"
)

// Path-normalization cases mirror com.agentmemory.core PagePathTest 1:1 so Go and Java collapse the
// same spellings to the same key (issue #3 acceptance criterion 3).
func TestNormalizePagePath(t *testing.T) {
	cases := []struct{ raw, want string }{
		{`concepts\recall.md`, "concepts/recall.md"},      // backslashes -> slashes
		{"concepts//recall.md", "concepts/recall.md"},     // collapse duplicate slashes
		{"/concepts/recall.md", "concepts/recall.md"},     // strip leading slash
		{"./concepts/recall.md", "concepts/recall.md"},    // strip leading ./
		{"concepts/./recall.md", "concepts/recall.md"},    // drop interior .
		{"concepts/recall", "concepts/recall.md"},         // append .md
		{"concepts/recall.md", "concepts/recall.md"},      // do not double .md
		{"Concepts/Recall.MD", "concepts/recall.md"},      // lower-case path + ext
		{"  concepts / recall.md ", "concepts/recall.md"}, // trim segments
		{`\a\.\b\\c`, "a/b/c.md"},                         // mixed separators + redundant segments
		{"log.md", "log.md"},                              // root-level page, no leading slash
	}
	for _, c := range cases {
		got, err := normalizePagePath(c.raw)
		if err != nil {
			t.Errorf("normalizePagePath(%q) unexpected error: %v", c.raw, err)
			continue
		}
		if got != c.want {
			t.Errorf("normalizePagePath(%q) = %q, want %q", c.raw, got, c.want)
		}
	}
}

func TestNormalizePagePathIsIdempotent(t *testing.T) {
	once, err := normalizePagePath(`Concepts\\Foo`)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	twice, err := normalizePagePath(once)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if once != twice {
		t.Errorf("not idempotent: %q -> %q", once, twice)
	}
}

func TestNormalizePagePathRejects(t *testing.T) {
	cases := []struct {
		raw, msgContains string
	}{
		{"../etc/passwd", ".."},
		{"concepts/../../secrets.md", ".."},
		{"   ", "empty"},
		{"/", "empty"},
		{"a\x00b.md", "NUL"},
	}
	for _, c := range cases {
		_, err := normalizePagePath(c.raw)
		if err == nil {
			t.Errorf("normalizePagePath(%q) expected error", c.raw)
			continue
		}
		if !strings.Contains(err.Error(), c.msgContains) {
			t.Errorf("normalizePagePath(%q) error %q, want substring %q", c.raw, err, c.msgContains)
		}
	}
}

func TestPagePathHelpers(t *testing.T) {
	p, err := NewPagePath("Concepts/Sub/Recall")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if p.String() != "concepts/sub/recall.md" {
		t.Errorf("String() = %q", p.String())
	}
	if p.FileName() != "recall.md" {
		t.Errorf("FileName() = %q", p.FileName())
	}
	if p.TopFolder() != "concepts" {
		t.Errorf("TopFolder() = %q", p.TopFolder())
	}

	root, _ := NewPagePath("log.md")
	if root.TopFolder() != "" {
		t.Errorf("root TopFolder() = %q, want empty", root.TopFolder())
	}
}

func TestSlugNormalization(t *testing.T) {
	ws, err := NewWorkspaceID("  Acme  ")
	if err != nil || ws.String() != "acme" {
		t.Errorf("NewWorkspaceID = %q, %v", ws.String(), err)
	}
	p, err := NewProjectID("Agent-Memory")
	if err != nil || p.String() != "agent-memory" {
		t.Errorf("NewProjectID = %q, %v", p.String(), err)
	}
	for _, bad := range []string{"  ", "a/b", `a\b`, "a\x00b"} {
		if _, err := NewWorkspaceID(bad); err == nil {
			t.Errorf("NewWorkspaceID(%q) expected error", bad)
		}
	}
}
