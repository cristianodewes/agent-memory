// Package orientation assembles the SessionStart "orientation" block injected as Claude Code
// additionalContext (issue #85). It extends the #23 handoff injection: where #23 injected only the open
// handoff ("where you left off"), this also primes the agent on WHAT memory the project has before it
// does anything — a bounded snapshot (counts + recent activity + rules/slots) drawn from the server's
// read-only briefing.
//
// Everything is bounded and advisory: this is orientation, not a dump, so the rendered block is capped
// (a handful of recent pages / rules / slots) to avoid flooding the session context, and any missing
// section is simply omitted. The handoff stays FIRST (it is the most actionable). An empty result means
// the caller injects nothing — a clean no-op.
//
// The scent / dependency-graph orientation map (top folders + hub pages) is a follow-up slice; this one
// delivers the briefing + recent-pages sections by composing the existing /briefing endpoint.
package orientation

import (
	"fmt"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
)

// Caps keep the orientation block small (a few hundred tokens): orientation, not a dump (#85).
const (
	// RecentLimit is how many recent pages to fetch AND render (the fetch passes it to the server so
	// the briefing returns no more than we will show).
	RecentLimit = 8
	maxRules    = 8
	maxSlots    = 8
)

// Render assembles the SessionStart orientation block from the already-rendered handoff block (#23) and
// the project briefing (#85). The handoff stays first; the briefing snapshot follows. Each section is
// optional — a nil or empty-project briefing contributes nothing, and a blank handoff is skipped — so
// the result is "" when there is nothing at all to inject (the caller then emits no output).
func Render(handoffBlock string, briefing *apiclient.Briefing) string {
	var sections []string
	if h := strings.TrimSpace(handoffBlock); h != "" {
		sections = append(sections, h)
	}
	if b := renderBriefing(briefing); b != "" {
		sections = append(sections, b)
	}
	return strings.Join(sections, "\n\n")
}

// renderBriefing formats the bounded project snapshot, or "" when there is nothing worth showing (a
// brand-new project with no pages and no activity).
func renderBriefing(b *apiclient.Briefing) string {
	if b == nil {
		return ""
	}
	if b.Pages == 0 && b.Observations == 0 && len(b.Recent) == 0 {
		return "" // empty project — nothing to orient around
	}

	var sb strings.Builder
	sb.WriteString("# Project memory orientation\n")
	sb.WriteString(
		"A snapshot of this project's compiled memory, so you know what you can recall. " +
			"Use memory_query / memory_read_page to pull details before acting.\n")
	fmt.Fprintf(&sb,
		"\n- Pages: %d · observations: %d (7d: %d, 30d: %d) · sessions: %d · links: %d\n",
		b.Pages, b.Observations, b.ObservationsLast7Days, b.ObservationsLast30Days,
		b.Sessions, b.Links)
	if rules := bounded(b.Rules, maxRules); len(rules) > 0 {
		fmt.Fprintf(&sb, "- Rules: %s\n", strings.Join(rules, ", "))
	}
	if slots := bounded(b.Slots, maxSlots); len(slots) > 0 {
		fmt.Fprintf(&sb, "- Slots: %s\n", strings.Join(slots, ", "))
	}
	if recent := boundedPages(b.Recent, RecentLimit); len(recent) > 0 {
		sb.WriteString("\n## Recent pages\n")
		for _, p := range recent {
			title := strings.TrimSpace(p.Title)
			if title == "" {
				title = p.Path
			}
			fmt.Fprintf(&sb, "- %s — `%s`\n", title, p.Path)
		}
	}
	return strings.TrimRight(sb.String(), "\n")
}

// bounded returns at most n non-blank entries from items.
func bounded(items []string, n int) []string {
	out := make([]string, 0, n)
	for _, it := range items {
		if strings.TrimSpace(it) == "" {
			continue
		}
		out = append(out, it)
		if len(out) == n {
			break
		}
	}
	return out
}

// boundedPages returns at most n pages that have a path.
func boundedPages(pages []apiclient.BriefingPage, n int) []apiclient.BriefingPage {
	out := make([]apiclient.BriefingPage, 0, n)
	for _, p := range pages {
		if strings.TrimSpace(p.Path) == "" {
			continue
		}
		out = append(out, p)
		if len(out) == n {
			break
		}
	}
	return out
}
