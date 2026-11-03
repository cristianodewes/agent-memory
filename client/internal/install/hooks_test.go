package install

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// readJSON loads a JSON object file for assertions (shared by the hooks and mcp tests).
func readJSON(t *testing.T, path string) map[string]any {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("invalid JSON in %s: %v\n%s", path, err, data)
	}
	return m
}

func TestInstallHooksCreatesAllEventsThenIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	bin := "/usr/local/bin/agent-memory"

	ch, err := Hooks(path, bin)
	if err != nil || ch != Created {
		t.Fatalf("install: change=%v err=%v", ch, err)
	}
	m := readJSON(t, path)
	hooks, ok := m["hooks"].(map[string]any)
	if !ok {
		t.Fatalf("no hooks map: %v", m)
	}
	for _, e := range ManagedHookEvents {
		if _, ok := hooks[e]; !ok {
			t.Errorf("event %s not registered", e)
		}
	}
	// The parsed command carries the (double-quoted) binary path; check the decoded value, not the
	// raw JSON bytes (which escape the quotes).
	ss := hooks["SessionStart"].([]any)
	cmd := ss[0].(map[string]any)["hooks"].([]any)[0].(map[string]any)["command"]
	if want := `"` + bin + `" hook --event SessionStart`; cmd != want {
		t.Errorf("hook command = %v, want %q", cmd, want)
	}

	ch2, err := Hooks(path, bin)
	if err != nil || ch2 != Unchanged {
		t.Fatalf("re-install: change=%v err=%v", ch2, err)
	}
}

func TestInstallHooksPreservesForeignHooksAndKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	foreign := `{
  "model": "claude-opus-4-8",
  "hooks": {
    "SessionStart": [
      {"matcher": "", "hooks": [{"type": "command", "command": "other-tool init"}]}
    ]
  }
}`
	if err := os.WriteFile(path, []byte(foreign), 0o644); err != nil {
		t.Fatal(err)
	}

	if _, err := Hooks(path, "/bin/agent-memory"); err != nil {
		t.Fatal(err)
	}
	m := readJSON(t, path)
	if m["model"] != "claude-opus-4-8" {
		t.Errorf("foreign top-level key lost: %v", m["model"])
	}
	data, _ := os.ReadFile(path)
	if !strings.Contains(string(data), "other-tool init") {
		t.Errorf("foreign hook lost:\n%s", data)
	}
	if !strings.Contains(string(data), "hook --event SessionStart") {
		t.Errorf("our hook not added:\n%s", data)
	}

	if ch, err := UninstallHooks(path); err != nil || ch != Removed {
		t.Fatalf("uninstall: change=%v err=%v", ch, err)
	}
	data2, _ := os.ReadFile(path)
	if !strings.Contains(string(data2), "other-tool init") {
		t.Errorf("foreign hook lost on uninstall:\n%s", data2)
	}
	if strings.Contains(string(data2), "hook --event") {
		t.Errorf("our hooks not removed:\n%s", data2)
	}
}

func TestInstallHooksUpgradeUpdatesBinaryPathNoDuplicate(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	if _, err := Hooks(path, "/old/agent-memory"); err != nil {
		t.Fatal(err)
	}
	ch, err := Hooks(path, "/new/agent-memory")
	if err != nil || ch != Updated {
		t.Fatalf("upgrade: change=%v err=%v", ch, err)
	}
	data, _ := os.ReadFile(path)
	if strings.Contains(string(data), "/old/agent-memory") {
		t.Errorf("old path not replaced:\n%s", data)
	}
	if !strings.Contains(string(data), "/new/agent-memory") {
		t.Errorf("new path missing:\n%s", data)
	}
	m := readJSON(t, path)
	ss := m["hooks"].(map[string]any)["SessionStart"].([]any)
	if len(ss) != 1 {
		t.Errorf("expected exactly 1 SessionStart group after upgrade, got %d", len(ss))
	}
}

// innerHook returns the first inner hook object of the first group registered under event (nested
// shape), failing the test when the structure is not there.
func innerHook(t *testing.T, path, event string) map[string]any {
	t.Helper()
	groups, ok := readJSON(t, path)["hooks"].(map[string]any)[event].([]any)
	if !ok || len(groups) == 0 {
		t.Fatalf("no %s hook group in %s", event, path)
	}
	inner, ok := groups[0].(map[string]any)["hooks"].([]any)
	if !ok || len(inner) == 0 {
		t.Fatalf("no inner hook under %s", event)
	}
	return inner[0].(map[string]any)
}

func TestInstallHooksClaudeSessionEndIsAsync(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	if _, err := Hooks(path, "/bin/agent-memory"); err != nil {
		t.Fatal(err)
	}

	if got := innerHook(t, path, "SessionEnd")["async"]; got != true {
		t.Errorf("SessionEnd async = %v, want true", got)
	}
	// SessionStart and UserPromptSubmit inject context through their stdout, which an async hook loses;
	// every other event stays synchronous too.
	for _, ev := range []string{"SessionStart", "UserPromptSubmit", "PreToolUse", "Stop"} {
		if got, ok := innerHook(t, path, ev)["async"]; ok {
			t.Errorf("%s async = %v, want the key absent", ev, got)
		}
	}
}

func TestInstallHooksUpgradeAddsAsyncToExistingSessionEnd(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	// A settings file written by an installer from before the async SessionEnd: all seven events, none
	// with "async". Without the async SessionEnd, re-installing over it would be Unchanged.
	var groups []string
	for _, ev := range []string{"SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse",
		"PreCompact", "Stop", "SessionEnd"} {
		groups = append(groups, `"`+ev+`": [{"matcher": "", "hooks": [`+
			`{"type": "command", "command": "\"/bin/agent-memory\" hook --event `+ev+`"}]}]`)
	}
	old := `{"hooks": {` + strings.Join(groups, ", ") + `}}`
	if err := os.WriteFile(path, []byte(old), 0o644); err != nil {
		t.Fatal(err)
	}

	ch, err := Hooks(path, "/bin/agent-memory")
	if err != nil || ch != Updated {
		t.Fatalf("upgrade: change=%v err=%v, want updated", ch, err)
	}
	if got := innerHook(t, path, "SessionEnd")["async"]; got != true {
		t.Errorf("SessionEnd async after upgrade = %v, want true", got)
	}
}

func TestInstallHooksGeminiSessionEndStaysSync(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".gemini", "settings.json")
	profile := &HookProfile{Shape: HookShapeNested, Events: geminiHookEvents}
	if _, err := HooksProfile(path, profile, "/bin/agent-memory"); err != nil {
		t.Fatal(err)
	}

	// "async" is a Claude Code hook option; Gemini CLI's hook schema does not define it.
	if got, ok := innerHook(t, path, "SessionEnd")["async"]; ok {
		t.Errorf("Gemini SessionEnd async = %v, want the key absent", got)
	}
}

func TestUninstallHooksAbsentWhenNothingOurs(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".claude", "settings.json")
	if ch, _ := UninstallHooks(path); ch != Absent {
		t.Errorf("missing file should be Absent, got %v", ch)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	foreign := `{"hooks":{"Stop":[{"matcher":"","hooks":[{"type":"command","command":"foo"}]}]}}`
	if err := os.WriteFile(path, []byte(foreign), 0o644); err != nil {
		t.Fatal(err)
	}
	if ch, _ := UninstallHooks(path); ch != Absent {
		t.Errorf("no managed entries should be Absent, got %v", ch)
	}
}
