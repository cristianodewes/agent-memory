package com.agentmemory.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-language golden-fixture round-trip on the Java side: every fixture under
 * {@code docs/contracts/fixtures/} is deserialized into its typed {@code core} record, re-serialized,
 * and the result is asserted <em>tree-equal</em> to the original fixture. The identical files are
 * round-tripped by the Go mirror ({@code client/internal/core}); if both suites are green the two
 * languages agree on the wire contract (issue #3 acceptance criterion 2).
 *
 * <p>Comparison is semantic (parsed {@link JsonNode} equality), not byte equality, so insignificant
 * whitespace in the human-edited fixtures does not matter — but field <em>names</em>, casing, types
 * and null-omission must match exactly, which is the property under test.
 *
 * <p>The fixtures live at the repo root, four levels up from {@code server/} where tests run; the
 * path is resolved relative to the module's working directory.
 */
class JsonRoundTripTest {

    /** Mapper mirroring the server's wire settings; see docs/contracts/serialization.md. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Path FIXTURES =
            Path.of("..", "docs", "contracts", "fixtures").toAbsolutePath().normalize();

    private static JsonNode readFixtureTree(String name) throws IOException {
        Path file = FIXTURES.resolve(name);
        assertThat(file).as("fixture %s exists", name).exists();
        return MAPPER.readTree(Files.readString(file));
    }

    /** Deserialize → re-serialize → assert tree-equal to the original fixture. */
    private <T> void assertRoundTrips(String fixtureName, Class<T> type) throws IOException {
        JsonNode original = readFixtureTree(fixtureName);
        T value = MAPPER.treeToValue(original, type);
        JsonNode reserialized = MAPPER.valueToTree(value);
        assertThat(reserialized)
                .as("round-trip of %s via %s", fixtureName, type.getSimpleName())
                .isEqualTo(original);
    }

    @Test
    void identityPageRoundTrips() throws IOException {
        assertRoundTrips("identity_page.json", Identity.class);
    }

    @Test
    void identityProjectRoundTrips() throws IOException {
        assertRoundTrips("identity_project.json", Identity.class);
    }

    @Test
    void pageRoundTrips() throws IOException {
        assertRoundTrips("page.json", Page.class);
    }

    @Test
    void observationRoundTrips() throws IOException {
        assertRoundTrips("observation.json", Observation.class);
    }

    @Test
    void observationMinimalRoundTrips() throws IOException {
        assertRoundTrips("observation_minimal.json", Observation.class);
    }

    @Test
    void sessionRoundTrips() throws IOException {
        assertRoundTrips("session.json", Session.class);
    }

    @Test
    void sessionOpenRoundTrips() throws IOException {
        assertRoundTrips("session_open.json", Session.class);
    }

    @Test
    void linkRoundTrips() throws IOException {
        assertRoundTrips("link.json", Link.class);
    }

    @Test
    void linkDeferredRoundTrips() throws IOException {
        assertRoundTrips("link_deferred.json", Link.class);
    }

    @Test
    void handoffRoundTrips() throws IOException {
        assertRoundTrips("handoff.json", Handoff.class);
    }

    @Test
    void handoffOpenRoundTrips() throws IOException {
        assertRoundTrips("handoff_open.json", Handoff.class);
    }

    @Test
    void observationKindsFixtureMatchesEnum() throws IOException {
        JsonNode tree = readFixtureTree("observation_kinds.json");
        @SuppressWarnings("unchecked")
        List<String> tokens = (List<String>) MAPPER.treeToValue(tree, List.class);

        // Every canonical kind appears in the fixture, in declaration order, and each token parses
        // back to the same constant: the enum and the contract fixture are locked together.
        assertThat(tokens).hasSameSizeAs(ObservationKind.values());
        for (int i = 0; i < tokens.size(); i++) {
            ObservationKind kind = ObservationKind.values()[i];
            assertThat(tokens.get(i)).isEqualTo(kind.wire());
            assertThat(ObservationKind.fromWire(tokens.get(i))).isEqualTo(kind);
        }
    }

    @Test
    void semanticEqualityIsOrderIndependentSanityCheck() throws IOException {
        // Guards the test's own premise: JsonNode equality ignores object key order, so a
        // re-serialization that differs only in field order would still (correctly) pass. Two
        // trees with the same entries in different source order must compare equal.
        JsonNode a = MAPPER.readTree("{\"workspace\":\"w\",\"project\":\"p\"}");
        JsonNode b = MAPPER.readTree("{\"project\":\"p\",\"workspace\":\"w\"}");
        assertThat(a).isEqualTo(b);
    }
}
