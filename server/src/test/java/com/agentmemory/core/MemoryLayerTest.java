package com.agentmemory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link MemoryLayer} vocabulary + per-layer regime defaults (issue #24). */
class MemoryLayerTest {

    @Test
    void wireTokensAreStableAndDistinct() {
        assertThat(MemoryLayer.WORKING.wire()).isEqualTo("working");
        assertThat(MemoryLayer.EPISODIC.wire()).isEqualTo("episodic");
        assertThat(MemoryLayer.SEMANTIC.wire()).isEqualTo("semantic");
        assertThat(MemoryLayer.PROCEDURAL.wire()).isEqualTo("procedural");
    }

    @Test
    void fromWireRoundTripsEveryLayerCaseInsensitively() {
        for (MemoryLayer layer : MemoryLayer.values()) {
            assertThat(MemoryLayer.fromWire(layer.wire())).isEqualTo(layer);
            assertThat(MemoryLayer.fromWire(layer.wire().toUpperCase(java.util.Locale.ROOT)))
                    .isEqualTo(layer);
        }
    }

    @Test
    void fromWireRejectsBlankAndUnknown() {
        assertThatThrownBy(() -> MemoryLayer.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemoryLayer.fromWire("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemoryLayer.fromWire("imaginary"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlySemanticAndProceduralAreAgeStable() {
        assertThat(MemoryLayer.WORKING.ageDecays()).isTrue();
        assertThat(MemoryLayer.EPISODIC.ageDecays()).isTrue();
        assertThat(MemoryLayer.SEMANTIC.ageDecays()).isFalse();
        assertThat(MemoryLayer.PROCEDURAL.ageDecays()).isFalse();
    }

    @Test
    void hotWindowIsWithinColdThresholdAndOrderedAcrossLayers() {
        for (MemoryLayer layer : MemoryLayer.values()) {
            assertThat(layer.defaultHotDays())
                    .as("%s hot <= cold", layer)
                    .isLessThanOrEqualTo(layer.defaultColdDays());
        }
        // Volatile → durable widening of the retention windows.
        assertThat(MemoryLayer.WORKING.defaultColdDays())
                .isLessThan(MemoryLayer.EPISODIC.defaultColdDays());
        // Semantic never ages out.
        assertThat(MemoryLayer.SEMANTIC.defaultColdDays()).isInfinite();
    }
}
