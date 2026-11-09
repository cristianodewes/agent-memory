package com.agentmemory.mcp;

/**
 * The canonical "self-routing" snippet (issue #40): the block a user drops into their agent's
 * project instructions ({@code CLAUDE.md} / {@code AGENTS.md}) so the agent knows <em>when</em> to
 * reach for the agent-memory MCP tools without being told each time. Returned verbatim by the
 * {@code memory_install_self_routing} MCP tool and written by the Go client's {@code install-instructions},
 * {@code setup-agent} and {@code upgrade} commands.
 *
 * <p>The snippet is wrapped in stable HTML marker comments so an installer can find, replace or remove
 * it idempotently.
 *
 * <p>It carries only what nothing else gives the agent: <em>when</em> to recall. The rest already
 * reaches the agent on its own: this server's MCP {@code instructions} ({@link McpConfiguration}) list
 * the tools, the default scope and the write-only-when-asked rule; {@code memory_write_page}'s
 * description repeats that rule; the client's SessionStart hook injects the project briefing and accepts
 * the open handoff. Repeating any of it here would only cost context in every session.
 *
 * <p>The same block lives in the Go client ({@code client/internal/install/snippet.go}). Both are pinned
 * to {@code docs/contracts/fixtures/self_routing_snippet.md} by their tests, so the three change
 * together.
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
        // BODY already ends with a newline (the text block's closing delimiter sits on its own line), so
        // END_MARKER follows it directly; another "\n" here would leave a blank line the Go installer
        // does not write.
        return BEGIN_MARKER + "\n" + BODY + END_MARKER + "\n";
    }

    private static final String BODY =
            """
            ## Project memory (agent-memory)

            Recall before you act — pull, don't guess: call `memory_query` before proposing a design or a
            non-trivial change, and when the user refers to earlier work you don't recognize ("like we did
            before", "where we left off"). One good query is cheaper than re-deriving what the project
            already knows.
            """;
}
