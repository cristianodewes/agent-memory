package com.agentmemory.web;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.Embedder;
import com.agentmemory.llm.EmbeddingResult;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual provider-verification endpoint ({@code GET /llm-test}) — the CLI/HTTP hook the issue asks
 * for so an operator can confirm the configured LLM (and embeddings) actually work, beyond the
 * one-shot startup probe.
 *
 * <p>It issues a real structured-JSON chat round-trip (invariant #7) against the configured chat
 * provider and, if an embedder is configured, a real embed call, then reports each axis's
 * {@code provider} / {@code model} / {@code dim} and ok/error status. Because the server already
 * failed fast at startup if the required LLM was unreachable (invariant #13), a healthy server
 * answers {@code ok:true} for the chat axis; the embeddings axis may report {@code ok:false} when it
 * is degraded.
 *
 * <p>No request body and no secrets in the response — only the denormalized provider metadata.
 */
@RestController
public class LlmTestController {

    private final LlmProvider llmProvider;
    private final ObjectProvider<Embedder> embedder;

    public LlmTestController(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            @Qualifier("embedder") ObjectProvider<Embedder> embedder) {
        // Inject by bean name: a test double implements both LlmProvider and Embedder, so binding by
        // type alone would be ambiguous. The named beans are defined in LlmModule.
        this.llmProvider = llmProvider;
        this.embedder = embedder;
    }

    @GetMapping("/llm-test")
    public Map<String, Object> llmTest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm", testLlm());
        result.put("embeddings", testEmbeddings());
        return result;
    }

    private Map<String, Object> testLlm() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", llmProvider.id());
        out.put("model", llmProvider.model());
        try {
            ChatRequest request = ChatRequest.structured(
                    java.util.List.of(
                            ChatMessage.system("You are a connectivity probe. Reply with the requested JSON only."),
                            ChatMessage.user("Return {\"ok\": true}.")),
                    new JsonSchema("llm_test",
                            "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                                    + "\"required\":[\"ok\"],\"additionalProperties\":false}"));
            ChatResponse response = llmProvider.chat(request);
            out.put("ok", true);
            out.put("reply", response.text());
            out.put("inputTokens", response.inputTokens());
            out.put("outputTokens", response.outputTokens());
        } catch (LlmException e) {
            out.put("ok", false);
            out.put("retryable", e.isRetryable());
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> testEmbeddings() {
        Map<String, Object> out = new LinkedHashMap<>();
        Embedder e = embedder.getIfAvailable();
        if (e == null) {
            out.put("configured", false);
            out.put("ok", false);
            out.put("note", "No embeddings provider configured; recall runs on FTS + graph only.");
            return out;
        }
        out.put("configured", true);
        out.put("provider", e.id());
        out.put("model", e.model());
        out.put("dim", e.dimensions());
        try {
            EmbeddingResult result = e.embed("agent-memory llm-test probe");
            out.put("ok", true);
            out.put("returnedDim", result.dim());
        } catch (LlmException ex) {
            out.put("ok", false);
            out.put("retryable", ex.isRetryable());
            out.put("error", ex.getMessage());
        }
        return out;
    }
}
