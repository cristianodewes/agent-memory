package com.agentmemory.chat;

import com.agentmemory.llm.LlmModule;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llmrecall.LlmRecallConfiguration;
import com.agentmemory.recall.RecallConfiguration;
import com.agentmemory.recall.RecallService;
import com.agentmemory.store.PageRepository;
import com.agentmemory.store.StoreConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires "chat with your memory" (issue #37). Chat grounds answers in the wiki via hybrid
 * {@link RecallService recall} (#15/#16/#21) and generates with the required {@link LlmProvider} (#6),
 * reading pages through {@link PageRepository} — all read-only.
 *
 * <p>Declared {@link AutoConfiguration} ordered after the recall, LLM-recall, LLM and store modules so
 * their beans exist when the conditions are evaluated; it injects {@link RecallService} by type, so the
 * {@link org.springframework.context.annotation.Primary @Primary} LLM-assisted recall decorator (#21)
 * is used when present. Every bean is gated on a {@link DataSource}
 * ({@link ConditionalOnSingleCandidate}, the same gate the store/recall/web modules use) so the DB-less
 * smoke context still loads — there recall/pages are absent, chat does not register, and
 * {@code ChatController} answers {@code 503}. The whole feature is also gated on
 * {@code agent-memory.chat.enabled} (default on), so an operator can switch it off entirely.
 *
 * <p>{@code ChatController} is a component-scanned {@code @RestController} that injects these beans via
 * {@code ObjectProvider}, mirroring {@code ApiV1Controller}/{@code RecallInjectionController}: it
 * constructs even without a {@code DataSource} (and answers 503), so it is not declared here.
 */
@AutoConfiguration(after = {
    RecallConfiguration.class,
    LlmRecallConfiguration.class,
    LlmModule.class,
    StoreConfiguration.class
})
@EnableConfigurationProperties(ChatProperties.class)
@ConditionalOnProperty(prefix = "agent-memory.chat", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ChatConfiguration {

    /** The versioned chat system prompt loaded from the classpath. No deps. */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ChatPrompts chatPrompts() {
        return new ChatPrompts();
    }

    /**
     * The read-only RAG chat service. Injects {@link RecallService} by type so the {@code @Primary}
     * LLM-assisted recall (#21) is used when wired; gated so it exists only when recall, pages and the
     * LLM are all present.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({RecallService.class, PageRepository.class, LlmProvider.class})
    public ChatService chatService(
            RecallService recall,
            PageRepository pages,
            @Qualifier("llmProvider") LlmProvider llm,
            ChatPrompts prompts,
            ChatProperties props) {
        return new ChatService(recall, pages, llm, prompts, props);
    }
}
