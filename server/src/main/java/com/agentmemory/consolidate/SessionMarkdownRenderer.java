package com.agentmemory.consolidate;

import com.agentmemory.core.Session;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a validated {@link SynthesizedSession} to the markdown body of a {@code sessions/<id>.md}
 * page (issue #18). The frontmatter is added by the wiki layer ({@code WikiWriter.toDocument}); this
 * produces only the body. Sections with no content are omitted so a thin session yields a clean page
 * rather than a wall of empty headings.
 *
 * <p>Deterministic: the same {@link SynthesizedSession} always renders byte-identically, which is
 * what makes the idempotency contract (re-synthesizing identical content is a no-op write) hold end
 * to end.
 */
public final class SessionMarkdownRenderer {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);

    /**
     * Render the session page body.
     *
     * @param session   the session being summarized (for the metadata line); may be null.
     * @param synthesis the validated synthesis content; never null.
     * @return the markdown body (no frontmatter), ending with a trailing newline.
     */
    public String render(Session session, SynthesizedSession synthesis) {
        if (synthesis == null) {
            throw new IllegalArgumentException("synthesis must not be null");
        }
        StringBuilder sb = new StringBuilder(512);

        // A small provenance line so the page reads as a session record, not an anonymous note.
        if (session != null) {
            sb.append("> Session `").append(session.id()).append('`');
            if (session.agent() != null && !session.agent().isBlank()) {
                sb.append(" · agent ").append(session.agent());
            }
            sb.append(" · ").append(TS.format(session.startedAt()));
            if (session.endedAt() != null) {
                sb.append(" – ").append(TS.format(session.endedAt()));
            }
            sb.append("\n\n");
        }

        sb.append("## Summary\n\n").append(synthesis.summary().strip()).append("\n");

        appendBullets(sb, "Decisions", synthesis.decisions());
        appendBullets(sb, "Follow-ups", synthesis.followUps());
        appendBullets(sb, "Open questions", synthesis.openQuestions());
        appendBullets(sb, "Highlights", synthesis.highlights());

        return sb.toString();
    }

    private static void appendBullets(StringBuilder sb, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("\n## ").append(heading).append("\n\n");
        for (String item : items) {
            sb.append("- ").append(item.strip()).append('\n');
        }
    }
}
