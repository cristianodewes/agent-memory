package com.agentmemory.chat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Chat with your memory" (issue #37, ARCHITECTURE §5.3): answer a question grounded in a project's
 * compiled wiki (RAG). A signature LLM-required capability — only possible because the LLM is a
 * required dependency (DD-005).
 *
 * <h2>Pipeline</h2>
 * {@link #retrieve} runs hybrid {@link RecallService recall} (the same retrieval as MCP
 * {@code memory_query} and the {@code /api/v1/search} API, including #21's LLM rerank) for the
 * question, then resolves each compiled-page hit to its real page body via
 * {@link PageRepository#readLatest} and assembles a numbered, budget-bounded grounding context with a
 * {@link Citation} per source. {@link #answer} then asks the {@link LlmProvider} to answer
 * <em>strictly from those excerpts</em>, citing them by number. {@link #chat} is the combined
 * convenience path (used by the non-streaming tests); the SSE controller calls {@code retrieve} then
 * {@code answer} so it can emit citations before the answer.
 *
 * <h2>Read-only (project guardrail)</h2>
 * This service composes <strong>only</strong> {@code RecallService.search},
 * {@code PageRepository.readLatest}/{@code findById}, and {@code LlmProvider.chat} — read/answer
 * operations. It holds no write collaborator (no {@code MemoryWriteService}, no {@code WikiWriter})
 * and performs no tool-calling, so a chat turn can never <em>create, update, or delete</em> memory:
 * no page or observation is ever written. Any such write-capable action remains an explicit,
 * separately-audited path (issue #37: "no silent write; browser LLM-mediated writes require
 * guardrails"). The one incidental write is recall's access-count reinforcement (ARCHITECTURE §3.3):
 * like {@code /api/v1/search} and MCP {@code memory_query}, searching bumps {@code access_count} on
 * the hit pages so used knowledge survives the forget sweep — it mutates no content and is the
 * intended recall behavior, not a chat side effect.
 *
 * <h2>Cost controls</h2>
 * Retrieval is capped at {@link ChatProperties#retrievalK()} hits; the assembled context is bounded by
 * {@link ChatProperties#maxContextChars()} (split fairly across the included pages, then hard-capped);
 * the reply is capped at {@link ChatProperties#maxOutputTokens()}.
 */
public final class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RecallService recall;
    private final PageRepository pages;
    private final LlmProvider llm;
    private final ChatPrompts prompts;
    private final ChatProperties props;

    public ChatService(
            RecallService recall,
            PageRepository pages,
            LlmProvider llm,
            ChatPrompts prompts,
            ChatProperties props) {
        this.recall = recall;
        this.pages = pages;
        this.llm = llm;
        this.prompts = prompts;
        this.props = props;
    }

    /**
     * Retrieve and assemble the grounding context for a question (the read-only RAG step).
     *
     * @param scope    the project to search; never null.
     * @param question the user's question; never null/blank.
     * @return the numbered citations (compiled-page sources) plus the bounded context text.
     */
    public Grounding retrieve(Scope scope, String question) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        requireQuestion(question);

        RecallResult result = recall.search(new RecallQuery(question, scope, props.retrievalK()));
        if (result.isEmpty()) {
            return new Grounding(List.of(), "", false);
        }

        // Fairly split the context budget across the hits we may include, so one large page can't
        // crowd out the others; the assembled text is then hard-capped at maxContextChars.
        int perHitChars = Math.max(1, props.maxContextChars() / props.retrievalK());

        List<Citation> citations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        int index = 0;
        for (RecallHit hit : result.hits()) {
            if (context.length() >= props.maxContextChars()) {
                break;
            }
            String excerpt;
            String title;
            String path;
            if (hit.source() == HitSource.PAGE && hit.path() != null) {
                // Full-text / link-graph page hit: it carries its path and title; read the body by path.
                Optional<PageRecord> record = readPage(scope, hit.path());
                if (record.isEmpty()) {
                    // Page was superseded/removed between recall and read; skip rather than fail.
                    continue;
                }
                title = hit.title();
                path = hit.path();
                excerpt = truncate(record.get().page().body(), perHitChars);
            } else if (hit.source() == HitSource.PAGE) {
                // Vector-only page hit (#16): a semantic match the lexical arms missed, so it carries
                // only its surrogate id (path/title/snippet are null). Resolve the compiled page by id
                // so it grounds and cites like any other page instead of being rendered as an empty,
                // mislabeled "raw observation".
                Optional<PageRecord> record = readPageById(hit.id());
                if (record.isEmpty()) {
                    continue;
                }
                Page page = record.get().page();
                title = page.title();
                path = page.identity().page().value();
                excerpt = truncate(page.body(), perHitChars);
            } else {
                // Raw-observation fallback (no compiled page yet): include the snippet as lower-confidence
                // context, but it is not a citeable page (path stays null).
                title = hit.title();
                path = null;
                excerpt = truncate(stripMarks(hit.snippet()), perHitChars);
            }

            index++;
            // Only compiled pages become resolvable citations (AC: "citations resolve to real pages").
            if (path != null) {
                citations.add(new Citation(index, path, title));
            }
            context.append('[').append(index).append("] ").append(title);
            if (path != null) {
                context.append(" (").append(path).append(')');
            } else {
                context.append(" (raw observation)");
            }
            context.append('\n').append(excerpt).append("\n\n");
        }

        String contextText = truncate(context.toString().strip(), props.maxContextChars());
        return new Grounding(List.copyOf(citations), contextText, result.rawFallback());
    }

    /**
     * Ask the LLM to answer the question strictly from the assembled grounding (the generation step).
     *
     * @param question  the user's question; never null/blank.
     * @param grounding the retrieved context + citations from {@link #retrieve}.
     * @return the model's answer plus token usage.
     */
    public Answer answer(String question, Grounding grounding) {
        requireQuestion(question);
        if (grounding == null) {
            throw new IllegalArgumentException("grounding must not be null");
        }

        String excerpts = grounding.contextText().isBlank()
                ? "(no matching memory excerpts were found for this project)"
                : grounding.contextText();
        String userContent = "Question: " + question.strip()
                + "\n\n--- MEMORY EXCERPTS ---\n" + excerpts;

        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(prompts.chatSystem()), ChatMessage.user(userContent)),
                null,
                props.maxOutputTokens());
        ChatResponse response = llm.chat(request);
        return new Answer(response.text(), response.inputTokens(), response.outputTokens());
    }

    /**
     * Convenience: retrieve then answer in one call (used by non-streaming callers/tests). The SSE
     * endpoint instead calls {@link #retrieve} and {@link #answer} separately so citations stream
     * before the answer.
     *
     * @param scope    the project to search.
     * @param question the user's question.
     * @return the answer, its citations, and token usage.
     */
    public ChatResult chat(Scope scope, String question) {
        Grounding grounding = retrieve(scope, question);
        Answer a = answer(question, grounding);
        return new ChatResult(a.text(), grounding.citations(), a.inputTokens(), a.outputTokens());
    }

    private Optional<PageRecord> readPage(Scope scope, String path) {
        try {
            Identity id = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(path));
            return pages.readLatest(id);
        } catch (RuntimeException e) {
            // A malformed path from a hit must not break the whole answer; drop this source.
            log.debug("chat: skipping unreadable page '{}' in {}/{}: {}",
                    path, scope.workspaceSlug(), scope.projectSlug(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<PageRecord> readPageById(String id) {
        try {
            return pages.findById(PageId.of(id));
        } catch (RuntimeException e) {
            // An unparseable/unknown id from a hit must not break the whole answer; drop this source.
            log.debug("chat: skipping unresolvable page id '{}': {}", id, e.toString());
            return Optional.empty();
        }
    }

    private static void requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("chat question must not be null or blank");
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max).strip() + "…";
    }

    /**
     * Strip the {@code <mark>} highlight tags a recall snippet carries down to plain text. Only those
     * literal tags are removed (mirroring {@code RecallInjection}): the surrounding excerpt comes from
     * Postgres {@code ts_headline}, which does <em>not</em> HTML-escape the source, so a blanket
     * {@code <[^>]+>} strip would eat legitimate text like {@code List<String>} or {@code x < y}.
     */
    private static String stripMarks(String snippet) {
        return snippet == null ? "" : snippet.replace("<mark>", "").replace("</mark>", "");
    }

    /**
     * One resolvable source the answer can cite: its 1-based {@code index} in the prompt, the page
     * {@code path} (resolves to a real page), and the page {@code title}.
     */
    public record Citation(int index, String path, String title) {}

    /** The retrieval output: the citeable sources and the bounded grounding context text. */
    public record Grounding(List<Citation> citations, String contextText, boolean rawFallback) {}

    /** The generation output: the model's answer text and token usage. */
    public record Answer(String text, int inputTokens, int outputTokens) {}

    /** The combined result of {@link ChatService#chat}: answer, citations, and usage. */
    public record ChatResult(String answer, List<Citation> citations, int inputTokens, int outputTokens) {}
}
