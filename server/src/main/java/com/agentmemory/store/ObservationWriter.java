package com.agentmemory.store;

import com.agentmemory.core.Observation;
import com.agentmemory.hooks.NewObservation;
import com.agentmemory.hooks.Sanitized;

/**
 * The single entry point for persisting a captured observation — and the structural half of the
 * DD-010 / invariant #6 privacy boundary. Its {@link #append(Sanitized)} method accepts
 * <strong>only</strong> a {@link Sanitized}{@code <NewObservation>}, never a bare
 * {@link NewObservation} or a raw payload {@link String}. Since a {@code Sanitized} can be produced
 * solely by {@code com.agentmemory.hooks.Sanitizer.sanitize(...)}, it is impossible to reach the
 * store with text that was not privacy-stripped: the requirement is encoded in the parameter type and
 * enforced by the compiler.
 *
 * <p>The concrete Postgres-backed, single-writer implementation lands with the storage issues (#4 /
 * #12). This interface exists now so the typed boundary is real and testable at the seam the writer
 * will occupy — an architecture test asserts no production code calls a writer with anything other
 * than a sanitized value.
 */
public interface ObservationWriter {

    /**
     * Persist a sanitized observation and return the stored {@link Observation} domain record (with
     * its server-assigned id). The argument's payload is guaranteed scrubbed and size-capped because
     * only the sanitizer can have constructed the {@link Sanitized} wrapper.
     *
     * @param observation the privacy-stripped, storable observation; never null.
     * @return the persisted observation domain record.
     */
    Observation append(Sanitized<NewObservation> observation);
}
