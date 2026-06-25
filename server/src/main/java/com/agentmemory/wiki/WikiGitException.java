package com.agentmemory.wiki;

/**
 * Thrown when a JGit operation backing the wiki repository fails (open/init/stage/commit). Unchecked
 * so it can propagate out of a {@code PageWriteCallback} and roll back the surrounding store write
 * (the DB row and the commit succeed or fail together).
 */
public class WikiGitException extends RuntimeException {

    public WikiGitException(String message, Throwable cause) {
        super(message, cause);
    }
}
