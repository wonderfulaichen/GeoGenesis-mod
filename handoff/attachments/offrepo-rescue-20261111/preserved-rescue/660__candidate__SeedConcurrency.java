package com.geogenesis.worldgen.terrain.candidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Cross-seed concurrency / determinism harness for the wired chain.
 *
 * <ol>
 *   <li><b>Shared-instance threads</b> on every seed: 8 threads over overlapping coordinates must
 *       reproduce the single-thread reference bit for bit, and stream order must not matter.</li>
 *   <li><b>Tile eviction order</b>: the same coordinate reached with a cold cache vs. a cache that
 *       has churned past {@code CACHE_TILES} entries must give identical bits.</li>
 *   <li><b>Interleaved seeds</b>: several seeds sampled alternately on separate instances must agree
 *       with each seed's own isolated run (no shared static state in the chain).</li>
 * </ol>
 *
 * <p>Read-only with respect to geometry / cache / concurrency code: it only observes the wiring.</p>
 */
public final class SeedConcurrency {

    private static final long[] SEEDS = {12345L, 0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
    private static int blockers;

    public static void main(String[] a) throws Exception {
        Locale.setDefault(Locale.ROOT);
        System.out.println("stage,seed,result,detail");
        for (long seed : SEEDS) {
            sharedInstance(seed);
            evictionOrder(seed);
        }
        interleaved();
        System.out.println("BLOCKERS=" + blockers);
        System.exit(blockers == 0 ? 0 : 2);
    }

    // ---------------------------------------------------------------- 1) shared instance

    private static void sharedInstance(long seed) throws Exception {
        DoubleFullMorphLand reference = new DoubleFullMorphLand(new RecipeMorphCatalog(seed));
        DoubleFullMorphLand shared = new DoubleFullMorphLand(new RecipeMorphCatalog(seed));
        int n = 12;
        double[] xs = new double[n], zs = new double[n];
        long[] refBits = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * 1024.0 + 31.0;
            zs[i] = 43.0 + (i % 3) * 1024.0;
            double v = reference.e(xs[i], zs[i]);
            if (!Double.isFinite(v)) {
                record("shared", seed, "FAIL", "non-finite reference at " + xs[i] + "," + zs[i]);
                return;
            }
            refBits[i] = Double.doubleToLongBits(v);
        }
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        var failures = java.util.Collections.synchronizedList(new ArrayList<String>());
        try {
            for (int t = 0; t < 8; t++) {
                final int id = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int k = 0; k < 240; k++) {
                        int i = (k + id * 3) % n;
                        double v = shared.e(xs[i], zs[i]);
                        if (Double.doubleToLongBits(v) != refBits[i]) {
                            failures.add("thread " + id + " point " + i + ": " + v);
                        }
                        shared.stats();
                        shared.dominantHistogram();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) f.get(300, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        DoubleFullMorphLand.Stats st = shared.stats();
        boolean counters = st.points() + st.cachedPoints() == 8L * 240
                && sum(shared.dominantHistogram()) == st.points();
        if (!failures.isEmpty()) {
            record("shared", seed, "FAIL", failures.size() + " bit mismatches: " + failures.get(0));
        } else if (!counters) {
            record("shared", seed, "FAIL", "counter integrity points=" + st.points()
                    + " cached=" + st.cachedPoints() + " hist=" + sum(shared.dominantHistogram()));
        } else {
            record("shared", seed, "PASS", "8x240 samples bit-identical, counters exact, tilesCached="
                    + st.tilesCached() + " evictions=" + st.tileEvictions());
        }
    }

    // ---------------------------------------------------------------- 2) eviction order

    private static void evictionOrder(long seed) {
        DoubleFullMorphLand cold = new DoubleFullMorphLand(new RecipeMorphCatalog(seed));
        double x = 31.0, z = 43.0;
        long coldBits = Double.doubleToLongBits(cold.e(x, z));
        DoubleFullMorphLand warm = new DoubleFullMorphLand(new RecipeMorphCatalog(seed));
        for (int i = 0; i < DoubleFullMorphLand.CACHE_TILES * 3; i++) {
            warm.e(i * 1024.0 + 31.0, -i * 1024.0 + 43.0);
        }
        long warmBits = Double.doubleToLongBits(warm.e(x, z));
        DoubleFullMorphLand.Stats st = warm.stats();
        if (coldBits == warmBits) {
            record("eviction", seed, "PASS", "cold==warm bits, evictions=" + st.tileEvictions()
                    + " cached=" + st.tilesCached());
        } else {
            record("eviction", seed, "FAIL", "cold=" + Double.longBitsToDouble(coldBits)
                    + " warm=" + Double.longBitsToDouble(warmBits));
        }
    }

    // ---------------------------------------------------------------- 3) interleaved seeds

    private static void interleaved() {
        int n = 24;
        Map<Long, double[]> isolated = new LinkedHashMap<>();
        for (long seed : SEEDS) {
            DoubleFullMorphLand land = new DoubleFullMorphLand(new RecipeMorphCatalog(seed));
            double[] v = new double[n];
            for (int i = 0; i < n; i++) v[i] = land.e(i * 700.0 - 4000, i * 430.0 - 2500);
            isolated.put(seed, v);
        }
        Map<Long, DoubleFullMorphLand> live = new LinkedHashMap<>();
        for (long seed : SEEDS) live.put(seed, new DoubleFullMorphLand(new RecipeMorphCatalog(seed)));
        int mismatch = 0;
        String first = "";
        for (int i = 0; i < n; i++) {
            for (long seed : SEEDS) {
                double v = live.get(seed).e(i * 700.0 - 4000, i * 430.0 - 2500);
                if (Double.doubleToLongBits(v) != Double.doubleToLongBits(isolated.get(seed)[i])) {
                    if (mismatch++ == 0) first = "seed " + seed + " index " + i;
                }
            }
        }
        if (mismatch == 0) {
            record("interleaved", 0L, "PASS", SEEDS.length + " instances x " + n + " coords, no crosstalk");
        } else {
            record("interleaved", 0L, "FAIL", mismatch + " mismatches, first: " + first);
        }
    }

    private static void record(String stage, long seed, String result, String detail) {
        if (result.equals("FAIL")) blockers++;
        System.out.printf(Locale.ROOT, "%s,%d,%s,%s%n", stage, seed, result, detail);
    }

    private static long sum(int[] a) {
        long s = 0;
        for (int v : a) s += v;
        return s;
    }

    private SeedConcurrency() { }
}
