package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/drain"
	"github.com/cristianodewes/agent-memory/client/internal/handoff"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
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

// recallInjectTimeout bounds the proactive recall call on UserPromptSubmit (#84). Unlike the capture
// hot path (local disk IO only, invariant #5), this event deliberately makes ONE bounded network call:
// the server runs LLM query-expansion + rerank, so it needs more than the capture budget but must
// never hang the prompt. On timeout the call is abandoned and nothing is injected — the prompt is
// already captured and proceeds regardless.
const recallInjectTimeout = 5 * time.Second

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
	cwd, _ := os.Getwd()
	id := identity.Resolve(cwd)
	cfg := config.Load().WithIdentityOverrides(id.ServerURL, id.Token)

	p, err := hook.BuildPayload(raw, hook.InputContext{
		Event:     eventFlag,
		Workspace: id.Workspace,
		Project:   id.Project,
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
		runBoundaryDrain(cmd, cfg, sp, id.Workspace, id.Project, true)
	case core.KindSessionEnd:
		runBoundaryDrain(cmd, cfg, sp, id.Workspace, id.Project, false)
	case core.KindUserPrompt:
		// Proactive recall injection (#84, closing the recall-gap): pull memory relevant to THIS prompt
		// and inject it as additional-context. The prompt text is the captured body. Bounded + advisory
		// — it never blocks or fails the prompt.
		var prompt string
		if p.Body != nil {
			prompt = *p.Body
		}
		runRecallInjection(cmd, cfg, id.Workspace, id.Project, prompt)
	default:
		// Regular event: no network on the hot path.
	}
	return nil
}

// runBoundaryDrain ships the spool at a session boundary. start=true runs the session-start shape (a
// backlog drain then the handoff fetch+inject, #23); start=false runs the session-end main drain. All
// errors are logged to stderr and swallowed — the capture already succeeded and the spool is intact.
//
// On session-start, after the backlog drain, it accepts the project's open handoff and, when present,
// prints it to STDOUT as Claude Code SessionStart additional-context (so the next agent sees "where
// you left off" before the first prompt). No open handoff, or any fetch error, prints nothing — a
// clean no-op that never breaks session start.
func runBoundaryDrain(
	cmd *cobra.Command, cfg config.Config, sp *spool.Spool, workspace, project string, start bool) {
	ctx, cancel := context.WithTimeout(context.Background(), boundaryDrainTimeout)
	defer cancel()

	client := apiclient.New(cfg.ServerURL, apiclient.WithToken(cfg.Token))
	d := drain.New(sp, client, drain.Options{})

	var res drain.Result
	var drainErr error
	if start {
		fetcher := handoff.NewFetcher(client, workspace, project)
		var handoffErr error
		res, drainErr, handoffErr = d.OnSessionStart(ctx, fetcher)
		if handoffErr != nil {
			// Advisory: a missing/unwired server must not break session start (#23: server-down
			// resilience). Log and continue with no injection.
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: handoff fetch:", handoffErr)
		} else if out, ok := hook.SessionStartAdditionalContext(fetcher.Rendered()); ok {
			// Emit the injected context on stdout for Claude Code to add to the session.
			fmt.Fprintln(cmd.OutOrStdout(), string(out))
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

// runRecallInjection performs proactive recall injection for a user prompt (#84). It POSTs the prompt
// to /recall/inject and, when the server returns a non-empty curated block, prints it to STDOUT as
// Claude Code UserPromptSubmit additional-context so the agent sees relevant memory before it answers.
//
// Everything is advisory: an empty prompt, an empty result (low-signal prompt), a timeout, an
// unwired/missing server, or any transport error logs to stderr (at most) and injects nothing. The
// prompt is NEVER blocked or failed — capture (the spool append) has already happened and is durable.
func runRecallInjection(
	cmd *cobra.Command, cfg config.Config, workspace, project, prompt string) {
	if strings.TrimSpace(prompt) == "" {
		return // nothing to recall on (a prompt event with no body) — skip the network call entirely
	}
	ctx, cancel := context.WithTimeout(context.Background(), recallInjectTimeout)
	defer cancel()

	client := apiclient.New(cfg.ServerURL, apiclient.WithToken(cfg.Token))
	block, err := client.InjectRecall(ctx, workspace, project, prompt)
	if err != nil {
		// Advisory: a missing/unwired/slow server must not block or delay the prompt (#84).
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: recall inject:", err)
		return
	}
	if out, ok := hook.UserPromptSubmitAdditionalContext(block); ok {
		// Emit the recall block on stdout for Claude Code to add to the turn's context.
		fmt.Fprintln(cmd.OutOrStdout(), string(out))
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

func plural(n int) string {
	if n == 1 {
		return "y"
	}
	return "ies"
}
