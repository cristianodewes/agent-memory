package com.agentmemory.mcp;

/**
 * The canonical "self-routing" snippet (issue #40): the block a user drops into their agent's
 * project instructions ({@code CLAUDE.md} / {@code AGENTS.md}) so the agent knows <em>when</em> to
 * reach for the agent-memory MCP tools without being told each time. Returned verbatim by the
 * {@code memory_install_self_routing} MCP tool and (later) written by the Go {@code install-instructions}
 * installer.
 *
 * <p>The snippet is wrapped in stable HTML marker comments so an installer can find, replace or remove
 * it idempotently (the {@code AGENTS.md} of this very repo carries the matching
 * {@code <!-- agent-memory:self-routing -->} placeholder). The guidance mirrors the MCP server's own
 * {@code instructions} string so the routing advice an agent gets is consistent whether it reads the
 * server capabilities or the pasted snippet.
 */
public final class SelfRoutingSnippet {

    /** Opening marker an installer matches to find/replace the managed block. */
    public static final String BEGIN_MARKER = "<!-- BEGIN agent-memory:self-routing -->";

    /** Closing marker an installer matches to find/replace the managed block. */
    public static final String END_MARKER = "<!-- END agent-memory:self-routing -->";

    private SelfRoutingSnippet() {
    }

    /**
     * The canonical routing block, fenced by {@link #BEGIN_MARKER}/{@link #END_MARKER}.
     *
     * @return the Markdown snippet to paste into {@code CLAUDE.md} / {@code AGENTS.md}.
     */
    public static String markdown() {
        return BEGIN_MARKER + "\n" + BODY + "\n" + END_MARKER + "\n";
    }

    private static final String BODY =
            """
            ## Project memory (agent-memory)

            This project has a long-term memory served over MCP (`agent-memory`). Use it so knowledge
            survives across sessions. Scope defaults to the current project; you rarely need to pass
            `workspace`/`project`.

            **Recall before you act — pull, don't guess:**
            - Before proposing architecture, a design, or a non-trivial change, call `memory_query`
              with the topic to check what was already decided or tried.
            - When the user references prior work ("like we did before", "the usual way", "where we
              left off") and you don't recognize it, call `memory_query` first; read full pages with
              `memory_read_page`.
            - At the start of a task, `memory_briefing` gives a no-LLM snapshot (counts, rules, recent
              pages); `memory_recent` lists the latest pages.

            **Resume handoffs:** if a handoff is waiting, `memory_handoff_accept` returns where the
            previous session left off (single-use — it is consumed on accept).

            **Write only when asked to remember:** when the user says to remember/note/record
            something durable, call `memory_write_page` (or `memory_delete_page` to remove one by
            path). Do not write memory unprompted.

            **Rule of thumb:** recall is cheap and proactive; writing is deliberate and only on
            request. Prefer one good `memory_query` over re-deriving what the project already knows.
            """;
}
