package com.agentmemory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that the application context (web + config layers) loads without a database. The
 * DataSource and Flyway auto-configurations are excluded here so this test stays infrastructure-free
 * — the live Postgres + migrations path is covered end-to-end by
 * {@link com.agentmemory.store.SchemaMigrationTest} against a Testcontainers pgvector instance.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
		+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
		+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
class AgentMemoryServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
