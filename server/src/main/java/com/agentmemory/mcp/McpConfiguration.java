package com.agentmemory.mcp;

import com.agentmemory.handoff.HandoffService;
import com.agentmemory.recall.RecallService;
import com.agentmemory.store.PageRepository;
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
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
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
            RecallService recall, PageRepository pages, McpReadRepository reads, ScopeResolver scopes) {
        return new MemoryTools(recall, pages, reads, scopes, new McpJson(JsonMapper.builder().build()));
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
     * The MCP server: binds the transport to the tool surface and advertises tool support. Registers
     * the read tools ({@link MemoryTools}) plus, when the handoff module is wired, the begin/accept/
     * cancel handoff tools ({@link HandoffTools}, injected via {@link ObjectProvider} so the server
     * still starts with just the read tools when handoffs are absent). Returned as
     * {@link McpSyncServer} so its lifecycle (graceful close) is managed by the context.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnSingleCandidate(DataSource.class)
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider transportProvider,
            MemoryTools tools,
            ObjectProvider<HandoffTools> handoffTools) {
        List<SyncToolSpecification> specs = new ArrayList<>(tools.all());
        HandoffTools handoff = handoffTools.getIfAvailable();
        if (handoff != null) {
            specs.addAll(handoff.all());
        }
        return McpServer.sync(transportProvider)
                .serverInfo("agent-memory", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions(
                        "agent-memory: recall over this project's compiled memory, plus session "
                                + "handoffs. Use memory_query for hybrid search, memory_read_page for "
                                + "full bodies, memory_recent for latest pages, memory_status/"
                                + "memory_briefing for a project snapshot. memory_handoff_accept picks "
                                + "up where the previous agent left off (single-use); "
                                + "memory_handoff_begin opens one explicitly; memory_handoff_cancel "
                                + "expires a mistaken one. Scope defaults to the most recently active "
                                + "project; pass workspace+project to override.")
                .tools(specs)
                .build();
    }
}
