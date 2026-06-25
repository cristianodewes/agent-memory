package com.agentmemory.lifecycle;

/**
 * Thrown when a project lifecycle operation (rename / move / purge / reset, issue #33) cannot
 * proceed: a destination identity already exists, the source project does not exist, or a destructive
 * op is refused because a live process holds the data dir (invariant #9). Unchecked so it rolls back
 * the surrounding DB transaction and surfaces to the controller as a 4xx.
 */
public class LifecycleException extends RuntimeException {

    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
