package com.agentmemory.llmrecall;

import com.agentmemory.llm.LlmModule;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.recall.RecallConfiguration;
import com.agentmemory.recall.RecallService;
import javax.sql.DataSource;
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
 * ({@code recallService}); this module publishes the {@link LlmRecallService} as {@link Primary}, so
 * the MCP tools and the injection endpoint resolve the LLM-assisted service by type while the
 * decorator injects the base by name ({@code @Qualifier("recallService")}). When the feature is
 * disabled or over budget, the decorator delegates straight through to that base — no behavior change.
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
        return new QueryExpander(llmProvider, prompts, props.expansion().maxTerms());
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(LlmProvider.class)
    public CandidateReranker candidateReranker(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            RecallPrompts prompts,
            LlmRecallProperties props) {
        return new CandidateReranker(llmProvider, prompts, props.maxCandidates());
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
     * The LLM-assisted recall decorator, published {@link Primary} so it is the {@link RecallService}
     * injected everywhere (MCP tools, injection endpoint). It wraps the base {@code recallService} bean
     * by name. The expander is optional (it is absent when expansion is disabled), injected via
     * {@link org.springframework.beans.factory.ObjectProvider}.
     */
    @Bean
    @Primary
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({RecallService.class, CandidateReranker.class})
    public RecallService llmRecallService(
            @Qualifier("recallService") RecallService base,
            org.springframework.beans.factory.ObjectProvider<QueryExpander> expander,
            CandidateReranker reranker,
            AccessReinforcer reinforcer,
            LlmRecallProperties props) {
        return new LlmRecallService(base, expander.getIfAvailable(), reranker, reinforcer, props);
    }

    /**
     * The curated-injection service for the {@code UserPromptSubmit} hook. Uses the {@link Primary}
     * (LLM-assisted) {@link RecallService}.
     *
     * <p>Scope resolution for the injection endpoint reuses the MCP module's
     * {@link com.agentmemory.mcp.ScopeResolver} bean (both modules are gated identically on a
     * {@link DataSource} and always ship together), so this module declares no resolver of its own —
     * avoiding a second {@code ScopeResolver} that would make the MCP {@code memoryTools} injection
     * ambiguous.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(RecallService.class)
    public RecallInjection recallInjection(RecallService recall, LlmRecallProperties props) {
        return new RecallInjection(recall, props.injection());
    }
}
