package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	applog "github.com/cristianodewes/agent-memory/client/internal/log"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
	"github.com/spf13/cobra"
)

// defaultLogsTail is how many trailing lines `logs` prints when --tail is not given. Bounded so the
// command is useful without dumping the whole (already size-rotated) file.
const defaultLogsTail = 200

// followPollInterval is how often `logs --follow` checks the file for appended bytes.
const followPollInterval = 500 * time.Millisecond

// newLogsCmd builds `agent-memory logs`: print (and optionally follow) the durable client log at
// <data-dir>/logs/client.log (#117). It exists so an operator can answer "what did the client do?"
// without hunting for the file or reaching for `cat`/`tail`.
func newLogsCmd() *cobra.Command {
	var tail int
	var follow bool
	var dataDir string

	cmd := &cobra.Command{
		Use:   "logs",
		Short: "Print the client log (<data-dir>/logs/client.log)",
		Long: "Prints the durable, rotating client log written by the capture hooks. With --tail it " +
			"shows only the last N lines; with --follow it then streams appended lines until interrupted.\n\n" +
			"By default the log records only request method/path/status/latency for server calls — never " +
			"headers or bodies, so the bearer token cannot leak. Setting AGENT_MEMORY_LOG_RESPONSE_BODIES " +
			"truthy (1|true|yes|on) additionally logs the FULL body of every server response at debug. " +
			"WARNING: that writes memory content (recall/inject, briefing, handoff, scent) to this file in " +
			"plaintext — a deliberate debugging aid, off by default. token/Authorization stay redacted.",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg := resolveDataDirCfg(dataDir)
			path := clientLogPath(cfg)
			if err := printTail(cmd.OutOrStdout(), path, tail); err != nil {
				return err
			}
			if follow {
				return followLog(cmd.Context(), cmd.OutOrStdout(), path)
			}
			return nil
		},
	}
	cmd.Flags().IntVar(&tail, "tail", defaultLogsTail, "print only the last N lines (0 = all)")
	cmd.Flags().BoolVar(&follow, "follow", false,
		"after printing, keep streaming appended lines (interrupt to stop)")
	cmd.Flags().StringVar(&dataDir, "data-dir", "",
		"data dir root (default: AGENT_MEMORY_DATA_DIR or ~/.agent-memory)")
	return cmd
}

// newDoctorCmd builds `agent-memory doctor`: a one-shot health snapshot — data dir, server URL,
// effective log level + log path, spool pending + quarantine counts, and the last few log lines —
// reusing internal/spool so an operator can triage without inspecting files by hand (#117).
func newDoctorCmd() *cobra.Command {
	var dataDir string
	var tail int

	cmd := &cobra.Command{
		Use:          "doctor",
		Short:        "Show client health: data dir, spool/quarantine counts, log path + last lines",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg := resolveDataDirCfg(dataDir)
			out := cmd.OutOrStdout()
			level := applog.Resolve(verbosity(cmd), cfg.LogLevel, cfg.Debug)
			logPath := clientLogPath(cfg)
			pending, quarantined := spoolCounts(cfg)

			fmt.Fprintf(out, "data dir:          %s\n", cfg.DataDir)
			fmt.Fprintf(out, "server url:        %s\n", cfg.ServerURL)
			fmt.Fprintf(out, "log level:         %s\n", strings.ToLower(level.String()))
			fmt.Fprintf(out, "log file:          %s\n", logPath)
			fmt.Fprintf(out, "spool pending:     %d\n", pending)
			fmt.Fprintf(out, "spool quarantine:  %d\n", quarantined)
			fmt.Fprintf(out, "\n--- last %d log line(s) ---\n", tail)
			return printTail(out, logPath, tail)
		},
	}
	cmd.Flags().StringVar(&dataDir, "data-dir", "",
		"data dir root (default: AGENT_MEMORY_DATA_DIR or ~/.agent-memory)")
	cmd.Flags().IntVar(&tail, "tail", 10, "how many trailing log lines to show")
	return cmd
}

// resolveDataDirCfg loads the env config and applies an explicit --data-dir override (expanding ~ and
// absolutizing it the same way Load does), so `logs`/`doctor` can target a non-default data dir.
func resolveDataDirCfg(dataDir string) config.Config {
	cfg := config.Load()
	if strings.TrimSpace(dataDir) != "" {
		cfg.DataDir = config.ResolveDataDir(dataDir)
	}
	return cfg
}

// clientLogPath is the absolute path of the active client log under cfg's data dir.
func clientLogPath(cfg config.Config) string {
	return filepath.Join(cfg.LogsDir(), applog.FileName)
}

// spoolCounts reports the pending and quarantined entry counts, best-effort: any error (e.g. a
// never-created spool) reports zero rather than failing the snapshot.
func spoolCounts(cfg config.Config) (pending, quarantined int) {
	sp, err := spool.Open(cfg.SpoolDir())
	if err != nil {
		return 0, 0
	}
	if entries, err := sp.List(); err == nil {
		pending = len(entries)
	}
	if q, err := sp.QuarantinedCount(); err == nil {
		quarantined = q
	}
	return pending, quarantined
}

// printTail writes the last n lines of the file at path to w (n ≤ 0 prints all). A missing file is
// not an error — it prints a friendly "(no client log yet …)" note, since a fresh install simply has
// not logged anything yet.
func printTail(w io.Writer, path string, n int) error {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(w, "(no client log yet at %s)\n", path)
		return nil
	}
	if err != nil {
		return fmt.Errorf("logs: read %q: %w", path, err)
	}
	lines := nonEmptyLines(data)
	if n > 0 && len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	for _, ln := range lines {
		fmt.Fprintln(w, ln)
	}
	return nil
}

// nonEmptyLines splits file bytes into lines, dropping a trailing newline so an N-line tail counts
// real records (not a phantom empty final line). An empty file yields no lines.
func nonEmptyLines(data []byte) []string {
	s := strings.TrimRight(string(data), "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

// followLog streams bytes appended to path after the initial tail, polling on a fixed interval until
// the context is cancelled. It is the `tail -f` half of `logs --follow`; the one-shot tail has
// already printed the existing content, so it starts from the current end of file.
func followLog(ctx context.Context, w io.Writer, path string) error {
	var offset int64
	if info, err := os.Stat(path); err == nil {
		offset = info.Size()
	}
	ticker := time.NewTicker(followPollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			f, err := os.Open(path)
			if err != nil {
				continue // file not created yet (or briefly absent mid-rotation): retry next tick
			}
			if _, err := f.Seek(offset, io.SeekStart); err == nil {
				n, _ := io.Copy(w, f)
				offset += n
			}
			_ = f.Close()
		}
	}
}
