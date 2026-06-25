package com.agentmemory.mcp;

import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.consolidate.MemoryExplore;
import com.agentmemory.handoff.HandoffService;
import com.agentmemory.hooks.Sanitizer;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.recall.CrossProjectRecallService;
import com.agentmemory.recall.RecallService;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.SlotsReader;
import com.agentmemory.wiki.WikiWriter;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the MCP server (issue #17 / DD-003): a Streamable-HTTP endpoint at {@code /mcp} exposing the
 * five read-only memory tools. Hosted on the Spring server because the tool logic touches the
 * storage and recall layers — one implementation, not duplicated in the Go client.
 *
 * <p>The transport is the SDK's {@link HttpServletStreamableServerTransportProvider} — a plain
 * Jakarta servlet — registered at {@code /mcp} via a {@link ServletRegistrationBean} (async-enabled,
 * which the streaming transport requires). The {@link McpSyncServer} binds that transport to the tool
 * specifications. JSON uses Spring Boot 4's Jackson 3 mapper, matching the SDK's Jackson-3 binding.
 *
 * <p>Like {@code StoreConfiguration}/{@code RecallConfiguration}, the DB-backed beans are gated on a
 * {@link DataSource} ({@link ConditionalOnSingleCandidate}) so the DB-less smoke context still loads;
 * registered in {@code META-INF/spring/.../AutoConfiguration.imports} after the JDBC auto-config so
 * the {@link JdbcTemplate} exists when the condition is evaluated.
 */
@AutoConfiguration(
        after = JdbcTemplateAutoConfiguration.class,
        afterName = "com.agentmemory.links.LinksConfiguration")
public class McpConfiguration {

    /** MCP endpoint path (ARCHITECTURE §5.1 / DD-003). */
    public static final String MCP_ENDPOINT = "/mcp";

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public McpReadRepository mcpReadRepository(JdbcTemplate jdbcTemplate) {
        return new McpReadRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ScopeResolver mcpScopeResolver(McpReadRepository reads) {
        return new ScopeResolver(reads);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public MemoryTools memoryTools(
            RecallService recall, CrossProjectRecallService crossRecall, PageRepository pages,
            McpReadRepository reads, ScopeResolver scopes, SlotsReader slots) {
        return new MemoryTools(
                recall, crossRecall, pages, reads, scopes, slots,
                new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The handoff MCP tools (issue #22): begin/accept/cancel over {@link HandoffService}. Built only
     * when the handoff module is wired — its service is injected through an {@link ObjectProvider}, so
     * the MCP server still starts (with just the read tools) if handoffs are unavailable.
     *
     * @param scopes the shared scope resolver (reused from the read surface).
     * @param handoff the handoff service, if the handoff module is present.
     * @return the handoff tools, or {@code null} when no {@link HandoffService} bean exists.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HandoffTools handoffTools(ScopeResolver scopes, ObjectProvider<HandoffService> handoff) {
        HandoffService service = handoff.getIfAvailable();
        if (service == null) {
            return null;
        }
        return new HandoffTools(service, scopes, new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The admission chain behind the two write tools (issue #20): redaction + versioned store +
     * wiki/git commit + audit, all atomically. DataSource-gated like the rest of the MCP beans.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public MemoryWriteService memoryWriteService(
            PageRepository pages, WikiWriter wikiWriter, WikiLinkService wikiLinkService,
            Sanitizer sanitizer, JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        return new MemoryWriteService(
                pages, wikiWriter, wikiLinkService, sanitizer, jdbcTemplate, txManager);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public MemoryWriteTools memoryWriteTools(MemoryWriteService writes, ScopeResolver scopes) {
        return new MemoryWriteTools(writes, scopes, new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The consolidation MCP tools (issue #19): {@code memory_consolidate} + {@code memory_explore}.
     * Built only when the consolidate module is wired — its {@link Consolidator} and
     * {@link MemoryExplore} live in {@code ConsolidateConfiguration} (ordered after this), so they are
     * injected through {@link ObjectProvider}s and resolved on demand when the MCP server is assembled;
     * the server still starts without them.
     *
     * @param scopes      the shared scope resolver.
     * @param consolidator the LLM multi-page consolidation orchestrator, if present.
     * @param explore     the prose-digest service, if present.
     * @return the consolidation tools, or {@code null} when the consolidate beans are absent.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ConsolidationTools consolidationTools(
            ScopeResolver scopes,
            ObjectProvider<Consolidator> consolidator,
            ObjectProvider<MemoryExplore> explore) {
        Consolidator c = consolidator.getIfAvailable();
        MemoryExplore e = explore.getIfAvailable();
        if (c == null || e == null) {
            return null;
        }
        return new ConsolidationTools(c, e, scopes, new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The {@code memory_forget_sweep} tool (issue #25). Built here (where the package-private
     * {@link McpJson} helper lives) over the {@link com.agentmemory.forget.ForgetSweepService} bean
     * wired by {@code ForgetConfiguration}. DataSource-gated like the rest.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public MemorySweepTools memorySweepTools(
            com.agentmemory.forget.ForgetSweepService sweep, ScopeResolver scopes) {
        return new MemorySweepTools(sweep, scopes, new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The {@code memory_lint} tool (issue #29) over the {@link com.agentmemory.curate.MemoryLintService}
     * bean wired by {@code CuratorConfiguration}. DataSource-gated and injecting the service directly —
     * the same shape as {@code memorySweepTools} over the cross-configuration
     * {@link com.agentmemory.forget.ForgetSweepService} — so it is present in every wired context (the
     * lint service exists whenever a DataSource + the required LLM do) and absent in the DB-less smoke
     * context. (A cross-configuration {@code @ConditionalOnBean} would be auto-config-ordering fragile.)
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public LintTools lintTools(
            com.agentmemory.curate.MemoryLintService lint, ScopeResolver scopes) {
        return new LintTools(lint, scopes, new McpJson(JsonMapper.builder().build()));
    }

    /**
     * The Streamable-HTTP transport provider (a Jakarta servlet). Built with the Jackson-3 JSON
     * mapper and the {@code /mcp} endpoint.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
    }

    /** Mount the transport servlet at {@code /mcp} (async dispatch is required by the streaming transport). */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, MCP_ENDPOINT, MCP_ENDPOINT + "/*");
        registration.setAsyncSupported(true);
        registration.setName("mcpStreamableHttpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * The MCP server: binds the transport to the full tool surface and advertises tool support.
     * Registers the read tools ({@link MemoryTools}), the issue #20 write tools
     * ({@link MemoryWriteTools}), and — when the handoff module is wired — the begin/accept/cancel
     * handoff tools ({@link HandoffTools}, injected via {@link ObjectProvider} so the server still
     * starts without them). Returned as {@link McpSyncServer} so its lifecycle (graceful close) is
     * managed by the context.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnSingleCandidate(DataSource.class)
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider transportProvider,
            MemoryTools tools,
            MemoryWriteTools writeTools,
            MemorySweepTools sweepTools,
            ObjectProvider<HandoffTools> handoffTools,
            ObjectProvider<ConsolidationTools> consolidationTools,
            ObjectProvider<LintTools> lintTools) {
        List<SyncToolSpecification> specs = new ArrayList<>(tools.all());
        specs.addAll(writeTools.all());
        specs.addAll(sweepTools.all());
        HandoffTools handoff = handoffTools.getIfAvailable();
        if (handoff != null) {
            specs.addAll(handoff.all());
        }
        ConsolidationTools consolidation = consolidationTools.getIfAvailable();
        if (consolidation != null) {
            specs.addAll(consolidation.all());
        }
        LintTools lints = lintTools.getIfAvailable();
        if (lints != null) {
            specs.addAll(lints.all());
        }
        return McpServer.sync(transportProvider)
                .serverInfo("agent-memory", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions(
                        "agent-memory: recall over and curation of this project's compiled memory, "
                                + "plus session handoffs. Read: memory_query for hybrid search, "
                                + "memory_read_page for full bodies, memory_recent for latest pages, "
                                + "memory_status/memory_briefing for a project snapshot, memory_explore "
                                + "for a prose digest. Write (only when the user explicitly asks to "
                                + "remember or delete): memory_write_page creates/updates a durable "
                                + "page, memory_delete_page removes one by path, memory_consolidate "
                                + "compiles a session's durable knowledge into pages (multi_page fans "
                                + "out atomically). Maintenance: memory_forget_sweep evicts cold pages "
                                + "(soft-delete then purge; pass dry_run=true to preview). Handoffs: "
                                + "memory_handoff_accept picks up where the previous agent left off "
                                + "(single-use), memory_handoff_begin opens one explicitly, "
                                + "memory_handoff_cancel expires a mistaken one. Scope defaults to the "
                                + "most recently active project; pass workspace+project to override.")
                .tools(specs)
                .build();
    }
}
