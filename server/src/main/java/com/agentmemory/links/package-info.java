/**
 * Scoped wikilinks: parsing, deferred-safe resolution and automatic backlinks (issue #27;
 * ARCHITECTURE §3.3, §4.2; Survey §2.8).
 *
 * <p>Wikilinks turn isolated pages into a graph, including across sibling projects and workspaces.
 * This package owns the {@code links} table's <em>write</em> side (the recall graph arm, #15, and the
 * reindex, #14, are readers/rebuilders). It is a small dedicated package rather than part of
 * {@code store} because the link graph is a distinct responsibility consumed by several layers:
 *
 * <ul>
 *   <li>{@link com.agentmemory.links.WikiLinkParser} — pure, IO-free parsing of the three scoped
 *       link forms ({@code [[path]]}, {@code [[project:path]]}, {@code [[workspace/project:path]]})
 *       into page-scoped target identities. Reusable: #14 reindex parses page bodies with this.</li>
 *   <li>{@link com.agentmemory.links.WikiLinkService} — maintains the {@code links} table on every
 *       page write: it replaces a source page's outgoing links and, crucially, re-points
 *       <strong>deferred</strong> links (a forward link to a page that did not exist yet) the moment
 *       their target page is created — including cross-project. It also serves backlinks.</li>
 *   <li>{@link com.agentmemory.links.RelatedPage} — a backlink/related-page view carrying the
 *       origin's {@code (workspace, project)} so cross-project relationships are visible.</li>
 * </ul>
 *
 * <p>The deferred-safe design keeps {@code links.to_page_id} nullable (the documented forward-link
 * bug class): a link to a missing page is stored unresolved and filled in later, so no link is ever
 * silently dropped because the target was written after the source.
 *
 * <h2>Out of scope</h2>
 * The graph API / lint (#28) and the curator (#29) build on this; they are not here.
 */
package com.agentmemory.links;
