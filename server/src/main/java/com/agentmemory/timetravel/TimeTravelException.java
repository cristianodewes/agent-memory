package com.agentmemory.timetravel;

/**
 * A time-travel operation could not be completed (issue #34): an unresolvable revision, a path that
 * does not exist at the requested revision, or a backup/restore IO failure. Maps to a {@code 409}/
 * {@code 400} at the web edge depending on the cause.
 */
public class TimeTravelException extends RuntimeException {

    public TimeTravelException(String message) {
        super(message);
    }

    public TimeTravelException(String message, Throwable cause) {
        super(message, cause);
    }
}
