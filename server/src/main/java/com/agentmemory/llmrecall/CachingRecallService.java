package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A short-TTL, bounded-LRU caching {@link RecallService} decorator (issue #142, Fase 4). It sits in
 * front of the LLM-assisted {@link LlmRecallService} (which itself decorates the hybrid base) and
 * memoizes each {@link RecallResult} so that repeated or reformulated prompts within a session do not
 * redo the whole pipeline (Voyage embedding + DB + cross-encoder). {@code /recall/inject} fires on
 * every prompt and the hook may re-fire, so this is the largest cheap win available.
 *
 * <h2>Key — scope-safe by construction</h2>
 * A cache entry is keyed by {@code (workspace, project, normalize(text), limit)}. The scope slugs are
 * the {@link com.agentmemory.core.WorkspaceId}/{@link com.agentmemory.core.ProjectId} normal form
 * (trimmed, lower-cased), so an entry can <em>never</em> be served across projects — a leak that would
 * be severe is structurally impossible because the scope is part of the key, not a post-filter.
 * {@code normalize} is {@code trim + lowercase + collapse ASCII whitespace runs to a single space}, so
 * cosmetic prompt variants ({@code "  Fix   the BUG "} vs {@code "fix the bug"}) share an entry while
 * genuinely different prompts do not. The caller's {@code limit} is part of the key because a larger
 * limit is a different (larger) result, not a superset view of a smaller one.
 *
 * <h2>Freshness — TTL is the only invalidation</h2>
 * Memory changes only after a consolidation/reindex, and a recall block is advisory, so a short TTL
 * (tens of seconds, {@link LlmRecallProperties.Cache#ttl()}) is enough: a stale entry simply expires on
 * its own. There is deliberately no explicit invalidation hook to get wrong — nothing has to remember
 * to evict after a write. The trade is bounded staleness for one TTL window, which is acceptable for an
 * advisory injection.
 *
 * <h2>Bound + thread-safety</h2>
 * The store is an access-order {@link LinkedHashMap} capped at {@link LlmRecallProperties.Cache#maxEntries()}
 * (LRU eviction past the cap), guarded by a single lock so it stays consistent under the concurrent
 * {@code /recall/inject} and MCP {@code memory_query} callers. The (slow) delegate search runs
 * <em>outside</em> the lock, so a miss never blocks another caller's hit; two concurrent misses for the
 * same key may both compute (a harmless, short-lived duplicate) rather than serialize behind one lock —
 * a deliberate simplicity trade for a short-TTL advisory cache (no single-flight).
 *
 * <h2>Never changes a result</h2>
 * A hit returns the cached {@link RecallResult} object verbatim — its {@code hits}, {@code rawFallback},
 * and {@code calibrated} flags are preserved exactly, so the downstream injection gate behaves
 * identically whether a result was just computed or replayed. The decorator is transparent: with the
 * cache {@link LlmRecallProperties.Cache#enabled() disabled} (or on any non-cacheable call) it simply
 * delegates. Stateless with respect to its delegate; the only state is the bounded cache itself.
 *
 * <p><strong>Reinforcement note.</strong> Because a hit short-circuits the delegate, the delegate's
 * best-effort access-reinforcement side effect ({@link LlmRecallService}'s #24 seam) does not fire on a
 * cache hit. That is intended and harmless: reinforcement is an additive, best-effort bump that already
 * fired on the miss that populated the entry and fires again on the next miss after the entry expires.
 */
public final class CachingRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(CachingRecallService.class);

    private final RecallService delegate;
    private final boolean enabled;
    private final long ttlNanos;
    private final int maxEntries;
    /** Monotonic clock in nanoseconds (real: {@code System::nanoTime}); injectable for hermetic TTL tests. */
    private final LongSupplier nowNanos;

    /** Guards {@link #cache}; held only for the O(1) map ops, never across the delegate search. */
    private final Object lock = new Object();

    /** Access-order LRU bounded to {@link #maxEntries}; all access is under {@link #lock}. */
    private final Map<Key, CacheEntry> cache;

    /**
     * @param delegate the wrapped recall service whose results are memoized; never null. In production
     *     this is the {@link LlmRecallService} (which decorates the hybrid base).
     * @param cfg      the cache tuning (enabled / ttl / maxEntries); never null.
     * @param nowNanos a monotonic nanosecond clock; {@code System::nanoTime} in production, a fake in
     *     tests so TTL expiry is exercised without sleeping.
     */
    public CachingRecallService(RecallService delegate, LlmRecallProperties.Cache cfg, LongSupplier nowNanos) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate RecallService must not be null");
        }
        if (cfg == null) {
            throw new IllegalArgumentException("cache config must not be null");
        }
        if (nowNanos == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.delegate = delegate;
        this.enabled = cfg.enabled();
        this.ttlNanos = cfg.ttl().toNanos();
        this.maxEntries = cfg.maxEntries();
        this.nowNanos = nowNanos;
        // accessOrder=true makes get()/put() move the entry to the MRU end, so removeEldestEntry evicts
        // the genuinely least-recently-used entry once the map exceeds the bound.
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CacheEntry> eldest) {
                return size() > CachingRecallService.this.maxEntries;
            }
        };
        if (enabled) {
            log.debug("Recall result cache enabled: ttl={}, maxEntries={}", cfg.ttl(), maxEntries);
        } else {
            log.debug("Recall result cache disabled; CachingRecallService delegates every search.");
        }
    }

    @Override
    public RecallResult search(RecallQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("recall query must not be null");
        }
        // Disabled => transparent pass-through (the decorator adds nothing), matching the "false makes it
        // a pass-through" config contract.
        if (!enabled) {
            return delegate.search(query);
        }

        Key key = keyFor(query);
        long now = nowNanos.getAsLong();

        synchronized (lock) {
            CacheEntry hit = cache.get(key); // touches access order on a live hit
            if (hit != null) {
                if (now - hit.storedAtNanos() < ttlNanos) {
                    return hit.result(); // fresh: replay the cached result, delegate untouched
                }
                cache.remove(key); // expired: drop it and fall through to recompute
            }
        }

        // Miss (or expired): compute OUTSIDE the lock — the delegate search is the slow part (embedding +
        // DB + cross-encoder) and must never block another caller holding the lock for a hit.
        RecallResult result = delegate.search(query);

        synchronized (lock) {
            // Stamp freshness as of when this search began (now); a marginally conservative TTL start.
            cache.put(key, new CacheEntry(result, now));
        }
        return result;
    }

    /** @return the wrapped delegate (the LLM-assisted service in production); used by wiring tests to unwrap. */
    public RecallService delegate() {
        return delegate;
    }

    private static Key keyFor(RecallQuery query) {
        Scope scope = query.scope();
        return new Key(scope.workspaceSlug(), scope.projectSlug(), normalize(query.text()), query.limit());
    }

    /**
     * Normal form for a prompt as a cache key: {@code trim}, collapse runs of ASCII whitespace to a
     * single space, then lower-case (ROOT locale). Cosmetic variants of the same prompt collapse to one
     * key; semantically different prompts stay distinct.
     */
    static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The scope-qualified, normalized, limit-bound cache key. Records give value equality for free. */
    private record Key(String workspace, String project, String text, int limit) {}

    /** A cached result plus the nanosecond timestamp it was stored at (for TTL expiry). */
    private record CacheEntry(RecallResult result, long storedAtNanos) {}
}
