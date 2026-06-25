package com.agentmemory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full-context smoke test of the web + config layers, without a database. Two things the context
 * needs to start are wired here (rather than in the shared test {@code application.properties}, so
 * neither leaks into the config defaults-precedence tests):
 *
 * <ul>
 *   <li>The LLM is a required dependency (DD-005), so the deterministic, network-free {@code test}
 *       provider is selected for both axes — otherwise the startup health gate fails fast.</li>
 *   <li>The DataSource + Flyway auto-configurations are excluded so this test stays
 *       infrastructure-free — the live Postgres + migrations path is covered end-to-end by
 *       {@link com.agentmemory.store.SchemaMigrationTest} against a Testcontainers pgvector
 *       instance.</li>
 * </ul>
 */
@SpringBootTest(properties = {
		"agent-memory.llm.auth.provider=test",
		"agent-memory.embeddings.auth.provider=test",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AgentMemoryServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
