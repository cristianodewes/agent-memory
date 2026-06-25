package com.agentmemory.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for {@link EvalGate} (issue #31). Spawns the real subprocess path against a portable
 * JVM probe ({@link EvalGateProbe}) to prove: gate-pass and gate-fail, the four fail-closed cases
 * (timeout/non-zero-exit/malformed/missing-{@code pass}), off-by-default and prefix selection (the gate
 * never runs), and the stdin JSON input contract.
 */
class EvalGateTest {

    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();
    // The probe uses only JDK classes, so its own code-source dir (target/test-classes) is a sufficient
    // and robust classpath for the child JVM — avoids Surefire's manifest-only-jar classpath on Windows.
    private static final String CLASSPATH = codeSource(EvalGateProbe.class);
    private static final String PROBE = EvalGateProbe.class.getName();

    private static String codeSource(Class<?> c) {
        try {
            return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    private static List<String> probe(String mode, String... extra) {
        List<String> cmd = new java.util.ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, PROBE, mode));
        cmd.addAll(List.of(extra));
        return List.copyOf(cmd);
    }

    private static EvalGate gate(boolean enabled, List<String> command, Duration timeout, List<String> prefixes) {
        return new EvalGate(new EvalGateProperties(enabled, prefixes, command, timeout, 65_536, null));
    }

    private static EvalProposal ruleProposal() {
        return EvalProposal.upsert("_rules/security.md", "Security rules", "Always validate input.");
    }

    @Test
    @Timeout(60)
    void passesWhenTheGateApproves() {
        EvalVerdict v = gate(true, probe("pass"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.PASSED);
        assertThat(v.allowed()).isTrue();
        assertThat(v.reasons()).containsExactly("ok");
    }

    @Test
    @Timeout(60)
    void blocksWhenTheGateRejects() {
        EvalVerdict v = gate(true, probe("fail"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
        assertThat(v.blocked()).isTrue();
        assertThat(v.reasons()).contains("rule violated");
    }

    @Test
    @Timeout(60)
    void failsClosedOnNonZeroExit() {
        EvalVerdict v = gate(true, probe("exit1"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
        assertThat(v.reasons().toString()).contains("status 3");
    }

    @Test
    @Timeout(60)
    void failsClosedOnMalformedOutput() {
        EvalVerdict v = gate(true, probe("garbage"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
    }

    @Test
    @Timeout(60)
    void failsClosedOnMissingPassField() {
        EvalVerdict v = gate(true, probe("nopass"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
    }

    @Test
    @Timeout(60)
    void failsClosedOnEmptyOutput() {
        EvalVerdict v = gate(true, probe("empty"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
    }

    @Test
    @Timeout(60)
    void failsClosedOnTimeoutAndKillsTheProcess() {
        long start = System.nanoTime();
        EvalVerdict v = gate(true, probe("hang"), Duration.ofMillis(500), null).evaluate(ruleProposal());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.BLOCKED);
        assertThat(v.reasons().toString()).contains("timed out");
        // The hung gate (sleeps 60s) was force-killed near the 500ms budget, not awaited.
        assertThat(elapsedMs).isLessThan(15_000);
    }

    @Test
    @Timeout(60)
    void skipsWhenDisabled() {
        // A disabled gate must not run the command at all — even one that would block.
        EvalVerdict v = gate(false, probe("fail"), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.SKIPPED);
        assertThat(v.allowed()).isTrue();
    }

    @Test
    @Timeout(60)
    void skipsWhenCommandIsEmpty() {
        EvalVerdict v = gate(true, List.of(), Duration.ofSeconds(30), null).evaluate(ruleProposal());
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.SKIPPED);
    }

    @Test
    @Timeout(60)
    void skipsWhenPathPrefixNotSelected() {
        EvalProposal note = EvalProposal.upsert("notes/scratch.md", "Scratch", "x");
        EvalVerdict v = gate(true, probe("fail"), Duration.ofSeconds(30), List.of("_rules/", "procedures/"))
                .evaluate(note);
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.SKIPPED);
    }

    @Test
    @Timeout(60)
    void runsForAConfiguredNonDefaultPrefix() {
        EvalProposal proc = EvalProposal.upsert("procedures/deploy.md", "Deploy", "step 1");
        EvalVerdict v = gate(true, probe("pass"), Duration.ofSeconds(30), List.of("procedures/"))
                .evaluate(proc);
        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.PASSED);
    }

    @Test
    @Timeout(60)
    void sendsTheProposalToTheGateAsJsonOnStdin(@TempDir Path tmp) throws Exception {
        Path captured = tmp.resolve("stdin.json");
        EvalVerdict v = gate(true, probe("capture", captured.toString()), Duration.ofSeconds(30), null)
                .evaluate(ruleProposal());

        assertThat(v.decision()).isEqualTo(EvalVerdict.Decision.PASSED);
        String sent = Files.readString(captured);
        assertThat(sent)
                .contains("\"version\":\"eval-gate/v1\"")
                .contains("\"action\":\"upsert\"")
                .contains("\"path\":\"_rules/security.md\"")
                .contains("\"title\":\"Security rules\"")
                .contains("\"body\":\"Always validate input.\"");
    }
}
