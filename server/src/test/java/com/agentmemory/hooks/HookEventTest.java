package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ObservationKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Table-driven alias-normalization coverage for {@link HookEvent} (issue #7), driven by the shared
 * golden fixture {@code docs/contracts/fixtures/hook_aliases.json}. The same fixture drives the Go
 * {@code event_test.go}, so the two alias tables are proven to normalize every documented Claude
 * Code / Codex / Cursor spelling — and casing/separator variants — to the <em>same</em> canonical
 * {@link ObservationKind} in both languages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HookEventTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Path FIXTURES =
            Path.of("..", "docs", "contracts", "fixtures").toAbsolutePath().normalize();

    /** One {@code {event, kind}} row of the alias fixture. */
    record AliasCase(String event, String kind) {
        @Override
        public String toString() {
            return "\"" + event + "\" -> " + kind;
        }
    }

    static List<AliasCase> aliasCases() throws IOException {
        JsonNode tree = MAPPER.readTree(Files.readString(FIXTURES.resolve("hook_aliases.json")));
        assertThat(tree.isArray()).as("hook_aliases.json is a JSON array").isTrue();
        return tree.valueStream()
                .map(n -> new AliasCase(n.get("event").asString(), n.get("kind").asString()))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aliasCases")
    void aliasNormalizesToCanonicalKind(AliasCase c) {
        assertThat(HookEvent.parse(c.event()).wire())
                .as("HookEvent.parse(%s)", c.event())
                .isEqualTo(c.kind());
    }

    /**
     * Pins the specific prior-art regression: the native hook spelling {@code "user-prompt-submit"}
     * (and its CamelCase / underscore forms) MUST resolve to {@code user-prompt}, never to
     * {@code other}.
     */
    @Test
    void userPromptSubmitIsNotDropped() {
        for (String spelling : List.of("user-prompt-submit", "UserPromptSubmit", "user_prompt_submit")) {
            assertThat(HookEvent.parse(spelling))
                    .as("prior-art drop must not recur for %s", spelling)
                    .isEqualTo(ObservationKind.USER_PROMPT);
        }
    }

    /** Resolution order: canonical kebab token (not in the table) still resolves; unknown → OTHER. */
    @Test
    void fallsBackToCanonicalThenOther() {
        assertThat(HookEvent.parse("session-start")).isEqualTo(ObservationKind.SESSION_START);
        assertThat(HookEvent.parse("   ")).isEqualTo(ObservationKind.OTHER);
        assertThat(HookEvent.parse(null)).isEqualTo(ObservationKind.OTHER);
        assertThat(HookEvent.parse("nonsense-event")).isEqualTo(ObservationKind.OTHER);
    }

    @Test
    void isRecognizedDistinguishesExtensionFromTypo() {
        for (String e : List.of("PostToolUse", "post_tool_use", "session-end", "other", "stop")) {
            assertThat(HookEvent.isRecognized(e)).as("%s recognized", e).isTrue();
        }
        for (String e : Arrays.asList("", "deploy.finished", "totally-unknown")) {
            assertThat(HookEvent.isRecognized(e)).as("%s not recognized", e).isFalse();
        }
    }

    /**
     * Completeness guard: every canonical kind except {@link ObservationKind#OTHER} must be reachable
     * through at least one alias entry, so no lifecycle moment is unmappable. {@code OTHER} is the
     * catch-all and intentionally has no alias.
     */
    @Test
    void everyNonOtherKindHasAnAlias() {
        for (ObservationKind k : ObservationKind.values()) {
            if (k == ObservationKind.OTHER) {
                continue;
            }
            assertThat(HookEvent.aliasedKinds()).as("kind %s has an alias", k.wire()).contains(k);
        }
    }
}
