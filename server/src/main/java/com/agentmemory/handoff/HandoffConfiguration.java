package com.agentmemory.handoff;

import com.agentmemory.hooks.IngestService;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.store.HandoffRepository;
import com.agentmemory.store.StoreConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

/**
 * Wires the LLM-generated handoff feature (issue #22): the {@link HandoffService}, and the
 * {@code session-end → open handoff} trigger.
 *
 * <p>The service needs the {@link HandoffRepository} (a {@code DataSource}-gated store bean) and the
 * required {@link LlmProvider}; both are present in a wired context. Like the other store-coupled
 * modules it is gated on a {@code DataSource} ({@link ConditionalOnSingleCandidate}) so the DB-less
 * smoke context still loads, and ordered after {@link StoreConfiguration} (declared in
 * {@code META-INF/spring/.../AutoConfiguration.imports}) so the repository bean exists when the
 * condition is evaluated.
 *
 * <p>The session-end trigger is attached to the {@link IngestService} as its post-write listener at
 * startup via {@link #handoffSessionEndRegistration}; it fires on the ingest worker thread after a
 * {@code session-end} event is captured. The registration depends on an {@code ObjectProvider} so it
 * is a no-op when ingest is absent (DB-less context).
 */
@AutoConfiguration(after = StoreConfiguration.class)
public class HandoffConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HandoffService handoffService(LlmProvider llmProvider, HandoffRepository handoffRepository) {
        return new HandoffService(llmProvider, handoffRepository);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public SessionEndHandoffTrigger sessionEndHandoffTrigger(HandoffService handoffService) {
        return new SessionEndHandoffTrigger(handoffService);
    }

    /**
     * Attach the session-end trigger to the ingest pipeline once both exist. Returned as a tiny
     * lifecycle bean whose construction performs the wiring; {@link IngestService} is resolved through
     * an {@link ObjectProvider} so this is a no-op when ingest is not wired.
     *
     * @param ingest  the ingest service (optional).
     * @param trigger the session-end handoff trigger.
     * @return a marker object recording that registration ran.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HandoffTriggerRegistration handoffSessionEndRegistration(
            ObjectProvider<IngestService> ingest, SessionEndHandoffTrigger trigger) {
        IngestService service = ingest.getIfAvailable();
        if (service != null) {
            service.setPostWriteListener(trigger);
        }
        return new HandoffTriggerRegistration(service != null);
    }

    /** Marker bean recording whether the session-end trigger was attached to ingest. */
    public record HandoffTriggerRegistration(boolean attached) {}
}
