package orientation

import (
	"fmt"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
)

func sampleBriefing() *apiclient.Briefing {
	return &apiclient.Briefing{
		Pages: 12, Observations: 40, Sessions: 3, Links: 7,
		ObservationsLast7Days: 5, ObservationsLast30Days: 18,
		Rules: []string{"_rules/style.md"},
		Slots: []string{"_slots/identity.md"},
		Recent: []apiclient.BriefingPage{
			{Path: "concepts/recall.md", Title: "Recall"},
			{Path: "concepts/wiki.md", Title: "Wiki"},
		},
	}
}

const handoffBlock = "# Handoff from your previous session\nLeft off mid-refactor."

func TestRenderHandoffFirstThenBriefing(t *testing.T) {
	out := Render(handoffBlock, sampleBriefing())
	hi := strings.Index(out, "Handoff from your previous session")
	bi := strings.Index(out, "Project memory orientation")
	if hi < 0 || bi < 0 {
		t.Fatalf("expected both sections, got:\n%s", out)
	}
	if hi > bi {
		t.Fatalf("handoff must come before the briefing:\n%s", out)
	}
	for _, want := range []string{
		"Pages: 12", "observations: 40", "7d: 5", "30d: 18", "sessions: 3", "links: 7",
		"_rules/style.md", "_slots/identity.md",
		"Recent pages", "concepts/recall.md", "Recall",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("briefing missing %q\n--- out ---\n%s", want, out)
		}
	}
}

func TestRenderBriefingOnlyWhenNoHandoff(t *testing.T) {
	out := Render("", sampleBriefing())
	if strings.Contains(out, "Handoff") {
		t.Errorf("did not expect a handoff section:\n%s", out)
	}
	if !strings.Contains(out, "Project memory orientation") {
		t.Errorf("expected the briefing section:\n%s", out)
	}
	if strings.HasPrefix(out, "\n") || strings.HasSuffix(out, "\n") {
		t.Errorf("must not start/end with a blank line:\n%q", out)
	}
}

func TestRenderHandoffOnlyWhenBriefingNil(t *testing.T) {
	out := Render(handoffBlock, nil)
	if !strings.Contains(out, "Handoff from your previous session") {
		t.Errorf("expected the handoff:\n%s", out)
	}
	if strings.Contains(out, "Project memory orientation") {
		t.Errorf("did not expect a briefing section:\n%s", out)
	}
}

func TestRenderEmptyWhenNothing(t *testing.T) {
	if got := Render("", nil); got != "" {
		t.Errorf("expected empty for no handoff + nil briefing, got %q", got)
	}
	if got := Render("   ", nil); got != "" {
		t.Errorf("a blank handoff must be skipped, got %q", got)
	}
	// An empty project (no pages, no activity, no recent) contributes no briefing.
	if got := Render("", &apiclient.Briefing{}); got != "" {
		t.Errorf("empty project must render nothing, got %q", got)
	}
}

func TestRenderBoundsRecentPages(t *testing.T) {
	b := &apiclient.Briefing{Pages: 100}
	for i := 0; i < RecentLimit+5; i++ {
		b.Recent = append(b.Recent, apiclient.BriefingPage{
			Path: fmt.Sprintf("p/%d.md", i), Title: fmt.Sprintf("T%d", i)})
	}
	out := Render("", b)
	if n := strings.Count(out, "— `p/"); n != RecentLimit {
		t.Fatalf("expected exactly %d recent pages, got %d:\n%s", RecentLimit, n, out)
	}
}

func TestRenderFallsBackToPathWhenTitleBlank(t *testing.T) {
	b := &apiclient.Briefing{
		Pages:  1,
		Recent: []apiclient.BriefingPage{{Path: "notes/x.md", Title: "   "}},
	}
	out := Render("", b)
	if !strings.Contains(out, "notes/x.md — `notes/x.md`") {
		t.Errorf("a blank title should fall back to the path:\n%s", out)
	}
}
