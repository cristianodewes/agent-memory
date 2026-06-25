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

// selfRoutingBodyLines is the canonical routing block body, mirroring the server's
// SelfRoutingSnippet.BODY verbatim (the body lives in two places — Java for the MCP tool, Go for the
// offline installer; keep them in sync). Assembled from lines because the body contains backticks,
// which a Go raw-string literal cannot hold.
var selfRoutingBodyLines = []string{
	"## Project memory (agent-memory)",
	"",
	"This project has a long-term memory served over MCP (`agent-memory`). Use it so knowledge",
	"survives across sessions. Scope defaults to the current project; you rarely need to pass",
	"`workspace`/`project`.",
	"",
	"**Recall before you act — pull, don't guess:**",
	"- Before proposing architecture, a design, or a non-trivial change, call `memory_query`",
	"  with the topic to check what was already decided or tried.",
	"- When the user references prior work (\"like we did before\", \"the usual way\", \"where we",
	"  left off\") and you don't recognize it, call `memory_query` first; read full pages with",
	"  `memory_read_page`.",
	"- At the start of a task, `memory_briefing` gives a no-LLM snapshot (counts, rules, recent",
	"  pages); `memory_recent` lists the latest pages.",
	"",
	"**Resume handoffs:** if a handoff is waiting, `memory_handoff_accept` returns where the",
	"previous session left off (single-use — it is consumed on accept).",
	"",
	"**Write only when asked to remember:** when the user says to remember/note/record",
	"something durable, call `memory_write_page` (or `memory_delete_page` to remove one by",
	"path). Do not write memory unprompted.",
	"",
	"**Rule of thumb:** recall is cheap and proactive; writing is deliberate and only on",
	"request. Prefer one good `memory_query` over re-deriving what the project already knows.",
}

// SelfRoutingSnippet returns the canonical self-routing block fenced by the BEGIN/END markers and
// terminated by a newline — byte-for-byte what com.agentmemory.mcp.SelfRoutingSnippet.markdown()
// produces.
func SelfRoutingSnippet() string {
	return SelfRoutingBegin + "\n" +
		strings.Join(selfRoutingBodyLines, "\n") + "\n" +
		SelfRoutingEnd + "\n"
}
