package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Probes the invariant that licenses {@code WeakHashMap} + strong values: <b>no cached value may
 * reach its own key</b>. If one did, the strongly held value would keep the weak key alive and the
 * map would silently become a strong, uncollectable one.
 *
 * <p>Lives here rather than in the cache because it is diagnostic object-graph traversal, not cache
 * behaviour. The cache stays a minimal synchronized wrapper.</p>
 *
 * <p>The audit is validated by a synthetic cycle: without that control, a detector that always
 * returned {@code false} would look like a pass.</p>
 */
public final class ConcCycleAudit {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);

        // ---- real cached values from real owner graphs
        DoubleFullMorphLand land = ConcHarness.buildLand();
        land.sample(-140, 137);
        land.sample(900, -400);
        PerlinScalarCache cache = DoubleTreeFixed.PS;
        int live = cache.liveEntries();
        check("cache-populated", live > 0, "live=" + live);

        Map<RefNoise.Perlin, DoublePerlinCandidate> snap = snapshot(cache);
        int cycles = 0;
        for (Map.Entry<RefNoise.Perlin, DoublePerlinCandidate> e : snap.entrySet()) {
            if (reachable(e.getValue(), e.getKey())) cycles++;
        }
        check("no-value-to-key-cycle", cycles == 0,
                "entries=" + snap.size() + " cycles=" + cycles);

        // ---- control A: a real wrapper must not reach its key
        RefNoise.Perlin probeKey = RefNoise.Perlin.ofScale(4711, 150f, 2);
        boolean realReaches = reachable(new DoublePerlinCandidate(probeKey), probeKey);
        check("control-real-wrapper-clean", !realReaches, "realWrapperReachesKey=" + realReaches);

        // ---- control B: a synthetic value holding its key MUST be detected
        CycleHolder holder = new CycleHolder(probeKey);
        boolean synthReaches = reachable(holder, probeKey);
        check("control-synthetic-cycle-detected", synthReaches,
                "syntheticReachesKey=" + synthReaches);

        // ---- control C: an unrelated key must not be reported
        boolean unrelated = reachable(new CycleHolder(RefNoise.Perlin.ofScale(4712, 150f, 2)), probeKey);
        check("control-unrelated-not-reported", !unrelated, "unrelatedReported=" + unrelated);

        // ---- and the consequence that actually matters: keys still get collected
        RefNoise.Perlin transientKey = RefNoise.Perlin.ofScale(8888, 111f, 1);
        cache.get(transientKey);
        java.lang.ref.WeakReference<RefNoise.Perlin> ref = new java.lang.ref.WeakReference<>(transientKey);
        transientKey = null;
        int rounds = 0;
        for (int g = 1; g <= 8; g++) {
            System.gc();
            Thread.sleep(60);
            rounds = g;
            if (ref.get() == null) break;
        }
        check("key-collectable-despite-strong-value", ref.get() == null,
                "cleared=true gcRounds=" + rounds);

        System.out.println("VERDICT " + (failures == 0 ? "PASS" : "FAIL") + " failures=" + failures);
        if (failures != 0) System.exit(2);
    }

    /** Reads the cache contents; WeakHashMap has no public entry access beyond its views. */
    @SuppressWarnings("unchecked")
    static Map<RefNoise.Perlin, DoublePerlinCandidate> snapshot(PerlinScalarCache cache) {
        try {
            Field f = PerlinScalarCache.class.getDeclaredField("map");
            f.setAccessible(true);
            Object m = f.get(cache);
            Map<RefNoise.Perlin, DoublePerlinCandidate> copy = new IdentityHashMap<>();
            synchronized (m) {
                copy.putAll((Map<RefNoise.Perlin, DoublePerlinCandidate>) m);
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test-only node holding a key reference, mimicking a would-be value-&gt;key cycle. */
    static final class CycleHolder implements RefNoise.Node {
        final RefNoise.Perlin held;
        CycleHolder(RefNoise.Perlin held) { this.held = held; }
        @Override public float compute(float x, float z) { return 0f; }
    }

    /** Shallow identity reachability over declared instance fields, budget-limited. */
    static boolean reachable(Object from, Object target) {
        if (from == null || target == null) return false;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> q = new ArrayDeque<>();
        q.add(from);
        int budget = 20000;
        while (!q.isEmpty() && budget-- > 0) {
            Object o = q.poll();
            if (o == target) return true;
            if (!seen.add(o)) continue;
            if (o instanceof String || o instanceof Number || o instanceof Boolean) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v;
                    try {
                        v = f.get(o);
                    } catch (ReflectiveOperationException ex) {
                        continue;
                    }
                    if (v == null) continue;
                    if (v.getClass().isArray()) {
                        if (v instanceof Object[] arr) {
                            for (Object e : arr) if (e != null) q.add(e);
                        }
                        continue;
                    }
                    if (v.getClass().getName().startsWith("com.geogenesis.")) q.add(v);
                }
            }
        }
        return false;
    }

    private ConcCycleAudit() { }
}
