package install

import (
	"bytes"
	"strings"
)

// McpServerName is the key agent-memory registers itself under in .mcp.json's mcpServers map.
const McpServerName = "agent-memory"

// McpSessionHeaderCommand builds the `headersHelper` command Claude Code runs (once per MCP
// connection) to add the per-session X-Agent-Memory-Session header (issue #87). It invokes the
// agent-memory binary (double quoted so a path with spaces — common on Windows — works, mirroring the
// capture-hook command) with the project identity baked in: headersHelper runs in an unspecified
// working directory, so workspace/project cannot be derived from cwd and must be passed explicitly.
// workspace and project are normalized slugs (single lower-case ASCII segment), so they need no
// quoting. The data dir is intentionally NOT baked — the command resolves it the same way the hook
// writer does (config.Load: AGENT_MEMORY_DATA_DIR or the default), and both subprocesses inherit
// Claude Code's environment, so they agree. Returns "" when identity is unknown, so the caller omits
// headersHelper rather than baking a broken command.
func McpSessionHeaderCommand(binPath, workspace, project string) string {
	if binPath == "" || workspace == "" || project == "" {
		return ""
	}
	return `"` + binPath + `" mcp-session-header --workspace ` + workspace + ` --project ` + project
}

// Mcp idempotently registers the agent-memory MCP server in the Claude Code project MCP file at path
// (.mcp.json), preserving any other servers. The entry is a streamable-HTTP transport pointing at
// {serverURL}/mcp, with an Authorization: Bearer header when token is non-empty and a headersHelper
// command (per-session header, #87) when sessionHeaderCmd is non-empty. Writes only on a real change:
//   - our entry absent before → Created;
//   - present but different (url/token/headersHelper changed) → Updated;
//   - already identical → Unchanged.
func Mcp(path, serverURL, token, sessionHeaderCmd string) (Change, error) {
	root, _, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	before, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}

	servers, ok := root["mcpServers"].(map[string]any)
	if !ok {
		servers = map[string]any{}
		root["mcpServers"] = servers
	}
	_, hadEntry := servers[McpServerName]

	entry := map[string]any{
		"type": "http",
		"url":  strings.TrimRight(serverURL, "/") + "/mcp",
	}
	if token != "" {
		entry["headers"] = map[string]any{"Authorization": "Bearer " + token}
	}
	if sessionHeaderCmd != "" {
		entry["headersHelper"] = sessionHeaderCmd
	}
	servers[McpServerName] = entry

	after, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}
	if bytes.Equal(before, after) {
		return Unchanged, nil
	}
	if err := atomicWrite(path, after, 0o644); err != nil {
		return "", err
	}
	if hadEntry {
		return Updated, nil
	}
	return Created, nil
}

// UninstallMcp removes the agent-memory MCP server entry from path, leaving other servers in place (and
// dropping the mcpServers map if it becomes empty). A missing file or absent entry is Absent.
func UninstallMcp(path string) (Change, error) {
	root, existed, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	if !existed {
		return Absent, nil
	}
	servers, ok := root["mcpServers"].(map[string]any)
	if !ok {
		return Absent, nil
	}
	if _, ok := servers[McpServerName]; !ok {
		return Absent, nil
	}
	delete(servers, McpServerName)
	if len(servers) == 0 {
		delete(root, "mcpServers")
	}
	after, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}
	if err := atomicWrite(path, after, 0o644); err != nil {
		return "", err
	}
	return Removed, nil
}
