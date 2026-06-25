/**
 * Atomic markdown writes, the file watcher, and git (the markdown wiki is the
 * source of truth — see design-decision DD-002). (docs/ARCHITECTURE.md §6.)
 *
 * <p>Issue #13: {@link com.agentmemory.wiki.AtomicFileWriter} (tmp+rename+fsync, invariant #10),
 * {@link com.agentmemory.wiki.WikiGit} (commit-on-write over one repo), {@link
 * com.agentmemory.wiki.WikiWriter} (the {@code PageWriteCallback} side effect — file + commit in the
 * store's write transaction), and {@link com.agentmemory.wiki.WikiFileWatcher} (reconciles external
 * edits into the index, ignoring the app's own writes via {@link com.agentmemory.wiki.SelfWriteTracker}).
 * The on-disk form is {@link com.agentmemory.wiki.MarkdownDocument} (frontmatter + body). Wired by
 * {@link com.agentmemory.wiki.WikiConfiguration}.
 */
package com.agentmemory.wiki;
