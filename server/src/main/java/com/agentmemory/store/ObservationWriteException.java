package com.agentmemory.store;

/**
 * Thrown when an {@link ObservationSideEffect} (the #11 {@code log.md} + {@code raw/} side effect)
 * fails during an observation write. It is a {@link RuntimeException} so it propagates out of the
 * writer's transaction and rolls the whole operation back — the observation row and its ledger/raw
 * writes commit together or not at all (invariants #3/#10). Mirrors {@link PageWriteException} on the
 * page side.
 */
public class ObservationWriteException extends RuntimeException {

    public ObservationWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
