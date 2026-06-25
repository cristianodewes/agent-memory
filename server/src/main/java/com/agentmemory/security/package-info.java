/**
 * Auth, tokens and the allowed-hosts guard (issue #38; loopback-only default, DD-007;
 * docs/ARCHITECTURE.md §6).
 *
 * <p>{@link com.agentmemory.security.SecurityConfiguration} is the single Spring Security chain:
 * permit-all in the default loopback-only mode, and — when {@code agent-memory.auth.enabled} is set —
 * a shared bearer token ({@link com.agentmemory.security.TokenAuthenticationFilter}, also accepted as
 * the HTTP Basic password on {@code /web}), an anti-DNS-rebinding
 * {@link com.agentmemory.security.AllowedHostsFilter}, and a cross-origin
 * {@link com.agentmemory.security.BrowserWriteGuardFilter} for non-GET browser requests.
 * {@link com.agentmemory.security.TokenGenerator} mints the token for {@code --generate-auth-token}.
 *
 * <p>Multi-user attribution + OIDC is a later issue (#39).
 */
package com.agentmemory.security;
