package com.agentmemory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure unit tests for the resolved configuration: absolute-path canonicalization, data-dir
 * creation + layout helpers, and fail-fast on bad input. No Spring context is booted (the
 * resolution logic is deliberately framework-free), per the issue's testing requirements.
 */
class AgentMemoryConfigTest {

    private static AgentMemoryProperties propsWithDataDir(String dir) {
        return propsWith(dir, AutoScope.SINGLE_SLOT);
    }

    private static AgentMemoryProperties propsWith(String dir, AutoScope auto) {
        return new AgentMemoryProperties(
                new AgentMemoryProperties.Server("127.0.0.1", 8080, "/", ""),
                new AgentMemoryProperties.Data(dir),
                new AgentMemoryProperties.Db("jdbc:postgresql://localhost/db", "u", ""),
                new AgentMemoryProperties.Llm(ProviderAuth.NONE),
                new AgentMemoryProperties.Embeddings(ProviderAuth.NONE),
                new AgentMemoryProperties.Auth(false, "", java.util.List.of(), ""),
                new AgentMemoryProperties.Sanitization(65536, java.util.List.of()),
                new AgentMemoryProperties.Ingest(1024, 0),
                new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, 30, 7),
                new AgentMemoryProperties.Scope(auto));
    }

    // --- canonicalization ----------------------------------------------------------------------

    @Test
    void resolvesRelativePathToAbsolute() {
        Path resolved = AgentMemoryConfig.canonicalizeDataDir("some/relative/dir");

        assertThat(resolved.isAbsolute()).isTrue();
        assertThat(resolved).isEqualTo(resolved.normalize());
    }

    @Test
    void normalizesDotSegments() {
        Path resolved = AgentMemoryConfig.canonicalizeDataDir("/tmp/a/b/../c/./d");

        assertThat(resolved.toString()).doesNotContain("..");
        assertThat(resolved.getFileName().toString()).isEqualTo("d");
    }

    @Test
    void expandsLeadingTildeToUserHome() {
        Path resolved = AgentMemoryConfig.canonicalizeDataDir("~/agent-memory-test");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

        // Compare as a boolean: AssertJ's Path startsWith assertion canonicalizes via toRealPath(),
        // which would throw because this directory intentionally does not exist on disk yet.
        assertThat(resolved.startsWith(home)).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("agent-memory-test");
    }

    @Test
    void keepsAbsolutePathAbsolute(@TempDir Path tmp) {
        Path resolved = AgentMemoryConfig.canonicalizeDataDir(tmp.toString());

        assertThat(resolved).isEqualTo(tmp.toAbsolutePath().normalize());
    }

    // --- creation + layout ---------------------------------------------------------------------

    @Test
    void createsDataDirAndLayoutWhenMissing(@TempDir Path tmp) {
        Path target = tmp.resolve("created-here");
        assertThat(target).doesNotExist();

        AgentMemoryConfig config = AgentMemoryConfig.resolve(propsWithDataDir(target.toString()));

        assertThat(config.dataDir()).isDirectory().isEqualTo(target.toAbsolutePath().normalize());
        assertThat(config.wikiDir()).isDirectory().isEqualTo(config.dataDir().resolve("wiki"));
        assertThat(config.rawDir()).isDirectory().isEqualTo(config.dataDir().resolve("raw"));
        assertThat(config.dbDir()).isDirectory().isEqualTo(config.dataDir().resolve("db"));
        assertThat(config.logsDir()).isDirectory().isEqualTo(config.dataDir().resolve("logs"));
    }

    @Test
    void resolutionIsIdempotentOnExistingDir(@TempDir Path tmp) {
        assertThatCode(() -> {
            AgentMemoryConfig.resolve(propsWithDataDir(tmp.toString()));
            AgentMemoryConfig.resolve(propsWithDataDir(tmp.toString()));
        }).doesNotThrowAnyException();
    }

    @Test
    void exposesResolvedAbsoluteDataDir(@TempDir Path tmp) {
        AgentMemoryConfig config = AgentMemoryConfig.resolve(propsWithDataDir(tmp.toString()));

        assertThat(config.dataDir().isAbsolute()).isTrue();
    }

    // --- fail-fast -----------------------------------------------------------------------------

    @Test
    void failsFastWhenDataDirIsBlank() {
        assertThatThrownBy(() -> AgentMemoryConfig.resolve(propsWithDataDir("   ")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agent-memory.data.dir");
    }

    @Test
    void failsFastWhenPathIsAFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("a-file");
        Files.writeString(file, "not a directory");

        assertThatThrownBy(() -> AgentMemoryConfig.resolve(propsWithDataDir(file.toString())))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void failsFastWhenParentIsAFile(@TempDir Path tmp) throws IOException {
        // A file sits where we'd need a parent directory -> createDirectories cannot proceed.
        Path fileAsParent = tmp.resolve("blocker");
        Files.writeString(fileAsParent, "x");
        Path target = fileAsParent.resolve("child");

        assertThatThrownBy(() -> AgentMemoryConfig.resolve(propsWithDataDir(target.toString())))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Could not create data dir");
    }

    // --- auth fail-fast (#38) ------------------------------------------------------------------

    @Test
    void failsFastWhenAuthEnabledWithoutToken() {
        // Enabling auth but leaving the token blank would lock everyone out (or accept a blank token).
        assertThatThrownBy(() -> AgentMemoryConfig.validateAuth(
                new AgentMemoryProperties.Auth(true, "", java.util.List.of(), "")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agent-memory.auth.enabled");
    }

    @Test
    void authValidationPassesWhenDisabledOrTokenPresent() {
        assertThatCode(() -> {
            // Default: disabled, no token.
            AgentMemoryConfig.validateAuth(new AgentMemoryProperties.Auth(false, "", java.util.List.of(), ""));
            // Enabled with a token.
            AgentMemoryConfig.validateAuth(new AgentMemoryProperties.Auth(true, "tok", java.util.List.of(), ""));
        }).doesNotThrowAnyException();
    }

    // --- auto_scope (#39 / #87) ----------------------------------------------------------------

    @Test
    void allAutoScopeModesResolveIncludingSessionAware(@TempDir Path tmp) {
        // session_aware is a supported mode as of #87 (the runtime session-id guard lives in
        // ScopeResolver, not at startup), so resolving a config with any mode must succeed.
        assertThatCode(() -> {
            for (AutoScope mode : AutoScope.values()) {
                AgentMemoryConfig.resolve(propsWith(tmp.resolve(mode.name()).toString(), mode));
            }
        }).doesNotThrowAnyException();
    }

    // --- OIDC fail-fast (#39 PR2) --------------------------------------------------------------

    private static AgentMemoryProperties.Auth oidcAuth(boolean authEnabled, String issuer, String audience) {
        return new AgentMemoryProperties.Auth(authEnabled, authEnabled ? "root" : "", java.util.List.of(),
                "", new AgentMemoryProperties.Auth.Oidc(issuer, audience, "", "sub"));
    }

    @Test
    void failsFastWhenOidcIssuerSetButAuthDisabled() {
        // OIDC only guards routes the auth filter is installed for; an issuer configured while auth is
        // off is a half-on identity that never actually runs -> refuse to start (refinement #4).
        assertThatThrownBy(() ->
                AgentMemoryConfig.validateOidc(oidcAuth(false, "https://idp.example.com", "agent-memory")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agent-memory.auth.enabled is false");
    }

    @Test
    void failsFastWhenOidcIssuerSetWithoutAudience() {
        // Audience is the access gate: without it, any token the IdP issues would authenticate here.
        assertThatThrownBy(() ->
                AgentMemoryConfig.validateOidc(oidcAuth(true, "https://idp.example.com", "  ")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void failsFastWhenOidcIssuerIsNotAnAbsoluteUrl() {
        assertThatThrownBy(() ->
                AgentMemoryConfig.validateOidc(oidcAuth(true, "idp.example.com", "agent-memory")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("absolute http(s) URL");
    }

    @Test
    void oidcValidationPassesWhenDisabledOrWellFormed() {
        assertThatCode(() -> {
            // Disabled (no issuer): nothing to validate, even with auth off.
            AgentMemoryConfig.validateOidc(new AgentMemoryProperties.Auth(
                    false, "", java.util.List.of(), "", AgentMemoryProperties.Auth.Oidc.DISABLED));
            // Auth enabled + well-formed issuer + audience.
            AgentMemoryConfig.validateOidc(oidcAuth(true, "https://idp.example.com", "agent-memory"));
        }).doesNotThrowAnyException();
    }

    // --- decay tuning (#24) --------------------------------------------------------------------

    @Test
    void decayDefaultsAreExposedThroughTheResolvedConfig(@TempDir Path tmp) {
        AgentMemoryConfig config = AgentMemoryConfig.resolve(propsWithDataDir(tmp.toString()));
        AgentMemoryProperties.Decay decay = config.decay();
        assertThat(decay.lambda()).isEqualTo(0.02);
        assertThat(decay.sigma()).isEqualTo(1.0);
        assertThat(decay.mu()).isEqualTo(0.01);
        assertThat(decay.defaultSalience()).isEqualTo(1.0);
        assertThat(decay.coldThreshold()).isEqualTo(0.05);
        assertThat(decay.hardDeleteAfterDays()).isEqualTo(30);
        assertThat(decay.recentlyAccessedDays()).isEqualTo(7);
    }

    @Test
    void decayRejectsNegativeRatesAndNonPositiveSalience() {
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(-0.01, 1.0, 0.01, 1.0, 0.05, 30, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lambda");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, -1.0, 0.01, 1.0, 0.05, 30, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sigma");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, 1.0, -0.01, 1.0, 0.05, 30, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mu");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 0.0, 0.05, 30, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-salience");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, -0.05, 30, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cold-threshold");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, -1, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hard-delete-after-days");
        assertThatThrownBy(() -> new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, 30, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recently-accessed-days");
    }
}
