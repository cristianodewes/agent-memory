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
 * <p>The session-end trigger is added to the {@link IngestService} as a post-write listener at
 * startup via {@link #handoffSessionEndRegistration} (the listeners fan out, so it coexists with
 * session consolidation's trigger, #18/#32). The cheap {@code session-end} check runs on the ingest
 * worker after the event is captured, but the blocking LLM generation is dispatched off the worker by
 * the trigger's own executor (issue #78), so it never stalls the ingest drain. The registration
 * depends on an {@code ObjectProvider} so it is a no-op when ingest is absent (DB-less context).
 */
@AutoConfiguration(after = StoreConfiguration.class)
public class HandoffConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HandoffService handoffService(LlmProvider llmProvider, HandoffRepository handoffRepository) {
        return new HandoffService(llmProvider, handoffRepository);
    }

    /**
     * The session-end → open-handoff trigger. Returned with {@code destroyMethod = "close"} so its
     * dedicated off-worker executor (issue #78) is shut down with the application context.
     */
    @Bean(destroyMethod = "close")
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
            service.addPostWriteListener(trigger);
        }
        return new HandoffTriggerRegistration(service != null);
    }

    /** Marker bean recording whether the session-end trigger was attached to ingest. */
    public record HandoffTriggerRegistration(boolean attached) {}
}
