package install

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const goldenBin = "/usr/local/bin/agent-memory"
const goldenServer = "http://127.0.0.1:8080"
const goldenToken = "secret"
const goldenHH = `"/usr/local/bin/agent-memory" mcp-session-header --workspace acme --project alpha`

// --- Client lookup -------------------------------------------------------------------------------

func TestLookupClientByIDAliasAndDefault(t *testing.T) {
	if c, err := LookupClient(""); err != nil || c.ID != DefaultClientID {
		t.Fatalf("empty → default: c=%v err=%v", c, err)
	}
	for _, in := range []string{"claude-code", "Claude-Code", "claude", "CC", "claudecode"} {
		if c, err := LookupClient(in); err != nil || c.ID != "claude-code" {
			t.Errorf("LookupClient(%q) → %v, %v", in, c, err)
		}
	}
	for _, in := range []string{"gemini", "gemini-cli", "GEMINI"} {
		if c, err := LookupClient(in); err != nil || c.ID != "gemini-cli" {
			t.Errorf("LookupClient(%q) → %v, %v", in, c, err)
		}
	}
	if _, err := LookupClient("nope"); err == nil {
		t.Errorf("unknown client must error")
	} else if !strings.Contains(err.Error(), "claude-code") {
		t.Errorf("error should list supported ids, got %v", err)
	}
}

func TestClientIDsCoversTargets(t *testing.T) {
	want := []string{"claude-code", "codex", "cursor", "gemini-cli", "vscode-copilot", "claude-desktop"}
	got := strings.Join(ClientIDs(), ",")
	for _, w := range want {
		if !strings.Contains(got, w) {
			t.Errorf("ClientIDs missing %q (got %s)", w, got)
		}
	}
}

// --- MCP golden + idempotency + uninstall, per client --------------------------------------------

func mustClient(t *testing.T, id string) *Client {
	t.Helper()
	c, err := LookupClient(id)
	if err != nil {
		t.Fatalf("LookupClient(%q): %v", id, err)
	}
	return c
}

func TestMCPGoldenPerClient(t *testing.T) {
	cases := []struct {
		id     string
		ext    string
		hh     string // headersHelper; only claude-code uses it
		golden string
	}{
		{
			id:  "claude-code",
			ext: ".json",
			hh:  goldenHH,
			golden: `{
  "mcpServers": {
    "agent-memory": {
      "headers": {
        "Authorization": "Bearer secret"
      },
      "headersHelper": "\"/usr/local/bin/agent-memory\" mcp-session-header --workspace acme --project alpha",
      "type": "http",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
`,
		},
		{
			id:  "codex",
			ext: ".toml",
			golden: `[mcp_servers]
  [mcp_servers.agent-memory]
    bearer_token = "secret"
    url = "http://127.0.0.1:8080/mcp"
`,
		},
		{
			id:  "cursor",
			ext: ".json",
			golden: `{
  "mcpServers": {
    "agent-memory": {
      "headers": {
        "Authorization": "Bearer secret"
      },
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
`,
		},
		{
			id:  "gemini-cli",
			ext: ".json",
			golden: `{
  "mcpServers": {
    "agent-memory": {
      "headers": {
        "Authorization": "Bearer secret"
      },
      "httpUrl": "http://127.0.0.1:8080/mcp"
    }
  }
}
`,
		},
		{
			id:  "vscode-copilot",
			ext: ".json",
			golden: `{
  "servers": {
    "agent-memory": {
      "headers": {
        "Authorization": "Bearer secret"
      },
      "type": "http",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
`,
		},
		{
			id:  "claude-desktop",
			ext: ".json",
			golden: `{
  "mcpServers": {
    "agent-memory": {
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:8080/mcp",
        "--header",
        "Authorization: Bearer secret"
      ],
      "command": "npx"
    }
  }
}
`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.id, func(t *testing.T) {
			c := mustClient(t, tc.id)
			path := filepath.Join(t.TempDir(), "mcp"+tc.ext)

			ch, err := McpProfile(path, c.MCP, goldenServer, goldenToken, tc.hh)
			if err != nil || ch != Created {
				t.Fatalf("install: change=%v err=%v", ch, err)
			}
			got, _ := os.ReadFile(path)
			if string(got) != tc.golden {
				t.Fatalf("golden mismatch for %s:\n--- got ---\n%s\n--- want ---\n%s", tc.id, got, tc.golden)
			}

			// Idempotent: a second identical render reports Unchanged and leaves the bytes untouched.
			if ch2, err := McpProfile(path, c.MCP, goldenServer, goldenToken, tc.hh); err != nil || ch2 != Unchanged {
				t.Fatalf("re-install: change=%v err=%v", ch2, err)
			}
			got2, _ := os.ReadFile(path)
			if string(got2) != tc.golden {
				t.Fatalf("idempotent render changed bytes for %s", tc.id)
			}

			// Uninstall removes our entry; a second uninstall is Absent.
			if ch, err := UninstallMcpProfile(path, c.MCP); err != nil || ch != Removed {
				t.Fatalf("uninstall: change=%v err=%v", ch, err)
			}
			if ch, _ := UninstallMcpProfile(path, c.MCP); ch != Absent {
				t.Fatalf("second uninstall: change=%v, want Absent", ch)
			}
			rest, _ := os.ReadFile(path)
			if strings.Contains(string(rest), McpServerName) {
				t.Fatalf("entry still present after uninstall for %s:\n%s", tc.id, rest)
			}
		})
	}
}

// --- MCP keyless (no token) omits the auth header / bearer_token ----------------------------------

func TestMCPKeylessOmitsAuth(t *testing.T) {
	for _, id := range []string{"claude-code", "codex", "cursor", "gemini-cli", "vscode-copilot", "claude-desktop"} {
		c := mustClient(t, id)
		ext := ".json"
		if c.MCP.Shape == MCPShapeCodexTOML {
			ext = ".toml"
		}
		path := filepath.Join(t.TempDir(), "mcp"+ext)
		if _, err := McpProfile(path, c.MCP, goldenServer, "", ""); err != nil {
			t.Fatalf("%s: %v", id, err)
		}
		data, _ := os.ReadFile(path)
		s := string(data)
		if strings.Contains(s, "Bearer") || strings.Contains(s, "bearer_token") || strings.Contains(s, "Authorization") {
			t.Errorf("%s keyless render leaked auth:\n%s", id, s)
		}
		if !strings.Contains(s, "/mcp") {
			t.Errorf("%s render missing endpoint:\n%s", id, s)
		}
	}
}

// --- Hook golden (the novel shapes: Cursor flat, Gemini mapped events) ----------------------------

func TestCursorFlatHooksGoldenAndIdempotent(t *testing.T) {
	c := mustClient(t, "cursor")
	path := filepath.Join(t.TempDir(), "hooks.json")
	golden := `{
  "hooks": {
    "postToolUse": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event PostToolUse"
      }
    ],
    "preToolUse": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event PreToolUse"
      }
    ],
    "sessionEnd": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event SessionEnd"
      }
    ],
    "sessionStart": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event SessionStart"
      }
    ],
    "stop": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event Stop"
      }
    ],
    "userPromptSubmit": [
      {
        "command": "\"/usr/local/bin/agent-memory\" hook --event UserPromptSubmit"
      }
    ]
  },
  "version": 1
}
`
	ch, err := HooksProfile(path, c.Hooks, goldenBin)
	if err != nil || ch != Created {
		t.Fatalf("install: change=%v err=%v", ch, err)
	}
	got, _ := os.ReadFile(path)
	if string(got) != golden {
		t.Fatalf("cursor hooks golden mismatch:\n--- got ---\n%s\n--- want ---\n%s", got, golden)
	}
	if ch2, err := HooksProfile(path, c.Hooks, goldenBin); err != nil || ch2 != Unchanged {
		t.Fatalf("re-install: change=%v err=%v", ch2, err)
	}
	if ch, err := UninstallHooksProfile(path, c.Hooks); err != nil || ch != Removed {
		t.Fatalf("uninstall: change=%v err=%v", ch, err)
	}
	got2, _ := os.ReadFile(path)
	if strings.Contains(string(got2), "hook --event") {
		t.Fatalf("managed hooks not removed:\n%s", got2)
	}
}

func TestGeminiMappedHooksGolden(t *testing.T) {
	c := mustClient(t, "gemini-cli")
	path := filepath.Join(t.TempDir(), "settings.json")
	golden := `{
  "hooks": {
    "AfterTool": [
      {
        "hooks": [
          {
            "command": "\"/usr/local/bin/agent-memory\" hook --event PostToolUse",
            "type": "command"
          }
        ],
        "matcher": ""
      }
    ],
    "BeforeTool": [
      {
        "hooks": [
          {
            "command": "\"/usr/local/bin/agent-memory\" hook --event PreToolUse",
            "type": "command"
          }
        ],
        "matcher": ""
      }
    ],
    "PreCompress": [
      {
        "hooks": [
          {
            "command": "\"/usr/local/bin/agent-memory\" hook --event PreCompact",
            "type": "command"
          }
        ],
        "matcher": ""
      }
    ]
  }
}
`
	if ch, err := HooksProfile(path, c.Hooks, goldenBin); err != nil || ch != Created {
		t.Fatalf("install: change=%v err=%v", ch, err)
	}
	got, _ := os.ReadFile(path)
	if string(got) != golden {
		t.Fatalf("gemini hooks golden mismatch:\n--- got ---\n%s\n--- want ---\n%s", got, golden)
	}
}

// --- Foreign-config preservation for the new shapes ----------------------------------------------

func TestCursorFlatHooksPreservesForeign(t *testing.T) {
	c := mustClient(t, "cursor")
	path := filepath.Join(t.TempDir(), "hooks.json")
	foreign := `{"version":1,"hooks":{"sessionStart":[{"command":"other-tool start"}]}}`
	if err := os.WriteFile(path, []byte(foreign), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := HooksProfile(path, c.Hooks, goldenBin); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, "other-tool start") {
		t.Errorf("foreign flat hook lost:\n%s", s)
	}
	if !strings.Contains(s, "hook --event SessionStart") {
		t.Errorf("our hook not added:\n%s", s)
	}
	// Uninstall must leave the foreign hook intact.
	if _, err := UninstallHooksProfile(path, c.Hooks); err != nil {
		t.Fatal(err)
	}
	data2, _ := os.ReadFile(path)
	if !strings.Contains(string(data2), "other-tool start") {
		t.Errorf("foreign flat hook lost on uninstall:\n%s", data2)
	}
	if strings.Contains(string(data2), "hook --event") {
		t.Errorf("our hooks not removed:\n%s", data2)
	}
}

func TestVSCodePreservesForeignServers(t *testing.T) {
	c := mustClient(t, "vscode-copilot")
	path := filepath.Join(t.TempDir(), "mcp.json")
	if err := os.WriteFile(path,
		[]byte(`{"servers":{"other":{"type":"stdio","command":"x"}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := McpProfile(path, c.MCP, goldenServer, goldenToken, ""); err != nil {
		t.Fatal(err)
	}
	servers := readJSON(t, path)["servers"].(map[string]any)
	if _, ok := servers["other"]; !ok {
		t.Errorf("foreign server lost: %v", servers)
	}
	if _, ok := servers[McpServerName]; !ok {
		t.Errorf("our server missing: %v", servers)
	}
	if _, err := UninstallMcpProfile(path, c.MCP); err != nil {
		t.Fatal(err)
	}
	if _, ok := readJSON(t, path)["servers"].(map[string]any)["other"]; !ok {
		t.Errorf("foreign server lost on uninstall")
	}
}

func TestCodexTOMLPreservesForeignTable(t *testing.T) {
	c := mustClient(t, "codex")
	path := filepath.Join(t.TempDir(), "config.toml")
	foreign := "model = \"o4\"\n\n[mcp_servers.other]\ncommand = \"x\"\n"
	if err := os.WriteFile(path, []byte(foreign), 0o644); err != nil {
		t.Fatal(err)
	}
	if ch, err := McpProfile(path, c.MCP, goldenServer, goldenToken, ""); err != nil || ch != Created {
		t.Fatalf("install: change=%v err=%v", ch, err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, "model = \"o4\"") {
		t.Errorf("foreign top-level key lost:\n%s", s)
	}
	if !strings.Contains(s, "[mcp_servers.other]") {
		t.Errorf("foreign server table lost:\n%s", s)
	}
	if !strings.Contains(s, "[mcp_servers.agent-memory]") {
		t.Errorf("our server table missing:\n%s", s)
	}
	// Idempotent now that our entry is present.
	if ch, err := McpProfile(path, c.MCP, goldenServer, goldenToken, ""); err != nil || ch != Unchanged {
		t.Fatalf("re-install: change=%v err=%v", ch, err)
	}
	// Uninstall keeps the foreign table.
	if ch, err := UninstallMcpProfile(path, c.MCP); err != nil || ch != Removed {
		t.Fatalf("uninstall: change=%v err=%v", ch, err)
	}
	data2, _ := os.ReadFile(path)
	if !strings.Contains(string(data2), "[mcp_servers.other]") {
		t.Errorf("foreign table lost on uninstall:\n%s", data2)
	}
	if strings.Contains(string(data2), "agent-memory") {
		t.Errorf("our table not removed:\n%s", data2)
	}
}

// TestGeminiHooksAndMCPShareOneFile verifies the two surfaces that both target .gemini/settings.json
// compose: installing hooks then MCP leaves both keys present, and uninstalling both cleans up without
// clobbering the other.
func TestGeminiHooksAndMCPShareOneFile(t *testing.T) {
	c := mustClient(t, "gemini-cli")
	path := filepath.Join(t.TempDir(), "settings.json")

	if _, err := HooksProfile(path, c.Hooks, goldenBin); err != nil {
		t.Fatal(err)
	}
	if _, err := McpProfile(path, c.MCP, goldenServer, goldenToken, ""); err != nil {
		t.Fatal(err)
	}
	m := readJSON(t, path)
	if _, ok := m["hooks"]; !ok {
		t.Errorf("hooks key missing after MCP install (clobbered):\n%v", m)
	}
	if _, ok := m["mcpServers"]; !ok {
		t.Errorf("mcpServers key missing:\n%v", m)
	}

	// Re-running each is Unchanged (idempotent on the shared file).
	if ch, _ := HooksProfile(path, c.Hooks, goldenBin); ch != Unchanged {
		t.Errorf("hooks re-install: %v", ch)
	}
	if ch, _ := McpProfile(path, c.MCP, goldenServer, goldenToken, ""); ch != Unchanged {
		t.Errorf("mcp re-install: %v", ch)
	}

	// Uninstalling MCP leaves hooks intact and vice-versa.
	if _, err := UninstallMcpProfile(path, c.MCP); err != nil {
		t.Fatal(err)
	}
	if _, ok := readJSON(t, path)["hooks"]; !ok {
		t.Errorf("hooks lost when uninstalling MCP from shared file")
	}
	if _, err := UninstallHooksProfile(path, c.Hooks); err != nil {
		t.Fatal(err)
	}
	final, _ := os.ReadFile(path)
	if strings.Contains(string(final), "hook --event") || strings.Contains(string(final), McpServerName) {
		t.Errorf("shared file not clean after both uninstalls:\n%s", final)
	}
}

// TestClaudeDesktopConfigPathPerOS sanity-checks the platform path resolution.
func TestClaudeDesktopConfigPathPerOS(t *testing.T) {
	got := claudeDesktopConfigPath(PathContext{Home: filepath.FromSlash("/home/u")})
	if !strings.HasSuffix(got, "claude_desktop_config.json") {
		t.Errorf("unexpected claude desktop path: %s", got)
	}
	if !strings.Contains(got, "Claude") {
		t.Errorf("path should be under a Claude dir: %s", got)
	}
}
