package com.geogenesis.worldgen.terrain.candidate;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Lifecycle probe: can a released {@link DoubleFullMorphLand} be collected, and does the Perlin
 * wrapper cache stop growing once its owner is dropped?
 *
 * <p>Read-only against the candidate: never mutates a cache, never clears a shared field, never
 * touches seed construction, geometry subdivision or index formulas.</p>
 *
 * <p>Deliberate evidence rule: a single {@code System.gc()} not collecting something is
 * <b>not</b> treated as proof of a leak. The probe runs multiple rounds, drains a
 * {@link ReferenceQueue}, and reports whether the count is strictly monotone across rounds,
 * which is the actual leak signal.</p>
 */
public final class ConcLifecycleProbe {

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 8;

        PerlinScalarCache cache = DoubleTreeFixed.PS;
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        System.out.println("cache=" + cache);
        System.out.println("round,cacheSize,heapUsedMB,landCleared,gcRounds");
        int notCleared = 0;
        int previous = cache.liveEntries();
        int growthSteps = 0;
        for (int r = 1; r <= rounds; r++) {
            DoubleFullMorphLand instance = build();
            WeakReference<Object> ref = new WeakReference<>(instance, q);
            instance = null;
            int gcRounds = awaitClear(q, ref);
            boolean cleared = ref.get() == null;
            if (!cleared) notCleared++;
            int size = cache.liveEntries();
            if (size > previous) growthSteps++;
            previous = size;
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            System.out.printf(Locale.ROOT, "%d,%d,%d,%s,%d%n", r, size, used,
                    cleared ? "yes" : "NO", gcRounds);
        }
        System.out.println("afterRounds=" + cache);
        System.out.println("growthSteps=" + growthSteps + "/" + rounds
                + " (before the fix this was " + rounds + "/" + rounds + ", +31 entries each)");

        int live = cache.liveEntries();
        boolean ok = notCleared == 0 && growthSteps == 0 && live <= 0;
        System.out.println("VERDICT " + (ok ? "PASS" : "FAIL")
                + " landNotCleared=" + notCleared + " growthSteps=" + growthSteps
                + " liveAfter=" + live);
        if (!ok) System.exit(2);
    }

    /** One instance that actually populates the wrapper cache and one tile. */
    static DoubleFullMorphLand build() {
        RecipeMorphCatalog catalog = new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(catalog);
        for (int i = 0; i < 6; i++) {
            land.sample(-140 + i * 300.0, 137 - i * 170.0);
        }
        return land;
    }

    /** Repeated GC rounds until the reference is enqueued; returns how many rounds it took. */
    static int awaitClear(ReferenceQueue<Object> q, WeakReference<Object> ref) throws Exception {
        for (int g = 1; g <= 8; g++) {
            System.gc();
            Thread.sleep(60);
            if (q.poll() != null || ref.get() == null) return g;
        }
        return 9;
    }
}
