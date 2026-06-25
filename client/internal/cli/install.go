package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/cristianodewes/agent-memory/client/internal/config"
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
	file      string // instructions filename; default = CLAUDE.md
}

// resolved holds the concrete paths/values the install ops act on.
type resolved struct {
	root         string
	bin          string
	serverURL    string
	token        string
	settingsPath string
	mcpPath      string
	instrPath    string
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

	file := f.file
	if file == "" {
		file = "CLAUDE.md"
	}

	return resolved{
		root:         root,
		bin:          bin,
		serverURL:    serverURL,
		token:        token,
		settingsPath: install.SettingsPath(root),
		mcpPath:      install.McpPath(root),
		instrPath:    filepath.Join(root, file),
	}, nil
}

// report prints "<label>: <change> (<path>)" for one install op.
func report(cmd *cobra.Command, label string, change install.Change, path string) {
	fmt.Fprintf(cmd.OutOrStdout(), "%s: %s (%s)\n", label, change, path)
}

func (f *installFlags) addDir(cmd *cobra.Command) {
	cmd.Flags().StringVar(&f.dir, "dir", "",
		"project root to install into (default: the git repository root, else the current directory)")
}

func newInstallHooksCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "install-hooks",
		Short:         "Wire the agent-memory capture hook into Claude Code (.claude/settings.json)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			change, err := install.Hooks(r.settingsPath, r.bin)
			if err != nil {
				return err
			}
			report(cmd, "hooks", change, r.settingsPath)
			return nil
		},
	}
	f.addDir(cmd)
	cmd.Flags().StringVar(&f.bin, "bin", "",
		"path to the agent-memory binary used in the hook command (default: this executable)")
	return cmd
}

func newInstallMcpCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use:           "install-mcp",
		Short:         "Register the agent-memory MCP server with Claude Code (.mcp.json)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			change, err := install.Mcp(r.mcpPath, r.serverURL, r.token)
			if err != nil {
				return err
			}
			report(cmd, "mcp", change, r.mcpPath)
			return nil
		},
	}
	f.addDir(cmd)
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
		Short:         "Write the self-routing snippet into the agent instructions (CLAUDE.md / AGENTS.md)",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			r, err := f.resolve()
			if err != nil {
				return err
			}
			change, err := install.Instructions(r.instrPath)
			if err != nil {
				return err
			}
			report(cmd, "instructions", change, r.instrPath)
			return nil
		},
	}
	f.addDir(cmd)
	cmd.Flags().StringVar(&f.file, "file", "CLAUDE.md",
		"instructions file to write the self-routing block into")
	return cmd
}

// runSetup installs all three surfaces (hooks + MCP + instructions). Shared by setup-agent and upgrade
// (both are idempotent refreshes — upgrade just re-runs with the current binary/URL).
func runSetup(cmd *cobra.Command, r resolved) error {
	hooks, err := install.Hooks(r.settingsPath, r.bin)
	if err != nil {
		return err
	}
	report(cmd, "hooks", hooks, r.settingsPath)

	mcp, err := install.Mcp(r.mcpPath, r.serverURL, r.token)
	if err != nil {
		return err
	}
	report(cmd, "mcp", mcp, r.mcpPath)

	instr, err := install.Instructions(r.instrPath)
	if err != nil {
		return err
	}
	report(cmd, "instructions", instr, r.instrPath)
	return nil
}

func (f *installFlags) addSetupFlags(cmd *cobra.Command) {
	f.addDir(cmd)
	cmd.Flags().StringVar(&f.bin, "bin", "", "path to the agent-memory binary (default: this executable)")
	cmd.Flags().StringVar(&f.serverURL, "server-url", "", "agent-memory server base URL")
	cmd.Flags().StringVar(&f.token, "token", "", "bearer token for the MCP server")
	cmd.Flags().StringVar(&f.file, "file", "CLAUDE.md", "instructions file for the self-routing block")
}

func newSetupAgentCmd() *cobra.Command {
	var f installFlags
	cmd := &cobra.Command{
		Use: "setup-agent",
		Short: "Install everything for Claude Code: hooks + MCP server + self-routing instructions " +
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
			hooks, err := install.UninstallHooks(r.settingsPath)
			if err != nil {
				return err
			}
			report(cmd, "hooks", hooks, r.settingsPath)

			mcp, err := install.UninstallMcp(r.mcpPath)
			if err != nil {
				return err
			}
			report(cmd, "mcp", mcp, r.mcpPath)

			instr, err := install.UninstallInstructions(r.instrPath)
			if err != nil {
				return err
			}
			report(cmd, "instructions", instr, r.instrPath)
			return nil
		},
	}
	f.addDir(cmd)
	cmd.Flags().StringVar(&f.file, "file", "CLAUDE.md", "instructions file to remove the block from")
	return cmd
}
