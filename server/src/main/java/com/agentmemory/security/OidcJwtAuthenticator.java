package com.agentmemory.security;

import com.agentmemory.config.AgentMemoryProperties;
import java.util.Optional;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Validates an OIDC access token (a JWT) for the {@link TokenAuthenticationFilter} (issue #39 PR2 —
 * the server half of the RFC 8628 device-grant the native-hook client runs). Built on Spring
 * Security's {@link NimbusJwtDecoder} so verification is done by a battle-tested library, never
 * hand-rolled:
 *
 * <ul>
 *   <li><strong>Signature</strong> — verified against the IdP's JWKS (fetched from the configured
 *       {@code jwksUri}, or discovered from the issuer's {@code /.well-known/openid-configuration} when
 *       {@code jwksUri} is blank) and cached.</li>
 *   <li><strong>Issuer + expiry</strong> — {@link JwtValidators#createDefaultWithIssuer(String)} checks
 *       the {@code iss} claim equals the configured issuer plus the standard timestamp validity.</li>
 *   <li><strong>Audience</strong> — the token's {@code aud} must contain the configured audience, so a
 *       token minted for another application cannot authenticate here. The audience is the access gate
 *       and is config-required ({@code AgentMemoryConfig.validateOidc}).</li>
 *   <li><strong>Algorithm</strong> — the accepted JWS algorithms are pinned to the asymmetric set
 *       (RSA / ECDSA / RSA-PSS), so {@code alg:none} and any HMAC (HS256…) are rejected outright. This
 *       closes the classic RS256→HS256 key-confusion attack (the public verification key abused as an
 *       HMAC secret).</li>
 * </ul>
 *
 * <p>On success the {@code principalClaim} (default {@code sub}) is returned as the authenticated user
 * — which {@link SecurityContextActorResolver} records as the audit actor (issue #39 attribution). Any
 * validation failure returns {@link Optional#empty()} (the filter then leaves the request anonymous and
 * the entry point issues a 401). The JWKS network fetch is lazy (first validation) and cached, so a
 * configured-but-unreachable IdP does not block startup.
 *
 * <h2>Revocation</h2>
 * An OIDC subject is <em>not</em> provisioned in the {@code users} table, so there is no local
 * kill-switch for an individual subject: trust derives from the issuer + audience, and revocation
 * relies on the IdP (and short token TTLs). This is a deliberate trade-off, safe because the required,
 * exact audience means only a token minted <em>for this server</em> is ever accepted. A per-subject
 * allow-list ({@code agent-memory.auth.oidc.allowed-subjects}) is a named follow-up if a local
 * kill-switch is later wanted.
 */
public final class OidcJwtAuthenticator implements TokenAuthenticationFilter.OidcAuthenticator {

    private final JwtDecoder decoder;
    private final String principalClaim;

    /** Production constructor: build the decoder from the resolved OIDC config. */
    public OidcJwtAuthenticator(AgentMemoryProperties.Auth.Oidc cfg) {
        this(buildDecoder(cfg), cfg.principalClaim());
    }

    /** Test/seam constructor: inject a pre-built decoder (e.g. one pointed at a mock IdP). */
    OidcJwtAuthenticator(JwtDecoder decoder, String principalClaim) {
        this.decoder = decoder;
        this.principalClaim = (principalClaim == null || principalClaim.isBlank()) ? "sub" : principalClaim;
    }

    private static JwtDecoder buildDecoder(AgentMemoryProperties.Auth.Oidc cfg) {
        // Build from an explicit JWKS uri when given, else discover it lazily from the issuer's
        // well-known document. withIssuerLocation defers the metadata/JWKS fetch to first decode (not
        // startup), so a briefly-unreachable IdP does not block boot; both factories yield the same
        // builder type.
        NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder =
                (cfg.jwksUri() == null || cfg.jwksUri().isBlank())
                        ? NimbusJwtDecoder.withIssuerLocation(cfg.issuer())
                        : NimbusJwtDecoder.withJwkSetUri(cfg.jwksUri());
        // Pin the accepted JWS algorithms to the asymmetric set: reject alg:none and any HMAC (HS*),
        // closing the RS256->HS256 key-confusion attack. (#39 PR2 review refinement.)
        NimbusJwtDecoder decoder = builder
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.RS256);
                    algs.add(SignatureAlgorithm.RS384);
                    algs.add(SignatureAlgorithm.RS512);
                    algs.add(SignatureAlgorithm.PS256);
                    algs.add(SignatureAlgorithm.PS384);
                    algs.add(SignatureAlgorithm.PS512);
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.ES384);
                    algs.add(SignatureAlgorithm.ES512);
                })
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(cfg.issuer()),
                new AudienceValidator(cfg.audience()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Override
    public Optional<String> authenticate(String jwt) {
        try {
            Jwt decoded = decoder.decode(jwt); // verifies signature + issuer + audience + expiry
            String subject = decoded.getClaimAsString(principalClaim);
            return (subject == null || subject.isBlank()) ? Optional.empty() : Optional.of(subject);
        } catch (JwtException e) {
            return Optional.empty(); // bad signature / wrong issuer or audience / expired / malformed
        }
    }

    /** Rejects a token whose {@code aud} claim does not contain the configured audience. */
    static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String audience;

        AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (jwt.getAudience() != null && jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "the token audience does not contain '" + audience + "'", null));
        }
    }
}
