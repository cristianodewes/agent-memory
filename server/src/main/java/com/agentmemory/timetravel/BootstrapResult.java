package com.agentmemory.timetravel;

import com.agentmemory.core.Identity;
import java.util.List;

/**
 * The outcome of a {@code bootstrap} (issue #34): the seed pages the single LLM pass produced and
 * wrote under the target project.
 *
 * @param target     the project the pages were written under ({@code workspace/project}).
 * @param pagesWritten the page paths created (relative, e.g. {@code concepts/overview.md}).
 * @param performed  whether bootstrap ran (false ⇒ refused by the live-process guard).
 * @param reason     a human-readable note (why it was refused, or a success summary).
 */
public record BootstrapResult(Identity target, List<String> pagesWritten, boolean performed,
                              String reason) {

    static BootstrapResult refused(Identity target, long foreignPid) {
        return new BootstrapResult(target, List.of(), false,
                "another agent-memory process (pid " + foreignPid + ") holds the data dir; "
                        + "bootstrap refused (invariant #9).");
    }
}
