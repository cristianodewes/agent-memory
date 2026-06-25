package com.agentmemory.core;

import java.util.Locale;

/**
 * The retention layer a page belongs to (ARCHITECTURE §3.3; ROADMAP #24). The layer is what makes
 * decay <em>regime-aware</em>: not every memory ages the same way. A throwaway scratch note and a
 * durable architectural decision should not share a half-life, so each page is classified into one
 * of four layers and the decay math ({@code com.agentmemory.store}) applies that layer's regime.
 *
 * <p>This is a pure {@code core} vocabulary type — IO-free and persistence-free (§6). It carries the
 * <em>default</em> regime knobs for its layer as plain numbers; the live, configurable values come
 * from the single config (#2) and only override these per-deployment. The mapping from a page to its
 * layer (by {@code kind}/path) and the formula itself live in {@code store}/{@code wiki}, not here.
 *
 * <p>The four layers (a standard memory taxonomy):
 * <ul>
 *   <li>{@link #WORKING} — volatile, session-scoped scratch. The shortest half-life; pages here are
 *       dropped from "latest" at session end (they survive only as raw observations).</li>
 *   <li>{@link #EPISODIC} — events in time (a session digest, a gotcha hit). Decays on a hot→cold
 *       schedule: fresh and frequently-recalled for a window, then fades.</li>
 *   <li>{@link #SEMANTIC} — distilled, timeless knowledge (a concept, a decision). Does <em>not</em>
 *       decay on age; it is reinforced by use and otherwise persists.</li>
 *   <li>{@link #PROCEDURAL} — how-to knowledge (a procedure/runbook). Frequency-driven: retention
 *       tracks how often it is actually used, not the calendar.</li>
 * </ul>
 */
public enum MemoryLayer {

    /** Volatile session scratch; shortest retention, dropped from latest at session end. */
    WORKING("working", /* ageDecays */ true, /* hotDays */ 1.0, /* coldDays */ 7.0),

    /** Events in time; hot→cold age decay (default hot 30d, cold 180d). */
    EPISODIC("episodic", /* ageDecays */ true, /* hotDays */ 30.0, /* coldDays */ 180.0),

    /** Timeless distilled knowledge; no age decay (reinforced by use, otherwise persists). */
    SEMANTIC("semantic", /* ageDecays */ false, /* hotDays */ Double.POSITIVE_INFINITY,
            /* coldDays */ Double.POSITIVE_INFINITY),

    /** How-to knowledge; frequency-driven retention (default hot 60d, cold 365d). */
    PROCEDURAL("procedural", /* ageDecays */ false, /* hotDays */ 60.0, /* coldDays */ 365.0);

    private final String wire;
    private final boolean ageDecays;
    private final double defaultHotDays;
    private final double defaultColdDays;

    MemoryLayer(String wire, boolean ageDecays, double defaultHotDays, double defaultColdDays) {
        this.wire = wire;
        this.ageDecays = ageDecays;
        this.defaultHotDays = defaultHotDays;
        this.defaultColdDays = defaultColdDays;
    }

    /** @return the stable wire/storage token for this layer (the {@code pages.layer} value). */
    public String wire() {
        return wire;
    }

    /**
     * @return {@code true} if this layer's retention decays with the page's <em>age</em> (the
     *     {@code salience·exp(−λ·Δt)} term). {@link #SEMANTIC} and {@link #PROCEDURAL} return
     *     {@code false}: semantic knowledge persists regardless of calendar age, and procedural
     *     retention is governed by use frequency rather than how old the runbook is.
     */
    public boolean ageDecays() {
        return ageDecays;
    }

    /**
     * @return the default "hot" window in days — within this many days of the relevant signal (age
     *     for {@link #EPISODIC}, last access for {@link #PROCEDURAL}) the page is considered fresh.
     *     A starting point only; the live value comes from config (#2).
     */
    public double defaultHotDays() {
        return defaultHotDays;
    }

    /**
     * @return the default "cold" threshold in days — past this the page is a sweep candidate (#25).
     *     {@code +Infinity} for {@link #SEMANTIC} (never ages out). A starting point only; the live
     *     value comes from config (#2).
     */
    public double defaultColdDays() {
        return defaultColdDays;
    }

    /**
     * Parse a wire/storage token to a layer, case-insensitively.
     *
     * @param token the stored {@code pages.layer} value (or a frontmatter token).
     * @return the matching layer.
     * @throws IllegalArgumentException if {@code token} is blank or not a known layer.
     */
    public static MemoryLayer fromWire(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("memory layer token must not be blank");
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (MemoryLayer layer : values()) {
            if (layer.wire.equals(normalized)) {
                return layer;
            }
        }
        throw new IllegalArgumentException("unknown memory layer: " + token);
    }
}
