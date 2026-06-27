package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/capturesession"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/drain"
	"github.com/cristianodewes/agent-memory/client/internal/handoff"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
	applog "github.com/cristianodewes/agent-memory/client/internal/log"
	"github.com/cristianodewes/agent-memory/client/internal/oidc"
	"github.com/cristianodewes/agent-memory/client/internal/orientation"
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

// The proactive recall call on UserPromptSubmit (#84) bounds itself with a deadline resolved at runtime
// from AGENT_MEMORY_RECALL_TIMEOUT (config.ResolveRecallTimeout, default config.DefaultRecallTimeout).
// Unlike the capture hot path (local disk IO only, invariant #5), this event deliberately makes ONE
// bounded network call: the server runs LLM query-expansion + rerank, so it needs more than the capture
// budget but must never hang the prompt. The deadline is configurable (#125) because that LLM latency
// swings with the provider/model and load; on timeout the call is abandoned and nothing is injected —
// the prompt is already captured and proceeds regardless.

// bodyPreviewRunes bounds the debug-only payload preview so a large body never bloats the log. The
// preview is masked for secrets by the logger's redaction boundary before it is written (#117).
const bodyPreviewRunes = 200

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
//
// A structured logger writes a durable JSON trail of the whole cycle to `<data-dir>/logs/client.log`
// (#117). It is fire-and-forget (non-blocking, off the hot path) and is Closed on return so the
// short-lived process flushes its records. The existing stderr lines are kept verbatim so the
// interactive `claude --debug` experience does not regress; the file is the new durable source.
func runHook(cmd *cobra.Command, eventFlag string, raw []byte) error {
	cwd, _ := os.Getwd()
	id := identity.Resolve(cwd)
	cfg := config.Load().WithIdentityOverrides(id.ServerURL, id.Token)

	logger := newHookLogger(cmd, cfg)
	defer func() { _ = logger.Close() }()

	// OIDC fallback (issue #39 PR2): when no explicit token is configured (env or .agent-memory.toml
	// marker), attach the device-grant access token from `auth login oidc-device` so the native hook
	// authenticates as the verified OIDC subject. A single small file read; the explicit token always
	// wins, so this never overrides a configured one. `oidcExpired` records the "credential present but
	// expired" case so the network-bound events below can surface a clear re-login hint instead of a
	// silent 401.
	var oidcExpired bool
	if cfg.Token == "" {
		cfg.Token, oidcExpired = oidc.AccessTokenStatus(cfg.DataDir)
	}

	p, err := hook.BuildPayload(raw, hook.InputContext{
		Event:     eventFlag,
		Workspace: id.Workspace,
		Project:   id.Project,
		Cwd:       cwd,
		Now:       time.Now().UTC(),
	})
	if err != nil {
		logger.Error("capture: build payload failed", "event", eventFlag, "err", err.Error())
		return err
	}

	sp, err := spool.Open(cfg.SpoolDir())
	if err != nil {
		logger.Error("capture: open spool failed", "err", err.Error())
		return err
	}
	name, err := sp.Append(p)
	if err != nil {
		logger.Error("capture: spool append failed", "kind", string(p.Kind), "err", err.Error())
		return err
	}
	logCapture(logger, p, name)

	// Capture is done and durable. From here on, nothing may fail the command.
	switch p.Kind {
	case core.KindSessionStart:
		// Record this project's current capture session (#87) BEFORE the slow boundary drain, so the
		// MCP `headersHelper` reads the freshest id with the smallest startup window. Best-effort: a
		// write failure only means session_aware MCP calls fail-closed on the server, never a leak.
		recordCaptureSession(cmd, logger, cfg, p)
		maybeWarnOidcExpired(cmd, logger, oidcExpired)
		runBoundaryDrain(cmd, logger, cfg, sp, id.Workspace, id.Project, true)
	case core.KindSessionEnd:
		maybeWarnOidcExpired(cmd, logger, oidcExpired)
		runBoundaryDrain(cmd, logger, cfg, sp, id.Workspace, id.Project, false)
	case core.KindUserPrompt:
		// Proactive recall injection (#84, closing the recall-gap): pull memory relevant to THIS prompt
		// and inject it as additional-context. The prompt text is the captured body. Bounded + advisory
		// — it never blocks or fails the prompt.
		maybeWarnOidcExpired(cmd, logger, oidcExpired)
		var prompt string
		if p.Body != nil {
			prompt = *p.Body
		}
		runRecallInjection(cmd, logger, cfg, id.Workspace, id.Project, prompt)
	default:
		// Regular event: no network on the hot path.
		logger.Debug("capture: regular event (no network on hot path)", "kind", string(p.Kind))
	}
	return nil
}

// newHookLogger resolves the effective log level (flag > AGENT_MEMORY_LOG_LEVEL > AGENT_MEMORY_DEBUG >
// info) and opens the rotating file logger under <data-dir>/logs. A logger-init failure must never
// fail capture: it degrades to a no-op logger (with a one-line stderr note) so the hot path proceeds.
func newHookLogger(cmd *cobra.Command, cfg config.Config) *applog.Logger {
	level := applog.Resolve(verbosity(cmd), cfg.LogLevel, cfg.Debug)
	logger, err := applog.New(applog.Options{Dir: cfg.LogsDir(), Level: level})
	if err != nil {
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: log init:", err)
		return applog.Nop()
	}
	return logger
}

// logCapture writes the durable "event captured" record (the spine of the per-event trail) and, only
// at debug, a masked + truncated payload preview. The body is potentially sensitive, so it appears
// solely at debug and still passes the logger's redaction boundary (invariant #6 / DD-010).
func logCapture(logger *applog.Logger, p hook.Payload, spoolFile string) {
	logger.Info("event captured",
		"kind", string(p.Kind),
		"event", p.Event,
		"workspace", p.Workspace.String(),
		"project", p.Project.String(),
		"session", p.SessionID.String(),
		"spoolFile", spoolFile,
	)
	if p.Body != nil && *p.Body != "" {
		logger.Debug("event payload",
			"kind", string(p.Kind),
			"bodyBytes", len(*p.Body),
			"bodyPreview", previewRunes(*p.Body, bodyPreviewRunes),
		)
	}
}

// previewRunes trims s and truncates it to at most maxRunes runes (appending an ellipsis when cut),
// never splitting a UTF-8 rune.
func previewRunes(s string, maxRunes int) string {
	s = strings.TrimSpace(s)
	r := []rune(s)
	if len(r) > maxRunes {
		return string(r[:maxRunes]) + "…"
	}
	return s
}

// maybeWarnOidcExpired prints a one-line re-login hint when the only available OIDC credential is
// expired, so the request that is about to go out unauthenticated surfaces clear guidance instead of a
// silent 401. Called only on the events that actually reach the server (boundaries + prompt), never on
// the per-event capture hot path. (#39 PR2)
func maybeWarnOidcExpired(cmd *cobra.Command, logger *applog.Logger, expired bool) {
	if expired {
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: your OIDC login has expired; "+
			"run `agent-memory auth login oidc-device` to re-authenticate")
		logger.Warn("oidc credential expired; request will go out unauthenticated")
	}
}

// recordCaptureSession records this project's current capture session id (issue #87) so the MCP
// transport can bind a no-scope tool call to the right session under auto_scope=session_aware. It is
// keyed by the SAME normalized (workspace, project) the event was attributed to, so the
// `mcp-session-header` command — invoked by Claude Code's headersHelper with that identity baked in at
// install time — reads back exactly this id. Best-effort and non-fatal: capture has already succeeded,
// and on any error the server simply fails closed (session_aware never defaults to a global scope).
func recordCaptureSession(cmd *cobra.Command, logger *applog.Logger, cfg config.Config, p hook.Payload) {
	if err := capturesession.Write(
		cfg.DataDir, p.Workspace.String(), p.Project.String(), p.SessionID.String()); err != nil {
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: record capture session:", err)
		logger.Error("record capture session failed", "err", err.Error())
	}
}

// runBoundaryDrain ships the spool at a session boundary. start=true runs the session-start shape (a
// backlog drain then the handoff fetch+inject, #23); start=false runs the session-end main drain. All
// errors are logged to stderr and swallowed — the capture already succeeded and the spool is intact.
//
// On session-start, after the backlog drain, it accepts the project's open handoff and, when present,
// prints it to STDOUT as Claude Code SessionStart additional-context (so the next agent sees "where
// you left off" before the first prompt). No open handoff, or any fetch error, prints nothing — a
// clean no-op that never breaks session start. The whole cycle (drain count + latency, each
// handoff/briefing/scent fetch status) is mirrored to the durable log (#117).
func runBoundaryDrain(
	cmd *cobra.Command, logger *applog.Logger, cfg config.Config,
	sp *spool.Spool, workspace, project string, start bool) {
	ctx, cancel := context.WithTimeout(context.Background(), boundaryDrainTimeout)
	defer cancel()

	boundary := "session-end"
	if start {
		boundary = "session-start"
	}

	client := apiclient.New(cfg.ServerURL,
		apiclient.WithToken(cfg.Token), apiclient.WithLogger(logger.Slog()))
	d := drain.New(sp, client, drain.Options{Logger: logger.Slog()})

	t0 := time.Now()
	var res drain.Result
	var drainErr error
	if start {
		fetcher := handoff.NewFetcher(client, workspace, project)
		var handoffErr error
		res, drainErr, handoffErr = d.OnSessionStart(ctx, fetcher)
		if handoffErr != nil {
			// Advisory: a missing/unwired server must not break session start (#23: server-down
			// resilience). Log and still attempt the orientation sections below.
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: handoff fetch:", handoffErr)
			logger.Warn("handoff fetch failed", "err", handoffErr.Error())
		} else {
			logger.Debug("handoff fetched", "present", fetcher.Rendered() != "")
		}
		// Assemble the SessionStart orientation block (#85): the handoff (first, most actionable), a
		// bounded project briefing snapshot, and the scent "memory map" (busiest folders + hub pages) so
		// the agent knows what memory it can recall. Both reads are read-only and advisory — any error
		// omits just that section; each section is optional, and an empty block emits nothing.
		briefing, briefErr := client.GetBriefing(ctx, workspace, project, orientation.RecentLimit)
		if briefErr != nil {
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: briefing:", briefErr)
			logger.Warn("briefing fetch failed", "err", briefErr.Error())
			briefing = nil
		}
		scent, scentErr := client.GetScent(
			ctx, workspace, project, orientation.ScentFolders, orientation.ScentHubs)
		if scentErr != nil {
			fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: scent:", scentErr)
			logger.Warn("scent fetch failed", "err", scentErr.Error())
			scent = nil
		}
		if out, ok := hook.SessionStartAdditionalContext(
			orientation.Render(fetcher.Rendered(), briefing, scent)); ok {
			// Emit the injected context on stdout for Claude Code to add to the session.
			fmt.Fprintln(cmd.OutOrStdout(), string(out))
			logger.Debug("session-start context injected", "bytes", len(out))
		}
	} else {
		res, drainErr = d.OnSessionEnd(ctx)
	}
	latency := time.Since(t0)
	if drainErr != nil && !errors.Is(drainErr, drain.ErrThrottled) {
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: drain:", drainErr)
		logger.Error("drain failed", "boundary", boundary, "err", drainErr.Error())
	}
	if res.Quarantined > 0 {
		fmt.Fprintf(cmd.ErrOrStderr(),
			"agent-memory: quarantined %d corrupt spool entr%s\n",
			res.Quarantined, plural(res.Quarantined))
	}
	logger.Info("drain complete",
		"boundary", boundary,
		"sent", res.Sent,
		"quarantined", res.Quarantined,
		"remaining", res.Remaining,
		"throttled", res.Throttled,
		"latencyMs", latency.Milliseconds(),
	)
}

// runRecallInjection performs proactive recall injection for a user prompt (#84). It POSTs the prompt
// to /recall/inject and, when the server returns a non-empty curated block, prints it to STDOUT as
// Claude Code UserPromptSubmit additional-context so the agent sees relevant memory before it answers.
//
// Everything is advisory: an empty prompt, an empty result (low-signal prompt), a timeout, an
// unwired/missing server, or any transport error logs to stderr (at most) and injects nothing. The
// prompt is NEVER blocked or failed — capture (the spool append) has already happened and is durable.
func runRecallInjection(
	cmd *cobra.Command, logger *applog.Logger, cfg config.Config, workspace, project, prompt string) {
	if strings.TrimSpace(prompt) == "" {
		logger.Debug("recall inject skipped: empty prompt")
		return // nothing to recall on (a prompt event with no body) — skip the network call entirely
	}
	// Resolve the deadline at runtime (#125): AGENT_MEMORY_RECALL_TIMEOUT, else the default. The context
	// deadline is the authoritative cap; the HTTP client gets a small headroom over it so the transport
	// never races the context and abandons the call early.
	timeout := config.ResolveRecallTimeout()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	client := apiclient.New(cfg.ServerURL,
		apiclient.WithToken(cfg.Token), apiclient.WithLogger(logger.Slog()),
		apiclient.WithHTTPClient(&http.Client{Timeout: config.RecallHTTPTimeout(timeout)}))
	t0 := time.Now()
	block, err := client.InjectRecall(ctx, workspace, project, prompt)
	latency := time.Since(t0)
	if err != nil {
		// Advisory: a missing/unwired/slow server must not block or delay the prompt (#84).
		fmt.Fprintln(cmd.ErrOrStderr(), "agent-memory: recall inject:", err)
		logger.Warn("recall inject failed", "err", err.Error(), "latencyMs", latency.Milliseconds())
		return
	}
	if out, ok := hook.UserPromptSubmitAdditionalContext(block); ok {
		// Emit the recall block on stdout for Claude Code to add to the turn's context.
		fmt.Fprintln(cmd.OutOrStdout(), string(out))
		logger.Info("recall injected", "chars", len(block), "latencyMs", latency.Milliseconds())
	} else {
		logger.Debug("recall: nothing to inject (low-signal prompt)",
			"latencyMs", latency.Milliseconds())
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
