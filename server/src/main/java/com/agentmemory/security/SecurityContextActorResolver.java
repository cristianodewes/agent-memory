package com.agentmemory.security;

import com.agentmemory.core.ActorResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The default {@link ActorResolver}: reads the Spring Security principal name from the current
 * thread's {@link SecurityContextHolder}. The {@link TokenAuthenticationFilter} sets the principal
 * name to the per-user {@code UserId} slug (multi-user mode) or to {@code "root"} (the shared token),
 * which is exactly the value to record as the {@code actor}.
 *
 * <p>Maps "no authentication" and the Spring anonymous principal ({@code anonymousUser}) to
 * {@code null}, so an audit/observation row carries a real actor only when a token actually
 * authenticated — an unauthenticated/loopback write stays unattributed rather than being filed under
 * a fake user.
 */
public final class SecurityContextActorResolver implements ActorResolver {

    @Override
    public String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }
}
