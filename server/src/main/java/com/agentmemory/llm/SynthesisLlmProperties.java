package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The synthesis route (issue #156), bound under {@code agent-memory.synthesis.llm}: an optional model
 * for the durable text the server writes from the sessions — the session page's final synthesis, the
 * thematic pages and the handoff — separate from the primary model that does the bulk work (the
 * per-chunk summaries of a long session, recall, lint, chat). Those three calls read a bounded input
 * (about 12k tokens each), so a stronger model there costs little next to the chunk summaries.
 *
 * @param auth optional provider/model in the shape of {@code llm.auth} ({@link ProviderAuth}); all
 *     fields optional. Empty (the default) ⇒ the synthesis calls reuse the primary chat provider (no
 *     behaviour change). Only a {@code model} ⇒ the chat auth (same provider/key/OAuth) with the model
 *     swapped, e.g. {@code AGENT_MEMORY_SYNTHESIS_LLM_AUTH_MODEL}; a {@code provider} ⇒ an independent
 *     provider on its own key. Resolved into the {@code synthesisLlmProvider} bean in {@link LlmModule},
 *     which falls back to the primary on any failure.
 */
@ConfigurationProperties(prefix = "agent-memory.synthesis.llm")
public record SynthesisLlmProperties(@DefaultValue ProviderAuth auth) {}
