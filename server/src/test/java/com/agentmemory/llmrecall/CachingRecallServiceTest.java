package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link CachingRecallService} — the short-TTL bounded-LRU recall-result cache
 * (issue #142, Fase 4). The underlying service is a {@link CountingRecallService} stub that counts how
 * often it is actually called, so a cache hit is observable as "the delegate was not called again", and
 * a fake nanosecond clock drives TTL expiry without sleeping.
 *
 * <p>Proves the acceptance criteria: a repeated {@code (scope, prompt, limit)} within the TTL does not
 * reach the delegate; a different scope (and a different limit) never shares an entry; cosmetic prompt
 * variants do; an entry expires after the TTL; the LRU bound holds; and the cached
 * {@link RecallResult}'s {@code calibrated}/{@code rawFallback}/hits are preserved verbatim.
 */
class CachingRecallServiceTest {

    private static final Scope SCOPE_A = Scope.of("ws-a", "proj");
    private static final Scope SCOPE_B = Scope.of("ws-b", "proj");

    /** A fake monotonic clock (nanoseconds) the cache reads; tests advance it to cross the TTL. */
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private CachingRecallService cache(CountingRecallService delegate, LlmRecallProperties.Cache cfg) {
        return new CachingRecallService(delegate, cfg, clock::get);
    }

    private static LlmRecallProperties.Cache cfg(boolean enabled, Duration ttl, int maxEntries) {
        return new LlmRecallProperties.Cache(enabled, ttl, maxEntries);
    }

    private static RecallQuery query(String text, Scope scope, int limit) {
        return new RecallQuery(text, scope, limit);
    }

    @Test
    void secondIdenticalSearchWithinTtlIsServedFromCacheAndDoesNotHitTheDelegate() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 256));

        RecallResult first = svc.search(query("where did we leave off", SCOPE_A, 20));
        RecallResult second = svc.search(query("where did we leave off", SCOPE_A, 20));

        assertThat(delegate.calls()).as("delegate called once; the repeat is served from cache").isOne();
        assertThat(second).as("the cached result object is replayed verbatim").isSameAs(first);
    }

    @Test
    void differentScopeDoesNotShareACacheEntry() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 256));

        // Same normalized text + limit, different scope — a shared entry here would be a severe scope leak.
        svc.search(query("the usual setup", SCOPE_A, 10));
        svc.search(query("the usual setup", SCOPE_B, 10));
        // And the first scope again — proves SCOPE_A's own entry is still cached (not overwritten).
        svc.search(query("the usual setup", SCOPE_A, 10));

        assertThat(delegate.calls()).as("each scope computes once; no cross-scope sharing").isEqualTo(2);
    }

    @Test
    void differentLimitIsADifferentEntry() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 256));

        svc.search(query("same words", SCOPE_A, 5));
        svc.search(query("same words", SCOPE_A, 20));

        assertThat(delegate.calls()).as("a larger limit is a different result, not a cache hit").isEqualTo(2);
    }

    @Test
    void cosmeticPromptVariantsShareAnEntryViaNormalization() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 256));

        // trim + collapse internal whitespace + lowercase => same key.
        svc.search(query("  Fix   the\tBUG ", SCOPE_A, 10));
        svc.search(query("fix the bug", SCOPE_A, 10));

        assertThat(delegate.calls()).as("normalized-equal prompts share one entry").isOne();
    }

    @Test
    void normalizeTrimsCollapsesWhitespaceAndLowercases() {
        assertThat(CachingRecallService.normalize("  Fix   the\tBUG ")).isEqualTo("fix the bug");
        assertThat(CachingRecallService.normalize("Already normal")).isEqualTo("already normal");
    }

    @Test
    void anEntryStaysFreshUntilTheTtlThenExpiresAndRecomputes() {
        CountingRecallService delegate = new CountingRecallService();
        Duration ttl = Duration.ofSeconds(30);
        CachingRecallService svc = cache(delegate, cfg(true, ttl, 256));

        svc.search(query("topic", SCOPE_A, 10)); // miss -> stored at t0
        assertThat(delegate.calls()).isOne();

        // Just under the TTL: still a fresh hit.
        clock.addAndGet(ttl.toNanos() - 1);
        svc.search(query("topic", SCOPE_A, 10));
        assertThat(delegate.calls()).as("within the TTL the entry is replayed").isOne();

        // Cross the TTL boundary: the entry has expired, so the delegate is consulted again.
        clock.addAndGet(1);
        svc.search(query("topic", SCOPE_A, 10));
        assertThat(delegate.calls()).as("past the TTL the entry expires and recomputes").isEqualTo(2);
    }

    @Test
    void theLruBoundEvictsTheLeastRecentlyUsedEntry() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 2)); // bound = 2

        svc.search(query("alpha", SCOPE_A, 10)); // miss A -> {A}            calls=1
        svc.search(query("beta", SCOPE_A, 10));  // miss B -> {A,B}  (A LRU) calls=2
        svc.search(query("alpha", SCOPE_A, 10)); // hit  A -> A is now MRU,  B is now LRU; calls stays 2
        assertThat(delegate.calls()).isEqualTo(2);

        svc.search(query("gamma", SCOPE_A, 10)); // miss C -> evicts LRU=B -> {A,C}  calls=3
        assertThat(delegate.calls()).isEqualTo(3);

        // alpha, used more recently than beta, was retained when gamma evicted the LRU: still a hit.
        svc.search(query("alpha", SCOPE_A, 10));
        assertThat(delegate.calls()).as("the recently-used entry (alpha) was kept").isEqualTo(3);

        // beta was the least-recently-used at the eviction, so it was dropped and now recomputes.
        svc.search(query("beta", SCOPE_A, 10));
        assertThat(delegate.calls()).as("the LRU entry (beta) was evicted past the bound").isEqualTo(4);
    }

    @Test
    void cachedResultPreservesCalibratedRawFallbackAndHits() {
        RecallHit hit = new RecallHit(HitSource.PAGE, "id-1", "p/a.md", "Alpha", null, 0.91, 1, "snip");
        RecallResult calibrated = new RecallResult(List.of(hit), false, true);
        RecallResult somethingElse = new RecallResult(List.of(), true, false);

        CountingRecallService delegate = new CountingRecallService();
        // First call returns the calibrated result; any later call would return a DIFFERENT result, so a
        // wrong cache miss would be visible as the flags/hits changing.
        delegate.respondWith(calibrated, somethingElse);
        CachingRecallService svc = cache(delegate, cfg(true, Duration.ofSeconds(45), 256));

        svc.search(query("relevant prompt", SCOPE_A, 10)); // populates the cache with `calibrated`
        RecallResult replayed = svc.search(query("relevant prompt", SCOPE_A, 10));

        assertThat(delegate.calls()).isOne();
        assertThat(replayed).isSameAs(calibrated);
        assertThat(replayed.calibrated()).isTrue();
        assertThat(replayed.rawFallback()).isFalse();
        assertThat(replayed.hits()).containsExactly(hit);
    }

    @Test
    void disabledCacheIsATransparentPassThrough() {
        CountingRecallService delegate = new CountingRecallService();
        CachingRecallService svc = cache(delegate, cfg(false, Duration.ofSeconds(45), 256));

        svc.search(query("repeat me", SCOPE_A, 10));
        svc.search(query("repeat me", SCOPE_A, 10));

        assertThat(delegate.calls()).as("a disabled cache delegates every search").isEqualTo(2);
    }

    @Test
    void nullQueryIsRejected() {
        CachingRecallService svc =
                cache(new CountingRecallService(), cfg(true, Duration.ofSeconds(45), 256));
        assertThatThrownBy(() -> svc.search(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullCollaborators() {
        LlmRecallProperties.Cache c = cfg(true, Duration.ofSeconds(45), 256);
        assertThatThrownBy(() -> new CachingRecallService(null, c, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingRecallService(new CountingRecallService(), null, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingRecallService(new CountingRecallService(), c, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A {@link RecallService} stub that counts how many times it is actually invoked (the signal for a
     * cache miss) and returns a fresh result per call by default, or a scripted sequence via
     * {@link #respondWith}. The last scripted response repeats once the sequence is exhausted.
     */
    private static final class CountingRecallService implements RecallService {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<RecallResult> scripted = new ArrayList<>();
        private Function<RecallQuery, RecallResult> fallback =
                q -> RecallResult.ofPages(List.of()); // a fresh (distinct) empty result each call

        void respondWith(RecallResult... results) {
            for (RecallResult r : results) {
                scripted.add(r);
            }
        }

        int calls() {
            return calls.get();
        }

        @Override
        public RecallResult search(RecallQuery query) {
            int n = calls.getAndIncrement();
            if (!scripted.isEmpty()) {
                return scripted.get(Math.min(n, scripted.size() - 1));
            }
            return fallback.apply(query);
        }
    }
}
