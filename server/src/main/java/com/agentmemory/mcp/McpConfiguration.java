package com.agentmemory.mcp;

import com.agentmemory.recall.RecallService;
import com.agentmemory.store.PageRepository;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
     * The MCP server: binds the transport to the read-only tool surface and advertises tool support.
     * Returned as {@link McpSyncServer} so its lifecycle (graceful close) is managed by the context.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnSingleCandidate(DataSource.class)
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider transportProvider, MemoryTools tools) {
        return McpServer.sync(transportProvider)
                .serverInfo("agent-memory", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions(
                        "agent-memory: read-only recall over this project's compiled memory. "
                                + "Use memory_query for hybrid search, memory_read_page for full bodies, "
                                + "memory_recent for latest pages, memory_status/memory_briefing for a "
                                + "project snapshot. Scope defaults to the most recently active project; "
                                + "pass workspace+project to override.")
                .tools(tools.all())
                .build();
    }
}
