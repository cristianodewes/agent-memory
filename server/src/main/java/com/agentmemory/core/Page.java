package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

/**
 * A compiled wiki page — the durable unit of knowledge the LLM writes into {@code wiki/} and the
 * store indexes (ARCHITECTURE §4.2 {@code pages}). Page bodies live in markdown files (the source
 * of truth, DD-002); this record is the typed in-memory view the domain passes around, not the
 * Postgres row mapping (that is {@code store}, #4) nor a persistence entity.
 *
 * <p>Identity is the full page-scoped 3-tuple ({@link Identity#isPageScoped()} is true). Versioning
 * is modeled by the {@code isLatest} flag plus a {@code supersedes} pointer to the previous version
 * id, mirroring the {@code is_latest}/{@code supersedes} chain in the schema (§4.2). The decay
 * access columns and embedding ref noted in §4.2 are deliberately deferred to {@code store} (#4) —
 * {@code core} models only the identity, content and version vocabulary.
 *
 * @param id         this page version's id (UUIDv7); never null.
 * @param identity   the {@code (workspace, project, path)} tuple; page-scoped (path non-null).
 * @param title      human-readable page title; never null (may be derived from the first heading).
 * @param body       markdown body; never null (may be empty for a freshly created page).
 * @param isLatest   {@code true} if this is the current version of the page.
 * @param supersedes id of the version this one replaces, or {@code null} for the first version.
 * @param createdAt  when this version was created (UTC instant); never null.
 * @param updatedAt  when this version was last written (UTC instant); never null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "id", "identity", "title", "body", "isLatest", "supersedes", "createdAt", "updatedAt"
})
public record Page(
        @JsonProperty("id") PageId id,
        @JsonProperty("identity") Identity identity,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("isLatest") boolean isLatest,
        @JsonProperty("supersedes") PageId supersedes,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("updatedAt") Instant updatedAt) {

    @JsonCreator
    public Page {
        if (id == null) {
            throw new IllegalArgumentException("page.id must not be null");
        }
        if (identity == null || !identity.isPageScoped()) {
            throw new IllegalArgumentException("page.identity must be page-scoped (path required)");
        }
        if (title == null) {
            throw new IllegalArgumentException("page.title must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("page.body must not be null");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("page timestamps must not be null");
        }
    }

    /** @return the page path coordinate (never null for a {@code Page}). */
    public PagePath path() {
        return identity.page();
    }
}
