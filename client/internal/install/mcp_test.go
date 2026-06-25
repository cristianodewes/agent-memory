package install

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInstallMcpCreatesEntryThenIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".mcp.json")

	ch, err := Mcp(path, "http://127.0.0.1:8080", "")
	if err != nil || ch != Created {
		t.Fatalf("install: change=%v err=%v", ch, err)
	}
	entry := readJSON(t, path)["mcpServers"].(map[string]any)[McpServerName].(map[string]any)
	if entry["type"] != "http" || entry["url"] != "http://127.0.0.1:8080/mcp" {
		t.Fatalf("bad entry: %v", entry)
	}
	if _, hasHeaders := entry["headers"]; hasHeaders {
		t.Errorf("no token → no headers, got %v", entry["headers"])
	}

	if ch2, err := Mcp(path, "http://127.0.0.1:8080", ""); err != nil || ch2 != Unchanged {
		t.Fatalf("re-install: change=%v err=%v", ch2, err)
	}
}

func TestInstallMcpTrimsTrailingSlashAndAddsTokenHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".mcp.json")
	if _, err := Mcp(path, "http://127.0.0.1:8080", ""); err != nil {
		t.Fatal(err)
	}
	ch, err := Mcp(path, "http://127.0.0.1:8080/", "secret")
	if err != nil || ch != Updated {
		t.Fatalf("update with token: change=%v err=%v", ch, err)
	}
	entry := readJSON(t, path)["mcpServers"].(map[string]any)[McpServerName].(map[string]any)
	if entry["url"] != "http://127.0.0.1:8080/mcp" {
		t.Errorf("trailing slash not handled: %v", entry["url"])
	}
	headers := entry["headers"].(map[string]any)
	if headers["Authorization"] != "Bearer secret" {
		t.Fatalf("bad auth header: %v", headers)
	}
}

func TestInstallMcpPreservesForeignServersAndUninstall(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".mcp.json")
	if err := os.WriteFile(path,
		[]byte(`{"mcpServers":{"other":{"type":"stdio","command":"x"}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Mcp(path, "http://127.0.0.1:8080", ""); err != nil {
		t.Fatal(err)
	}
	servers := readJSON(t, path)["mcpServers"].(map[string]any)
	if _, ok := servers["other"]; !ok {
		t.Errorf("foreign server lost: %v", servers)
	}
	if _, ok := servers[McpServerName]; !ok {
		t.Errorf("our server missing: %v", servers)
	}

	if ch, err := UninstallMcp(path); err != nil || ch != Removed {
		t.Fatalf("uninstall: change=%v err=%v", ch, err)
	}
	servers2 := readJSON(t, path)["mcpServers"].(map[string]any)
	if _, ok := servers2["other"]; !ok {
		t.Errorf("foreign server lost on uninstall: %v", servers2)
	}
	if _, ok := servers2[McpServerName]; ok {
		t.Errorf("our server not removed: %v", servers2)
	}
}

func TestUninstallMcpAbsent(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".mcp.json")
	if ch, _ := UninstallMcp(path); ch != Absent {
		t.Errorf("missing file should be Absent, got %v", ch)
	}
}
