package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A directed wikilink / cross-reference between pages (ARCHITECTURE §4.2 {@code links}). Links power
 * the graph-neighborhood arm of hybrid recall (§3.3). Two shapes the schema calls out are modeled
 * here:
 *
 * <ul>
 *   <li><strong>Forward / deferred links</strong> — {@code to_page_id} is nullable: the source may
 *       link to a page that does not exist yet. We still record the <em>intended</em> target
 *       identity ({@code target}); {@code targetResolved} is {@code false} until the page is
 *       created, at which point the store fills the page id (#4).</li>
 *   <li><strong>Cross-project scope</strong> — {@code target} may name a different
 *       {@code (workspace, project)} than {@code source}; nothing here constrains them to match.</li>
 * </ul>
 *
 * <p>Both {@code source} and {@code target} are page-scoped {@link Identity} tuples (each has a
 * {@code path}); {@code target} itself may be {@code null} for a bare anchor with no destination yet
 * recorded. {@code anchor} is the link text / wikilink token as written in the source markdown.
 *
 * @param id             link id (UUIDv7); never null.
 * @param source         page-scoped identity of the linking page; never null.
 * @param target         page-scoped identity of the linked page (possibly cross-project), or
 *                       {@code null} for a recorded anchor with no destination identity yet.
 * @param anchor         the wikilink text/token as written, or {@code null} if not captured.
 * @param targetResolved whether {@code target} currently resolves to an existing page version.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "source", "target", "anchor", "targetResolved"})
public record Link(
        @JsonProperty("id") LinkId id,
        @JsonProperty("source") Identity source,
        @JsonProperty("target") Identity target,
        @JsonProperty("anchor") String anchor,
        @JsonProperty("targetResolved") boolean targetResolved) {

    @JsonCreator
    public Link {
        if (id == null) {
            throw new IllegalArgumentException("link.id must not be null");
        }
        if (source == null || !source.isPageScoped()) {
            throw new IllegalArgumentException("link.source must be page-scoped (path required)");
        }
        if (target != null && !target.isPageScoped()) {
            throw new IllegalArgumentException(
                    "link.target, when present, must be page-scoped (path required)");
        }
        if (target == null && targetResolved) {
            throw new IllegalArgumentException("link.targetResolved cannot be true without a target");
        }
    }

    /** @return {@code true} when source and target name different {@code (workspace, project)}. */
    @JsonIgnore
    public boolean isCrossProject() {
        if (target == null) {
            return false;
        }
        return !source.workspace().equals(target.workspace())
                || !source.project().equals(target.project());
    }
}
