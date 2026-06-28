package com.agentmemory.llmrecall;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.ProviderAuth;
import com.agentmemory.llm.CrossEncoderClient;
import com.agentmemory.llm.LlmModule;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.ReasoningEffort;
import com.agentmemory.llm.VoyageReranker;
import com.agentmemory.recall.PageEmbeddingService;
import com.agentmemory.recall.RecallConfiguration;
import com.agentmemory.recall.RecallService;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires LLM-assisted recall (issue #21): query expansion, candidate re-rank, curated injection, and
 * access reinforcement, layered over the base hybrid recall from {@link RecallConfiguration}.
 *
 * <p>Declared {@link AutoConfigureAfter} {@link RecallConfiguration} (it decorates that module's
 * {@link RecallService}) and {@link LlmModule} (it needs the required {@link LlmProvider}). Every
 * DB-touching bean is gated on a {@link DataSource} ({@link ConditionalOnSingleCandidate}) — the same
 * gate the store/recall/mcp modules use — so the DB-less smoke context still loads (there the base
 * {@code RecallService} is absent, so this decorator simply does not register and recall is unwired,
 * exactly as before). Listed in {@code META-INF/spring/.../AutoConfiguration.imports} after the JDBC
 * auto-config.
 *
 * <h2>Decoration</h2>
 * The base {@link RecallService} from {@link RecallConfiguration} keeps its bean name
 * ({@code recallService}); this module decorates it by name in two layers. {@link LlmRecallService}
 * wraps the base ({@code @Qualifier("recallService")}) and is published under the name
 * {@code llmRecallService}; since Fase 4 (issue #142) the {@link CachingRecallService} wraps
 * <em>that</em> by name ({@code @Qualifier("llmRecallService")}) and is the {@link Primary} bean, so the
 * full chain is {@code cache → llmRecallService → recallService (hybrid)}. The MCP tools and the
 * injection endpoint resolve the {@code @Primary} cache by type; each layer delegates straight through
 * when its feature is disabled (the cache on a pass-through, the LLM service when over budget), so the
 * result is never worse than the hybrid baseline.
 *
 * <h2>Access reinforcement (#24 seam)</h2>
 * {@link JdbcAccessReinforcer} is published {@code @ConditionalOnMissingBean(AccessReinforcer.class)},
 * so issue #24 can later supply its own reinforcer (with the full decay model) and take over without
 * this module changing; until then the additive column bump satisfies #21's "reinforcement fires on
 * returned hits".
 */
@AutoConfiguration(after = {RecallConfiguration.class, LlmModule.class})
@EnableConfigurationProperties(LlmRecallProperties.class)
public class LlmRecallConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmRecallConfiguration.class);

    /** The static prompts + structured-output schemas for the two LLM steps. No deps. */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallPrompts recallPrompts() {
        return new RecallPrompts();
    }

    /**
     * The query-expander. Built only when expansion is enabled, so a deployment that turns it off pays
     * nothing (and the decorator sees a {@code null} expander via {@link org.springframework.beans.factory.ObjectProvider}).
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(LlmProvider.class)
    public QueryExpander queryExpander(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            RecallPrompts prompts,
            LlmRecallProperties props) {
        return new QueryExpander(
                llmProvider, prompts, props.expansion().maxTerms(), recallEffort(props));
    }

    /**
     * The generative LLM reranker. Since Fase 2 it is the <em>fallback</em> behind the calibrated
     * cross-encoder ({@link #reranker}): it runs when the cross-encoder is absent (no Voyage embeddings
     * auth) or fails. Always built when an {@link LlmProvider} exists.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(LlmProvider.class)
    public CandidateReranker candidateReranker(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            RecallPrompts prompts,
            LlmRecallProperties props) {
        return new CandidateReranker(
                llmProvider, prompts, props.maxCandidates(), recallEffort(props));
    }

    /**
     * The calibrated cross-encoder client (issue #130, Fase 2), or {@code null} when it is not active.
     * It is wired only when the cross-encoder is enabled <em>and</em> the embeddings provider is Voyage
     * with an API key — the rerank API reuses that same Voyage key (DD-005). A {@code null} bean is the
     * "not configured" signal (mirrors {@code LlmModule.embedder}); the {@link #reranker} seam then keeps
     * the LLM reranker. No network call is made here — the client is built lazily and probed per request,
     * degrading on failure.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CrossEncoderClient crossEncoderClient(AgentMemoryConfig config, LlmRecallProperties props) {
        LlmRecallProperties.CrossEncoder ce = props.crossEncoder();
        if (!ce.enabled()) {
            log.info("Cross-encoder rerank disabled by config; recall rerank uses the LLM reranker.");
            return null;
        }
        ProviderAuth auth = config.embeddings().auth();
        if (auth == null
                || !VoyageReranker.PROVIDER_KEY.equals(auth.providerKey())
                || !auth.hasApiKey()) {
            log.info("Cross-encoder rerank inactive: embeddings provider is not Voyage with an API key; "
                    + "recall rerank uses the LLM reranker.");
            return null;
        }
        VoyageReranker reranker = new VoyageReranker(auth, ce.model());
        log.info("Cross-encoder rerank enabled: provider=voyage, model={}", reranker.model());
        return reranker;
    }

    /**
     * The {@link Reranker} seam injected into {@link LlmRecallService}: the calibrated cross-encoder in
     * front of the LLM reranker, degrading to it (then to raw RRF) on absence/error/timeout — never worse
     * than the hybrid baseline. The cross-encoder client is optional (absent when Voyage is not
     * configured), injected via {@link ObjectProvider}.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(CandidateReranker.class)
    public Reranker reranker(
            ObjectProvider<CrossEncoderClient> crossEncoder,
            CandidateReranker candidateReranker,
            LlmRecallProperties props) {
        return new CrossEncoderReranker(
                crossEncoder.getIfAvailable(), candidateReranker, props.crossEncoder().maxDocuments());
    }

    /**
     * The MMR diversifier (issue #141, Fase 4) injected into {@link LlmRecallService}: the post-rerank,
     * pre-trim step that re-orders the candidates by relevance − redundancy over their embeddings so a
     * final injection block is not several bullets of one topic. It reuses {@link PageEmbeddingService}
     * to read the candidate vectors under the active embedder's {@code {provider, model}} (one bounded
     * by-id query; no network), so it is built only when that bean exists — i.e. alongside the rest of
     * the DB-backed recall. Whether it fires per request is gated by {@code mmr.enabled} (default true),
     * and it degrades to the rerank order whenever embeddings are absent.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(PageEmbeddingService.class)
    public MmrDiversifier mmrDiversifier(
            PageEmbeddingService pageEmbeddingService, LlmRecallProperties props) {
        return new MmrDiversifier(pageEmbeddingService::embeddingsFor, props.mmr().lambda());
    }

    /**
     * The brief synthesizer (issue #135, Fase 3) — the optional final curation step that turns the
     * relevance-gated hits into a short cited paragraph. Built whenever an {@link LlmProvider} exists
     * (like {@link #candidateReranker}); whether it actually fires is gated per-request by
     * {@code injection.brief.enabled} (default off) and by calibrated scores inside {@link RecallInjection},
     * so an operator enables the brief by config alone without re-wiring. It carries the same
     * {@link ReasoningEffort#MINIMAL} hint as the other recall calls.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(LlmProvider.class)
    public BriefSynthesizer briefSynthesizer(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            RecallPrompts prompts,
            LlmRecallProperties props) {
        return new BriefSynthesizer(llmProvider, prompts, recallEffort(props));
    }

    /**
     * The reasoning-effort hint the recall steps put on their LLM calls (issue #130, Fase 1):
     * {@link ReasoningEffort#MINIMAL} when {@code minimal-reasoning} is on (default), else {@code null}
     * (unchanged provider behavior) so an operator can disable it should a backend reject the param.
     */
    private static ReasoningEffort recallEffort(LlmRecallProperties props) {
        return props.minimalReasoning() ? ReasoningEffort.MINIMAL : null;
    }

    /**
     * The additive access-reinforcement bump (#24 seam). Overridable: if #24 publishes its own
     * {@link AccessReinforcer}, that one wins and this is not created.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(AccessReinforcer.class)
    public AccessReinforcer accessReinforcer(JdbcTemplate jdbcTemplate) {
        return new JdbcAccessReinforcer(jdbcTemplate);
    }

    /**
     * Per-stage recall latency telemetry (issue #130 follow-up). Binds the Micrometer
     * {@link MeterRegistry} Spring Actuator auto-configures (the actuator starter is on the classpath),
     * injected via {@link ObjectProvider} so the seam degrades to structured-log-only if no registry is
     * present rather than failing to wire.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallMetrics recallMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new RecallMetrics(meterRegistry.getIfAvailable());
    }

    /**
     * The LLM-assisted recall decorator. It wraps the base {@code recallService} bean by name and keeps
     * its own bean name ({@code llmRecallService}); since Fase 4 it is itself wrapped by the
     * {@link #cachingRecallService} which is the {@link Primary} {@link RecallService}, so this bean is
     * resolved by name (by the cache) rather than by type. The expander and the MMR diversifier are
     * optional (the expander is absent when expansion is disabled; the diversifier when there is no
     * embeddings module), both injected via {@link org.springframework.beans.factory.ObjectProvider}
     * and tolerated as {@code null}.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({RecallService.class, Reranker.class})
    public RecallService llmRecallService(
            @Qualifier("recallService") RecallService base,
            ObjectProvider<QueryExpander> expander,
            Reranker reranker,
            ObjectProvider<MmrDiversifier> mmr,
            AccessReinforcer reinforcer,
            LlmRecallProperties props,
            RecallMetrics metrics) {
        return new LlmRecallService(
                base, expander.getIfAvailable(), reranker, mmr.getIfAvailable(), reinforcer, props,
                System::currentTimeMillis, metrics);
    }

    /**
     * The short-TTL recall-result cache (issue #142, Fase 4), published {@link Primary} so it is the
     * {@link RecallService} every by-type injection point resolves — the MCP tools, the
     * {@link #recallInjection} endpoint, and {@link com.agentmemory.recall.CrossProjectRecallService}
     * (which all inject {@code RecallService} by type, and {@code @Primary} wins over a bean-name match).
     *
     * <p><strong>Wiring (mirrors the {@link LlmRecallService} decoration).</strong> Exactly as the
     * LLM-assisted service wraps the base {@code recallService} <em>by name</em>, this cache wraps the
     * LLM-assisted service by name ({@code @Qualifier("llmRecallService")}) and takes over {@code @Primary}.
     * The decoration chain is therefore {@code cache → llmRecallService → recallService (hybrid)}: the
     * LLM service stays a named, by-type-resolvable bean (its {@code base()} and the {@code RecallInjection}
     * calibrated gate are untouched — the gate reads the {@code calibrated} flag off the cached
     * {@link com.agentmemory.recall.RecallResult}, which the cache preserves verbatim). Gated identically
     * to {@code llmRecallService} so the two are created together (or neither is, in the DB-less context),
     * keeping the {@code @Qualifier("llmRecallService")} delegate guaranteed present.
     */
    @Bean
    @Primary
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({RecallService.class, Reranker.class})
    public RecallService cachingRecallService(
            @Qualifier("llmRecallService") RecallService delegate, LlmRecallProperties props) {
        return new CachingRecallService(delegate, props.cache(), System::nanoTime);
    }

    /**
     * The curated-injection service for the {@code UserPromptSubmit} hook. Uses the {@link Primary}
     * {@link RecallService} — since Fase 4 the {@link CachingRecallService} in front of the LLM-assisted
     * service — resolved by type.
     *
     * <p>Scope resolution for the injection endpoint reuses the MCP module's
     * {@link com.agentmemory.mcp.ScopeResolver} bean (both modules are gated identically on a
     * {@link DataSource} and always ship together), so this module declares no resolver of its own —
     * avoiding a second {@code ScopeResolver} that would make the MCP {@code memoryTools} injection
     * ambiguous.
     *
     * <p>The {@link BriefSynthesizer} (Fase 3) is optional — absent when no {@link LlmProvider} is
     * configured — so it is injected via {@link ObjectProvider}, exactly like the cross-encoder client.
     * When absent (or disabled by config) the injection renders the pre-Fase-3 bullets, unchanged.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(RecallService.class)
    public RecallInjection recallInjection(
            RecallService recall,
            ObjectProvider<BriefSynthesizer> briefSynthesizer,
            LlmRecallProperties props) {
        return new RecallInjection(recall, props.injection(), briefSynthesizer.getIfAvailable());
    }
}
