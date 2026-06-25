package com.agentmemory.store;

/**
 * Thrown when a {@link PageWriteCallback} (the #13 markdown/git side effect) fails during a page
 * write. It is a {@link RuntimeException} so it propagates out of the {@code @Transactional}
 * {@code create} and rolls the whole operation back — the DB row and the wiki file commit together
 * or not at all (invariants #3/#10).
 */
public class PageWriteException extends RuntimeException {

    public PageWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
