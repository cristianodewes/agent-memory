package install

import (
	"os"
	"path/filepath"
	"testing"
)

// TestSelfRoutingSnippetMatchesContractFixture pins the block the offline installer writes to the shared
// contract docs/contracts/fixtures/self_routing_snippet.md. The server's SelfRoutingSnippetTest pins the
// block the memory_install_self_routing MCP tool returns to the same file, so both suites green mean the
// two hand out the same bytes. If they diverged, a block pasted from the MCP tool and a later
// `agent-memory upgrade` would keep rewriting each other.
func TestSelfRoutingSnippetMatchesContractFixture(t *testing.T) {
	// up from internal/install -> internal -> client -> repo root
	path := filepath.Join("..", "..", "..", "docs", "contracts", "fixtures", "self_routing_snippet.md")
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read contract fixture %s: %v", path, err)
	}
	if got := SelfRoutingSnippet(); got != string(want) {
		t.Fatalf("SelfRoutingSnippet() differs from the contract fixture %s\n--- got ---\n%s--- want ---\n%s",
			path, got, want)
	}
}
