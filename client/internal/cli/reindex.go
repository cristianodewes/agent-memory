package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// reindexRequest is the POST /reindex body. Fields mirror the server's
// ReindexController.Request (mode/since/reembed); omitempty keeps an
// unspecified field absent so the server applies its defaults.
type reindexRequest struct {
	Mode    string `json:"mode,omitempty"`
	Since   string `json:"since,omitempty"`
	Reembed bool   `json:"reembed,omitempty"`
}

// newReindexCmd builds the `agent-memory reindex` subcommand: a thin trigger for
// the server's reindex operation, which rebuilds the Postgres index from the
// markdown wiki source of truth (DD-002). It POSTs to /reindex and prints the
// returned report; it never touches Postgres or the wiki directly.
func newReindexCmd() *cobra.Command {
	var (
		serverURL   string
		token       string
		incremental bool
		full        bool
		since       string
		reembed     bool
	)

	cmd := &cobra.Command{
		Use:   "reindex",
		Short: "Rebuild the server's Postgres index from the wiki (full or incremental)",
		Long: "Rebuild the derived Postgres index from the markdown wiki source of truth.\n\n" +
			"The wiki is authoritative and Postgres is a derived index (DD-002), so reindex makes\n" +
			"the database disposable. A full run wipes and rebuilds pages/links; an incremental run\n" +
			"(--incremental, optionally --since <ref>) rebuilds only files changed since a git ref.\n" +
			"Re-embedding is expensive and only runs with --reembed (and only if the server has an\n" +
			"embedder configured).",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if full && incremental {
				return fmt.Errorf("--full and --incremental are mutually exclusive")
			}
			mode := "full"
			if incremental {
				mode = "incremental"
			}
			if since != "" && mode != "incremental" {
				return fmt.Errorf("--since is only valid with --incremental")
			}

			req := reindexRequest{Mode: mode, Since: since, Reembed: reembed}
			return runReindex(cmd.OutOrStdout(), resolveAdminTarget(serverURL, token), req)
		},
	}

	addAdminFlags(cmd, &serverURL, &token)
	cmd.Flags().BoolVar(&full, "full", false, "full rebuild of the whole index (default)")
	cmd.Flags().BoolVar(&incremental, "incremental", false,
		"rebuild only files changed since a git ref")
	cmd.Flags().StringVar(&since, "since", "",
		"git ref to diff from for --incremental (default the previous commit, HEAD~1)")
	cmd.Flags().BoolVar(&reembed, "reembed", false,
		"also recompute embeddings (expensive; no-op if the server has no embedder)")
	return cmd
}

// runReindex POSTs the request to the target's /reindex and renders the JSON report.
func runReindex(out io.Writer, target adminTarget, req reindexRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("encoding request: %w", err)
	}

	httpReq, err := target.newRequest(http.MethodPost, "/reindex", body)
	if err != nil {
		return err
	}

	client := &http.Client{Timeout: 10 * time.Minute}
	resp, err := client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("calling %s: %w", httpReq.URL, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("reading response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("reindex failed: server returned %s: %s",
			resp.Status, strings.TrimSpace(string(respBody)))
	}

	return renderReport(out, respBody)
}

// renderReport prints a human-readable one-line-per-field summary of the report,
// falling back to the raw body if it is not the expected shape.
func renderReport(out io.Writer, body []byte) error {
	var report struct {
		Mode          string `json:"mode"`
		FilesScanned  int    `json:"filesScanned"`
		PagesIndexed  int    `json:"pagesIndexed"`
		PagesDeleted  int    `json:"pagesDeleted"`
		LinksWritten  int    `json:"linksWritten"`
		LinksResolved int    `json:"linksResolved"`
		Skipped       []struct {
			Path   string `json:"path"`
			Reason string `json:"reason"`
		} `json:"skipped"`
	}
	if err := json.Unmarshal(body, &report); err != nil {
		// Not the shape we expected — show what the server sent rather than failing.
		fmt.Fprintln(out, string(body))
		return nil
	}

	fmt.Fprintf(out, "reindex %s: scanned=%d indexed=%d deleted=%d links=%d (resolved %d)\n",
		report.Mode, report.FilesScanned, report.PagesIndexed, report.PagesDeleted,
		report.LinksWritten, report.LinksResolved)
	for _, s := range report.Skipped {
		fmt.Fprintf(out, "  skipped %s: %s\n", s.Path, s.Reason)
	}
	return nil
}
