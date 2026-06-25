package install

import (
	"bytes"
	"strings"
)

// McpServerName is the key agent-memory registers itself under in .mcp.json's mcpServers map.
const McpServerName = "agent-memory"

// Mcp idempotently registers the agent-memory MCP server in the Claude Code project MCP file at path
// (.mcp.json), preserving any other servers. The entry is a streamable-HTTP transport pointing at
// {serverURL}/mcp, with an Authorization: Bearer header when token is non-empty. Writes only on a real
// change:
//   - our entry absent before → Created;
//   - present but different (url/token changed) → Updated;
//   - already identical → Unchanged.
func Mcp(path, serverURL, token string) (Change, error) {
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
