package com.agentmemory.recall;

import com.agentmemory.llm.Embedder;
import com.agentmemory.store.PageEmbeddingStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code recall} beans (issue #15). Like {@link com.agentmemory.store.StoreConfiguration},
 * the DB-backed beans ({@link RecallRepository}, {@link HybridRecallService}) are registered only
 * when a JDBC {@link DataSource} / {@link JdbcTemplate} is present
 * ({@link ConditionalOnSingleCandidate}) — the same gate Spring Boot uses for {@code JdbcTemplate} —
 * so the DB-less smoke test (which excludes {@code DataSourceAutoConfiguration}) still loads cleanly,
 * while production and the Testcontainers tests get a fully wired {@link RecallService}.
 *
 * <p>The {@link RrfFusion} strategy carries no DB dependency and is a plain {@code @Component} (so a
 * future arm/re-ranker can replace it via the {@link Fusion} interface); it is injected here.
 * Declared {@link AutoConfigureAfter} the JDBC auto-configuration and listed in
 * {@code META-INF/spring/.../AutoConfiguration.imports} so the {@code JdbcTemplate} bean exists when
 * the condition is evaluated.
 *
 * <p><strong>Vector arm (#16).</strong> {@link PageEmbeddingStore}, {@link VectorArm}, and
 * {@link PageEmbeddingService} are also registered when a {@link DataSource} is present. The
 * {@link Embedder} they need is optional (DD-005): it is injected by bean name via an
 * {@link ObjectProvider} (the {@code test} double implements both {@link Embedder} and the chat
 * provider, so a by-type injection would be ambiguous, exactly as in {@code LlmModule}), and may
 * resolve to {@code null}. When absent or width-mismatched, the vector arm contributes nothing and
 * recall degrades to FTS + graph — the {@link HybridRecallService} is wired with the arm regardless,
 * and the arm self-disables.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@EnableConfigurationProperties(RecencyProperties.class)
public class RecallConfiguration {

    /**
     * The rank-fusion strategy: RRF, wrapped by the per-layer recency prior (issue #140) unless that
     * prior is disabled by config. Declared {@code @ConditionalOnMissingBean} so a later issue (#16
     * vector arm, #21 re-rank) can still override it by publishing its own {@link Fusion}. Carries no DB
     * dependency, so it is available even in the DB-less smoke context.
     *
     * <p>The recency decorator is applied here, in the {@code recall} base fusion, so it runs
     * <em>before</em> the LLM-recall cross-encoder re-rank ({@code LlmRecallService}): it reorders the
     * fused candidate pool and the non-LLM fast path, and the cross-encoder re-scores the resulting head
     * by pure relevance (see {@link RecencyDecayFusion}). The {@link Clock} is the shared retention clock
     * when present (so recency and the decay sweep read one "now"), falling back to the system UTC clock
     * in a context that wires no clock bean — keeping this DB-less bean robust.
     *
     * @param recency the recency-prior tuning (half-lives + on/off).
     * @param clock   provider for the shared clock; system UTC when none is wired.
     * @return the (optionally recency-decorated) {@link Fusion}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(Fusion.class)
    public Fusion rrfFusion(RecencyProperties recency, ObjectProvider<Clock> clock) {
        Fusion base = new RrfFusion();
        if (!recency.enabled()) {
            return base; // prior off → untouched RRF order
        }
        RecencyDecay decay = new RecencyDecay(recency.toParameters(), clock.getIfAvailable(Clock::systemUTC));
        return new RecencyDecayFusion(base, decay);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallRepository recallRepository(JdbcTemplate jdbcTemplate) {
        return new RecallRepository(jdbcTemplate);
    }

    /**
     * The {@code page_embeddings} persistence (#16). DB-backed, so gated on a {@link DataSource}.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public PageEmbeddingStore pageEmbeddingStore(JdbcTemplate jdbcTemplate) {
        return new PageEmbeddingStore(jdbcTemplate);
    }

    /**
     * The vector (semantic) recall arm (#16). The {@link Embedder} is optional (DD-005) and injected
     * by name through an {@link ObjectProvider}; {@code getIfAvailable()} yields {@code null} when the
     * embeddings axis is off, and the arm self-disables.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public VectorArm vectorArm(
            PageEmbeddingStore store,
            @Qualifier("embedder") ObjectProvider<Embedder> embedder) {
        return new VectorArm(store, embedder.getIfAvailable());
    }

    /**
     * The embed-on-write / backfill (#14 seam) service (#16). Optional {@link Embedder} as above;
     * never throws into a write when embeddings are unavailable.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public PageEmbeddingService pageEmbeddingService(
            PageEmbeddingStore store,
            @Qualifier("embedder") ObjectProvider<Embedder> embedder) {
        return new PageEmbeddingService(store, embedder.getIfAvailable());
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallService recallService(
            RecallRepository recallRepository, Fusion fusion, VectorArm vectorArm) {
        return new HybridRecallService(recallRepository, fusion, vectorArm);
    }

    /**
     * Cross-project recall (#29): fans the single-scope {@link RecallService} out over named scopes or
     * every project ({@code global}) and merges the per-scope ranked hits. DB-backed (it enumerates
     * scopes for {@code global}), so gated on a {@link DataSource} like the rest.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CrossProjectRecallService crossProjectRecallService(
            RecallService recallService, RecallRepository recallRepository) {
        return new CrossProjectRecallService(recallService, recallRepository);
    }
}
