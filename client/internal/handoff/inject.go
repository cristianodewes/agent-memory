// Package handoff implements SessionStart handoff injection (issue #23, M4): at session start the
// client fetches the project's open handoff from the server and emits it as Claude Code SessionStart
// additional-context, so the next agent sees "where you left off" before the first prompt.
//
// It plugs into the drain's HandoffFetcher seam (internal/drain): the boundary runs a short backlog
// drain first (so the handoff reflects the latest captured state) and then calls the fetcher. Fetching
// uses POST /handoff/accept, which both returns the open handoff AND marks it accepted — single-use,
// so a handoff is injected exactly once and never re-injected on the next start (#22 semantics).
//
// Everything here is advisory: a missing server, a 503, or any transport error must NOT break session
// start. The fetcher records the outcome; the caller decides what (if anything) to print.
package handoff

import (
	"context"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
)

// accepter is the subset of *apiclient.Client this package needs (kept tiny for testability).
type accepter interface {
	AcceptHandoff(ctx context.Context, workspace, project string) (*apiclient.Handoff, error)
}

// Fetcher implements drain.HandoffFetcher. It accepts the project's open handoff at session start and
// holds the result so the caller can render it AFTER the boundary completes (the drain seam only
// surfaces an error; the content is read back from here). Not safe for concurrent use — it is used
// once, synchronously, on the session-start path.
type Fetcher struct {
	client    accepter
	workspace string
	project   string

	handoff *apiclient.Handoff // the accepted handoff, or nil when there was none
}

// NewFetcher builds a Fetcher that accepts the open handoff for (workspace, project) via client.
func NewFetcher(client accepter, workspace, project string) *Fetcher {
	return &Fetcher{client: client, workspace: workspace, project: project}
}

// FetchHandoff accepts (fetches + consumes) the project's open handoff. Satisfies
// drain.HandoffFetcher. The returned error is advisory; on success the handoff (if any) is stored for
// Rendered() to read. A nil handoff (HTTP 204, no open handoff) is a clean no-op, not an error.
func (f *Fetcher) FetchHandoff(ctx context.Context) error {
	h, err := f.client.AcceptHandoff(ctx, f.workspace, f.project)
	if err != nil {
		return err
	}
	f.handoff = h
	return nil
}

// Handoff returns the accepted handoff, or nil when none was injected (no open handoff, or the fetch
// failed). Exposed for tests and callers that want the structured value.
func (f *Fetcher) Handoff() *apiclient.Handoff {
	return f.handoff
}

// Rendered returns the SessionStart additional-context block for the accepted handoff, or "" when
// there is nothing to inject (no open handoff). "" signals the caller to emit no output (a clean
// no-op, per the acceptance criteria — no empty-block spam).
func (f *Fetcher) Rendered() string {
	if f.handoff == nil {
		return ""
	}
	return Render(f.handoff)
}

// Render formats a handoff as the "where you left off" additional-context block injected at session
// start. Returns "" for a nil or wholly-empty handoff so the caller can no-op. The format is plain
// Markdown (a heading, the summary, then optional Open questions / Next steps lists) — readable both
// to the agent and to a human inspecting the session.
func Render(h *apiclient.Handoff) string {
	if h == nil {
		return ""
	}
	summary := strings.TrimSpace(h.Summary)
	questions := nonEmpty(h.OpenQuestions)
	steps := nonEmpty(h.NextSteps)
	if summary == "" && len(questions) == 0 && len(steps) == 0 {
		return "" // nothing useful to inject
	}

	var b strings.Builder
	b.WriteString("# Handoff from your previous session\n\n")
	b.WriteString("This is where you left off in this project. ")
	b.WriteString("Use it to resume; it will not be shown again.\n")
	if summary != "" {
		b.WriteString("\n## Summary\n")
		b.WriteString(summary)
		b.WriteString("\n")
	}
	if len(questions) > 0 {
		b.WriteString("\n## Open questions\n")
		writeBullets(&b, questions)
	}
	if len(steps) > 0 {
		b.WriteString("\n## Next steps\n")
		writeBullets(&b, steps)
	}
	return b.String()
}

func writeBullets(b *strings.Builder, items []string) {
	for _, it := range items {
		b.WriteString("- ")
		b.WriteString(strings.TrimSpace(it))
		b.WriteString("\n")
	}
}

// nonEmpty drops blank entries (defensive against a list with empty strings).
func nonEmpty(items []string) []string {
	out := make([]string, 0, len(items))
	for _, it := range items {
		if strings.TrimSpace(it) != "" {
			out = append(out, it)
		}
	}
	return out
}
