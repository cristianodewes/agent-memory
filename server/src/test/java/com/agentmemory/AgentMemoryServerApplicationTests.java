package com.agentmemory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full-context smoke test. The LLM is a required dependency (DD-005), so the context will not start
 * without a reachable chat provider; we select the deterministic, network-free {@code test} provider
 * for both axes here (rather than in the shared test {@code application.properties}) so it does not
 * leak into the config defaults-precedence tests.
 */
@SpringBootTest(properties = {
		"agent-memory.llm.auth.provider=test",
		"agent-memory.embeddings.auth.provider=test"
})
class AgentMemoryServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
