package com.agentmemory.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Fast, container-free unit tests for the issue #38 auth building blocks: the allowed-hosts guard's
 * host parsing and loopback detection (exercising the non-loopback bind path that an HTTP test on a
 * loopback {@code RANDOM_PORT} cannot reach), the token generator's entropy/format, and the
 * fail-fast config validation.
 */
class SecurityUnitTest {

    // --- AllowedHostsFilter.hostOnly ---------------------------------------------------------------

    @Test
    void hostOnlyStripsPortAndLowercases() {
        assertThat(AllowedHostsFilter.hostOnly("Example.COM:8080")).isEqualTo("example.com");
        assertThat(AllowedHostsFilter.hostOnly("example.com")).isEqualTo("example.com");
        assertThat(AllowedHostsFilter.hostOnly("[::1]:8080")).isEqualTo("[::1]");
        assertThat(AllowedHostsFilter.hostOnly("  host.local  ")).isEqualTo("host.local");
        assertThat(AllowedHostsFilter.hostOnly(null)).isNull();
        assertThat(AllowedHostsFilter.hostOnly("")).isNull();
    }

    @Test
    void loopbackBindIsDetected() {
        assertThat(AllowedHostsFilter.isLoopbackBind("127.0.0.1")).isTrue();
        assertThat(AllowedHostsFilter.isLoopbackBind("127.0.0.5")).isTrue();
        assertThat(AllowedHostsFilter.isLoopbackBind("::1")).isTrue();
        assertThat(AllowedHostsFilter.isLoopbackBind("localhost")).isTrue();
        assertThat(AllowedHostsFilter.isLoopbackBind("0.0.0.0")).isFalse();
        assertThat(AllowedHostsFilter.isLoopbackBind("192.168.1.10")).isFalse();
        // Unknown ⇒ treat as exposed (guard active) — the safe default.
        assertThat(AllowedHostsFilter.isLoopbackBind("")).isFalse();
    }

    // --- AllowedHostsFilter behavior on a non-loopback bind ----------------------------------------

    @Test
    void nonLoopbackBindRejectsDisallowedHostButAllowsListedAndLoopback() throws Exception {
        var filter = new AllowedHostsFilter("0.0.0.0", List.of("memory.example.com"));

        // Disallowed Host on an exposed bind → 403, chain not continued (rebinding probe).
        assertThat(statusFor(filter, "evil.attacker.test")).isEqualTo(403);
        // Explicitly allowed host passes.
        assertThat(statusFor(filter, "memory.example.com")).isEqualTo(0); // 0 = chain continued, no error
        // Loopback host name always passes even on an exposed bind.
        assertThat(statusFor(filter, "localhost")).isEqualTo(0);
    }

    @Test
    void loopbackBindAcceptsAnyHost() throws Exception {
        // Inert on a loopback bind: even a foreign Host header is passed through (no rebinding risk).
        var filter = new AllowedHostsFilter("127.0.0.1", List.of());
        assertThat(statusFor(filter, "anything.example")).isEqualTo(0);
    }

    /**
     * Run the filter for a request with the given Host header; returns the rejection status the filter
     * set ({@code setStatus}), or 0 if the chain continued (request allowed through).
     */
    private static int statusFor(AllowedHostsFilter filter, String host) throws Exception {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        Mockito.when(req.getHeader("Host")).thenReturn(host);
        int[] status = {0};
        Mockito.doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(resp).setStatus(Mockito.anyInt());
        // The filter writes a short body on rejection; give it a real writer.
        Mockito.when(resp.getWriter()).thenReturn(
                new java.io.PrintWriter(new java.io.StringWriter()));
        filter.doFilter(req, resp, chain);
        return status[0];
    }

    // --- TokenGenerator ----------------------------------------------------------------------------

    @Test
    void generatedTokenIsHighEntropyUrlSafeAndUnique() {
        String a = TokenGenerator.generate();
        String b = TokenGenerator.generate();
        assertThat(a).isNotEqualTo(b);
        // 32 bytes base64url-unpadded ≈ 43 chars; only URL-safe alphabet, no padding.
        assertThat(a).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void flagDetection() {
        assertThat(TokenGenerator.isRequested(new String[] {"--generate-auth-token"})).isTrue();
        assertThat(TokenGenerator.isRequested(new String[] {"server", "--generate-auth-token"})).isTrue();
        assertThat(TokenGenerator.isRequested(new String[] {"--other"})).isFalse();
        assertThat(TokenGenerator.isRequested(new String[] {})).isFalse();
        assertThat(TokenGenerator.isRequested(null)).isFalse();
    }

    // --- Auth properties + fail-fast validation ----------------------------------------------------

    @Test
    void allowedHostsAreNormalizedAndBlanksDropped() {
        var auth = new AgentMemoryProperties.Auth(true, "tok", List.of("  Example.COM  ", "", "  "));
        assertThat(auth.allowedHosts()).containsExactly("example.com");
        assertThat(auth.hasToken()).isTrue();
    }

    @Test
    void nullAllowedHostsBecomesEmpty() {
        var auth = new AgentMemoryProperties.Auth(true, "supersecretvalue", null);
        assertThat(auth.allowedHosts()).isEmpty();
        // The token is redacted in toString (never logged in the clear).
        assertThat(auth.toString()).contains("***").doesNotContain("supersecretvalue");
    }

    // The enabled-without-token fail-fast lives in AgentMemoryConfigTest (same package as the
    // package-private validateAuth seam).
}
