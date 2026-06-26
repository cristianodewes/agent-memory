package install

import "bytes"

// codexMCPEntry is the OpenAI Codex CLI MCP entry, mirroring ai-memory's codex_upsert_mcp_server
// (install_mcp.rs). It is stored as the TOML table [mcp_servers.agent-memory] with:
//   - url: the Streamable-HTTP endpoint (the `url` key selects Codex's streamable_http transport);
//   - default_tools_approval_mode = "approve": auto-approve agent-memory's tool calls so Codex does not
//     prompt on every memory_query/memory_briefing (which would make auto-capture unusable);
//   - [.http_headers] Authorization = "Bearer <token>" when a token is present. IMPORTANT: Codex rejects
//     the literal `bearer_token` key for streamable_http ("bearer_token is not supported") — the static
//     header MUST go under http_headers (ai-memory's documented v3 fix; v2's bearer_token was wrong).
func codexMCPEntry(serverURL, token string) map[string]any {
	entry := map[string]any{
		"url":                         mcpEndpoint(serverURL),
		"default_tools_approval_mode": "approve",
	}
	if token != "" {
		entry["http_headers"] = map[string]any{"Authorization": "Bearer " + token}
	}
	return entry
}

// upsertMCPCodexTOML idempotently sets [mcp_servers.agent-memory] in the Codex config.toml at path,
// preserving every other table and key, and writes only on a real change (Created/Updated/Unchanged).
// When nothing changed the file is left byte-for-byte untouched, so comments and hand-formatting survive
// a re-install.
func upsertMCPCodexTOML(path, serverURL, token string) (Change, error) {
	root, _, err := loadTOMLObject(path)
	if err != nil {
		return "", err
	}
	before, err := marshalTOMLStable(root)
	if err != nil {
		return "", err
	}

	servers, ok := root["mcp_servers"].(map[string]any)
	if !ok {
		servers = map[string]any{}
		root["mcp_servers"] = servers
	}
	_, hadEntry := servers[McpServerName]
	servers[McpServerName] = codexMCPEntry(serverURL, token)

	after, err := marshalTOMLStable(root)
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

// removeMCPCodexTOML removes [mcp_servers.agent-memory] from the Codex config.toml at path, leaving
// other servers in place (and dropping the mcp_servers table if it becomes empty). A missing file or
// absent entry is Absent.
func removeMCPCodexTOML(path string) (Change, error) {
	root, existed, err := loadTOMLObject(path)
	if err != nil {
		return "", err
	}
	if !existed {
		return Absent, nil
	}
	servers, ok := root["mcp_servers"].(map[string]any)
	if !ok {
		return Absent, nil
	}
	if _, ok := servers[McpServerName]; !ok {
		return Absent, nil
	}
	delete(servers, McpServerName)
	if len(servers) == 0 {
		delete(root, "mcp_servers")
	}
	after, err := marshalTOMLStable(root)
	if err != nil {
		return "", err
	}
	if err := atomicWrite(path, after, 0o644); err != nil {
		return "", err
	}
	return Removed, nil
}
