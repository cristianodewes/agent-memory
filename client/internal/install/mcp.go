package install

import (
	"bytes"
	"fmt"
	"strings"
)

// McpServerName is the key agent-memory registers itself under in a client's MCP server map (and the
// TOML table name for Codex).
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

// mcpEndpoint is the Streamable-HTTP MCP URL: the server base with a single trailing /mcp.
func mcpEndpoint(serverURL string) string {
	return strings.TrimRight(serverURL, "/") + "/mcp"
}

// bearerHeaders returns the Authorization header map for token, or nil when token is empty (loopback /
// no-auth servers carry no header).
func bearerHeaders(token string) map[string]any {
	if token == "" {
		return nil
	}
	return map[string]any{"Authorization": "Bearer " + token}
}

// Mcp idempotently registers the agent-memory MCP server in the Claude Code project MCP file at path
// (.mcp.json). It is the default-client convenience wrapper over McpProfile and preserves the exact
// pre-#115 behavior (type:"http" + headers + headersHelper).
func Mcp(path, serverURL, token, sessionHeaderCmd string) (Change, error) {
	return upsertMCPJSON(path, "mcpServers", claudeMCPEntry(serverURL, token, sessionHeaderCmd))
}

// McpProfile idempotently registers the agent-memory MCP server in a client's MCP file at path,
// rendering the client's shape from m. Like Mcp it preserves any other servers and writes only on a
// real change (Created/Updated/Unchanged).
func McpProfile(path string, m *MCPProfile, serverURL, token, sessionHeaderCmd string) (Change, error) {
	switch m.Shape {
	case MCPShapeClaudeHTTP:
		return upsertMCPJSON(path, "mcpServers", claudeMCPEntry(serverURL, token, sessionHeaderCmd))
	case MCPShapeURLHeaders:
		return upsertMCPJSON(path, "mcpServers", urlHeadersMCPEntry(serverURL, token))
	case MCPShapeGeminiHTTP:
		return upsertMCPJSON(path, "mcpServers", geminiMCPEntry(serverURL, token))
	case MCPShapeVSCodeServers:
		return upsertMCPJSON(path, "servers", vscodeMCPEntry(serverURL, token))
	case MCPShapeRemoteStdio:
		return upsertMCPJSON(path, "mcpServers", mcpRemoteEntry(serverURL, token))
	case MCPShapeCodexTOML:
		return upsertMCPCodexTOML(path, serverURL, token)
	default:
		return "", fmt.Errorf("install: unknown MCP shape %d", m.Shape)
	}
}

// UninstallMcp removes the agent-memory MCP server entry from a Claude Code project MCP file (the
// default-client wrapper over UninstallMcpProfile).
func UninstallMcp(path string) (Change, error) {
	return removeMCPJSON(path, "mcpServers")
}

// UninstallMcpProfile removes the agent-memory MCP server entry from path for the client's shape,
// leaving other servers in place. A missing file or absent entry is Absent.
func UninstallMcpProfile(path string, m *MCPProfile) (Change, error) {
	if m.Shape == MCPShapeCodexTOML {
		return removeMCPCodexTOML(path)
	}
	return removeMCPJSON(path, mcpContainerKey(m.Shape))
}

// mcpContainerKey is the top-level map a shape stores its servers under. VS Code uses "servers"; every
// other JSON shape uses "mcpServers".
func mcpContainerKey(shape MCPShape) string {
	if shape == MCPShapeVSCodeServers {
		return "servers"
	}
	return "mcpServers"
}

// claudeMCPEntry is the Claude Code MCP entry: an explicit http transport with a Bearer header and the
// per-session headersHelper (#87) when supplied.
func claudeMCPEntry(serverURL, token, sessionHeaderCmd string) map[string]any {
	entry := map[string]any{
		"type": "http",
		"url":  mcpEndpoint(serverURL),
	}
	if h := bearerHeaders(token); h != nil {
		entry["headers"] = h
	}
	if sessionHeaderCmd != "" {
		entry["headersHelper"] = sessionHeaderCmd
	}
	return entry
}

// urlHeadersMCPEntry is the Cursor MCP entry: a bare url (the HTTP transport is inferred) plus an
// optional Bearer header. Cursor has no per-session headersHelper.
func urlHeadersMCPEntry(serverURL, token string) map[string]any {
	entry := map[string]any{"url": mcpEndpoint(serverURL)}
	if h := bearerHeaders(token); h != nil {
		entry["headers"] = h
	}
	return entry
}

// geminiMCPTimeoutMs is the connection timeout Gemini CLI MCP entries carry, mirroring ai-memory's
// GEMINI_MCP_TIMEOUT_MS (install_mcp.rs).
const geminiMCPTimeoutMs = 5000

// geminiMCPEntry is the Gemini CLI MCP entry: the streamable-HTTP endpoint under httpUrl, a timeout, plus
// an optional Bearer header (ai-memory build_mcp_entry McpClient::GeminiCli).
func geminiMCPEntry(serverURL, token string) map[string]any {
	entry := map[string]any{
		"httpUrl": mcpEndpoint(serverURL),
		"timeout": geminiMCPTimeoutMs,
	}
	if h := bearerHeaders(token); h != nil {
		entry["headers"] = h
	}
	return entry
}

// vscodeMCPEntry is the VS Code MCP entry: an explicit http transport (stored under the top-level
// "servers" map by the caller) plus an optional Bearer header.
func vscodeMCPEntry(serverURL, token string) map[string]any {
	entry := map[string]any{
		"type": "http",
		"url":  mcpEndpoint(serverURL),
	}
	if h := bearerHeaders(token); h != nil {
		entry["headers"] = h
	}
	return entry
}

// mcpRemoteAuthEnvVar is the environment variable the mcp-remote bridge interpolates into its
// Authorization header. Carrying the credential in env (not in args) keeps it out of the process
// command line, mirroring ai-memory's AI_MEMORY_AUTH_HEADER indirection (install_mcp.rs ClaudeDesktop).
const mcpRemoteAuthEnvVar = "AGENT_MEMORY_AUTH_HEADER"

// mcpRemoteEntry is the Claude Desktop MCP entry: Claude Desktop speaks stdio only, so it bridges to the
// Streamable-HTTP server via `npx -y mcp-remote <url>`. When a token is present the Authorization header
// is passed by env-var indirection (`--header Authorization:${VAR}` + an `env` block holding
// "Bearer <token>"), so the secret never lands in the args/command line.
func mcpRemoteEntry(serverURL, token string) map[string]any {
	args := []any{"-y", "mcp-remote", mcpEndpoint(serverURL)}
	entry := map[string]any{"command": "npx"}
	if token != "" {
		args = append(args, "--header", "Authorization:${"+mcpRemoteAuthEnvVar+"}")
		entry["env"] = map[string]any{mcpRemoteAuthEnvVar: "Bearer " + token}
	}
	entry["args"] = args
	return entry
}

// upsertMCPJSON idempotently sets root[containerKey][McpServerName] = entry in the JSON file at path,
// preserving every other server and key, and writes only on a real change:
//   - our entry absent before → Created;
//   - present but different (url/token/headersHelper/transport changed) → Updated;
//   - already identical → Unchanged.
func upsertMCPJSON(path, containerKey string, entry map[string]any) (Change, error) {
	root, _, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	before, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}

	servers, ok := root[containerKey].(map[string]any)
	if !ok {
		servers = map[string]any{}
		root[containerKey] = servers
	}
	_, hadEntry := servers[McpServerName]
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

// removeMCPJSON removes root[containerKey][McpServerName] from the JSON file at path, leaving other
// servers in place (and dropping the container map if it becomes empty). A missing file or absent entry
// is Absent.
func removeMCPJSON(path, containerKey string) (Change, error) {
	root, existed, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	if !existed {
		return Absent, nil
	}
	servers, ok := root[containerKey].(map[string]any)
	if !ok {
		return Absent, nil
	}
	if _, ok := servers[McpServerName]; !ok {
		return Absent, nil
	}
	delete(servers, McpServerName)
	if len(servers) == 0 {
		delete(root, containerKey)
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
