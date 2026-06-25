// Package cli wires the agent-memory client subcommands. Every command is a
// thin HTTP client of the server — it never touches Postgres or the wiki
// directly (see docs/ARCHITECTURE.md §2.1 and design-decision DD-001).
package cli

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// Version is the client version. It is overridden at build time via
// -ldflags "-X .../internal/cli.Version=<v>".
var Version = "0.0.0-dev"

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "agent-memory",
		Short:         "agent-memory client (hooks, spool, drain, CLI)",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.AddCommand(&cobra.Command{
		Use:   "version",
		Short: "Print the client version",
		Run: func(_ *cobra.Command, _ []string) {
			fmt.Println(Version)
		},
	})
	root.AddCommand(newHookCmd())
	root.AddCommand(newReindexCmd())
	root.AddCommand(newRenameProjectCmd())
	root.AddCommand(newMoveProjectCmd())
	root.AddCommand(newPurgeProjectCmd())
	root.AddCommand(newResetCmd())
	root.AddCommand(newCheckpointsCmd())
	root.AddCommand(newRestorePageCmd())
	root.AddCommand(newBackupCmd())
	root.AddCommand(newRestoreCmd())
	root.AddCommand(newBootstrapCmd())
	root.AddCommand(newUserCmd())
	root.AddCommand(newAuthCmd())
	root.AddCommand(newInstallHooksCmd())
	root.AddCommand(newInstallMcpCmd())
	root.AddCommand(newInstallInstructionsCmd())
	root.AddCommand(newSetupAgentCmd())
	root.AddCommand(newUpgradeCmd())
	root.AddCommand(newUninstallCmd())
	return root
}

// Execute runs the root command and exits non-zero on error.
func Execute() {
	if err := newRootCmd().Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "agent-memory:", err)
		os.Exit(1)
	}
}
