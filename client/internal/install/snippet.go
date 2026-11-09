package install

import "strings"

// Self-routing snippet markers (issue #40/#86). These MUST match the server's canonical
// com.agentmemory.mcp.SelfRoutingSnippet BEGIN_MARKER/END_MARKER exactly so the block an installer
// writes is the same one the memory_install_self_routing MCP tool returns, and so a re-install replaces
// it in place idempotently.
const (
	// SelfRoutingBegin opens the managed self-routing block.
	SelfRoutingBegin = "<!-- BEGIN agent-memory:self-routing -->"
	// SelfRoutingEnd closes the managed self-routing block.
	SelfRoutingEnd = "<!-- END agent-memory:self-routing -->"
)

// selfRoutingBodyLines is the canonical routing block body. It carries only what nothing else gives the
// agent: WHEN to recall. The rest already reaches the agent on its own: the MCP server's instructions
// list the tools, the default scope and the write-only-when-asked rule; memory_write_page's description
// repeats that rule; the SessionStart hook injects the project briefing and accepts the open handoff.
// Repeating any of it here would only cost context in every session.
//
// The same block lives in the server's com.agentmemory.mcp.SelfRoutingSnippet (Java, for the MCP tool).
// Both are pinned to docs/contracts/fixtures/self_routing_snippet.md by their tests, so the three change
// together. Assembled from lines because the body contains backticks, which a Go raw-string literal
// cannot hold.
var selfRoutingBodyLines = []string{
	"## Project memory (agent-memory)",
	"",
	"Recall before you act — pull, don't guess: call `memory_query` before proposing a design or a",
	"non-trivial change, and when the user refers to earlier work you don't recognize (\"like we did",
	"before\", \"where we left off\"). One good query is cheaper than re-deriving what the project",
	"already knows.",
}

// SelfRoutingSnippet returns the canonical self-routing block fenced by the BEGIN/END markers and
// terminated by a newline — byte-for-byte what com.agentmemory.mcp.SelfRoutingSnippet.markdown()
// produces.
func SelfRoutingSnippet() string {
	return SelfRoutingBegin + "\n" +
		strings.Join(selfRoutingBodyLines, "\n") + "\n" +
		SelfRoutingEnd + "\n"
}
