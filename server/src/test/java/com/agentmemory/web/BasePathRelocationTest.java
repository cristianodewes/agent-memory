package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the {@code --base-path} relocation (issue #35 acceptance): setting
 * {@code agent-memory.server.base-path} moves <strong>all</strong> HTTP surfaces — the {@code /api/v1}
 * controllers, the {@code /mcp} servlet, and {@code /healthz} — under one prefix together, and the
 * bare (un-prefixed) paths no longer resolve. The bridge is
 * {@link BasePathEnvironmentPostProcessor}, which maps the config key onto
 * {@code server.servlet.context-path} before the servlet container starts.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.server.base-path=/agent-memory"
        })
@Testcontainers
class BasePathRelocationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @Test
    void allSurfacesMoveUnderTheBasePathTogether() {
        // /healthz relocates: present under the prefix, absent at the root.
        assertThat(http.get("/agent-memory/healthz").status()).isEqualTo(200);
        assertThat(http.get("/healthz").status()).isEqualTo(404);

        // /api/v1 relocates: the graph seam answers 200 under the prefix, 404 at the root.
        assertThat(http.get("/agent-memory/api/v1/graph").status()).isEqualTo(200);
        assertThat(http.get("/api/v1/graph").status()).isEqualTo(404);
    }

    @Test
    void mcpEndpointAlsoMovesUnderTheBasePath() {
        // The MCP streamable-HTTP servlet is reachable under the prefix (not 404); at the bare /mcp it
        // is gone — i.e. it moved with the others.
        assertThat(http.get("/agent-memory/mcp").status()).isNotEqualTo(404);
        assertThat(http.get("/mcp").status()).isEqualTo(404);
    }

    @Test
    void webUiAlsoMovesUnderTheBasePath() {
        // The embedded /web browser (#36) relocates with the API/MCP: the reference index is served
        // under the prefix, and the bare /web/ is gone — so its relative ../api/v1 fetch stays aligned.
        assertThat(http.get("/agent-memory/web/").status()).isEqualTo(200);
        assertThat(http.get("/web/").status()).isEqualTo(404);
    }
}
