package install

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// This file defines the multi-agent "client profile" (issue #115): the per-client knowledge an
// installer needs — where each surface's config file lives, and which on-disk SHAPE its hooks and MCP
// entry take. The native runtime does NOT change across clients: every wired hook still invokes the
// same `agent-memory hook --event <canonical>` binary, and every MCP entry still points at the same
// Streamable-HTTP `/mcp` endpoint with a Bearer token. Only the surrounding config schema differs, and
// that difference is captured here so the writers in hooks.go / mcp.go can render any client from one
// profile.
//
// The default client is claude-code; its profile reproduces the exact pre-#115 behavior
// (`<root>/.claude/settings.json`, `<root>/.mcp.json`, `<root>/CLAUDE.md`), so an install with no
// `--agent` flag is byte-for-byte unchanged.

// PathContext carries the roots an installer resolves a client's config paths against. ProjectRoot is
// the repository root (or cwd) used for project-scoped clients; Home is the user's home directory used
// for the clients whose config is conventionally global (codex, cursor, gemini-cli, claude-desktop).
// The user/project SCOPE toggle (issue #116) layers on top of this by parametrizing which root a
// project-capable client like claude-code resolves against.
type PathContext struct {
	ProjectRoot string
	Home        string
}

// HookShape is the on-disk structure a client's hooks file uses.
type HookShape int

const (
	// HookShapeNested is the Claude Code shape: hooks.<Event> is an array of groups, each
	// {matcher, hooks:[{type:"command", command}]}. Gemini CLI uses the same nesting with its own
	// event names.
	HookShapeNested HookShape = iota
	// HookShapeFlat is the Cursor shape: hooks.<event> is a flat array of {command} entries (no matcher
	// or per-entry type wrapper), alongside a top-level "version": 1.
	HookShapeFlat
)

// MCPShape is the on-disk structure a client's MCP registration uses. All shapes encode the SAME
// streamable-HTTP server at <serverURL>/mcp with a Bearer token; they differ only in the surrounding
// keys/format each client expects.
type MCPShape int

const (
	// MCPShapeClaudeHTTP — mcpServers.<name> = {type:"http", url, headers, headersHelper}. The only
	// shape that carries the per-session headersHelper (#87). Used by Claude Code.
	MCPShapeClaudeHTTP MCPShape = iota
	// MCPShapeURLHeaders — mcpServers.<name> = {url, headers}. Cursor infers the HTTP transport from the
	// url key and has no headersHelper.
	MCPShapeURLHeaders
	// MCPShapeGeminiHTTP — mcpServers.<name> = {httpUrl, headers}. Gemini CLI names the streamable-HTTP
	// endpoint httpUrl.
	MCPShapeGeminiHTTP
	// MCPShapeVSCodeServers — servers.<name> = {type:"http", url, headers}. VS Code's mcp.json uses the
	// top-level "servers" key (not "mcpServers").
	MCPShapeVSCodeServers
	// MCPShapeRemoteStdio — mcpServers.<name> = {command:"npx", args:[-y, mcp-remote, url, --header, ...]}.
	// Claude Desktop speaks stdio only, so it bridges to the HTTP server through mcp-remote.
	MCPShapeRemoteStdio
	// MCPShapeCodexTOML — [mcp_servers.<name>] with url + bearer_token, written to a TOML config. Used by
	// the OpenAI Codex CLI.
	MCPShapeCodexTOML
)

// hookEvent pairs the event NAME a client keys its hook config under with the canonical --event value
// the native binary is invoked with. The client fires on its own lifecycle name (clientName); the
// command always passes the canonical kind, which `agent-memory hook` canonicalizes (internal/hook),
// so attribution is identical regardless of client.
type hookEvent struct {
	clientName string // key in the client's hooks file (e.g. "BeforeTool", "preToolUse", "SessionStart")
	eventArg   string // canonical value passed as `hook --event <eventArg>`
}

// HookProfile describes a client's hook surface: where the file lives, its shape, and the event map.
type HookProfile struct {
	Shape  HookShape
	Events []hookEvent
	// Path resolves the absolute hooks file for ctx.
	Path func(ctx PathContext) string
}

// MCPProfile describes a client's MCP surface: where the file lives and its shape.
type MCPProfile struct {
	Shape MCPShape
	// Path resolves the absolute MCP config file for ctx.
	Path func(ctx PathContext) string
}

// Client is one agent's complete install profile. A nil Hooks/MCP, or an empty InstrFile name, means
// the client does not support that surface; the installer reports it as Unsupported rather than failing.
type Client struct {
	ID      string   // canonical id, e.g. "claude-code"
	Name    string   // human label, e.g. "Claude Code"
	Aliases []string // additional accepted ids, e.g. "claude", "cc"

	Hooks *HookProfile // nil when the client has no hook surface
	MCP   *MCPProfile  // nil when the client has no MCP surface

	// InstrFile is the agent-instructions filename the self-routing block is written into (e.g.
	// "CLAUDE.md", "AGENTS.md", "GEMINI.md"), resolved at the project root. Empty when unsupported.
	InstrFile string
}

// InstrPath returns the absolute instructions file path for ctx, or ("", false) when the client has no
// instructions surface.
func (c *Client) InstrPath(ctx PathContext) (string, bool) {
	if c.InstrFile == "" {
		return "", false
	}
	return filepath.Join(ctx.ProjectRoot, c.InstrFile), true
}

// DefaultClientID is the client an install command targets when no --agent/--client flag is given. It
// MUST stay claude-code so the historical behavior is the default.
const DefaultClientID = "claude-code"

// claudeCodeHookEvents are the six Claude Code lifecycle events agent-memory wires (the canonical set;
// client name == canonical --event value).
var claudeCodeHookEvents = func() []hookEvent {
	evs := make([]hookEvent, len(ManagedHookEvents))
	for i, e := range ManagedHookEvents {
		evs[i] = hookEvent{clientName: e, eventArg: e}
	}
	return evs
}()

// cursorHookEvents are Cursor's camelCase lifecycle names mapped to the canonical --event values.
var cursorHookEvents = []hookEvent{
	{"sessionStart", "SessionStart"},
	{"userPromptSubmit", "UserPromptSubmit"},
	{"preToolUse", "PreToolUse"},
	{"postToolUse", "PostToolUse"},
	{"stop", "Stop"},
	{"sessionEnd", "SessionEnd"},
}

// geminiHookEvents are Gemini CLI's event names mapped to the canonical --event values. Gemini exposes
// tool and compaction lifecycle moments under BeforeTool/AfterTool/PreCompress.
var geminiHookEvents = []hookEvent{
	{"BeforeTool", "PreToolUse"},
	{"AfterTool", "PostToolUse"},
	{"PreCompress", "PreCompact"},
}

// clients is the registry of supported install profiles. Order is the listing order in --help.
var clients = []*Client{
	{
		ID:      "claude-code",
		Name:    "Claude Code",
		Aliases: []string{"claude", "cc", "claudecode"},
		Hooks: &HookProfile{
			Shape:  HookShapeNested,
			Events: claudeCodeHookEvents,
			Path:   func(ctx PathContext) string { return SettingsPath(ctx.ProjectRoot) },
		},
		MCP: &MCPProfile{
			Shape: MCPShapeClaudeHTTP,
			Path:  func(ctx PathContext) string { return McpPath(ctx.ProjectRoot) },
		},
		InstrFile: "CLAUDE.md",
	},
	{
		ID:      "codex",
		Name:    "OpenAI Codex CLI",
		Aliases: []string{"openai-codex"},
		// Codex has no lifecycle-hook surface; only MCP + AGENTS.md instructions.
		MCP: &MCPProfile{
			Shape: MCPShapeCodexTOML,
			Path:  func(ctx PathContext) string { return filepath.Join(ctx.Home, ".codex", "config.toml") },
		},
		InstrFile: "AGENTS.md",
	},
	{
		ID:      "cursor",
		Name:    "Cursor",
		Aliases: nil,
		Hooks: &HookProfile{
			Shape:  HookShapeFlat,
			Events: cursorHookEvents,
			Path:   func(ctx PathContext) string { return filepath.Join(ctx.Home, ".cursor", "hooks.json") },
		},
		MCP: &MCPProfile{
			Shape: MCPShapeURLHeaders,
			Path:  func(ctx PathContext) string { return filepath.Join(ctx.Home, ".cursor", "mcp.json") },
		},
		InstrFile: "AGENTS.md",
	},
	{
		ID:      "gemini-cli",
		Name:    "Gemini CLI",
		Aliases: []string{"gemini"},
		Hooks: &HookProfile{
			Shape:  HookShapeNested,
			Events: geminiHookEvents,
			Path:   func(ctx PathContext) string { return geminiSettingsPath(ctx) },
		},
		MCP: &MCPProfile{
			Shape: MCPShapeGeminiHTTP,
			Path:  func(ctx PathContext) string { return geminiSettingsPath(ctx) },
		},
		InstrFile: "GEMINI.md",
	},
	{
		ID:      "vscode-copilot",
		Name:    "VS Code (Copilot)",
		Aliases: []string{"vscode", "copilot"},
		// VS Code wires MCP only; hooks and self-routing instructions are not part of its model here.
		MCP: &MCPProfile{
			Shape: MCPShapeVSCodeServers,
			Path:  func(ctx PathContext) string { return filepath.Join(ctx.ProjectRoot, ".vscode", "mcp.json") },
		},
	},
	{
		ID:      "claude-desktop",
		Name:    "Claude Desktop",
		Aliases: []string{"claude-app", "desktop"},
		MCP: &MCPProfile{
			Shape: MCPShapeRemoteStdio,
			Path:  func(ctx PathContext) string { return claudeDesktopConfigPath(ctx) },
		},
	},
}

// geminiSettingsPath is the single settings.json Gemini CLI reads for both hooks and MCP.
func geminiSettingsPath(ctx PathContext) string {
	return filepath.Join(ctx.Home, ".gemini", "settings.json")
}

// claudeDesktopConfigPath resolves Claude Desktop's per-OS config file. Claude Desktop stores its
// config globally (there is no project variant), so it is keyed off Home / the platform app-data dir:
//   - Windows: %APPDATA%\Claude\claude_desktop_config.json
//   - macOS:   ~/Library/Application Support/Claude/claude_desktop_config.json
//   - Linux:   ~/.config/Claude/claude_desktop_config.json
func claudeDesktopConfigPath(ctx PathContext) string {
	const file = "claude_desktop_config.json"
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("APPDATA")
		if base == "" {
			base = filepath.Join(ctx.Home, "AppData", "Roaming")
		}
		return filepath.Join(base, "Claude", file)
	case "darwin":
		return filepath.Join(ctx.Home, "Library", "Application Support", "Claude", file)
	default:
		base := os.Getenv("XDG_CONFIG_HOME")
		if base == "" {
			base = filepath.Join(ctx.Home, ".config")
		}
		return filepath.Join(base, "Claude", file)
	}
}

// LookupClient resolves an --agent/--client value (case-insensitive, id or alias) to its profile. An
// empty value resolves to the default client. An unknown value is an error listing the supported ids.
func LookupClient(idOrAlias string) (*Client, error) {
	want := strings.ToLower(strings.TrimSpace(idOrAlias))
	if want == "" {
		want = DefaultClientID
	}
	for _, c := range clients {
		if strings.ToLower(c.ID) == want {
			return c, nil
		}
		for _, a := range c.Aliases {
			if strings.ToLower(a) == want {
				return c, nil
			}
		}
	}
	return nil, fmt.Errorf("unknown agent/client %q (supported: %s)", idOrAlias, strings.Join(ClientIDs(), ", "))
}

// ClientIDs returns the canonical client ids in registry order, for help text and error messages.
func ClientIDs() []string {
	ids := make([]string, len(clients))
	for i, c := range clients {
		ids[i] = c.ID
	}
	return ids
}

// ResolveHome returns the user's home directory for path resolution, or "" when it cannot be
// determined (rare/sandboxed) — callers targeting a home-based client surface should treat "" as an
// actionable error rather than writing to a bogus path.
func ResolveHome() string {
	if home, err := os.UserHomeDir(); err == nil {
		return home
	}
	return ""
}
