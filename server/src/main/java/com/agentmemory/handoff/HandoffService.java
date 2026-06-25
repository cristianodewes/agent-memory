package com.agentmemory.handoff;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.HandoffRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The flagship LLM feature (ARCHITECTURE §3.4; issue #22): when a session ends, the LLM reads the
 * session's observations and writes a typed "where you left off" handoff — a {@code summary} plus
 * {@code openQuestions} and {@code nextSteps} — instead of a rule-based template. The handoff is
 * single-use: opened here, consumed once by {@link #accept} at the next session start.
 *
 * <p><b>Required LLM (DD-005 / invariant #13).</b> Generation goes through the configured
 * {@link LlmProvider}; there is no rule-based fallback. <b>Structured JSON (invariant #7):</b> the
 * request carries a {@link JsonSchema}, so the reply is a single JSON object validated against it; a
 * reply that does not parse is surfaced as a {@link HandoffException} rather than persisted.
 *
 * <p>The grounding material is the session's observations (the captured prompts/tool calls). When
 * the LLM-written session synthesis page (#18) is available it can be folded into the prompt as
 * additional context; this service already reads the raw observations, which is the material
 * §3.4 calls for.
 */
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);

    /** How many of the session's most-recent observations to ground the handoff on. */
    private static final int MAX_OBSERVATIONS = 200;

    /** Soft cap on the prompt's observation block, so a huge session does not blow the context. */
    private static final int MAX_PROMPT_CHARS = 24_000;

    /**
     * Versioned prompt id — bump when the prompt text changes so a handoff's provenance is traceable
     * and prompt experiments are comparable (issue note: "keep the prompt versioned").
     */
    public static final String PROMPT_VERSION = "handoff/v1";

    /** The structured-output schema the model must satisfy (invariant #7). */
    private static final JsonSchema HANDOFF_SCHEMA = new JsonSchema(
            "handoff",
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"summary\":{\"type\":\"string\"},"
                    + "\"openQuestions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                    + "\"nextSteps\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                    + "\"required\":[\"summary\",\"openQuestions\",\"nextSteps\"],"
                    + "\"additionalProperties\":false}");

    private static final String SYSTEM_PROMPT =
            "You are writing a concise, accurate session handoff for the NEXT agent that will work on "
                    + "this project. Read the session's captured events (prompts, tool calls, results) "
                    + "and produce a 'where you left off' note. Be specific and grounded ONLY in the "
                    + "events; do not invent work that did not happen. Respond with a single JSON object "
                    + "matching the schema: 'summary' is a short prose paragraph; 'openQuestions' lists "
                    + "unresolved questions; 'nextSteps' lists concrete next actions. Use empty arrays "
                    + "when there are none.";

    private final LlmProvider llm;
    private final HandoffRepository repository;
    private final ObjectMapper json;

    public HandoffService(LlmProvider llm, HandoffRepository repository) {
        this.llm = llm;
        this.repository = repository;
        this.json = JsonMapper.builder().build();
    }

    /**
     * Synthesize and open a handoff for {@code session} in {@code scope}: read the session's
     * observations, ask the LLM for a structured handoff, and persist it as the project's open
     * handoff (superseding any prior open one). Used by the {@code session-end} trigger, the
     * {@code POST /handoff} endpoint, and {@code memory_handoff_begin}.
     *
     * @param scope   the project; never null.
     * @param session the session to summarize; never null.
     * @return the opened handoff.
     * @throws HandoffException if the LLM reply cannot be parsed into a handoff.
     * @throws LlmException     if the provider call fails (propagated).
     */
    public Handoff begin(Scope scope, SessionId session) {
        if (scope == null || session == null) {
            throw new IllegalArgumentException("scope and session must not be null");
        }
        List<HandoffRepository.ObservationLine> observations =
                repository.sessionObservations(session, MAX_OBSERVATIONS);

        ChatRequest request = ChatRequest.structured(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(renderPrompt(observations))),
                HANDOFF_SCHEMA);
        ChatResponse response = llm.chat(request);

        Body body = parse(response.text());
        Handoff handoff = repository.open(
                scope, session, body.summary(), body.openQuestions(), body.nextSteps());
        log.info("opened handoff {} for {}/{} from session {} (prompt {}, {} observations)",
                handoff.id().value(), scope.workspaceSlug(), scope.projectSlug(), session.value(),
                PROMPT_VERSION, observations.size());
        return handoff;
    }

    /**
     * Accept (consume) the latest open handoff for {@code scope} — single-use; a second accept
     * returns empty.
     *
     * @param scope the project; never null.
     * @return the accepted handoff, or empty when none was open.
     */
    public Optional<Handoff> accept(Scope scope) {
        return repository.acceptLatestOpen(scope);
    }

    /**
     * Cancel (expire) the latest open handoff for {@code scope} without consuming it.
     *
     * @param scope the project; never null.
     * @return the expired handoff, or empty when none was open.
     */
    public Optional<Handoff> cancel(Scope scope) {
        return repository.cancelLatestOpen(scope);
    }

    /** Peek the latest open handoff without consuming it. */
    public Optional<Handoff> peekOpen(Scope scope) {
        return repository.findLatestOpen(scope);
    }

    // --- prompt + parsing ----------------------------------------------------------------------

    /** Render the session's observations into the user prompt, chronological and size-capped. */
    private static String renderPrompt(List<HandoffRepository.ObservationLine> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session events (oldest first):\n");
        if (observations.isEmpty()) {
            sb.append("(no captured events for this session)\n");
        }
        for (HandoffRepository.ObservationLine o : observations) {
            String line = "- [" + o.createdAt() + "] " + o.kind() + ": " + oneLine(o.payload()) + "\n";
            if (sb.length() + line.length() > MAX_PROMPT_CHARS) {
                sb.append("… (older events truncated)\n");
                break;
            }
            sb.append(line);
        }
        sb.append("\nWrite the handoff JSON now.");
        return sb.toString();
    }

    /** Collapse a payload to a single line and trim, so one event is one prompt line. */
    private static String oneLine(String payload) {
        if (payload == null) {
            return "";
        }
        String collapsed = payload.replaceAll("\\s+", " ").strip();
        return collapsed.length() > 600 ? collapsed.substring(0, 600) + "…" : collapsed;
    }

    /** Parse the structured reply into the handoff body, mapping malformed JSON to a clear failure. */
    private Body parse(String reply) {
        JsonNode root;
        try {
            root = json.readTree(reply);
        } catch (JacksonException e) {
            throw new HandoffException(
                    "LLM handoff reply was not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new HandoffException("LLM handoff reply was not a JSON object: " + truncate(reply));
        }
        String summary = text(root.get("summary"));
        if (summary == null) {
            throw new HandoffException("LLM handoff reply is missing a 'summary' string");
        }
        return new Body(summary, stringList(root.get("openQuestions")), stringList(root.get("nextSteps")));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isString()) {
                    String s = item.stringValue().strip();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "<null>";
        }
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** The parsed handoff body the model emitted. */
    private record Body(String summary, List<String> openQuestions, List<String> nextSteps) {}
}
