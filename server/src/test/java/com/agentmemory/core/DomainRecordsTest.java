package com.agentmemory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Invariant coverage for the domain records: the 3-tuple identity is mandatory and correctly
 * scoped on every one (ARCHITECTURE invariant #4), and a record can never be built half-populated.
 */
class DomainRecordsTest {

    private static final WorkspaceId WS = WorkspaceId.of("acme");
    private static final ProjectId PR = ProjectId.of("agent-memory");
    private static final Identity PAGE_ID = Identity.ofPage(WS, PR, PagePath.of("concepts/a.md"));
    private static final Identity PROJECT_ID = Identity.ofProject(WS, PR);
    private static final Instant T = Instant.parse("2026-06-25T12:00:00Z");

    @Test
    void pageRequiresPageScopedIdentity() {
        assertThatThrownBy(() -> new Page(
                        PageId.newId(), PROJECT_ID, "t", "b", true, null, T, T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page-scoped");

        assertThatCode(() -> new Page(PageId.newId(), PAGE_ID, "t", "b", true, null, T, T))
                .doesNotThrowAnyException();
    }

    @Test
    void pageExposesItsPath() {
        Page page = new Page(PageId.newId(), PAGE_ID, "t", "b", true, null, T, T);
        assertThat(page.path()).isEqualTo(PagePath.of("concepts/a.md"));
    }

    @Test
    void observationRequiresProjectScopedIdentity() {
        assertThatThrownBy(() -> new Observation(
                        ObservationId.newId(), SessionId.newId(), PAGE_ID,
                        ObservationKind.USER_PROMPT, null, null, "x", T))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-scoped");

        assertThatCode(() -> new Observation(
                        ObservationId.newId(), SessionId.newId(), PROJECT_ID,
                        ObservationKind.USER_PROMPT, null, null, "x", T))
                .doesNotThrowAnyException();
    }

    @Test
    void sessionRequiresProjectScopedIdentityAndTracksOpenState() {
        assertThatThrownBy(() -> new Session(SessionId.newId(), PAGE_ID, null, T, null))
                .isInstanceOf(IllegalArgumentException.class);

        Session open = new Session(SessionId.newId(), PROJECT_ID, "claude-code", T, null);
        assertThat(open.isOpen()).isTrue();
        Session closed = new Session(SessionId.newId(), PROJECT_ID, "claude-code", T, T);
        assertThat(closed.isOpen()).isFalse();
    }

    @Test
    void linkSourceMustBePageScopedAndDetectsCrossProject() {
        assertThatThrownBy(() -> new Link(LinkId.newId(), PROJECT_ID, PAGE_ID, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");

        Identity otherProject =
                Identity.ofPage(WS, ProjectId.of("other"), PagePath.of("b.md"));
        Link cross = new Link(LinkId.newId(), PAGE_ID, otherProject, "x", true);
        assertThat(cross.isCrossProject()).isTrue();

        Link same = new Link(LinkId.newId(), PAGE_ID, PAGE_ID, "x", true);
        assertThat(same.isCrossProject()).isFalse();
    }

    @Test
    void linkResolvedFlagRequiresATarget() {
        assertThatThrownBy(() -> new Link(LinkId.newId(), PAGE_ID, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetResolved");

        // A bare anchor with no target is allowed as long as it is not marked resolved.
        assertThatCode(() -> new Link(LinkId.newId(), PAGE_ID, null, "anchor", false))
                .doesNotThrowAnyException();
    }

    @Test
    void handoffCoalescesNullListsToEmptyAndIsProjectScoped() {
        Handoff h = new Handoff(
                HandoffId.newId(), PROJECT_ID, SessionId.newId(), HandoffStatus.OPEN,
                "summary", null, null, T, null);
        assertThat(h.openQuestions()).isEmpty();
        assertThat(h.nextSteps()).isEmpty();

        assertThatThrownBy(() -> new Handoff(
                        HandoffId.newId(), PAGE_ID, SessionId.newId(), HandoffStatus.OPEN,
                        "s", null, null, T, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-scoped");
    }

    @Test
    void handoffListsAreImmutable() {
        Handoff h = new Handoff(
                HandoffId.newId(), PROJECT_ID, SessionId.newId(), HandoffStatus.ACCEPTED,
                "s", java.util.List.of("q"), java.util.List.of("n"), T, T);
        assertThatThrownBy(() -> h.openQuestions().add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void handoffStatusParsesStrictlyAndRejectsUnknown() {
        assertThat(HandoffStatus.fromWire("ACCEPTED")).isEqualTo(HandoffStatus.ACCEPTED);
        assertThatThrownBy(() -> HandoffStatus.fromWire("frobnicated"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observationKindParsesLenientlyToOther() {
        assertThat(ObservationKind.fromWire("post_tool_use")).isEqualTo(ObservationKind.POST_TOOL_USE);
        assertThat(ObservationKind.fromWire("totally-unknown")).isEqualTo(ObservationKind.OTHER);
        assertThat(ObservationKind.fromWire(null)).isEqualTo(ObservationKind.OTHER);
    }
}
