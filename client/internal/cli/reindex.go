package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// defaultServerURL is the server base URL used when neither --server nor the
// AGENT_MEMORY_SERVER environment variable is set. The client is a thin HTTP
// client of the server (ARCHITECTURE §2.1); reindex is a server operation
// (issue #14) the client only triggers.
const defaultServerURL = "http://127.0.0.1:8080"

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

			base := resolveServerURL(serverURL)
			req := reindexRequest{Mode: mode, Since: since, Reembed: reembed}
			return runReindex(cmd.OutOrStdout(), base, req)
		},
	}

	cmd.Flags().StringVar(&serverURL, "server", "",
		"server base URL (default $AGENT_MEMORY_SERVER or "+defaultServerURL+")")
	cmd.Flags().BoolVar(&full, "full", false, "full rebuild of the whole index (default)")
	cmd.Flags().BoolVar(&incremental, "incremental", false,
		"rebuild only files changed since a git ref")
	cmd.Flags().StringVar(&since, "since", "",
		"git ref to diff from for --incremental (default the previous commit, HEAD~1)")
	cmd.Flags().BoolVar(&reembed, "reembed", false,
		"also recompute embeddings (expensive; no-op if the server has no embedder)")
	return cmd
}

// resolveServerURL applies the precedence: explicit flag, then env, then default.
func resolveServerURL(flagValue string) string {
	if flagValue != "" {
		return strings.TrimRight(flagValue, "/")
	}
	if env := os.Getenv("AGENT_MEMORY_SERVER"); env != "" {
		return strings.TrimRight(env, "/")
	}
	return defaultServerURL
}

// runReindex POSTs the request to <base>/reindex and renders the JSON report.
func runReindex(out io.Writer, base string, req reindexRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("encoding request: %w", err)
	}

	url := base + "/reindex"
	httpReq, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("building request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 10 * time.Minute}
	resp, err := client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("calling %s: %w", url, err)
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
