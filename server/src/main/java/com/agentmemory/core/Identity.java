package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The typed 3-tuple identity {@code (workspace, project, path)} that ARCHITECTURE invariant #4
 * requires on <em>every</em> domain row. Bundling the three coordinates into one value type — used
 * by {@link Page}, {@link Observation}, {@link Session}, {@link Link} and {@link Handoff} — is what
 * makes it impossible to construct a domain record that is missing or half-populates its identity.
 * Retrofitting this tuple after the fact is the exact bug this design prevents.
 *
 * <p>The {@code path} coordinate is page-level. Rows that are not about a single page — a
 * {@link Session} or a {@link Handoff}, which scope to a whole project — carry a {@code null}
 * {@code page} here; rows that <em>are</em> page-scoped ({@link Page}, a page-bound {@link Link})
 * carry a non-null one. The {@code workspace}/{@code project} coordinates are always present.
 *
 * <p>Serializes as a nested JSON object with keys {@code workspace}, {@code project}, {@code path}
 * (in that order). {@code path} is omitted when null (see the contract under
 * {@code docs/contracts/}); the two slugs are never null.
 *
 * @param workspace the workspace coordinate; never null.
 * @param project   the project coordinate; never null.
 * @param page      the page coordinate, or {@code null} for project-scoped rows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"workspace", "project", "path"})
public record Identity(
        @JsonProperty("workspace") WorkspaceId workspace,
        @JsonProperty("project") ProjectId project,
        @JsonProperty("path") PagePath page) {

    @JsonCreator
    public Identity(
            @JsonProperty("workspace") WorkspaceId workspace,
            @JsonProperty("project") ProjectId project,
            @JsonProperty("path") PagePath page) {
        if (workspace == null) {
            throw new IllegalArgumentException("identity.workspace must not be null");
        }
        if (project == null) {
            throw new IllegalArgumentException("identity.project must not be null");
        }
        this.workspace = workspace;
        this.project = project;
        this.page = page; // nullable: project-scoped rows have no page coordinate
    }

    /**
     * Project-scoped identity (no page coordinate) — for {@link Session} / {@link Handoff} and
     * cross-project {@link Link} endpoints that name a project but not a page.
     *
     * @param workspace the workspace coordinate.
     * @param project   the project coordinate.
     * @return an identity whose {@code page} is {@code null}.
     */
    public static Identity ofProject(WorkspaceId workspace, ProjectId project) {
        return new Identity(workspace, project, null);
    }

    /**
     * Page-scoped identity — for {@link Page} and page-bound {@link Link} endpoints.
     *
     * @param workspace the workspace coordinate.
     * @param project   the project coordinate.
     * @param page      the page coordinate; must not be null.
     * @return a fully-populated 3-tuple identity.
     */
    public static Identity ofPage(WorkspaceId workspace, ProjectId project, PagePath page) {
        if (page == null) {
            throw new IllegalArgumentException("page-scoped identity requires a non-null path");
        }
        return new Identity(workspace, project, page);
    }

    /** @return {@code true} when this identity names a specific page (its {@code path} is set). */
    @JsonIgnore
    public boolean isPageScoped() {
        return page != null;
    }
}
