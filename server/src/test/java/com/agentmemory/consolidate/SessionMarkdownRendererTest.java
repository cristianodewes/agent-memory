package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no DB/LLM) for {@link SessionMarkdownRenderer} — the JSON→markdown rendering of a
 * synthesized session (#18), including section omission for thin sessions and determinism.
 */
class SessionMarkdownRendererTest {

    private final SessionMarkdownRenderer renderer = new SessionMarkdownRenderer();

    private static Session session() {
        return new Session(
                SessionId.of("018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55"),
                Identity.ofProject(WorkspaceId.of("ws"), ProjectId.of("proj")),
                "claude-code",
                Instant.parse("2026-06-25T12:00:00Z"),
                Instant.parse("2026-06-25T13:30:00Z"));
    }

    @Test
    void rendersAllSectionsWhenPresent() {
        SynthesizedSession s = new SynthesizedSession(
                "Vector arm", "We added the pgvector arm.",
                List.of("Chose cosine"), List.of("Wire #14"), List.of("Backfill timing?"),
                List.of("279 tests pass"));

        String md = renderer.render(session(), s);

        assertThat(md).contains("## Summary").contains("We added the pgvector arm.");
        assertThat(md).contains("## Decisions").contains("- Chose cosine");
        assertThat(md).contains("## Follow-ups").contains("- Wire #14");
        assertThat(md).contains("## Open questions").contains("- Backfill timing?");
        assertThat(md).contains("## Highlights").contains("- 279 tests pass");
        // provenance line with session id + agent
        assertThat(md).contains("018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55").contains("claude-code");
        assertThat(md).endsWith("\n");
    }

    @Test
    void omitsEmptySections() {
        SynthesizedSession thin = new SynthesizedSession(
                "Quiet session", "Not much happened.", List.of(), List.of(), List.of(), List.of());
        String md = renderer.render(session(), thin);

        assertThat(md).contains("## Summary").contains("Not much happened.");
        assertThat(md).doesNotContain("## Decisions");
        assertThat(md).doesNotContain("## Follow-ups");
        assertThat(md).doesNotContain("## Open questions");
        assertThat(md).doesNotContain("## Highlights");
    }

    @Test
    void rendersWithoutASessionMetadataLine() {
        SynthesizedSession s = new SynthesizedSession(
                "t", "body", List.of(), List.of(), List.of(), List.of());
        String md = renderer.render(null, s);
        assertThat(md).startsWith("## Summary");
        assertThat(md).doesNotContain("> Session");
    }

    @Test
    void isDeterministic() {
        SynthesizedSession s = new SynthesizedSession(
                "t", "body", List.of("d1", "d2"), List.of("f1"), List.of(), List.of("h1"));
        assertThat(renderer.render(session(), s)).isEqualTo(renderer.render(session(), s));
    }
}
