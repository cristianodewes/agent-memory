package com.agentmemory.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only stand-in for a project's external eval gate: a tiny program {@link EvalGateTest} spawns as a
 * real subprocess (via {@code java -cp … EvalGateProbe <mode> [arg]}). Using the JVM itself keeps the
 * subprocess test fully portable across Windows/Linux CI without shipping shell scripts.
 *
 * <p>It always drains stdin first (as a real gate would), then emits a scripted result per {@code mode}.
 */
public final class EvalGateProbe {

    private EvalGateProbe() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        String mode = args.length > 0 ? args[0] : "pass";
        byte[] stdin = System.in.readAllBytes();
        switch (mode) {
            case "pass" -> System.out.print("{\"pass\":true,\"reasons\":[\"ok\"]}");
            case "fail" -> System.out.print("{\"pass\":false,\"reasons\":[\"rule violated\"]}");
            case "exit1" -> {
                System.err.print("gate exploded");
                System.exit(3);
            }
            case "garbage" -> System.out.print("this is not json");
            case "empty" -> { /* print nothing — empty verdict */ }
            case "nopass" -> System.out.print("{\"reasons\":[\"missing pass field\"]}");
            case "hang" -> Thread.sleep(60_000);
            case "capture" -> {
                // Record exactly what arrived on stdin so the test can assert the input contract.
                Files.write(Path.of(args[1]), stdin);
                System.out.print("{\"pass\":true,\"reasons\":[]}");
            }
            default -> System.out.print("{\"pass\":true}");
        }
        System.out.flush();
    }
}
