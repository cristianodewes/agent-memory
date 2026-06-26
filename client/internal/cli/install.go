package cli

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
	"github.com/cristianodewes/agent-memory/client/internal/install"
	"github.com/spf13/cobra"
)

// installFlags are the resolved inputs the install/uninstall commands share.
type installFlags struct {
	dir       string // project root override; default = git root / cwd
	bin       string // agent-memory binary path for hook commands; default = os.Executable()
	serverURL string // MCP server base URL; default = config/identity
	token     string // MCP bearer token; default = config/identity
	file      string // instructions filename override; default = the client's instructions file
	agent     string // target agent/client id (--agent); default claude-code
	client    string // target agent/client id (--client alias); wins over --agent when set
	scope     string // install scope: project (default) | user (--scope)
	global    bool   // shorthand for --scope user (--global)
}

// resolved holds the concrete values the install ops act on, plus the selected client profile and the
// path context its per-surface files resolve against.
type resolved struct {
	client    *install.Client
	ctx       install.PathContext
	bin       string
	serverURL string
	token     string
	workspace string // normalized identity slug, for the per-session MCP header (#87)
	project   string // normalized identity slug, for the per-session MCP header (#87)
	instrFile string // instructions filename to use (override or client default; "" if none)
}

// sessionHeaderCommand is the headersHelper command baked into Claude Code's MCP config so it emits the
// per-session X-Agent-Memory-Session header (#87). At project scope it bakes this project's identity; at
// user scope (#116) one global wiring serves every repo, so it bakes NO identity and the helper derives
// it from cwd at runtime. Empty when identity did not normalize (project scope) — the MCP entry is still
// written, just without the header; clients whose MCP shape does not support headersHelper ignore it.
func (r resolved) sessionHeaderCommand() string {
	if r.ctx.Scope == install.ScopeUser {
		return install.McpSessionHeaderCommandGlobal(r.bin)
	}
	return install.McpSessionHeaderCommand(r.bin, r.workspace, r.project)
}

// hooksTarget returns the absolute hooks file and true when the selected client has a hook surface.
func (r resolved) hooksTarget() (string, bool, error) {
	if r.client.Hooks == nil {
		return "", false, nil
	}
	return requireAbs(r.client.Hooks.Path(r.ctx))
}

// mcpTarget returns the absolute MCP file and true when the selected client has an MCP surface.
func (r resolved) mcpTarget() (string, bool, error) {
	if r.client.MCP == nil {
		return "", false, nil
	}
	return requireAbs(r.client.MCP.Path(r.ctx))
}

// instrTarget returns the absolute instructions file and true when the client has an instructions
// surface (or the user supplied an explicit --file). The directory is scope-aware (Claude Code's global
// instructions live in ~/.claude at user scope — see Client.InstrDir).
func (r resolved) instrTarget() (string, bool, error) {
	if r.instrFile == "" {
		return "", false, nil
	}
	return requireAbs(filepath.Join(r.client.InstrDir(r.ctx), r.instrFile))
}

// requireAbs guards against a path that did not absolutize — which happens for a home-based client when
// the user home directory could not be resolved (rare/sandboxed). Writing such a relative path would
// land config under the cwd, so it is an actionable error instead.
func requireAbs(path string) (string, bool, error) {
	if !filepath.IsAbs(path) {
		return "", true, fmt.Errorf("could not resolve an absolute config path %q "+
			"(is the user home directory available?)", path)
	}
	return path, true, nil
}

// agentID returns the selected client id: --client wins over --agent when both are set, else --agent,
// else the default (resolved downstream by LookupClient).
func (f installFlags) agentID() string {
	if strings.TrimSpace(f.client) != "" {
		return f.client
	}
	return f.agent
}

func (f installFlags) resolve() (resolved, error) {
	cwd, _ := os.Getwd()

	root := f.dir
	if root == "" {
		root = install.ProjectRoot(cwd)
	} else if abs, err := filepath.Abs(root); err == nil {
		root = abs
	}

	bin := f.bin
	if bin == "" {
		exe, err := os.Executable()
		if err != nil {
			return resolved{}, fmt.Errorf("could not resolve the agent-memory binary path: %w "+
				"(pass --bin)", err)
		}
		bin = exe
	}

	id := identity.Resolve(cwd)
	cfg := config.Load().WithIdentityOverrides(id.ServerURL, id.Token)
	serverURL := cfg.ServerURL
	if f.serverURL != "" {
		serverURL = f.serverURL
	}
	token := cfg.Token
	if f.token != "" {
		token = f.token
	}

	// Normalize identity to the SAME slugs the hook attributes events under, so the per-session header
	// command (#87) reads back the file the SessionStart hook writes. A non-normalizable name (rare for
	// a derived basename) leaves the slugs empty, and the MCP entry is written without the header.
	workspace, project := normalizedIdentity(id)

	client, err := install.LookupClient(f.agentID())
	if err != nil {
		return resolved{}, err
	}

	scope, err := f.resolveScope()
	if err != nil {
		return resolved{}, err
	}

	// The instructions file: an explicit --file wins; otherwise the client's conventional file
	// (CLAUDE.md / AGENTS.md / GEMINI.md). Empty means the client has no instructions surface.
	instrFile := strings.TrimSpace(f.file)
	if instrFile == "" {
		instrFile = client.InstrFile
	}

	return resolved{
		client:    client,
		ctx:       install.PathContext{ProjectRoot: root, Home: install.ResolveHome(), Scope: scope},
		bin:       bin,
		serverURL: serverURL,
		token:     token,
		workspace: workspace,
		project:   project,
		instrFile: instrFile,
	}, nil
}

// resolveScope resolves the install scope from the flags: --global is shorthand for --scope user, and an
// explicit --scope always wins. Defaults to project (backward-compatible).
func (f installFlags) resolveScope() (install.Scope, error) {
	s := f.scope
	if s == "" && f.global {
		s = string(install.ScopeUser)
	}
	return install.ParseScope(s)
}

// normalizedIdentity returns the (workspace, project) slugs for id, normalized exactly as the hook
// does (core.NewWorkspaceID/NewProjectID). Either is "" when its raw name does not normalize.
func normalizedIdentity(id identity.Resolved) (string, string) {
	var workspace, project string
	if w, err := core.NewWorkspaceID(id.Workspace); err == nil {
		workspace = w.String()
	}
	if p, err := core.NewProjectID(id.Project); err == nil {
		project = p.String()
	}
	return workspace, project
}

// report prints "<label>: <change> (<path>)" for one install op.
func report(cmd *cobra.Command, label string, change install.Change, path string) {
	fmt.Fprintf(cmd.OutOrStdout(), "%s: %s (%s)\n", label, change, path)
}

// reportUnsupported prints a clear, non-fatal line when the selected client lacks a surface (e.g. Codex
// has no hooks). The command continues / exits 0 — an unsupported surface is a no-op, not an error.
func reportUnsupported(cmd *cobra.Command, label string, client *install.Client) {
	fmt.Fprintf(cmd.OutOrStdout(), "%s: unsupported by %s\n", label, client.ID)
}

func (f *installFlags) addDir(cmd *cobra.Command) {
	cmd.Flags().StringVar(&f.dir, "dir", "",
		"project root to install into (default: the git repository root, else the current directory)")
}

// addAgent registers the target selectors on a command: the --agent/--client agent picker and the
// --scope/--global install-location picker (issue #116).
func (f *installFlags) addAgent(cmd *cobra.Command) {
	cmd.Flags().StringVar(&f.agent, "agent", install.DefaultClientID,
		"target agent/client: "+strings.Join(install.ClientIDs(), ", "))
	cmd.Flags().StringVar(&f.client, "client", "",
		"alias for --agent (target agent/client id)")
	cmd.Flags().StringVar(&f.scope, "scope", "",
		"install scope for Claude Code: 'project' (default; this repo) or 'user' (recommended: "+
			"~/.claude, covers every repo)")
	cmd.Flags().BoolVar(&f.global, "global", false,
		"shorthand for --scope user (install once for every repository)")
}

// doHooks installs the hook surface for the resolved client, reporting unsupported as a no-op.
func doHooks(cmd *cobra.Command, r resolved) error {
	path, ok, err := r.hooksTarget()
	if err != nil {
		return err
	}
	if !ok {
		reportUnsupported(cmd, "hooks", r.client)
		return nil
	}
	change, err := install.HooksProfile(path, r.client.Hooks, r.bin)
	if err != nil {
		return err
	}
	report(cmd, "hooks", change, path)
	return nil
}

// doMcp installs the MCP surface for the resolved client, reporting unsupported as a no-op.
func doMcp(cmd *cobra.Command, r resolved) error {
	path, ok, err := r.mcpTarget()
	if err != nil {
		return err
	}
	if !ok {
		reportUnsupported(cmd, "mcp", r.client)
		return nil
	}
	change, err := install.McpProfile(path, r.client.MCP, r.serverURL, r.token, r.sessionHeaderCommand())
	if err != nil {
		return err
	}
	report(cmd, "mcp", change, path)
	return nil
}

// doInstructions installs the self-routing block for the resolved client, reporting unsupported as a
// no-op.
func doInstructions(cmd *cobra.Command, r resolved) error {
	path, ok, err := r.instrTarget()
	if err != nil {
		return err
	}
	if !ok {
		reportUnsupported(cmd, "instructions", r.client)
		return nil
	}
	change, err := install.Instructions(path)
	if err != nil {
		return err
	}
	report(cmd, "instructions", change, path)
	return nil
}

func newInstallHooksCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "install-hooks",
		Short:         "Wire the agent-memory capture hook into an agent's config (default: Claude Code)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			return doHooks(cmd, r)
		},
	}
	f.addDir(cmd)
	f.addAgent(cmd)
	cmd.Flags().StringVar(&f.bin, "bin", "",
		"path to the agent-memory binary used in the hook command (default: this executable)")
	return cmd
}

func newInstallMcpCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "install-mcp",
		Short:         "Register the agent-memory MCP server with an agent (default: Claude Code)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			return doMcp(cmd, r)
		},
	}
	f.addDir(cmd)
	f.addAgent(cmd)
	cmd.Flags().StringVar(&f.serverURL, "server-url", "",
		"agent-memory server base URL (default: AGENT_MEMORY_SERVER_URL or the marker / built-in default)")
	cmd.Flags().StringVar(&f.token, "token", "",
		"bearer token for the MCP server (default: AGENT_MEMORY_TOKEN or the marker)")
	return cmd
}

func newInstallInstructionsCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "install-instructions",
		Short:         "Write the self-routing snippet into the agent instructions (CLAUDE.md / AGENTS.md / GEMINI.md)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			return doInstructions(cmd, r)
		},
	}
	f.addDir(cmd)
	f.addAgent(cmd)
	cmd.Flags().StringVar(&f.file, "file", "",
		"instructions file to write the self-routing block into (default: the client's file)")
	return cmd
}

// runSetup installs all supported surfaces (hooks + MCP + instructions) for the resolved client. Shared
// by setup-agent and upgrade (both are idempotent refreshes — upgrade just re-runs with the current
// binary/URL). Surfaces the client does not support are reported as no-ops, not errors.
func runSetup(cmd *cobra.Command, r resolved) error {
	if err := doHooks(cmd, r); err != nil {
		return err
	}
	if err := doMcp(cmd, r); err != nil {
		return err
	}
	return doInstructions(cmd, r)
}

func (f *installFlags) addSetupFlags(cmd *cobra.Command) {
	f.addDir(cmd)
	f.addAgent(cmd)
	cmd.Flags().StringVar(&f.bin, "bin", "", "path to the agent-memory binary (default: this executable)")
	cmd.Flags().StringVar(&f.serverURL, "server-url", "", "agent-memory server base URL")
	cmd.Flags().StringVar(&f.token, "token", "", "bearer token for the MCP server")
	cmd.Flags().StringVar(&f.file, "file", "",
		"instructions file for the self-routing block (default: the client's file)")
}

func newSetupAgentCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use: "setup-agent",
		Short: "Install everything for an agent: hooks + MCP server + self-routing instructions " +
			"(idempotent; default: Claude Code)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			return runSetup(cmd, r)
		},
	}
	f.addSetupFlags(cmd)
	return cmd
}

func newUpgradeCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use: "upgrade",
		Short: "Refresh the installed hooks, MCP entry and instructions to the current binary/config " +
			"(idempotent)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			return runSetup(cmd, r)
		},
	}
	f.addSetupFlags(cmd)
	return cmd
}

func newUninstallCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "uninstall",
		Short:         "Remove the agent-memory hooks, MCP entry and self-routing instructions",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}

			if path, ok, err := r.hooksTarget(); err != nil {
				return err
			} else if !ok {
				reportUnsupported(cmd, "hooks", r.client)
			} else {
				change, err := install.UninstallHooksProfile(path, r.client.Hooks)
				if err != nil {
					return err
				}
				report(cmd, "hooks", change, path)
			}

			if path, ok, err := r.mcpTarget(); err != nil {
				return err
			} else if !ok {
				reportUnsupported(cmd, "mcp", r.client)
			} else {
				change, err := install.UninstallMcpProfile(path, r.client.MCP)
				if err != nil {
					return err
				}
				report(cmd, "mcp", change, path)
			}

			if path, ok, err := r.instrTarget(); err != nil {
				return err
			} else if !ok {
				reportUnsupported(cmd, "instructions", r.client)
			} else {
				change, err := install.UninstallInstructions(path)
				if err != nil {
					return err
				}
				report(cmd, "instructions", change, path)
			}
			return nil
		},
	}
	f.addDir(cmd)
	f.addAgent(cmd)
	cmd.Flags().StringVar(&f.file, "file", "",
		"instructions file to remove the block from (default: the client's file)")
	return cmd
}
