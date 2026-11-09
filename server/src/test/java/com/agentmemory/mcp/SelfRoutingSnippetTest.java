package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the block {@code memory_install_self_routing} returns to the shared contract
 * {@code docs/contracts/fixtures/self_routing_snippet.md}. The Go installer's
 * {@code TestSelfRoutingSnippetMatchesContractFixture} pins the block it writes to the same file, so both
 * suites green mean the MCP tool and the offline installer hand out the same bytes. If they diverged, a
 * block pasted from the tool and a later {@code agent-memory upgrade} would keep rewriting each other.
 *
 * <p>The fixture lives at the repo root, one level up from {@code server/} where tests run.
 */
class SelfRoutingSnippetTest {

    private static final Path FIXTURE = Path.of("..", "docs", "contracts", "fixtures", "self_routing_snippet.md")
            .toAbsolutePath()
            .normalize();

    @Test
    void markdownMatchesTheSharedContractFixture() throws IOException {
        String expected = Files.readString(FIXTURE, StandardCharsets.UTF_8);

        assertThat(SelfRoutingSnippet.markdown()).isEqualTo(expected);
    }
}
