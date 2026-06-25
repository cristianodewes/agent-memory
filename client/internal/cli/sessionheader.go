package cli

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/cristianodewes/agent-memory/client/internal/capturesession"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
	"github.com/spf13/cobra"
)

// newMcpSessionHeaderCmd builds the `agent-memory mcp-session-header` command: the read side of the
// capture-session transport (issue #87). Claude Code's MCP `headersHelper` runs this once per
// connection and uses its STDOUT — a JSON object of header name→value — to add HTTP headers to every
// MCP request on that connection. We print {"X-Agent-Memory-Session":"<id>"} when this project has a
// recorded capture session (written by the SessionStart hook), so the server can resolve a no-scope
// tool call to THIS session's default scope under auto_scope=session_aware.
//
// The project identity is taken from --workspace/--project, which `install-mcp` bakes into the
// headersHelper command line at install time (when the project root is known). headersHelper runs in an
// unspecified working directory with almost no environment, so we must NOT rely on cwd; when the flags
// are nonetheless absent we fall back to resolving identity from cwd as a convenience for manual use.
//
// It ALWAYS prints a valid JSON object and ALWAYS exits 0 — a missing/blank/garbage session, an
// unreadable file, or any other problem prints "{}" (add no header) rather than failing, because a
// non-zero exit or non-JSON output would break the MCP connection. With no header the server fails
// closed (session_aware rejects the no-scope call) — safe, never a cross-session leak.
func newMcpSessionHeaderCmd() *cobra.Command {
	var workspace, project, dataDir string

	cmd := &cobra.Command{
		Use:   "mcp-session-header",
		Short: "Emit the MCP X-Agent-Memory-Session header for this project (auto_scope=session_aware)",
		Long: "Reads this project's current capture session id (recorded by the SessionStart hook) and " +
			"prints it as a JSON object of HTTP headers for Claude Code's MCP headersHelper. Prints '{}' " +
			"when no session is bound. Always exits 0 so it can never break the MCP connection.",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			ws, proj := resolveHeaderIdentity(workspace, project)
			dir := config.Load().DataDir
			if dataDir != "" {
				dir = config.ResolveDataDir(dataDir)
			}
			emitSessionHeader(cmd, dir, ws, proj)
			return nil
		},
	}
	cmd.Flags().StringVar(&workspace, "workspace", "",
		"workspace slug of the project (default: resolved from the current directory)")
	cmd.Flags().StringVar(&project, "project", "",
		"project slug (default: resolved from the current directory)")
	cmd.Flags().StringVar(&dataDir, "data-dir", "",
		"data directory root (default: AGENT_MEMORY_DATA_DIR or ~/.agent-memory)")
	return cmd
}

// resolveHeaderIdentity returns the normalized (workspace, project) to look up. The flags win (the
// install-baked path); when either is empty it falls back to deriving identity from cwd and normalizing
// it the SAME way the hook does, so a manual invocation inside the project still finds the file.
func resolveHeaderIdentity(workspace, project string) (string, string) {
	if workspace != "" && project != "" {
		return workspace, project
	}
	cwd, _ := os.Getwd()
	id := identity.Resolve(cwd)
	ws := workspace
	if ws == "" {
		if w, err := core.NewWorkspaceID(id.Workspace); err == nil {
			ws = w.String()
		}
	}
	proj := project
	if proj == "" {
		if p, err := core.NewProjectID(id.Project); err == nil {
			proj = p.String()
		}
	}
	return ws, proj
}

// emitSessionHeader writes the headers JSON object to stdout: the session header when a valid id is
// bound for (workspace, project), otherwise an empty object. A read error is logged to stderr (which
// headersHelper ignores) and treated as "no session". The id is validated as a UUID so a corrupt file
// never puts a junk value on the wire.
func emitSessionHeader(cmd *cobra.Command, dataDir, workspace, project string) {
	headers := map[string]string{}
	if workspace != "" && project != "" {
		id, err := capturesession.Read(dataDir, workspace, project)
		if err != nil {
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: mcp-session-header:", err)
		} else if _, perr := core.ParseSessionID(id); perr == nil {
			headers[capturesession.HeaderName] = id
		}
	}
	// json.Marshal of a map[string]string never fails; ignore the error and always print an object.
	out, _ := json.Marshal(headers)
	fmt.Fprintln(cmd.OutOrStdout(), string(out))
}
