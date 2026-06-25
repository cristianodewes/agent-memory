package com.agentmemory.wiki;

/**
 * Thrown when a wiki file on disk cannot be parsed into a {@link MarkdownDocument} — a missing
 * frontmatter fence, a malformed line, or a missing required key. The watcher catches it per file
 * so one bad external edit does not abort reconciliation of the rest.
 */
public class WikiFormatException extends RuntimeException {

    public WikiFormatException(String message) {
        super(message);
    }

    public WikiFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
