package com.agentmemory.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.CaptureSessionResolver;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for the capture-session transport (issue #87, guard 1): {@link CaptureSessionHeaderFilter}
 * binds the {@code X-Agent-Memory-Session} header to the request thread so {@link RequestSessionResolver}
 * sees it while a tool runs, and clears it afterward. A missing or malformed header binds nothing — the
 * value session_aware then fail-fasts on rather than guesses.
 */
class CaptureSessionHeaderFilterTest {

    private final CaptureSessionHeaderFilter filter = new CaptureSessionHeaderFilter();
    private final CaptureSessionResolver resolver = new RequestSessionResolver();

    /** Run the filter with the given header value and return what the resolver saw inside the chain. */
    private String sessionSeenDuringRequest(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (headerValue != null) {
            request.addHeader(CaptureSessionResolver.SESSION_HEADER, headerValue);
        }
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = resolver.currentSessionId();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }

    @Test
    void bindsAWellFormedSessionIdForTheRequestThenClearsIt() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(sessionSeenDuringRequest(id)).isEqualTo(id);
        // After the request completes the binding is gone — no leak onto a pooled request thread.
        assertThat(resolver.currentSessionId()).isNull();
    }

    @Test
    void canonicalizesAnUppercaseUuid() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(sessionSeenDuringRequest(id.toString().toUpperCase())).isEqualTo(id.toString());
    }

    @Test
    void bindsNothingWhenTheHeaderIsAbsent() throws Exception {
        assertThat(sessionSeenDuringRequest(null)).isNull();
    }

    @Test
    void bindsNothingWhenTheHeaderIsNotAUuid() throws Exception {
        // A garbage value must not reach the uuid-typed query; it is treated as "no session present".
        assertThat(sessionSeenDuringRequest("not-a-uuid")).isNull();
    }
}
