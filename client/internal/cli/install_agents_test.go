package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// redirectHome points os.UserHomeDir() (and the Windows app-data lookups) at a temp dir, so tests that
// target home-based clients (codex, cursor, gemini-cli, claude-desktop) never touch the real home.
// Returns the temp home root.
func redirectHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)                                         // unix os.UserHomeDir
	t.Setenv("USERPROFILE", home)                                  // windows os.UserHomeDir
	t.Setenv("APPDATA", filepath.Join(home, "AppData", "Roaming")) // windows claude-desktop
	t.Setenv("XDG_CONFIG_HOME", "")                                // force ~/.config on linux claude-desktop
	return home
}

// TestSetupAgentCodexMCPOnly drives setup-agent for Codex: MCP (TOML, home) + AGENTS.md instructions
// (project), with hooks reported unsupported. Idempotent on a second run; uninstall removes.
func TestSetupAgentCodexMCPOnly(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	args := []string{"setup-agent", "--agent", "codex", "--dir", proj,
		"--bin", filepath.Join(proj, "agent-memory"), "--server-url", "http://127.0.0.1:8080", "--token", "tok"}

	out, err := runCLI(t, args...)
	if err != nil {
		t.Fatalf("setup-agent codex: %v\n%s", err, out)
	}
	if !strings.Contains(out, "hooks: unsupported by codex") {
		t.Errorf("expected hooks unsupported line:\n%s", out)
	}
	for _, want := range []string{"mcp: created", "instructions: created"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q:\n%s", want, out)
		}
	}
	toml, err := os.ReadFile(filepath.Join(home, ".codex", "config.toml"))
	if err != nil {
		t.Fatalf("codex config.toml not written: %v", err)
	}
	if !strings.Contains(string(toml), "[mcp_servers.agent-memory]") ||
		!strings.Contains(string(toml), "http://127.0.0.1:8080/mcp") ||
		!strings.Contains(string(toml), `bearer_token = "tok"`) {
		t.Errorf("codex toml content wrong:\n%s", toml)
	}
	if _, err := os.Stat(filepath.Join(proj, "AGENTS.md")); err != nil {
		t.Errorf("AGENTS.md not written: %v", err)
	}

	out2, _ := runCLI(t, args...)
	for _, want := range []string{"mcp: unchanged", "instructions: unchanged"} {
		if !strings.Contains(out2, want) {
			t.Errorf("second run not unchanged, missing %q:\n%s", want, out2)
		}
	}

	out3, err := runCLI(t, "uninstall", "--agent", "codex", "--dir", proj)
	if err != nil {
		t.Fatalf("uninstall: %v\n%s", err, out3)
	}
	if !strings.Contains(out3, "mcp: removed") {
		t.Errorf("uninstall did not remove mcp:\n%s", out3)
	}
}

// TestInstallMcpGeminiUsesHttpUrl checks the Gemini renderer + the --client alias.
func TestInstallMcpGeminiUsesHttpUrl(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	out, err := runCLI(t, "install-mcp", "--client", "gemini", "--dir", proj, "--server-url", "http://h:9")
	if err != nil {
		t.Fatalf("install-mcp gemini: %v\n%s", err, out)
	}
	data, err := os.ReadFile(filepath.Join(home, ".gemini", "settings.json"))
	if err != nil {
		t.Fatalf("gemini settings.json not written: %v", err)
	}
	if !strings.Contains(string(data), `"httpUrl": "http://h:9/mcp"`) {
		t.Errorf("gemini MCP should use httpUrl:\n%s", data)
	}
}

// TestInstallHooksCursorFlat checks the Cursor flat hook shape via the CLI.
func TestInstallHooksCursorFlat(t *testing.T) {
	home := redirectHome(t)
	proj := t.TempDir()
	bin := filepath.Join(proj, "agent-memory")
	out, err := runCLI(t, "install-hooks", "--agent", "cursor", "--dir", proj, "--bin", bin)
	if err != nil {
		t.Fatalf("install-hooks cursor: %v\n%s", err, out)
	}
	data, err := os.ReadFile(filepath.Join(home, ".cursor", "hooks.json"))
	if err != nil {
		t.Fatalf("cursor hooks.json not written: %v", err)
	}
	s := string(data)
	if !strings.Contains(s, `"preToolUse"`) || !strings.Contains(s, "hook --event PreToolUse") {
		t.Errorf("cursor flat hook shape wrong:\n%s", s)
	}
	if !strings.Contains(s, `"version": 1`) {
		t.Errorf("cursor hooks should carry version:\n%s", s)
	}
}

// TestSetupAgentDefaultIsClaudeCodeUnchanged guards that omitting --agent reproduces the exact
// pre-#115 project layout (.claude/settings.json + .mcp.json + CLAUDE.md).
func TestSetupAgentDefaultIsClaudeCodeUnchanged(t *testing.T) {
	redirectHome(t)
	proj := t.TempDir()
	out, err := runCLI(t, "setup-agent", "--dir", proj, "--bin", filepath.Join(proj, "agent-memory"),
		"--server-url", "http://127.0.0.1:8080")
	if err != nil {
		t.Fatalf("setup-agent: %v\n%s", err, out)
	}
	for _, f := range []string{
		filepath.Join(proj, ".claude", "settings.json"),
		filepath.Join(proj, ".mcp.json"),
		filepath.Join(proj, "CLAUDE.md"),
	} {
		if _, err := os.Stat(f); err != nil {
			t.Errorf("expected %s: %v", f, err)
		}
	}
	for _, want := range []string{"hooks: created", "mcp: created", "instructions: created"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q:\n%s", want, out)
		}
	}
}

func TestUnknownAgentErrors(t *testing.T) {
	proj := t.TempDir()
	out, err := runCLI(t, "install-mcp", "--agent", "nope", "--dir", proj)
	if err == nil {
		t.Fatalf("unknown agent should error, got:\n%s", out)
	}
	if !strings.Contains(err.Error(), "unknown agent/client") {
		t.Errorf("error should name the problem, got %v", err)
	}
}
