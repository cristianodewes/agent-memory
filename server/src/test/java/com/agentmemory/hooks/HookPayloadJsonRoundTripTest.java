package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ObservationKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-language golden-fixture round-trip on the Java side for the hook wire envelope (issue #7).
 * Every {@code docs/contracts/fixtures/hook_*.json} fixture is deserialized into a {@link HookPayload},
 * re-serialized, and asserted <em>tree-equal</em> to the original. The identical files are
 * round-tripped by the Go mirror ({@code client/internal/hook}); both suites green ⇒ the two
 * languages agree on the hook payload contract (issue #7 acceptance: golden JSON fixtures round-trip
 * in Go and Java).
 *
 * <p>Comparison is semantic (parsed {@link JsonNode} equality), not byte equality, mirroring the #3
 * {@code JsonRoundTripTest}: field names, casing, types and null-omission must match exactly. The
 * fixtures live at the repo root, one level up from {@code server/} where tests run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HookPayloadJsonRoundTripTest {

    /** Mapper mirroring the server's wire settings; see docs/contracts/serialization.md. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Path FIXTURES =
            Path.of("..", "docs", "contracts", "fixtures").toAbsolutePath().normalize();

    /** Every committed hook-payload fixture; kept in lockstep with the Go suite's {@code hookFixtures}. */
    static List<String> hookFixtures() {
        return List.of(
                "hook_session_start.json",
                "hook_user_prompt.json",
                "hook_pre_tool_use.json",
                "hook_post_tool_use.json",
                "hook_post_tool_use_array.json",
                "hook_pre_compact.json",
                "hook_notification.json",
                "hook_stop.json",
                "hook_session_end.json",
                "hook_extension.json",
                "hook_minimal.json",
                "hook_idempotent.json");
    }

    private static JsonNode readFixtureTree(String name) throws IOException {
        Path file = FIXTURES.resolve(name);
        assertThat(file).as("fixture %s exists", name).exists();
        return MAPPER.readTree(Files.readString(file));
    }

    @ParameterizedTest(name = "{0} round-trips")
    @MethodSource("hookFixtures")
    void hookPayloadFixtureRoundTrips(String fixtureName) throws IOException {
        JsonNode original = readFixtureTree(fixtureName);
        HookPayload value = MAPPER.treeToValue(original, HookPayload.class);
        JsonNode reserialized = MAPPER.valueToTree(value);
        assertThat(reserialized)
                .as("round-trip of %s via HookPayload", fixtureName)
                .isEqualTo(original);
    }

    /**
     * Explicit guard for the prior-art "Bug A": an array {@code toolResponse} must survive ingest as
     * a JSON array (modeled as {@link tools.jackson.databind.JsonNode}), not be dropped or coerced to
     * an object.
     */
    @Test
    void postToolUseArrayResponseIsPreserved() throws IOException {
        HookPayload p =
                MAPPER.treeToValue(readFixtureTree("hook_post_tool_use_array.json"), HookPayload.class);
        assertThat(p.toolResponse()).as("toolResponse dropped (Bug A)").isNotNull();
        assertThat(p.toolResponse().isArray()).as("toolResponse must be a JSON array").isTrue();
        assertThat(p.toolResponse()).hasSize(2);
    }

    /**
     * The extension seam: a non-canonical event with an {@code extension} namespace deserializes to
     * {@link ObservationKind#OTHER}, preserves its raw {@code event}, and is flagged as an extension
     * event.
     */
    @Test
    void extensionEventIsRecognized() throws IOException {
        HookPayload p =
                MAPPER.treeToValue(readFixtureTree("hook_extension.json"), HookPayload.class);
        assertThat(p.kind()).isEqualTo(ObservationKind.OTHER);
        assertThat(p.event()).isEqualTo("deploy.finished");
        assertThat(p.isExtensionEvent()).isTrue();
    }

    /** The #8 idempotency key survives the round-trip and is exposed when present. */
    @Test
    void clientEventIdIsPreserved() throws IOException {
        HookPayload p =
                MAPPER.treeToValue(readFixtureTree("hook_idempotent.json"), HookPayload.class);
        assertThat(p.clientEventId()).isEqualTo("spool-000123");
    }

    /** Unknown object fields are ignored on read (forward compatibility, per the contract). */
    @Test
    void unknownFieldsAreIgnored() throws IOException {
        String json =
                """
                {
                  "event": "Stop",
                  "kind": "stop",
                  "sessionId": "0190b3e2-1d00-7a00-8000-000000000002",
                  "workspace": "acme",
                  "project": "agent-memory",
                  "timestamp": "2026-06-25T12:40:00Z",
                  "futureField": "ignored"
                }
                """;
        HookPayload p = MAPPER.readValue(json, HookPayload.class);
        assertThat(p.kind()).isEqualTo(ObservationKind.STOP);
    }
}
