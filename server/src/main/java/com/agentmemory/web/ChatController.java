package com.agentmemory.web;

import com.agentmemory.chat.ChatService;
import com.agentmemory.recall.Scope;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/workspaces/{ws}/projects/{p}/chat}: "chat with your memory" (issue #37) — a
 * folder/project-scoped, LLM answer grounded in the wiki (RAG), streamed over Server-Sent Events with
 * citations to the source pages. The first streaming endpoint in the server.
 *
 * <h2>SSE protocol</h2>
 * The response is {@code text/event-stream}. Events (each {@code data:} is JSON):
 * <ul>
 *   <li>{@code sources} — {@code {citations:[{index,path,title}], rawFallback}} — the resolvable page
 *       citations, emitted first (before the answer) so the UI can show what grounds the reply;</li>
 *   <li>{@code delta} — {@code {text}} — an incremental chunk of the answer (delivered progressively
 *       for a streaming feel; see the note below);</li>
 *   <li>{@code done} — {@code {citations, inputTokens, outputTokens}} — terminal success event;</li>
 *   <li>{@code error} — {@code {message}} — terminal failure event (e.g. the LLM call failed).</li>
 * </ul>
 *
 * <p><strong>Streaming granularity.</strong> The {@link com.agentmemory.llm.LlmProvider} is a blocking
 * call (no token streaming), so {@code delta} events are server-side chunks of the completed answer.
 * Citations genuinely stream ahead of the answer. Token-level streaming awaits a provider streaming API
 * (a follow-up, aligned with the provider matrix #40).
 *
 * <h2>Synchronous on the request thread (and read-only)</h2>
 * The events are written directly to the response on the request thread and flushed as they are
 * produced — the same synchronous model the other LLM-backed web endpoints use
 * ({@code HandoffController}/{@code ReindexController} call the provider on the request thread). This
 * keeps the endpoint compatible with the {@code /api/v1} security chain (#38) with no async-dispatch
 * re-authorization surprises: the chain authorizes the single request once. The underlying
 * {@link ChatService} is read-only (recall + page reads + LLM), so a chat turn never mutates memory
 * (issue #37 guardrail).
 *
 * <h2>Auth &amp; DB-less context</h2>
 * No special security wiring: when {@code agent-memory.auth.enabled}, the chain is deny-by-default
 * ({@code anyRequest().hasRole}), so this route requires the bearer token automatically; a browser POST
 * from {@code /web} is same-origin and clears the {@code BrowserWriteGuardFilter}.
 * {@link ChatService} is injected via {@link ObjectProvider} (like {@code RecallInjectionController}) so
 * a DB-less context still constructs the controller and it answers {@code 503}.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Tools-Jackson mapper for SSE event payloads (matches the MCP/web JSON stack). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Target size of an answer {@code delta} chunk (split on word boundaries). */
    private static final int CHUNK_CHARS = 72;

    private final ObjectProvider<ChatService> chat;

    public ChatController(ObjectProvider<ChatService> chat) {
        this.chat = chat;
    }

    /**
     * Answer a question grounded in the project's memory, streamed over SSE.
     *
     * @param ws       workspace slug (path).
     * @param p        project slug (path).
     * @param body     the request carrying the user's {@code message}.
     * @param response the servlet response the SSE frames are written to.
     * @throws ResponseStatusException {@code 503} when chat is unwired, {@code 400} on a missing message
     *     or invalid scope, or {@code 500} when retrieval fails — all thrown before any streaming begins,
     *     so a pre-stream failure is a real HTTP error status, not a 200 with an in-band error event.
     */
    @PostMapping(value = "/workspaces/{ws}/projects/{p}/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(
            @PathVariable String ws,
            @PathVariable String p,
            @RequestBody(required = false) ChatRequestBody body,
            HttpServletResponse response) {
        ChatService service = chat.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "chat not configured (no datasource or llm)");
        }
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing required 'message'");
        }

        Scope scope;
        try {
            scope = Scope.of(ws, p);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid scope: " + e.getMessage());
        }

        String question = body.message();

        // Retrieve BEFORE committing to a 200 stream. Recall + page reads are the DB-dependent step most
        // likely to fail (datasource down, recall pool exhausted); doing it here means such a failure is
        // a proper HTTP error — nothing has been written yet — rather than a 200 carrying an in-band
        // "error" event that load balancers and health probes would count as success.
        ChatService.Grounding grounding;
        try {
            grounding = service.retrieve(scope, question);
        } catch (RuntimeException e) {
            log.warn("chat retrieve failed for {}/{}: {}",
                    scope.workspaceSlug(), scope.projectSlug(), e.toString());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "chat retrieval failed");
        }

        // Past retrieval: commit to a 200 event-stream and write the events directly. A failure of the
        // (now already-committed) generation step is surfaced as a terminal "error" event instead.
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // disable proxy buffering so events flush promptly

        try {
            PrintWriter writer = response.getWriter();
            stream(writer, service, scope, question, grounding);
        } catch (IOException disconnect) {
            // The client went away mid-stream; nothing more to send.
            log.debug("chat stream client disconnected: {}", disconnect.toString());
        }
    }

    /** Produce the SSE events synchronously, flushing each so the client renders progressively. */
    private void stream(PrintWriter writer, ChatService service, Scope scope, String question,
            ChatService.Grounding grounding) throws IOException {
        ChatService.Answer answer;
        try {
            sendEvent(writer, "sources",
                    new SourcesEvent(grounding.citations(), grounding.rawFallback()));
            answer = service.answer(question, grounding);
        } catch (RuntimeException e) {
            // The generation step failed after the stream was committed, so we cannot switch to an HTTP
            // error status: report a terminal, generic error event. The detail (which may carry SQL or
            // upstream-provider text) is logged server-side only, never streamed to the client.
            log.warn("chat generation failed for {}/{}: {}",
                    scope.workspaceSlug(), scope.projectSlug(), e.toString());
            sendEvent(writer, "error", new ErrorEvent("chat generation failed"));
            return;
        }

        for (String chunk : chunk(answer.text())) {
            sendEvent(writer, "delta", new DeltaEvent(chunk));
        }
        sendEvent(writer, "done",
                new DoneEvent(grounding.citations(), answer.inputTokens(), answer.outputTokens()));
    }

    /** Write one SSE frame ({@code event:}/{@code data:} + blank line) and flush; data is JSON. */
    private static void sendEvent(PrintWriter writer, String event, Object payload) throws IOException {
        writer.write("event: " + event + "\n");
        writer.write("data: " + JSON.writeValueAsString(payload) + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            // PrintWriter swallows IOExceptions; surface a client disconnect so the loop stops.
            throw new IOException("chat SSE write failed (client disconnected)");
        }
    }

    /**
     * Split an answer into word-boundary chunks of roughly {@link #CHUNK_CHARS} for progressive
     * rendering. Returns no chunks for empty text.
     */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            int end = Math.min(i + CHUNK_CHARS, n);
            if (end < n) {
                // Prefer to break at the last whitespace within the window so words stay intact.
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > i) {
                    end = lastSpace + 1;
                }
            }
            chunks.add(text.substring(i, end));
            i = end;
        }
        return chunks;
    }

    /** The chat request body. */
    public record ChatRequestBody(String message) {}

    /** {@code sources} event payload. */
    public record SourcesEvent(List<ChatService.Citation> citations, boolean rawFallback) {}

    /** {@code delta} event payload (an incremental answer chunk). */
    public record DeltaEvent(String text) {}

    /** {@code done} event payload (terminal success). */
    public record DoneEvent(List<ChatService.Citation> citations, int inputTokens, int outputTokens) {}

    /** {@code error} event payload (terminal failure). */
    public record ErrorEvent(String message) {}
}
