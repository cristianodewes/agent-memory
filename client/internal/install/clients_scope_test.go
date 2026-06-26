package install

import (
	"path/filepath"
	"strings"
	"testing"
)

// --- Scope parsing -------------------------------------------------------------------------------

func TestParseScope(t *testing.T) {
	cases := map[string]Scope{
		"":        ScopeProject,
		"project": ScopeProject,
		"user":    ScopeUser,
		"USER":    ScopeUser,
		" User ":  ScopeUser,
	}
	for in, want := range cases {
		if got, err := ParseScope(in); err != nil || got != want {
			t.Errorf("ParseScope(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
	if _, err := ParseScope("global"); err == nil {
		t.Errorf("ParseScope(global) should error (it is a CLI shorthand, not a scope value)")
	}
}

// --- Claude Code scope-aware path resolution -----------------------------------------------------

func TestClaudeScopePaths(t *testing.T) {
	proj := filepath.FromSlash("/repo")
	home := filepath.FromSlash("/home/u")
	projCtx := PathContext{ProjectRoot: proj, Home: home, Scope: ScopeProject}
	userCtx := PathContext{ProjectRoot: proj, Home: home, Scope: ScopeUser}

	claude := mustClientS(t, "claude-code")

	if got, want := claude.Hooks.Path(projCtx), SettingsPath(proj); got != want {
		t.Errorf("project hooks path = %q, want %q", got, want)
	}
	if got, want := claude.Hooks.Path(userCtx), SettingsPath(home); got != want {
		t.Errorf("user hooks path = %q, want %q", got, want)
	}
	if got, want := claude.MCP.Path(projCtx), McpPath(proj); got != want {
		t.Errorf("project mcp path = %q, want %q", got, want)
	}
	if got, want := claude.MCP.Path(userCtx), ClaudeGlobalMcpPath(home); got != want {
		t.Errorf("user mcp path = %q, want %q", got, want)
	}
	// Instructions: project root vs ~/.claude.
	if got, want := claude.InstrDir(projCtx), proj; got != want {
		t.Errorf("project instr dir = %q, want %q", got, want)
	}
	if got, want := claude.InstrDir(userCtx), filepath.Join(home, ".claude"); got != want {
		t.Errorf("user instr dir = %q, want %q", got, want)
	}
}

// TestNonClaudeIgnoresScope confirms user scope does not (yet) move other clients off their
// conventional location — multi-agent global is phase 2.
func TestNonClaudeIgnoresScope(t *testing.T) {
	home := filepath.FromSlash("/home/u")
	proj := filepath.FromSlash("/repo")
	userCtx := PathContext{ProjectRoot: proj, Home: home, Scope: ScopeUser}

	codex := mustClientS(t, "codex")
	if got := codex.MCP.Path(userCtx); got != filepath.Join(home, ".codex", "config.toml") {
		t.Errorf("codex MCP path changed under user scope: %q", got)
	}
	if got := codex.InstrDir(userCtx); got != proj {
		t.Errorf("codex instr dir should stay project root under user scope: %q", got)
	}
}

// --- Global session-header command (no baked identity) -------------------------------------------

func TestMcpSessionHeaderCommandGlobal(t *testing.T) {
	got := McpSessionHeaderCommandGlobal("/opt/bin/agent memory")
	want := `"/opt/bin/agent memory" mcp-session-header`
	if got != want {
		t.Fatalf("global header cmd = %q, want %q", got, want)
	}
	if strings.Contains(got, "--workspace") || strings.Contains(got, "--project") {
		t.Errorf("global header cmd must bake no identity: %q", got)
	}
	if McpSessionHeaderCommandGlobal("") != "" {
		t.Errorf("empty bin → empty command")
	}
}

// TestUserScopeMcpRenderOmitsBakedIdentity renders the Claude Code MCP entry with the global header
// command and verifies the headersHelper carries no fixed workspace/project.
func TestUserScopeMcpRenderOmitsBakedIdentity(t *testing.T) {
	claude := mustClientS(t, "claude-code")
	path := filepath.Join(t.TempDir(), "claude.json")
	hh := McpSessionHeaderCommandGlobal("/usr/local/bin/agent-memory")
	if _, err := McpProfile(path, claude.MCP, "http://127.0.0.1:8080", "secret", hh); err != nil {
		t.Fatal(err)
	}
	entry := readJSON(t, path)["mcpServers"].(map[string]any)[McpServerName].(map[string]any)
	got, _ := entry["headersHelper"].(string)
	if got != `"/usr/local/bin/agent-memory" mcp-session-header` {
		t.Fatalf("headersHelper = %q, want the no-flag global command", got)
	}
	if strings.Contains(got, "--workspace") {
		t.Errorf("user-scope headersHelper leaked a baked workspace: %q", got)
	}
}

func mustClientS(t *testing.T, id string) *Client {
	t.Helper()
	c, err := LookupClient(id)
	if err != nil {
		t.Fatalf("LookupClient(%q): %v", id, err)
	}
	return c
}
