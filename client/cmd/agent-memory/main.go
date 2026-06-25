// Command agent-memory is the agent-memory client: lifecycle hooks, a local
// spool + drain, a thin HTTP CLI over the server, and the installers that wire
// an agent (Claude Code, Codex, ...) to the agent-memory server.
//
// See docs/ARCHITECTURE.md §2.1.
package main

import "github.com/cristianodewes/agent-memory/client/internal/cli"

func main() {
	cli.Execute()
}
