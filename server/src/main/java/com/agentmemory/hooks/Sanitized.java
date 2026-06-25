package com.agentmemory.hooks;

import java.util.Objects;

/**
 * A value that has passed through the privacy {@link Sanitizer} — the typed boundary DD-010 and
 * invariant #6 require. The whole point is the <strong>absence of a public constructor</strong>:
 * the only way to obtain a {@code Sanitized<T>} is to call {@link Sanitizer#sanitize(NewObservation)},
 * because the constructor is package-private and {@link Sanitizer} is the sole caller in this package.
 *
 * <p>Downstream code expresses "I require scrubbed text" simply by accepting a {@code Sanitized<T>}
 * parameter. The store's writer ({@code com.agentmemory.store}) does exactly this, so it is
 * <em>impossible to call the writer with text that has not been sanitized</em> — the guarantee is
 * carried by the type, checked by the compiler, not by reviewer vigilance. An architecture test
 * ({@code SanitizationBoundaryTest}) backs this up by failing if any class other than
 * {@link Sanitizer} ever constructs a {@code Sanitized}.
 *
 * <p>The wrapper is immutable and transparent: {@link #value()} returns the cleaned inner value.
 * It carries no Jackson annotations and is never serialized — it is an in-process marker, not a wire
 * shape (the wire shapes are {@link HookPayload} in and {@code core.Observation} out).
 *
 * @param <T> the wrapped, already-scrubbed value type (e.g. {@link NewObservation}).
 */
public final class Sanitized<T> {

    private final T value;

    /**
     * Package-private: only {@link Sanitizer} may construct a {@code Sanitized}. This is the lock on
     * the boundary — there is deliberately no public or protected constructor and no factory outside
     * the sanitizer.
     *
     * @param value the value the sanitizer has just finished scrubbing; never null.
     */
    Sanitized(T value) {
        this.value = Objects.requireNonNull(value, "sanitized value must not be null");
    }

    /**
     * @return the wrapped value, guaranteed to have been produced by the sanitizer.
     */
    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sanitized<?> other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "Sanitized[" + value + "]";
    }
}
