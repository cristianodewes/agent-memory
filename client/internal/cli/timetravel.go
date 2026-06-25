package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// Time-travel / backup / bootstrap subcommands (issue #34): thin HTTP triggers of the
// server's /checkpoints, /restore-page, /backup, /restore and /bootstrap endpoints.
// The client never touches Postgres or the wiki directly (ARCHITECTURE §2.1).

// newCheckpointsCmd: agent-memory checkpoints [--limit N]
func newCheckpointsCmd() *cobra.Command {
	var serverURL string
	var limit int
	cmd := &cobra.Command{
		Use:           "checkpoints",
		Short:         "List recent wiki commits (time-travel points)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if limit <= 0 {
				return fmt.Errorf("--limit must be positive")
			}
			q := url.Values{}
			q.Set("limit", strconv.Itoa(limit))
			target := resolveServerURL(serverURL) + "/checkpoints?" + q.Encode()
			return getJSON(cmd.OutOrStdout(), target)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().IntVar(&limit, "limit", 20, "maximum number of checkpoints to list")
	return cmd
}

// newRestorePageCmd: agent-memory restore-page --workspace W --project P --path PATH --from REV
func newRestorePageCmd() *cobra.Command {
	var serverURL, workspace, project, path, from string
	cmd := &cobra.Command{
		Use:           "restore-page",
		Short:         "Restore a single page's markdown from a git revision and reindex it",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--workspace": workspace, "--project": project,
				"--path": path, "--from": from}); err != nil {
				return err
			}
			body := map[string]any{
				"workspace": workspace, "project": project, "path": path, "from": from}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/restore-page", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&workspace, "workspace", "", "the page's workspace slug")
	cmd.Flags().StringVar(&project, "project", "", "the page's project slug")
	cmd.Flags().StringVar(&path, "path", "", "the page path (e.g. concepts/recall.md)")
	cmd.Flags().StringVar(&from, "from", "", "the git revision to restore from (sha, HEAD~2, tag)")
	return cmd
}

// newBackupCmd: agent-memory backup --out PATH
func newBackupCmd() *cobra.Command {
	var serverURL, out string
	cmd := &cobra.Command{
		Use:           "backup",
		Short:         "Write an online DB-only backup tarball (the source stays writable)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{"--out": out}); err != nil {
				return err
			}
			body := map[string]any{"target": out}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/backup", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&out, "out", "",
		"path of the .tar.gz archive to write (server-side path)")
	return cmd
}

// newRestoreCmd: agent-memory restore --in PATH [--force] [--yes]
func newRestoreCmd() *cobra.Command {
	var serverURL, in string
	var force, yes bool
	cmd := &cobra.Command{
		Use:   "restore",
		Short: "Restore DB-only state from a backup tarball (destructive; live-process guarded)",
		Long: "Restore the DB-only primary state from a backup tarball produced by 'backup'.\n\n" +
			"This is destructive: it truncates the capture/state tables before reloading them, and is\n" +
			"guarded by a live-process check (invariant #9) — the server refuses while a live\n" +
			"agent-memory process holds the data dir. Pass --force to override that guard. --yes\n" +
			"confirms the destructive reload.",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{"--in": in}); err != nil {
				return err
			}
			if !yes {
				return fmt.Errorf("restore overwrites DB state; pass --yes to confirm")
			}
			body := map[string]any{"source": in, "force": force}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/restore", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&in, "in", "", "path of the .tar.gz archive to restore (server-side path)")
	cmd.Flags().BoolVar(&force, "force", false, "override the live-process guard and restore anyway")
	cmd.Flags().BoolVar(&yes, "yes", false, "confirm the destructive reload")
	return cmd
}

// newBootstrapCmd: agent-memory bootstrap --workspace W --project P --repo PATH
func newBootstrapCmd() *cobra.Command {
	var serverURL, workspace, project, repo string
	cmd := &cobra.Command{
		Use:           "bootstrap",
		Short:         "Seed a project's memory from its existing repo history via one LLM pass",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--workspace": workspace, "--project": project, "--repo": repo}); err != nil {
				return err
			}
			body := map[string]any{"workspace": workspace, "project": project, "repo": repo}
			return postLifecycle(cmd.OutOrStdout(), resolveServerURL(serverURL), "/bootstrap", body)
		},
	}
	addServerFlag(cmd, &serverURL)
	cmd.Flags().StringVar(&workspace, "workspace", "", "the target workspace slug")
	cmd.Flags().StringVar(&project, "project", "", "the target project slug")
	cmd.Flags().StringVar(&repo, "repo", "",
		"the source repository directory to mine for history (server-side path)")
	return cmd
}

// getJSON GETs a URL and pretty-prints the JSON response; a non-2xx status is an error
// carrying the server's body (mirrors postLifecycle so the two read consistently).
func getJSON(out io.Writer, target string) error {
	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return fmt.Errorf("building request: %w", err)
	}
	client := &http.Client{Timeout: 1 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("calling %s: %w", target, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("reading response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("request failed: server returned %s: %s",
			resp.Status, strings.TrimSpace(string(respBody)))
	}
	var pretty bytes.Buffer
	if json.Indent(&pretty, respBody, "", "  ") == nil {
		fmt.Fprintln(out, pretty.String())
	} else {
		fmt.Fprintln(out, strings.TrimSpace(string(respBody)))
	}
	return nil
}
