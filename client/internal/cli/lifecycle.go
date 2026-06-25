package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// Lifecycle subcommands (issue #33): thin HTTP triggers of the server's project
// rename/move/purge and the guarded reset. The client never touches Postgres or
// the wiki directly (ARCHITECTURE §2.1) — these only POST to the server.

// newRenameProjectCmd: agent-memory rename-project --workspace W --project P --to NEW
func newRenameProjectCmd() *cobra.Command {
	var serverURL, workspace, project, to string
	cmd := &cobra.Command{
		Use:           "rename-project",
		Short:         "Rename a project within its workspace",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--workspace": workspace, "--project": project, "--to": to}); err != nil {
				return err
			}
			body := map[string]any{"workspace": workspace, "project": project, "newProject": to}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/projects/rename", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&workspace, "workspace", "", "the project's workspace slug")
	cmd.Flags().StringVar(&project, "project", "", "the project's current slug")
	cmd.Flags().StringVar(&to, "to", "", "the new project slug")
	return cmd
}

// newMoveProjectCmd: agent-memory move-project --workspace W --project P --to-workspace W2 [--to NEW]
func newMoveProjectCmd() *cobra.Command {
	var serverURL, workspace, project, toWorkspace, to string
	cmd := &cobra.Command{
		Use:           "move-project",
		Short:         "Move a project to another workspace (optionally renaming it)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--workspace": workspace, "--project": project, "--to-workspace": toWorkspace}); err != nil {
				return err
			}
			body := map[string]any{
				"workspace": workspace, "project": project,
				"newWorkspace": toWorkspace, "newProject": to,
			}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/projects/move", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&workspace, "workspace", "", "the project's current workspace slug")
	cmd.Flags().StringVar(&project, "project", "", "the project's slug")
	cmd.Flags().StringVar(&toWorkspace, "to-workspace", "", "the destination workspace slug")
	cmd.Flags().StringVar(&to, "to", "", "the new project slug (default: keep the current name)")
	return cmd
}

// newPurgeProjectCmd: agent-memory purge-project --workspace W --project P [--yes]
func newPurgeProjectCmd() *cobra.Command {
	var serverURL, workspace, project string
	var yes bool
	cmd := &cobra.Command{
		Use:           "purge-project",
		Short:         "Delete a project's wiki subtree and DB rows (irreversible)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--workspace": workspace, "--project": project}); err != nil {
				return err
			}
			if !yes {
				return fmt.Errorf(
					"purge-project is irreversible; pass --yes to confirm purging %s/%s",
					workspace, project)
			}
			body := map[string]any{"workspace": workspace, "project": project}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/projects/purge", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&workspace, "workspace", "", "the project's workspace slug")
	cmd.Flags().StringVar(&project, "project", "", "the project's slug")
	cmd.Flags().BoolVar(&yes, "yes", false, "confirm the irreversible purge")
	return cmd
}

// newResetCmd: agent-memory reset [--force] [--yes]
func newResetCmd() *cobra.Command {
	var serverURL string
	var force, yes bool
	cmd := &cobra.Command{
		Use:   "reset",
		Short: "Wipe ALL memory (DB + wiki); refused while a live process holds the data dir",
		Long: "Wipe all agent-memory state — every DB table and the wiki contents.\n\n" +
			"This is destructive and guarded by a live-process check (invariant #9): the server\n" +
			"refuses to reset while a live agent-memory process holds the data dir. Pass --force to\n" +
			"override that guard (you accept the risk). --yes confirms the irreversible wipe.",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if !yes {
				return fmt.Errorf("reset is irreversible and wipes ALL memory; pass --yes to confirm")
			}
			body := map[string]any{"force": force}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/reset", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().BoolVar(&force, "force", false,
		"override the live-process guard and reset anyway")
	cmd.Flags().BoolVar(&yes, "yes", false, "confirm the irreversible wipe")
	return cmd
}

// --- shared helpers -------------------------------------------------------------------------------

func addServerFlag(cmd *cobra.Command, target *string) {
	cmd.Flags().StringVar(target, "server", "",
		"server base URL (default $AGENT_MEMORY_SERVER or "+defaultServerURL+")")
}

// requireAll returns an error naming the first missing (empty) required flag.
func requireAll(flags map[string]string) error {
	for name, value := range flags {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s is required", name)
		}
	}
	return nil
}

// postLifecycle POSTs the JSON body to <base><path> and prints the server's JSON
// response. A non-2xx status is surfaced as an error carrying the server's reason
// (so a 409 refusal — e.g. a live-process reset block — is visible to the user).
func postLifecycle(out io.Writer, base, path string, body map[string]any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encoding request: %w", err)
	}
	url := base + path
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("building request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 2 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("calling %s: %w", url, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("reading response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("%s failed: server returned %s: %s",
			strings.TrimPrefix(path, "/"), resp.Status, strings.TrimSpace(string(respBody)))
	}
	// Pretty-print the JSON response for the operator; fall back to raw on any oddity.
	var pretty bytes.Buffer
	if json.Indent(&pretty, respBody, "", "  ") == nil {
		fmt.Fprintln(out, pretty.String())
	} else {
		fmt.Fprintln(out, strings.TrimSpace(string(respBody)))
	}
	return nil
}
