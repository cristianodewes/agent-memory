package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.ObservationKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no DB/LLM) for the small consolidate value/policy pieces (#18): which observation
 * kinds trigger synthesis, the idempotency count marker, and {@link SynthesizedSession} validation.
 */
class SessionConsolidationUnitTest {

    // --- trigger kind gating -----------------------------------------------------------------------

    @Test
    void sessionEndAndPreCompactTrigger() {
        assertThat(SessionConsolidationTrigger.triggers(ObservationKind.SESSION_END)).isTrue();
        assertThat(SessionConsolidationTrigger.triggers(ObservationKind.PRE_COMPACT)).isTrue();
    }

    @Test
    void otherKindsDoNotTrigger() {
        for (ObservationKind k : List.of(
                ObservationKind.SESSION_START, ObservationKind.USER_PROMPT,
                ObservationKind.PRE_TOOL_USE, ObservationKind.POST_TOOL_USE,
                ObservationKind.NOTIFICATION, ObservationKind.STOP, ObservationKind.OTHER)) {
            assertThat(SessionConsolidationTrigger.triggers(k)).as(k.wire()).isFalse();
        }
    }

    // --- idempotency fingerprint marker ------------------------------------------------------------

    @Test
    void fingerprintMarkerRoundTrips() {
        String body = "## Summary\n\nstuff\n<!-- synth:42@1750000000000 -->\n";
        assertThat(SessionSynthesizer.synthFingerprint(body)).isEqualTo("42@1750000000000");
    }

    @Test
    void fingerprintIsNullWhenAbsentOrMalformed() {
        assertThat(SessionSynthesizer.synthFingerprint("no marker here")).isNull();
        assertThat(SessionSynthesizer.synthFingerprint(null)).isNull();
        assertThat(SessionSynthesizer.synthFingerprint("<!-- synth:7")).isNull(); // unterminated
        assertThat(SessionSynthesizer.synthFingerprint("<!-- synth: -->")).isNull(); // empty value
    }

    @Test
    void fingerprintTakesTheLastOccurrence() {
        // Defensive: if a body somehow carried two markers, the trailing (authoritative) one wins.
        assertThat(SessionSynthesizer.synthFingerprint("<!-- synth:1@5 -->\nx\n<!-- synth:9@8 -->\n"))
                .isEqualTo("9@8");
    }

    @Test
    void pagePathForIsDeterministicAndUnderSessions() {
        var id = com.agentmemory.core.SessionId.of("018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55");
        assertThat(SessionSynthesizer.pagePathFor(id).value())
                .isEqualTo("sessions/018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55.md");
    }

    // --- SynthesizedSession validation -------------------------------------------------------------

    @Test
    void synthesizedSessionRejectsBlankTitleOrSummary() {
        assertThatThrownBy(() -> new SynthesizedSession(
                " ", "s", List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SynthesizedSession(
                "t", " ", List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void synthesizedSessionNormalizesNullAndBlankListEntries() {
        var s = new SynthesizedSession(
                "t", "s",
                java.util.Arrays.asList("keep", null, "  ", " trim "),
                null, null, null);
        assertThat(s.decisions()).containsExactly("keep", "trim");
        assertThat(s.followUps()).isEmpty();
        assertThat(s.openQuestions()).isEmpty();
        assertThat(s.highlights()).isEmpty();
    }
}
