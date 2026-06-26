package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestSetupAgentUserScopeGlobal drives the recommended user-scope (global) install for Claude Code:
// hooks → ~/.claude/settings.json, MCP → ~/.claude.json (with a NO-identity headersHelper), and the
// self-routing block → ~/.claude/CLAUDE.md. It must NOT touch the project tree, must be idempotent, and
// uninstall --global must remove the global wiring.
func TestSetupAgentUserScopeGlobal(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	bin := filepath.Join(home, "bin", "agent-memory")
	args := []string{"setup-agent", "--global", "--dir", proj, "--bin", bin,
		"--server-url", "http://127.0.0.1:8080", "--token", "tok"}

	out, err := runCLI(t, args...)
	if err != nil {
		t.Fatalf("setup-agent --global: %v\n%s", err, out)
	}
	for _, want := range []string{"hooks: created", "mcp: created", "instructions: created"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q:\n%s", want, out)
		}
	}

	settings := filepath.Join(home, ".claude", "settings.json")
	if data, err := os.ReadFile(settings); err != nil {
		t.Fatalf("~/.claude/settings.json not written: %v", err)
	} else if !strings.Contains(string(data), "hook --event SessionStart") {
		t.Errorf("global hooks not wired:\n%s", data)
	}

	mcp := filepath.Join(home, ".claude.json")
	data, err := os.ReadFile(mcp)
	if err != nil {
		t.Fatalf("~/.claude.json not written: %v", err)
	}
	s := string(data)
	if !strings.Contains(s, `"agent-memory"`) || !strings.Contains(s, "http://127.0.0.1:8080/mcp") {
		t.Errorf("global MCP entry wrong:\n%s", s)
	}
	// The global headersHelper bakes NO project identity — it derives per-cwd at runtime (#116/#87).
	if !strings.Contains(s, "mcp-session-header") {
		t.Errorf("global MCP missing headersHelper:\n%s", s)
	}
	if strings.Contains(s, "--workspace") || strings.Contains(s, "--project") {
		t.Errorf("global headersHelper must not bake identity:\n%s", s)
	}

	if data, err := os.ReadFile(filepath.Join(home, ".claude", "CLAUDE.md")); err != nil {
		t.Fatalf("~/.claude/CLAUDE.md not written: %v", err)
	} else if !strings.Contains(string(data), "Project memory (agent-memory)") {
		t.Errorf("global instructions missing self-routing body:\n%s", data)
	}

	// The project tree must be untouched in user scope.
	for _, p := range []string{
		filepath.Join(proj, ".claude", "settings.json"),
		filepath.Join(proj, ".mcp.json"),
		filepath.Join(proj, "CLAUDE.md"),
	} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Errorf("user scope must not write project file %s (err=%v)", p, err)
		}
	}

	// Idempotent.
	out2, _ := runCLI(t, args...)
	for _, want := range []string{"hooks: unchanged", "mcp: unchanged", "instructions: unchanged"} {
		if !strings.Contains(out2, want) {
			t.Errorf("second --global run not unchanged, missing %q:\n%s", want, out2)
		}
	}

	// Uninstall the global wiring.
	out3, err := runCLI(t, "uninstall", "--global", "--dir", proj)
	if err != nil {
		t.Fatalf("uninstall --global: %v\n%s", err, out3)
	}
	for _, want := range []string{"hooks: removed", "mcp: removed", "instructions: removed"} {
		if !strings.Contains(out3, want) {
			t.Errorf("uninstall --global missing %q:\n%s", want, out3)
		}
	}
	if data, _ := os.ReadFile(settings); strings.Contains(string(data), "hook --event") {
		t.Errorf("global hooks not removed:\n%s", data)
	}
}

// TestScopeUserAndGlobalAreEquivalent checks the --scope user spelling lands in the same place as
// --global.
func TestScopeUserAndGlobalAreEquivalent(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	if _, err := runCLI(t, "install-mcp", "--scope", "user", "--dir", proj,
		"--server-url", "http://h:9"); err != nil {
		t.Fatalf("install-mcp --scope user: %v", err)
	}
	if _, err := os.Stat(filepath.Join(home, ".claude.json")); err != nil {
		t.Fatalf("--scope user should write ~/.claude.json: %v", err)
	}
}

// TestScopeProjectIsDefault confirms the default and an explicit --scope project both write the project
// tree (and nothing global), preserving the pre-#116 behavior.
func TestScopeProjectIsDefault(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	if _, err := runCLI(t, "install-mcp", "--scope", "project", "--dir", proj,
		"--server-url", "http://127.0.0.1:8080"); err != nil {
		t.Fatalf("install-mcp --scope project: %v", err)
	}
	if _, err := os.Stat(filepath.Join(proj, ".mcp.json")); err != nil {
		t.Errorf("project scope should write <repo>/.mcp.json: %v", err)
	}
	if _, err := os.Stat(filepath.Join(home, ".claude.json")); !os.IsNotExist(err) {
		t.Errorf("project scope must not write the global ~/.claude.json (err=%v)", err)
	}
}

func TestInvalidScopeErrors(t *testing.T) {
	proj := t.TempDir()
	_, err := runCLI(t, "install-mcp", "--scope", "bogus", "--dir", proj)
	if err == nil {
		t.Fatalf("invalid --scope should error")
	}
	if !strings.Contains(err.Error(), "unknown scope") {
		t.Errorf("error should name the bad scope, got %v", err)
	}
}
