package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the shared OAuth token file (issue #113): the {@code openai} entry round-trips, other
 * providers' entries are preserved on write, a non-{@code oauth} entry is ignored, and an absent file
 * reads as "no token" rather than an error. This is the on-disk contract the client login writes and
 * the server reads/refreshes.
 */
class OpenAiOAuthTokenStoreTest {

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path tmp) {
        Path file = tmp.resolve("auth.json");
        OpenAiOAuthTokenStore store = new OpenAiOAuthTokenStore(file);

        store.save(new OpenAiOAuthTokenStore.Token("acc", "ref", 1730000000000L, "acct-1"));

        Optional<OpenAiOAuthTokenStore.Token> loaded = store.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().access()).isEqualTo("acc");
        assertThat(loaded.get().refresh()).isEqualTo("ref");
        assertThat(loaded.get().expiresAtMs()).isEqualTo(1730000000000L);
        assertThat(loaded.get().accountId()).isEqualTo("acct-1");
    }

    @Test
    void preservesOtherProviderEntries(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("auth.json");
        Files.writeString(file, "{\"copilot\":{\"type\":\"oauth\",\"access\":\"x\"}}");
        OpenAiOAuthTokenStore store = new OpenAiOAuthTokenStore(file);

        store.save(new OpenAiOAuthTokenStore.Token("acc", "ref", 1L, null));

        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(root.get("copilot")).isNotNull(); // untouched
        assertThat(root.get("openai").get("access").stringValue()).isEqualTo("acc");
        // No accountId written when absent (optional field).
        assertThat(root.get("openai").get("accountId")).isNull();
    }

    @Test
    void ignoresNonOauthOpenaiEntry(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("auth.json");
        Files.writeString(file, "{\"openai\":{\"type\":\"api\",\"key\":\"sk-test\"}}");

        assertThat(new OpenAiOAuthTokenStore(file).load()).isEmpty();
    }

    @Test
    void absentFileLoadsEmpty(@TempDir Path tmp) {
        assertThat(new OpenAiOAuthTokenStore(tmp.resolve("nope.json")).load()).isEmpty();
    }
}
