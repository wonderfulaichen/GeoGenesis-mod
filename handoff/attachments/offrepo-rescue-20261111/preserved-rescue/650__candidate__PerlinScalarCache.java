package com.geogenesis.worldgen.terrain.candidate;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Minimal synchronized weak-keyed cache of {@link DoublePerlinCandidate} wrappers.
 *
 * <h3>What was wrong before</h3>
 * <p>The cache used to be {@code Collections.synchronizedMap(new IdentityHashMap<>())}: strong
 * keys, strong values, no removal. The keys are real {@link RefNoise.Perlin} instances owned by a
 * {@code DoubleFullMorphLand} graph, so <b>every constructed instance leaked its whole Perlin set
 * into a process-wide static map, permanently</b>: +31 live entries per instance, strictly monotone
 * across rounds, with repeated {@code System.gc()} releasing nothing.</p>
 *
 * <h3>Why a plain {@link WeakHashMap} is sufficient here</h3>
 * <p>{@link WeakHashMap} compares keys with {@code equals}/{@code hashCode}. That is identity
 * semantics <b>only as long as the key type does not override them</b>, so this class depends on a
 * real, checkable property of {@link RefNoise.Perlin} rather than on an accident:</p>
 * <ul>
 *   <li>{@code RefNoise.Perlin} is a {@code final class} that inherits {@code Object.equals} and
 *       {@code Object.hashCode}. {@code ConcPerlinIdentityContract} asserts this and fails if a
 *       future edit ever adds them — at which point parameter-equal instances would silently start
 *       sharing one cache entry, so that contract test is the guard, not this comment.</li>
 *   <li>Values must not reach their keys, or the strong value would keep the weak key alive and
 *       the map would degenerate into a strong one. {@link DoublePerlinCandidate} copies its seven
 *       parameters out of the {@code Perlin} in its constructor and keeps no reference back;
 *       {@code ConcCycleAudit} walks the real cached values and asserts zero such cycles.</li>
 * </ul>
 *
 * <h3>Why no entry ceiling</h3>
 * <p>Weak keying already ties each entry's lifetime to its owner graph, which is the leak that
 * mattered. A fixed ceiling was measured to be actively harmful: one owner graph holds <b>181</b>
 * distinct {@code Perlin} instances, so twelve live owners reference 2172 keys, and a ceiling of
 * 512 produced 9704 evictions against 10216 misses — nearly every miss was an eviction rather than
 * a first touch.</p>
 *
 * <h3>Concurrency</h3>
 * <p>All map access is serialized on the map instance, so this cache is internally consistent and
 * each key is constructed exactly once. The mapping work happens under that lock, which is coarse;
 * it is acceptable because wrapper construction is a one-time per-key event. This is explicitly
 * <b>not</b> a claim that downstream consumers of the land/sea chain become thread-safe — the
 * land/sea classes keep their own monitor for their own state.</p>
 *
 * <p>Note on removal: {@link WeakHashMap} expunges stale entries during its own operations
 * ({@code get}, {@code size}, ...), so no reference-queue draining is needed here.</p>
 */
final class PerlinScalarCache {

    private final Map<RefNoise.Perlin, DoublePerlinCandidate> map = new WeakHashMap<>();

    private long hits, misses;

    /**
     * Returns the wrapper for {@code key}, building it if absent. Never returns {@code null}.
     *
     * <p>Identity semantics are inherited from {@link RefNoise.Perlin} not overriding
     * {@code equals}/{@code hashCode}; see the class comment.</p>
     */
    DoublePerlinCandidate get(RefNoise.Perlin key) {
        if (key == null) throw new IllegalArgumentException("key");
        synchronized (map) {
            DoublePerlinCandidate cached = map.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }
            misses++;
            DoublePerlinCandidate fresh = new DoublePerlinCandidate(key);
            map.put(key, fresh);
            return fresh;
        }
    }

    /** Live entries now. {@link WeakHashMap#size()} expunges entries whose key has been collected. */
    int liveEntries() {
        synchronized (map) {
            return map.size();
        }
    }

    long hitCount() {
        synchronized (map) { return hits; }
    }

    long missCount() {
        synchronized (map) { return misses; }
    }

    @Override
    public String toString() {
        synchronized (map) {
            return "PerlinScalarCache(size=" + map.size()
                    + " hits=" + hits + " misses=" + misses + ")";
        }
    }
}
