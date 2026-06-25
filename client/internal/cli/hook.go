package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/drain"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
	"github.com/spf13/cobra"
)

// The capture path holds a fire-and-forget budget of ≤200 ms (ARCHITECTURE invariant #5): the hook
// does local disk IO only and never blocks on the network, so it finishes well within that. Only the
// session-boundary drain below talks to the server, under its own bounded timeout.
//
// boundaryDrainTimeout bounds the synchronous drain run at a session boundary so even a slow/throttled
// server cannot hang the agent indefinitely. The drain leaves the spool intact on timeout (nothing
// lost); the next boundary retries.
const boundaryDrainTimeout = 15 * time.Second

// newHookCmd builds the `agent-memory hook` command: the native lifecycle hook entry point. It
// parses the agent's hook JSON from stdin (or --payload), canonicalizes the event kind (#7), appends
// the event to the local spool, and returns immediately. At session boundaries it also runs the
// drain (#10) to ship the spool to the server.
func newHookCmd() *cobra.Command {
	var event string
	var payload string

	cmd := &cobra.Command{
		Use:   "hook --event <kind> [--payload -]",
		Short: "Capture a lifecycle hook event to the local spool (fire-and-forget)",
		Long: "Reads the agent's hook JSON (from --payload, or stdin when --payload is '-' or " +
			"omitted), canonicalizes the event kind, appends it to the on-disk spool, and returns " +
			"immediately. On session-start/-end it also drains the spool to the server.",
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			raw, err := readPayload(cmd.InOrStdin(), payload)
			if err != nil {
				return err
			}
			return runHook(cmd, event, raw)
		},
	}
	cmd.Flags().StringVar(&event, "event", "",
		"canonical-or-agent-native event name (e.g. UserPromptSubmit, session-end)")
	cmd.Flags().StringVar(&payload, "payload", "-",
		"agent hook JSON: '-' (default) reads stdin, otherwise a literal JSON string")
	return cmd
}

// runHook performs the capture: resolve identity, build the payload, append to the spool, then (at a
// boundary) drain. A capture failure is returned (non-zero exit); a drain failure at a boundary is
// logged but NOT fatal — the event is already safely spooled, which is the guarantee that matters.
func runHook(cmd *cobra.Command, eventFlag string, raw []byte) error {
	cfg := config.Load()

	cwd, _ := os.Getwd()
	ws, proj := resolveIdentity(cwd)

	p, err := hook.BuildPayload(raw, hook.InputContext{
		Event:     eventFlag,
		Workspace: ws,
		Project:   proj,
		Cwd:       cwd,
		Now:       time.Now().UTC(),
	})
	if err != nil {
		return err
	}

	sp, err := spool.Open(cfg.SpoolDir())
	if err != nil {
		return err
	}
	if _, err := sp.Append(p); err != nil {
		return err
	}

	// Capture is done and durable. From here on, nothing may fail the command.
	switch p.Kind {
	case core.KindSessionStart:
		runBoundaryDrain(cmd, cfg, sp, true)
	case core.KindSessionEnd:
		runBoundaryDrain(cmd, cfg, sp, false)
	default:
		// Regular event: no network on the hot path.
	}
	return nil
}

// runBoundaryDrain ships the spool at a session boundary. start=true runs the session-start shape (a
// backlog drain then the handoff-fetch seam, #23); start=false runs the session-end main drain. All
// errors are logged to stderr and swallowed — the capture already succeeded and the spool is intact.
func runBoundaryDrain(cmd *cobra.Command, cfg config.Config, sp *spool.Spool, start bool) {
	ctx, cancel := context.WithTimeout(context.Background(), boundaryDrainTimeout)
	defer cancel()

	client := apiclient.New(cfg.ServerURL, apiclient.WithToken(cfg.Token))
	d := drain.New(sp, client, drain.Options{})

	var res drain.Result
	var drainErr error
	if start {
		var handoffErr error
		res, drainErr, handoffErr = d.OnSessionStart(ctx, drain.NoHandoff)
		if handoffErr != nil {
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: handoff fetch:", handoffErr)
		}
	} else {
		res, drainErr = d.OnSessionEnd(ctx)
	}
	if drainErr != nil && !errors.Is(drainErr, drain.ErrThrottled) {
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: drain:", drainErr)
	}
	if res.Quarantined > 0 {
		fmt.Fprintf(cmd.ErrOrStderr(),
			"agent-memory: quarantined %d corrupt spool entr%s\n",
			res.Quarantined, plural(res.Quarantined))
	}
}

// readPayload reads the agent hook JSON: "-" (or empty) reads all of stdin; anything else is treated
// as a literal JSON string. Reading stdin is bounded only by the agent's input, which is small.
func readPayload(stdin io.Reader, flag string) ([]byte, error) {
	if flag == "" || flag == "-" {
		data, err := io.ReadAll(stdin)
		if err != nil {
			return nil, fmt.Errorf("hook: read stdin: %w", err)
		}
		return data, nil
	}
	return []byte(flag), nil
}

// resolveIdentity derives (workspace, project) from cwd by walking up to the main git root: the git
// root directory name is the project, and its parent directory name is the workspace (ARCHITECTURE
// §2.1). Without a git root it falls back to the cwd's own name for both, so capture still works
// outside a repo. This is the minimal resolver #10 needs; the .agent-memory.toml marker override is
// #2/#32.
func resolveIdentity(cwd string) (workspace, project string) {
	if cwd == "" {
		return "unknown", "unknown"
	}
	if root := gitRoot(cwd); root != "" {
		project = filepath.Base(root)
		workspace = filepath.Base(filepath.Dir(root))
		return workspace, project
	}
	base := filepath.Base(cwd)
	return base, base
}

// gitRoot walks up from dir looking for a `.git` entry, returning the directory that contains it, or
// "" if none is found before the filesystem root.
func gitRoot(dir string) string {
	for {
		if _, err := os.Stat(filepath.Join(dir, ".git")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return ""
		}
		dir = parent
	}
}

func plural(n int) string {
	if n == 1 {
		return "y"
	}
	return "ies"
}
