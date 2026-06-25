package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.AgentMemoryProperties.Sanitization;
import com.agentmemory.config.AgentMemoryProperties.Sanitization.CustomPattern;
import com.agentmemory.core.Identity;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Redaction + truncation unit tests for {@link Sanitizer} (issue #9). Positive cases assert each
 * built-in class (secrets/keys/tokens, emails, home-dir paths) and custom patterns are masked;
 * negative cases assert ordinary text is left intact and the pipeline is idempotent; the truncation
 * cases assert the deterministic size cap + marker. The boundary guarantee (no bypass) is covered by
 * {@link SanitizedBoundaryTest} and {@link SanitizationArchitectureTest}.
 */
class SanitizerTest {

    /** Default-config sanitizer (64 KiB cap, no custom patterns). */
    private final Sanitizer sanitizer = new Sanitizer(new Sanitization(65536, List.of()));

    private static NewObservation obs(String payload) {
        return new NewObservation(
                SessionId.newId(),
                Identity.ofProject(WorkspaceId.of("acme"), ProjectId.of("agent-memory")),
                ObservationKind.USER_PROMPT,
                "UserPromptSubmit",
                null,
                payload,
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    private String clean(String payload) {
        return sanitizer.sanitize(obs(payload)).value().payload();
    }

    // --- emails ------------------------------------------------------------------------------------

    @Test
    void redactsEmailAddresses() {
        assertThat(clean("ping cristiano@example.com about it"))
                .isEqualTo("ping [REDACTED:email] about it")
                .doesNotContain("cristiano@example.com");
    }

    @Test
    void redactsMultipleEmails() {
        assertThat(clean("a@b.io and c.d+tag@sub.example.org"))
                .isEqualTo("[REDACTED:email] and [REDACTED:email]");
    }

    // --- secrets / keys / tokens -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "sk-abcdefghij0123456789ABCDEFGHIJ",                 // OpenAI-style
        "sk-ant-api03-AbCdEf0123456789xyzAbCdEf0123",        // Anthropic-style
        "ghp_0123456789abcdefABCDEF0123456789abcd",          // GitHub PAT
        "AKIAIOSFODNN7EXAMPLE",                              // AWS access key id
        "AIzaSyA1234567890abcdefghijklmnopqrstu",            // Google API key
    })
    void redactsKnownProviderKeys(String key) {
        String out = clean("here is the key " + key + " end");
        assertThat(out).isEqualTo("here is the key [REDACTED:secret] end").doesNotContain(key);
    }

    @Test
    void redactsBearerTokenKeepingTheScheme() {
        assertThat(clean("Authorization: Bearer abc.def-123_XYZ"))
                .isEqualTo("Authorization: Bearer [REDACTED:token]")
                .doesNotContain("abc.def-123_XYZ");
    }

    @Test
    void redactsKeyedSecretsKeepingTheLabel() {
        // The key name and operator survive; only the value is masked — useful in an audit log.
        assertThat(clean("api_key=supersecretvalue123")).isEqualTo("api_key=[REDACTED:secret]");
        assertThat(clean("password: hunter2!")).isEqualTo("password: [REDACTED:secret]");
        assertThat(clean("client_secret = \"zzz-yyy\"")).isEqualTo("client_secret = [REDACTED:secret]");
    }

    @Test
    void keyedSecretWins_doesNotLeakValueAsEmail() {
        // Secret redaction runs before email redaction, so a credential that looks like an email is
        // masked as a secret rather than leaking through the email rule.
        assertThat(clean("password=alice@corp.com"))
                .isEqualTo("password=[REDACTED:secret]")
                .doesNotContain("alice@corp.com");
    }

    // --- home-dir paths ----------------------------------------------------------------------------

    @Test
    void redactsUnixHomePrefixKeepingTail() {
        assertThat(clean("see /home/alice/projects/app/main.go"))
                .isEqualTo("see [REDACTED:home]/projects/app/main.go");
    }

    @Test
    void redactsMacHomePrefix() {
        assertThat(clean("/Users/bob/Library/x")).isEqualTo("[REDACTED:home]/Library/x");
    }

    @Test
    void redactsWindowsHomePrefixBothSeparators() {
        assertThat(clean("C:\\Users\\carol\\Desktop\\notes.txt"))
                .isEqualTo("[REDACTED:home]\\Desktop\\notes.txt");
        assertThat(clean("D:/Users/dave/tmp")).isEqualTo("[REDACTED:home]/tmp");
    }

    // --- negative / no-op --------------------------------------------------------------------------

    @Test
    void leavesOrdinaryTextUntouched() {
        String text = "Refactored the recall ranker; see concepts/recall.md for the RRF notes.";
        assertThat(clean(text)).isEqualTo(text);
    }

    @Test
    void emptyPayloadStaysEmpty() {
        assertThat(clean("")).isEmpty();
    }

    @Test
    void pipelineIsIdempotent() {
        String once = clean("mail x@y.io key sk-abcdefghij0123456789ABCDEFGHIJ at /home/erin/z");
        String twice = clean(once);
        assertThat(twice).isEqualTo(once);
        // markers survived a second pass untouched (not re-redacted)
        assertThat(twice).contains("[REDACTED:email]", "[REDACTED:secret]", "[REDACTED:home]");
    }

    // --- custom patterns ---------------------------------------------------------------------------

    @Test
    void appliesCustomPatternsAfterBuiltins() {
        Sanitizer custom = new Sanitizer(new Sanitization(65536,
                List.of(new CustomPattern("TICKET-\\d+", "ticket"))));
        NewObservation o = obs("fixed TICKET-4521 for user@x.io");
        String out = custom.sanitize(o).value().payload();
        assertThat(out).isEqualTo("fixed [REDACTED:ticket] for [REDACTED:email]");
    }

    @Test
    void invalidCustomRegexFailsFastAtConstruction() {
        assertThatThrownBy(() -> new Sanitizer(new Sanitization(65536,
                List.of(new CustomPattern("(unclosed", "bad"))))
        ).isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    // --- structural fields preserved ---------------------------------------------------------------

    @Test
    void sanitizeRewritesOnlyThePayload() {
        NewObservation in = obs("token=secretzzz");
        NewObservation out = sanitizer.sanitize(in).value();
        assertThat(out.payload()).isEqualTo("token=[REDACTED:secret]");
        // everything else is identical
        assertThat(out.sessionId()).isEqualTo(in.sessionId());
        assertThat(out.identity()).isEqualTo(in.identity());
        assertThat(out.kind()).isEqualTo(in.kind());
        assertThat(out.sourceEvent()).isEqualTo(in.sourceEvent());
        assertThat(out.extension()).isEqualTo(in.extension());
        assertThat(out.createdAt()).isEqualTo(in.createdAt());
    }

    // --- truncation --------------------------------------------------------------------------------

    @Test
    void truncatesOversizedPayloadDeterministicallyWithMarker() {
        Sanitizer small = new Sanitizer(new Sanitization(64, List.of()));
        String big = "a".repeat(500);
        String out = small.sanitize(obs(big)).value().payload();

        assertThat(out).hasSizeLessThanOrEqualTo(64);
        assertThat(out).startsWith("a");
        assertThat(out).contains("[TRUNCATED:").contains("chars omitted]");
        // deterministic: same input ⇒ identical output
        assertThat(small.sanitize(obs(big)).value().payload()).isEqualTo(out);
        // the omitted count is the real remainder (length kept reconciles to 500)
        int kept = out.indexOf("…[TRUNCATED:");
        int omitted = 500 - kept;
        assertThat(out).endsWith("…[TRUNCATED: " + omitted + " chars omitted]");
    }

    @Test
    void doesNotTruncateWhenWithinCap() {
        Sanitizer cap = new Sanitizer(new Sanitization(100, List.of()));
        String text = "short enough";
        assertThat(cap.sanitize(obs(text)).value().payload()).isEqualTo(text);
    }

    @Test
    void redactsBeforeTruncating_noHalfCutSecretLeaks() {
        // A secret sitting beyond the cap is fully redacted first; truncation then drops the marker
        // tail, never a partial raw secret.
        Sanitizer small = new Sanitizer(new Sanitization(40, List.of()));
        String payload = "x".repeat(30) + " sk-abcdefghij0123456789ABCDEFGHIJ";
        String out = small.sanitize(obs(payload)).value().payload();
        assertThat(out).doesNotContain("sk-abcdefghij");
    }

    @Test
    void sanitizeRejectsNull() {
        assertThatThrownBy(() -> sanitizer.sanitize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
