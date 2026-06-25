package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryProperties.Sanitization;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.ObservationWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the DD-010 / invariant #6 boundary holds in practice: a {@link Sanitized} can only come from
 * the {@link Sanitizer}, and a writer that demands {@code Sanitized<NewObservation>} simply cannot be
 * called with unsanitized text. The compiler enforces the second part; this test pins the
 * supporting facts that make the guarantee meaningful (and {@link SanitizationArchitectureTest}
 * statically forbids any other class from constructing a {@code Sanitized}).
 */
class SanitizedBoundaryTest {

    private final Sanitizer sanitizer = new Sanitizer(new Sanitization(65536, List.of()));

    private static NewObservation obs(String payload) {
        return new NewObservation(
                SessionId.newId(),
                Identity.ofProject(WorkspaceId.of("acme"), ProjectId.of("agent-memory")),
                ObservationKind.POST_TOOL_USE,
                "PostToolUse",
                null,
                null,
                payload,
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void sanitizedHasNoPublicConstructor() throws Exception {
        // The lock on the boundary: no public/protected ctor exists, so no caller outside the
        // `hooks` package can fabricate a Sanitized — only Sanitizer (same package) can.
        for (Constructor<?> c : Sanitized.class.getDeclaredConstructors()) {
            int m = c.getModifiers();
            assertThat(Modifier.isPublic(m) || Modifier.isProtected(m))
                    .as("Sanitized must not expose a public/protected constructor")
                    .isFalse();
        }
    }

    @Test
    void sanitizerProducesSanitizedAndValueRoundTrips() {
        Sanitized<NewObservation> s = sanitizer.sanitize(obs("token=abc123secret"));
        assertThat(s.value()).isInstanceOf(NewObservation.class);
        assertThat(s.value().payload()).isEqualTo("token=[REDACTED:secret]");
    }

    @Test
    void writerAcceptsOnlySanitizedAndPersistsTheScrubbedPayload() {
        // A stand-in writer (the real Postgres one lands in #4/#12). Its append() signature accepts
        // ONLY Sanitized<NewObservation>; there is no overload taking a raw NewObservation/String, so
        // a caller with unsanitized text cannot reach it. The stand-in just echoes what it stored.
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        ObservationWriter writer = sanitized -> {
            NewObservation stored = sanitized.value();
            captured.set(stored.payload());
            return new Observation(
                    new ObservationId(Uuid7.randomUuid()),
                    stored.sessionId(),
                    stored.identity(),
                    stored.kind(),
                    stored.sourceEvent(),
                    stored.extension(),
                    stored.payload(),
                    stored.createdAt());
        };

        // The only way to obtain the argument is to sanitize first.
        Sanitized<NewObservation> safe = sanitizer.sanitize(obs("ping me at dev@corp.io"));
        Observation persisted = writer.append(safe);

        assertThat(captured.get()).isEqualTo("ping me at [REDACTED:email]");
        assertThat(persisted.payload()).isEqualTo("ping me at [REDACTED:email]");
        assertThat(persisted.payload()).doesNotContain("dev@corp.io");
    }
}
