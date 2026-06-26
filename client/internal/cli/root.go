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
	// --verbose/-v is a persistent (global) flag so any subcommand can raise log verbosity. It is a
	// count: each -v bumps it; ≥1 selects debug. It composes with AGENT_MEMORY_LOG_LEVEL /
	// AGENT_MEMORY_DEBUG via internal/log.Resolve (the flag wins). (#117)
	root.PersistentFlags().CountP("verbose", "v",
		"increase log verbosity (-v ⇒ debug); also AGENT_MEMORY_LOG_LEVEL / AGENT_MEMORY_DEBUG")
	root.AddCommand(&cobra.Command{
		Use:   "version",
		Short: "Print the client version",
		Run: func(_ *cobra.Command, _ []string) {
			fmt.Println(Version)
		},
	})
	root.AddCommand(newHookCmd())
	root.AddCommand(newLogsCmd())
	root.AddCommand(newDoctorCmd())
	root.AddCommand(newMcpSessionHeaderCmd())
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
	root.AddCommand(newUpdateCmd())
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

// verbosity reads the inherited persistent -v/--verbose count (0 when absent or on any lookup error),
// so a subcommand can fold the flag into internal/log.Resolve without re-declaring it. (#117)
func verbosity(cmd *cobra.Command) int {
	n, err := cmd.Flags().GetCount("verbose")
	if err != nil {
		return 0
	}
	return n
}
