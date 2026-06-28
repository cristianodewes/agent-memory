package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.MemoryLayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecencyDecayFusion} (no DB): the recency prior re-orders the RRF result so
 * a fresher page outranks a higher-RRF-but-stale one, pulls a fresh candidate up from beyond the cap,
 * leaves the non-decaying layers alone, and stays deterministic — all over a fixed clock (issue #140).
 */
class RecencyDecayFusionTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The decorator over plain RRF with the default half-lives. */
    private static RecencyDecayFusion fusion() {
        return new RecencyDecayFusion(
                new RrfFusion(), new RecencyDecay(RecencyParameters.defaults(), CLOCK));
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    /** A PAGE hit carrying recency metadata (the fields the prior reads). */
    private static RecallHit hit(String key, Instant updatedAt, MemoryLayer layer) {
        return new RecallHit(
                HitSource.PAGE, key, key + ".md", key, null, 0, 0, "<mark>" + key + "</mark>",
                updatedAt, layer);
    }

    private static Map<String, RecallHit> hits(RecallHit... hs) {
        Map<String, RecallHit> m = new LinkedHashMap<>();
        for (RecallHit h : hs) {
            m.put(h.id(), h);
        }
        return m;
    }

    @Test
    void fresherPageOutranksAHigherRrfButStaleOne() {
        // Single FTS arm; "stale" is ranked above "fresh" by RRF (rank 1 vs 2), so WITHOUT the prior the
        // order is [stale, fresh]. Both are episodic; stale is 120d old (heavily decayed), fresh is new.
        RankedList fts = new RankedList("fts", List.of("stale", "fresh"));
        Map<String, RecallHit> hits = hits(
                hit("stale", daysAgo(120), MemoryLayer.EPISODIC),
                hit("fresh", NOW, MemoryLayer.EPISODIC));

        List<RecallHit> fused = fusion().fuse(List.of(fts), hits, 10);

        // The prior flips the order: the fresher page wins despite its lower base RRF rank.
        assertThat(fused).extracting(RecallHit::id).containsExactly("fresh", "stale");
        assertThat(fused.get(0).rank()).isEqualTo(1);
        assertThat(fused.get(1).rank()).isEqualTo(2);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        // Recency metadata survives the fusion (carried through for the render layer).
        assertThat(fused.get(0).layer()).isEqualTo(MemoryLayer.EPISODIC);
        assertThat(fused.get(0).updatedAt()).isEqualTo(NOW);
    }

    @Test
    void semanticDoesNotDecaySoItOutranksADecayedEpisodicOfEqualAge() {
        // Both equally old (120d); "epi" is ranked above "sem" by RRF. The episodic page decays, the
        // semantic one does not, so after the prior the semantic page wins.
        RankedList fts = new RankedList("fts", List.of("epi", "sem"));
        Map<String, RecallHit> hits = hits(
                hit("epi", daysAgo(120), MemoryLayer.EPISODIC),
                hit("sem", daysAgo(120), MemoryLayer.SEMANTIC));

        List<RecallHit> fused = fusion().fuse(List.of(fts), hits, 10);

        assertThat(fused).extracting(RecallHit::id).containsExactly("sem", "epi");
        // The semantic page keeps (essentially) its full RRF score; the episodic one is crushed.
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score() * 10);
    }

    @Test
    void pullsAFreshCandidateUpFromBeyondTheCap() {
        // Three equal-relevance candidates ranked a > b > c by RRF; a and b are stale, c is fresh. With
        // limit 2, fusing only the top-2 would return [a, b] — but the prior fuses the whole pool, so the
        // fresh c (base rank 3) is lifted into the page.
        RankedList fts = new RankedList("fts", List.of("a", "b", "c"));
        Map<String, RecallHit> hits = hits(
                hit("a", daysAgo(120), MemoryLayer.EPISODIC),
                hit("b", daysAgo(120), MemoryLayer.EPISODIC),
                hit("c", NOW, MemoryLayer.EPISODIC));

        List<RecallHit> fused = fusion().fuse(List.of(fts), hits, 2);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).id()).isEqualTo("c"); // fresh, pulled up from base rank 3
        assertThat(fused).extracting(RecallHit::id).contains("c");
    }

    @Test
    void undatedHitsKeepTheBaseRrfOrder() {
        // No recency metadata (e.g. a vector-only / raw hit): the prior is a no-op, so the order and the
        // contiguous ranks match plain RRF.
        RankedList fts = new RankedList("fts", List.of("x", "y", "z"));
        Map<String, RecallHit> hits = hits(
                hit("x", null, null), hit("y", null, null), hit("z", null, null));

        List<RecallHit> fused = fusion().fuse(List.of(fts), hits, 10);

        assertThat(fused).extracting(RecallHit::id).containsExactly("x", "y", "z");
        assertThat(fused).extracting(RecallHit::rank).containsExactly(1, 2, 3);
    }

    @Test
    void tieOnDecayedScoreBreaksByIdAscendingDeterministically() {
        // Two semantic (undecayed) hits, each rank 1 in its own arm → identical decayed scores. The
        // tie-break is id ascending, regardless of arm order.
        RankedList armA = new RankedList("a", List.of("zzz"));
        RankedList armB = new RankedList("b", List.of("aaa"));
        Map<String, RecallHit> hits = hits(
                hit("zzz", daysAgo(5), MemoryLayer.SEMANTIC),
                hit("aaa", daysAgo(5), MemoryLayer.SEMANTIC));

        List<RecallHit> one = fusion().fuse(List.of(armA, armB), hits, 10);
        List<RecallHit> two = fusion().fuse(List.of(armB, armA), hits, 10);

        assertThat(one).extracting(RecallHit::id).containsExactly("aaa", "zzz");
        assertThat(one).isEqualTo(two);
    }

    @Test
    void respectsLimit() {
        RankedList fts = new RankedList("fts", List.of("a", "b", "c", "d", "e"));
        Map<String, RecallHit> hits = hits(
                hit("a", NOW, MemoryLayer.EPISODIC), hit("b", NOW, MemoryLayer.EPISODIC),
                hit("c", NOW, MemoryLayer.EPISODIC), hit("d", NOW, MemoryLayer.EPISODIC),
                hit("e", NOW, MemoryLayer.EPISODIC));

        assertThat(fusion().fuse(List.of(fts), hits, 3)).hasSize(3);
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new RecencyDecayFusion(null, new RecencyDecay(RecencyParameters.defaults(), CLOCK)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyDecayFusion(new RrfFusion(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion().fuse(List.of(), Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
