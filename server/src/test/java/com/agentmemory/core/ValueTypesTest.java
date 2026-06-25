package com.agentmemory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the identity value types and their invariants: slug normalization
 * ({@link WorkspaceId}/{@link ProjectId}), UUIDv7 id minting/parsing, and the page-vs-project
 * scoping rules enforced by {@link Identity} and the domain records.
 */
class ValueTypesTest {

    // --- workspace / project slugs --------------------------------------------------------------

    @Test
    void workspaceSlugIsTrimmedAndLowerCased() {
        assertThat(WorkspaceId.of("  Acme  ").value()).isEqualTo("acme");
        assertThat(ProjectId.of("Agent-Memory").value()).isEqualTo("agent-memory");
    }

    @Test
    void slugRejectsBlankNullAndSeparators() {
        assertThatThrownBy(() -> WorkspaceId.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectId.of("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectId.of("a\\b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slugEqualityFollowsNormalForm() {
        assertThat(WorkspaceId.of("ACME")).isEqualTo(WorkspaceId.of("acme"));
    }

    // --- uuidv7 ids -----------------------------------------------------------------------------

    @Test
    void mintedIdsAreVersion7AndIetfVariant() {
        UUID u = SessionId.newId().value();
        assertThat(u.version()).isEqualTo(7);
        assertThat(u.variant()).isEqualTo(2); // IETF variant (10xx)
    }

    @Test
    void uuid7EmbedsTimestampAndIsTimeOrdered() {
        long earlier = 1_700_000_000_000L;
        long later = earlier + 5_000L;
        UUID a = Uuid7.fromMillis(earlier);
        UUID b = Uuid7.fromMillis(later);

        assertThat(Uuid7.timestampMillis(a)).isEqualTo(earlier);
        assertThat(Uuid7.timestampMillis(b)).isEqualTo(later);
        // k-sortable: the later timestamp yields a larger unsigned high-bits ordering.
        assertThat(Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits()))
                .isNegative();
    }

    @Test
    void idsParseFromCanonicalStringRoundTrip() {
        SessionId id = SessionId.newId();
        assertThat(SessionId.of(id.asString())).isEqualTo(id);

        ObservationId oid = ObservationId.newId();
        assertThat(ObservationId.of(oid.asString())).isEqualTo(oid);
    }

    @Test
    void idTypesAreDistinctEvenWithSameUuid() {
        UUID shared = Uuid7.randomUuid();
        // Different wrapper types are never equal, even over the same UUID — that is the whole point
        // of typing the ids. (Records compare by class + components.)
        assertThat((Object) new SessionId(shared)).isNotEqualTo(new ObservationId(shared));
    }

    @Test
    void idRejectsNullAndGarbage() {
        assertThatThrownBy(() -> SessionId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- identity scoping -----------------------------------------------------------------------

    @Test
    void projectScopedIdentityHasNoPage() {
        Identity id = Identity.ofProject(WorkspaceId.of("w"), ProjectId.of("p"));
        assertThat(id.isPageScoped()).isFalse();
        assertThat(id.page()).isNull();
    }

    @Test
    void pageScopedIdentityRequiresPath() {
        Identity id = Identity.ofPage(WorkspaceId.of("w"), ProjectId.of("p"), PagePath.of("a.md"));
        assertThat(id.isPageScoped()).isTrue();
        assertThatThrownBy(() -> Identity.ofPage(WorkspaceId.of("w"), ProjectId.of("p"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityRejectsNullCoordinates() {
        assertThatThrownBy(() -> new Identity(null, ProjectId.of("p"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Identity(WorkspaceId.of("w"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
