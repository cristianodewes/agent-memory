package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// runCLI executes the real root command with args, capturing combined stdout+stderr.
func runCLI(t *testing.T, args ...string) (string, error) {
	t.Helper()
	root := newRootCmd()
	root.SetArgs(args)
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	err := root.Execute()
	return out.String(), err
}

// TestSetupAgentEndToEndIdempotentThenUninstall drives the real setup-agent command against a temp
// project: it writes .claude/settings.json, .mcp.json and CLAUDE.md; a second run is fully Unchanged;
// uninstall removes all three managed surfaces.
func TestSetupAgentEndToEndIdempotentThenUninstall(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "agent-memory")
	args := []string{
		"setup-agent", "--dir", dir, "--bin", bin,
		"--server-url", "http://127.0.0.1:8080", "--file", "CLAUDE.md",
	}

	out, err := runCLI(t, args...)
	if err != nil {
		t.Fatalf("setup-agent failed: %v\n%s", err, out)
	}
	for _, f := range []string{
		filepath.Join(dir, ".claude", "settings.json"),
		filepath.Join(dir, ".mcp.json"),
		filepath.Join(dir, "CLAUDE.md"),
	} {
		if _, err := os.Stat(f); err != nil {
			t.Errorf("expected %s to exist: %v", f, err)
		}
	}
	for _, want := range []string{"hooks: created", "mcp: created", "instructions: created"} {
		if !strings.Contains(out, want) {
			t.Errorf("first-run output missing %q:\n%s", want, out)
		}
	}
	settings, _ := os.ReadFile(filepath.Join(dir, ".claude", "settings.json"))
	if !strings.Contains(string(settings), "hook --event UserPromptSubmit") {
		t.Errorf("UserPromptSubmit hook not wired:\n%s", settings)
	}
	mcp, _ := os.ReadFile(filepath.Join(dir, ".mcp.json"))
	if !strings.Contains(string(mcp), "http://127.0.0.1:8080/mcp") {
		t.Errorf("MCP url not wired:\n%s", mcp)
	}

	out2, err := runCLI(t, args...)
	if err != nil {
		t.Fatalf("second setup-agent: %v\n%s", err, out2)
	}
	for _, want := range []string{"hooks: unchanged", "mcp: unchanged", "instructions: unchanged"} {
		if !strings.Contains(out2, want) {
			t.Errorf("second run not fully unchanged, missing %q:\n%s", want, out2)
		}
	}

	out3, err := runCLI(t, "uninstall", "--dir", dir, "--file", "CLAUDE.md")
	if err != nil {
		t.Fatalf("uninstall: %v\n%s", err, out3)
	}
	for _, want := range []string{"hooks: removed", "mcp: removed", "instructions: removed"} {
		if !strings.Contains(out3, want) {
			t.Errorf("uninstall output missing %q:\n%s", want, out3)
		}
	}
	settings2, _ := os.ReadFile(filepath.Join(dir, ".claude", "settings.json"))
	if strings.Contains(string(settings2), "hook --event") {
		t.Errorf("managed hooks not removed:\n%s", settings2)
	}
	if _, err := os.Stat(filepath.Join(dir, "CLAUDE.md")); !os.IsNotExist(err) {
		t.Errorf("CLAUDE.md (block-only) should be removed, stat err=%v", err)
	}
}

// TestInstallHooksCommandIdempotent exercises the standalone install-hooks command's reporting.
func TestInstallHooksCommandIdempotent(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "agent-memory")
	args := []string{"install-hooks", "--dir", dir, "--bin", bin}

	out, err := runCLI(t, args...)
	if err != nil || !strings.Contains(out, "hooks: created") {
		t.Fatalf("install-hooks first run: out=%q err=%v", out, err)
	}
	out2, err := runCLI(t, args...)
	if err != nil || !strings.Contains(out2, "hooks: unchanged") {
		t.Fatalf("install-hooks second run: out=%q err=%v", out2, err)
	}
}
