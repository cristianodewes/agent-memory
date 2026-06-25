package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RrfFusion} (no DB): the RRF math, cross-arm reinforcement, the stable
 * deterministic tie-break (issue #15 acceptance: "stable tie-breaking" + "RRF determinism"), and
 * limit capping.
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    /** A trivial PAGE hit for key {@code k} (the payload content is irrelevant to fusion ordering). */
    private static RecallHit hit(String k) {
        return new RecallHit(HitSource.PAGE, k, k + ".md", k, null, 0, 0, "<mark>" + k + "</mark>");
    }

    private static Map<String, RecallHit> hits(String... keys) {
        Map<String, RecallHit> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, hit(k));
        }
        return m;
    }

    @Test
    void itemRankedByBothArmsOutscoresItemRankedByOne() {
        // "b" is rank 2 in fts and rank 1 in graph; "a" is rank 1 in fts only. With k=60:
        //   score(a) = 1/61                ≈ 0.01639
        //   score(b) = 1/62 + 1/61         ≈ 0.01613 + 0.01639 = 0.03252  -> b wins
        RankedList fts = new RankedList("fts", List.of("a", "b"));
        RankedList graph = new RankedList("graph", List.of("b"));

        List<RecallHit> fused = fusion.fuse(List.of(fts, graph), hits("a", "b"), 10);

        assertThat(fused).extracting(RecallHit::id).containsExactly("b", "a");
        assertThat(fused.get(0).rank()).isEqualTo(1);
        assertThat(fused.get(1).rank()).isEqualTo(2);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void singleArmPreservesItsOrder() {
        RankedList fts = new RankedList("fts", List.of("x", "y", "z"));
        List<RecallHit> fused = fusion.fuse(List.of(fts), hits("x", "y", "z"), 10);
        assertThat(fused).extracting(RecallHit::id).containsExactly("x", "y", "z");
    }

    @Test
    void tieBreakIsStableByKeyAscending() {
        // Two keys with identical contribution (each rank 1 in its own arm) must order by key asc,
        // regardless of the order the arms are presented in — that is the determinism guarantee.
        RankedList armA = new RankedList("a", List.of("zzz"));
        RankedList armB = new RankedList("b", List.of("aaa"));

        List<RecallHit> one = fusion.fuse(List.of(armA, armB), hits("zzz", "aaa"), 10);
        List<RecallHit> two = fusion.fuse(List.of(armB, armA), hits("zzz", "aaa"), 10);

        assertThat(one).extracting(RecallHit::id).containsExactly("aaa", "zzz");
        assertThat(two).extracting(RecallHit::id).containsExactly("aaa", "zzz");
        assertThat(one).isEqualTo(two); // byte-identical fused result regardless of arm order
    }

    @Test
    void respectsLimit() {
        RankedList fts = new RankedList("fts", List.of("a", "b", "c", "d", "e"));
        List<RecallHit> fused = fusion.fuse(List.of(fts), hits("a", "b", "c", "d", "e"), 3);
        assertThat(fused).hasSize(3).extracting(RecallHit::id).containsExactly("a", "b", "c");
    }

    @Test
    void ranksAreContiguousFrom1() {
        RankedList fts = new RankedList("fts", List.of("a", "b", "c"));
        List<RecallHit> fused = fusion.fuse(List.of(fts), hits("a", "b", "c"), 10);
        assertThat(fused).extracting(RecallHit::rank).containsExactly(1, 2, 3);
    }

    @Test
    void emptyArmsYieldEmptyResult() {
        assertThat(fusion.fuse(List.of(), Map.of(), 10)).isEmpty();
        assertThat(fusion.fuse(List.of(new RankedList("fts", List.of())), Map.of(), 10)).isEmpty();
    }

    @Test
    void keyWithoutPayloadIsSkipped() {
        // "ghost" is ranked but has no hit payload — it must be dropped, not NPE.
        RankedList fts = new RankedList("fts", List.of("real", "ghost"));
        List<RecallHit> fused = fusion.fuse(List.of(fts), hits("real"), 10);
        assertThat(fused).extracting(RecallHit::id).containsExactly("real");
    }

    @Test
    void largerKFlattensTopRankAdvantage() {
        // Sanity on the k knob: the gap between rank-1 and rank-2 contributions shrinks as k grows.
        double gapSmallK = (1.0 / (1 + 1)) - (1.0 / (1 + 2));   // k=1
        double gapBigK = (1.0 / (1000 + 1)) - (1.0 / (1000 + 2)); // k=1000
        assertThat(gapBigK).isLessThan(gapSmallK);
        assertThat(new RrfFusion(1000).k()).isEqualTo(1000);
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new RrfFusion(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(), Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
