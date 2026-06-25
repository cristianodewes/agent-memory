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

func sampleScent() *apiclient.Scent {
	return &apiclient.Scent{
		Folders: []apiclient.ScentFolder{
			{Folder: "concepts", Pages: 12},
			{Folder: "decisions", Pages: 8},
		},
		Hubs: []apiclient.ScentHub{
			{Path: "concepts/recall.md", Title: "Recall", Inbound: 5},
		},
	}
}

const handoffBlock = "# Handoff from your previous session\nLeft off mid-refactor."

func TestRenderHandoffFirstThenBriefingThenScent(t *testing.T) {
	out := Render(handoffBlock, sampleBriefing(), sampleScent())
	hi := strings.Index(out, "Handoff from your previous session")
	bi := strings.Index(out, "Project memory orientation")
	si := strings.Index(out, "Memory map")
	if hi < 0 || bi < 0 || si < 0 {
		t.Fatalf("expected all three sections, got:\n%s", out)
	}
	if !(hi < bi && bi < si) {
		t.Fatalf("order must be handoff < briefing < scent:\n%s", out)
	}
	for _, want := range []string{
		"Pages: 12", "_rules/style.md", "Recent pages", "concepts/recall.md", // briefing
		"Folders: concepts (12), decisions (8)", "Hubs: Recall (`concepts/recall.md`)", // scent
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q\n--- out ---\n%s", want, out)
		}
	}
}

func TestRenderBriefingOnlyWhenNoHandoff(t *testing.T) {
	out := Render("", sampleBriefing(), nil)
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

func TestRenderScentOnly(t *testing.T) {
	out := Render("", nil, sampleScent())
	if strings.Contains(out, "Project memory orientation") || strings.Contains(out, "Handoff") {
		t.Errorf("expected only the scent section:\n%s", out)
	}
	if !strings.Contains(out, "Memory map") || !strings.Contains(out, "concepts (12)") {
		t.Errorf("expected the scent map:\n%s", out)
	}
}

func TestRenderHandoffOnlyWhenBriefingAndScentNil(t *testing.T) {
	out := Render(handoffBlock, nil, nil)
	if !strings.Contains(out, "Handoff from your previous session") {
		t.Errorf("expected the handoff:\n%s", out)
	}
	if strings.Contains(out, "Project memory orientation") || strings.Contains(out, "Memory map") {
		t.Errorf("did not expect briefing/scent sections:\n%s", out)
	}
}

func TestRenderEmptyWhenNothing(t *testing.T) {
	if got := Render("", nil, nil); got != "" {
		t.Errorf("expected empty for no handoff + nil briefing + nil scent, got %q", got)
	}
	if got := Render("   ", nil, nil); got != "" {
		t.Errorf("a blank handoff must be skipped, got %q", got)
	}
	// An empty project contributes no briefing and no scent.
	if got := Render("", &apiclient.Briefing{}, &apiclient.Scent{}); got != "" {
		t.Errorf("empty project must render nothing, got %q", got)
	}
}

func TestRenderBoundsRecentPages(t *testing.T) {
	b := &apiclient.Briefing{Pages: 100}
	for i := 0; i < RecentLimit+5; i++ {
		b.Recent = append(b.Recent, apiclient.BriefingPage{
			Path: fmt.Sprintf("p/%d.md", i), Title: fmt.Sprintf("T%d", i)})
	}
	out := Render("", b, nil)
	if n := strings.Count(out, "— `p/"); n != RecentLimit {
		t.Fatalf("expected exactly %d recent pages, got %d:\n%s", RecentLimit, n, out)
	}
}

func TestRenderBoundsScentHubs(t *testing.T) {
	s := &apiclient.Scent{}
	for i := 0; i < ScentHubs+4; i++ {
		s.Hubs = append(s.Hubs, apiclient.ScentHub{
			Path: fmt.Sprintf("h/%d.md", i), Title: fmt.Sprintf("H%d", i), Inbound: int64(i)})
	}
	out := Render("", nil, s)
	if n := strings.Count(out, "(`h/"); n != ScentHubs {
		t.Fatalf("expected exactly %d hubs, got %d:\n%s", ScentHubs, n, out)
	}
}

func TestRenderFallsBackToPathWhenTitleBlank(t *testing.T) {
	b := &apiclient.Briefing{
		Pages:  1,
		Recent: []apiclient.BriefingPage{{Path: "notes/x.md", Title: "   "}},
	}
	out := Render("", b, nil)
	if !strings.Contains(out, "notes/x.md — `notes/x.md`") {
		t.Errorf("a blank briefing title should fall back to the path:\n%s", out)
	}
	// A blank hub title also falls back to the path.
	s := &apiclient.Scent{Hubs: []apiclient.ScentHub{{Path: "h/y.md", Title: "", Inbound: 2}}}
	out2 := Render("", nil, s)
	if !strings.Contains(out2, "h/y.md (`h/y.md`)") {
		t.Errorf("a blank hub title should fall back to the path:\n%s", out2)
	}
}
