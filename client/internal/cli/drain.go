package cli

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/drain"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
	applog "github.com/cristianodewes/agent-memory/client/internal/log"
	"github.com/cristianodewes/agent-memory/client/internal/oidc"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
	"github.com/spf13/cobra"
)

// The manual drain (#158) ships batches of the same size as the hooks' boundary drain.
const drainBatchSize = 100

// drainProgressEvery is how many shipped events go by between two progress lines.
const drainProgressEvery = 1000

// drainSaturatedWait is how long `drain` waits before another pass when the server stayed saturated
// through a whole pass's own 429 backoff; it doubles up to drainSaturatedMaxWait and resets once a pass
// ships something. Variables, not constants, so tests do not really wait.
var (
	drainSaturatedWait    = 5 * time.Second
	drainSaturatedMaxWait = time.Minute
)

// newDrainCmd is `agent-memory drain` (#158): ship the whole spool now. The hooks only drain at a
// session boundary, inside a 15 s budget at session start (shared with the handoff and orientation
// fetches) and before Claude Code tears the session-end hook down, so a large backlog would take weeks
// to leave on its own. This runs the very same drain with no time limit until the spool is empty.
func newDrainCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "drain",
		Short: "Ship the whole local spool to the server now (no session-boundary time limit)",
		Long: "Ships every spooled event to the server, in capture order, until the spool is empty. " +
			"When the server is saturated it waits and keeps going; a transport error or Ctrl+C stops " +
			"it with the unsent events still spooled. Avoid starting or ending agent sessions while it " +
			"runs: their own boundary drain would ship the same files in parallel.",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
			defer stop()
			return runDrain(ctx, cmd)
		},
	}
}

func runDrain(ctx context.Context, cmd *cobra.Command) error {
	// The same server/token resolution as the hooks, so the spool goes where the hooks would send it.
	cwd, _ := os.Getwd()
	id := identity.Resolve(cwd)
	cfg := config.Load().WithIdentityOverrides(id.ServerURL, id.Token)
	logger := newHookLogger(cmd, cfg)
	defer func() { _ = logger.Close() }()
	if cfg.Token == "" {
		cfg.Token, _ = oidc.AccessTokenStatus(cfg.DataDir)
	}

	sp, err := spool.Open(cfg.SpoolDir())
	if err != nil {
		return err
	}
	client := apiclient.New(cfg.ServerURL,
		apiclient.WithToken(cfg.Token), apiclient.WithLogger(logger.Slog()),
		apiclient.WithResponseBodyLogging(cfg.LogResponseBodies))

	out := cmd.OutOrStdout()
	total, printed := 0, 0
	d := drain.New(sp, client, drain.Options{
		BatchSize: drainBatchSize,
		Logger:    logger.Slog(),
		Progress: func(sent int) {
			if n := total + sent; n-printed >= drainProgressEvery {
				fmt.Fprintf(out, "sent %d\n", n)
				printed = n
			}
		},
	})

	fmt.Fprintf(out, "draining %s to %s\n", cfg.SpoolDir(), cfg.ServerURL)
	t0 := time.Now()
	wait := drainSaturatedWait
	quarantined := 0
	for {
		res, err := d.Drain(ctx)
		total += res.Sent
		quarantined += res.Quarantined
		if res.Sent > 0 {
			wait = drainSaturatedWait
		}
		switch {
		case err == nil && res.Remaining >= drainBatchSize:
			// A long pass: what a live session captured meanwhile is worth another pass.
			continue
		case err == nil:
			fmt.Fprintf(out, "done: sent %d event(s) in %s", total, time.Since(t0).Round(time.Second))
			if res.Remaining > 0 {
				fmt.Fprintf(out, "; %d captured meanwhile stay spooled for the hooks", res.Remaining)
			}
			if quarantined > 0 {
				fmt.Fprintf(out, "; %d corrupt entr%s quarantined", quarantined, plural(quarantined))
			}
			fmt.Fprintln(out)
			logger.Info("drain complete", "boundary", "manual", "sent", total,
				"quarantined", quarantined, "remaining", res.Remaining,
				"latencyMs", time.Since(t0).Milliseconds())
			return nil
		case errors.Is(err, drain.ErrThrottled):
			fmt.Fprintf(out, "server saturated: sent %d so far, %d remaining; waiting %s\n",
				total, res.Remaining, wait)
			if sleepErr := sleepOrDone(ctx, wait); sleepErr != nil {
				return drainStopped(logger, total, res.Remaining, sleepErr)
			}
			wait = min(2*wait, drainSaturatedMaxWait)
		default:
			return drainStopped(logger, total, res.Remaining, err)
		}
	}
}

// drainStopped reports a drain that ended before the spool was empty (transport error, rejection,
// Ctrl+C); the unsent events are still spooled.
func drainStopped(logger *applog.Logger, sent, remaining int, err error) error {
	logger.Error("drain failed", "boundary", "manual", "sent", sent, "remaining", remaining,
		"err", err.Error())
	return fmt.Errorf("drain stopped after sending %d event(s), %d still spooled: %w",
		sent, remaining, err)
}

// sleepOrDone waits d, returning early with the context's error when it is cancelled (Ctrl+C).
func sleepOrDone(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
