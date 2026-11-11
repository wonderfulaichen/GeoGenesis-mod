package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency / lifecycle harness for the double-coordinate land+sea chain.
 *
 * <p>Read-only against the candidate: exercises {@link DoubleFullMorphLand},
 * {@link DoubleOceanAssemblyCandidate} and {@link DoubleTreeFixed#PS} but never edits them.
 * Owns only its own instances. Does not touch seed construction, geometry subdivision or
 * index formulas.</p>
 *
 * <p>Every stage prints one CSV row so a before/after diff is machine-comparable. Negative
 * controls are mandatory: they must FAIL, which proves the comparisons actually bite.</p>
 */
public final class ConcHarness {

    // ------------------------------------------------------------------ fixture

    /** Fixed probe grid: spans several 1024-block tiles and 2 tile columns. */
    static double[][] points(int n) {
        double[][] p = new double[n][2];
        // Deterministic spread over x,z in [-2600, 2600]; crosses tile lines at -1024/0/1024.
        long s = 0x5DEECE66DL;
        for (int i = 0; i < n; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            double ux = ((s >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            s = s * 6364136223846793005L + 1442695040888963407L;
            double uz = ((s >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            p[i][0] = -2600 + ux * 5200;
            p[i][1] = -2600 + uz * 5200;
        }
        return p;
    }

    static final class Bundle {
        final RecipeMorphCatalog catalog;
        final DoubleFullMorphLand land;
        final DoubleOceanAssemblyCandidate ocean;
        Bundle(long seed) {
            catalog = new RecipeMorphCatalog(seed);
            land = new DoubleFullMorphLand(catalog);
            ocean = new DoubleOceanAssemblyCandidate(land::e, ConcHarness::continent, seed + 3)
                    .enableCoastalDetail(seed);
        }
        Bundle(LandRegionContext context) {
            catalog = context.catalog();
            land = new DoubleFullMorphLand(context);
            ocean = new DoubleOceanAssemblyCandidate(land::e, ConcHarness::continent, context.seed() + 3)
                    .enableCoastalDetail(context.seed());
        }
    }

    /** Stand-in continent sampler: deterministic, pure, no shared state. */
    static double continent(double x, double z) {
        double a = Math.sin(x * 0.000913) * Math.cos(z * 0.001177);
        double b = Math.sin((x + z) * 0.000271);
        return Math.max(-1, Math.min(1, a * 0.8 + b * 0.35));
    }

    /** A bare land instance (no ocean wrapper), used by cache-shape diagnostics. */
    static DoubleFullMorphLand buildLand() {
        return new DoubleFullMorphLand(new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED));
    }

    // ------------------------------------------------------------------ value tuple

    /** One observation of the full land+sea chain, as exact bits. */
    static final class Obs {
        final long e, region, covered, mask, oceanE;
        Obs(Bundle b, double x, double z) {
            e = bits(b.land.e(x, z));
            region = bits(b.land.regionHeight(x, z));
            covered = bits(b.land.coveredHeight(x, z));
            mask = bits(b.land.mask(x, z));
            oceanE = bits(b.ocean.e(x, z));
        }
        Obs(long e, long region, long covered, long mask, long oceanE) {
            this.e = e; this.region = region; this.covered = covered;
            this.mask = mask; this.oceanE = oceanE;
        }
        boolean sameInvariants(Obs o) {
            return e == o.e && region == o.region && covered == o.covered
                    && mask == o.mask && oceanE == o.oceanE;
        }
        /** Bit-compare of the mask+land half only (mask is the only path that bypasses the monitor). */
        boolean sameMaskHalf(Obs o) { return mask == o.mask && e == o.e && region == o.region; }
        String diff(Obs o) {
            StringBuilder sb = new StringBuilder();
            if (e != o.e) sb.append("e ");
            if (region != o.region) sb.append("region ");
            if (covered != o.covered) sb.append("covered ");
            if (mask != o.mask) sb.append("mask ");
            if (oceanE != o.oceanE) sb.append("oceanE ");
            return sb.toString().trim();
        }
    }

    static long bits(double d) { return Double.doubleToRawLongBits(d); }

    // ------------------------------------------------------------------ report

    static final StringBuilder CSV = new StringBuilder();
    static int failures = 0;

    static void row(String... cells) {
        CSV.append(String.join(",", cells)).append('\n');
        System.out.println(String.join(",", cells));
    }

    static void fail(String stage, String detail) {
        failures++;
        row("FAIL", stage, detail.replace(',', ';'));
    }

    // ------------------------------------------------------------------ stage 1: reference

    static Obs[] reference(Bundle b, double[][] pts) {
        Obs[] ref = new Obs[pts.length];
        for (int i = 0; i < pts.length; i++) ref[i] = new Obs(b, pts[i][0], pts[i][1]);
        return ref;
    }

    // ------------------------------------------------------------------ stage 2/3: concurrency

    /** Runs one worker set. Each worker owns a Bundle or shares {@code shared}. */
    static long[] runWorkers(String stage, int threads, int rounds, double[][] pts, Obs[] ref,
                             Bundle shared, boolean independent) throws Exception {
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger exceptions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        ConcurrentLinkedQueue<String> firstError = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        AtomicLong elapsed = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                Bundle mine = independent ? new Bundle(RecipeMorphCatalog.GALLERY_SEED) : shared;
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    for (int r = 0; r < rounds; r++) {
                        // Rotate the start offset so workers collide on points at different times.
                        for (int k = 0; k < pts.length; k++) {
                            int i = (k + id * 37 + r * 11) % pts.length;
                            try {
                                Obs got = new Obs(mine, pts[i][0], pts[i][1]);
                                evaluations.incrementAndGet();
                                if (!got.sameInvariants(ref[i])) {
                                    mismatches.incrementAndGet();
                                    if (firstError.size() < 8) {
                                        firstError.add("i=" + i + " diff=" + got.diff(ref[i]));
                                    }
                                }
                            } catch (RuntimeException ex) {
                                exceptions.incrementAndGet();
                                if (firstError.size() < 8) {
                                    firstError.add("i=" + i + " " + ex.getClass().getSimpleName()
                                            + ":" + ex.getMessage());
                                }
                            }
                        }
                    }
                    elapsed.addAndGet(System.nanoTime() - t0);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    exceptions.incrementAndGet();
                    if (firstError.size() < 8) firstError.add("setup " + ex);
                }
            }, "w" + t);
            th.setDaemon(true);
            pool.add(th);
            th.start();
        }
        long wall0 = System.nanoTime();
        start.countDown();
        for (Thread th : pool) th.join();
        long wall = System.nanoTime() - wall0;

        long ms = elapsed.get() / 1_000_000L;
        long eps = ms > 0 ? evaluations.get() * 1000L / ms : -1;
        row("STAGE", stage, "threads=" + threads, "rounds=" + rounds,
                "evals=" + evaluations.get(), "mismatches=" + mismatches.get(),
                "exceptions=" + exceptions.get(), "wallMs=" + (wall / 1_000_000L),
                "threadMs=" + ms, "evalsPerSecPerThread=" + eps);
        int i = 0;
        for (String s : firstError) { row("ERR", stage, s.replace(',', ';')); if (++i >= 8) break; }
        if (mismatches.get() != 0) fail(stage, "mismatch=" + mismatches.get());
        if (exceptions.get() != 0) fail(stage, "exceptions=" + exceptions.get());
        return new long[]{elapsed.get(), evaluations.get(), mismatches.get(), exceptions.get()};
    }

    // ------------------------------------------------------------------ stage 4: LRU

    static void stageLru() {
        Bundle b = new Bundle(RecipeMorphCatalog.GALLERY_SEED);
        int cap = DoubleFullMorphLand.CACHE_TILES;
        // 16 distinct tiles on a 4x4 lattice well inside the harness window.
        List<double[]> tiles = new ArrayList<>();
        for (int tx = -2; tx <= 1; tx++) {
            for (int tz = -2; tz <= 1; tz++) {
                tiles.add(new double[]{DoubleFullMorphLand.center(tx) + 13.5,
                        DoubleFullMorphLand.center(tz) - 7.25});
            }
        }
        double[][] pts = tiles.toArray(new double[0][]);
        Obs[] ref = reference(b, pts);

        int maxCached = 0;
        for (int i = 0; i < pts.length; i++) {
            b.land.sample(pts[i][0], pts[i][1]);
            b.land.stats();
            DoubleFullMorphLand.Stats s = b.land.stats();
            maxCached = Math.max(maxCached, s.tilesCached());
        }
        DoubleFullMorphLand.Stats afterFill = b.land.stats();
        // Recompute check: every tile was evicted at least once (16 tiles > cap 8) except the tail.
        Obs[] again = reference(b, pts);
        int rebuiltMismatch = 0;
        for (int i = 0; i < ref.length; i++) if (!again[i].sameInvariants(ref[i])) rebuiltMismatch++;
        DoubleFullMorphLand.Stats afterRe = b.land.stats();

        row("STAGE", "lru-bound", "cap=" + cap, "distinctTiles=" + pts.length,
                "maxTilesCached=" + maxCached, "tilesBuilt=" + afterRe.tilesBuilt(),
                "tileEvictions=" + afterRe.tileEvictions(),
                "cachedAfter=" + afterRe.tilesCached(),
                "evictEqualsBuiltMinusCached=" + (afterRe.tileEvictions() == afterRe.tilesBuilt() - afterRe.tilesCached()),
                "recomputeMismatch=" + rebuiltMismatch);
        if (maxCached > cap) fail("lru-bound", "tilesCached " + maxCached + " > " + cap);
        if (afterRe.tileEvictions() != afterRe.tilesBuilt() - afterRe.tilesCached()) {
            fail("lru-bound", "eviction accounting inconsistent");
        }
        if (rebuiltMismatch != 0) fail("lru-bound", "recompute after eviction changed values: " + rebuiltMismatch);
        if (afterFill.tilesCached() != Math.min(cap, 16)) {
            fail("lru-bound", "cache not saturated to cap: " + afterFill.tilesCached());
        }
    }

    // ------------------------------------------------------------------ stage 5: multi-seed

    static void stageSeeds() {
        double[][] pts = points(12);
        long[] seeds = {0L, -419L, 12345L};
        Bundle[] bundles = new Bundle[seeds.length];
        LandRegionContext[] ctxs = new LandRegionContext[seeds.length];
        for (int i = 0; i < seeds.length; i++) {
            ctxs[i] = new LandRegionContext(seeds[i], 600f);
            bundles[i] = new Bundle(ctxs[i]);
        }
        Obs[][] solo = new Obs[seeds.length][];
        for (int i = 0; i < seeds.length; i++) solo[i] = reference(bundles[i], pts);

        // Concurrency: 6 threads, each keeps sampling a rotating bundle.
        AtomicInteger cross = new AtomicInteger();
        AtomicInteger exc = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    for (int r = 0; r < 3; r++) {
                        for (int k = 0; k < pts.length; k++) {
                            int si = (id + r + k) % seeds.length;
                            Obs got = new Obs(bundles[si], pts[k][0], pts[k][1]);
                            if (!got.sameInvariants(solo[si][k])) cross.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    exc.incrementAndGet();
                }
            }, "seed" + t);
            th.setDaemon(true);
            pool.add(th);
            th.start();
        }
        go.countDown();
        try { for (Thread th : pool) th.join(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        int distinct = 0;
        for (int i = 1; i < seeds.length; i++) if (solo[i][0].e != solo[0][0].e) distinct++;
        row("STAGE", "multi-seed", "seeds=" + seeds.length, "pts=" + pts.length,
                "crossSeedMismatch=" + cross.get(), "exceptions=" + exc.get(),
                "distinctFromSeed0=" + distinct);
        if (cross.get() != 0) fail("multi-seed", "cross-seed contamination=" + cross.get());
        if (exc.get() != 0) fail("multi-seed", "exceptions=" + exc.get());
        if (distinct == 0) fail("multi-seed", "seeds indistinguishable -> isolation test is vacuous");
    }

    // ------------------------------------------------------------------ stage 6: cancel/retry

    static void stageCancelRetry() throws Exception {
        double[][] pts = points(48);
        Bundle shared = new Bundle(RecipeMorphCatalog.GALLERY_SEED);
        Obs[] ref = reference(shared, pts);

        AtomicInteger tried = new AtomicInteger();
        AtomicInteger interruptedMid = new AtomicInteger();
        AtomicInteger postCancelMismatch = new AtomicInteger();
        AtomicInteger postCancelExc = new AtomicInteger();
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int round = 0; round < 6; round++) {
                    for (int k = 0; k < pts.length; k++) {
                        tried.incrementAndGet();
                        // Cancel roughly midway through each round.
                        if (k == pts.length / 2 && (round + id) % 2 == 0) {
                            Thread.currentThread().interrupt();
                            interruptedMid.incrementAndGet();
                            if (Thread.interrupted()) {
                                // Abandon this round, then retry below.
                                break;
                            }
                        }
                        try {
                            Obs got = new Obs(shared, pts[k][0], pts[k][1]);
                            if (!got.sameInvariants(ref[k])) postCancelMismatch.incrementAndGet();
                        } catch (RuntimeException ex) {
                            postCancelExc.incrementAndGet();
                        }
                    }
                }
                // Retry phase: clear the flag and re-verify the whole grid.
                Thread.interrupted();
                for (int k = 0; k < pts.length; k++) {
                    try {
                        Obs got = new Obs(shared, pts[k][0], pts[k][1]);
                        if (!got.sameInvariants(ref[k])) postCancelMismatch.incrementAndGet();
                    } catch (RuntimeException ex) {
                        postCancelExc.incrementAndGet();
                    }
                }
            }, "cancel" + t);
            th.setDaemon(true);
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) th.join();

        row("STAGE", "cancel-retry", "workers=4", "tried=" + tried.get(),
                "cancelsIssued=" + interruptedMid.get(),
                "mismatchAfter=" + postCancelMismatch.get(),
                "exceptions=" + postCancelExc.get());
        if (postCancelMismatch.get() != 0) fail("cancel-retry", "values drifted after cancel: " + postCancelMismatch.get());
        if (postCancelExc.get() != 0) fail("cancel-retry", "exceptions: " + postCancelExc.get());
    }

    // ------------------------------------------------------------------ stage 7: PS cache size

    static int psSize() {
        return DoubleTreeFixed.PS.liveEntries();
    }

    static String psDetail() {
        PerlinScalarCache c = DoubleTreeFixed.PS;
        return "hits=" + c.hitCount() + " misses=" + c.missCount();
    }

    static void stagePs(int rounds) {
        int before = psSize();
        int[] curve = new int[rounds + 1];
        curve[0] = before;
        for (int r = 1; r <= rounds; r++) {
            Bundle b = new Bundle(RecipeMorphCatalog.GALLERY_SEED);
            b.land.sample(-140, 137);
            b.land.sample(900, -400);
            // Drop the owner, then force a reclaim so the curve measures live retention, not
            // "whenever the GC happened to run".
            b = null;
            System.gc();
            curve[r] = psSize();
        }
        StringBuilder sb = new StringBuilder();
        int monotone = 0;
        for (int r = 1; r <= rounds; r++) {
            sb.append(curve[r]).append(r < rounds ? '|' : "");
            if (curve[r] > curve[r - 1]) monotone++;
        }
        int live = psSize();
        row("STAGE", "ps-cache-curve", "before=" + before, "after=" + curve[rounds],
                "growth=" + (curve[rounds] - before),
                "monotoneSteps=" + monotone + "/" + rounds,
                "liveNow=" + live, "detail=" + psDetail().replace(',', ';'), "curve=" + sb);
        // A leak signal is monotone growth sustained across many rounds. One round cannot show a
        // trend, so the check only applies when there are at least three of them.
        if (rounds >= 3 && monotone == rounds) fail("ps-cache-curve", "cache grew on every round: " + sb);
        if (live < 0) fail("ps-cache-curve", "negative size: " + live);
    }

    // ------------------------------------------------------------------ stage 8: negative controls

    /** Negative controls. Each MUST be detected; an undetected control is itself a failure. */
    static void stageNegative() {
        int detected = 0, total = 0;

        // NC1: perturb one bit of a reference observation -> comparison must bite.
        Bundle b = new Bundle(RecipeMorphCatalog.GALLERY_SEED);
        Obs good = new Obs(b, -140, 137);
        Obs bad = new Obs(good.e ^ 1L, good.region, good.covered, good.mask, good.oceanE);
        total++;
        if (!good.sameInvariants(bad)) detected++;
        else fail("neg-control", "bit flip in e not detected");

        // NC2: two bundles from different seeds must differ at a point where terrain differs.
        Bundle b2 = new Bundle(new LandRegionContext(12345L, 600f));
        total++;
        int differing = 0;
        double[][] pts = points(16);
        for (double[] p : pts) {
            if (bits(b.land.e(p[0], p[1])) != bits(b2.land.e(p[0], p[1]))) differing++;
        }
        if (differing > 0) detected++;
        else fail("neg-control", "different seeds produced identical e everywhere -> seed isolation untestable");

        // NC3: mask() must not equal e() generically (guards against a harness that compares nothing).
        total++;
        int maskEqualsE = 0;
        for (double[] p : pts) {
            if (bits(b.land.mask(p[0], p[1])) == bits(b.land.e(p[0], p[1]))) maskEqualsE++;
        }
        if (maskEqualsE < pts.length) detected++;
        else fail("neg-control", "mask always equals e -> observation tuple is degenerate");

        // NC4: LRU bound control - a capacity-1 map must report evictions, proving the accounting works.
        total++;
        java.util.Map<Long, Object> probe = new java.util.LinkedHashMap<>(4, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Object> eldest) {
                return size() > 1;
            }
        };
        for (long k = 0; k < 5; k++) probe.put(k, k);
        if (probe.size() == 1) detected++;
        else fail("neg-control", "capacity-1 LRU kept " + probe.size());

        row("STAGE", "neg-control", "detected=" + detected + "/" + total);
        if (detected != total) fail("neg-control", detected + "/" + total + " detected");
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String mode = args.length > 0 ? args[0] : "all";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int pts = args.length > 3 ? Integer.parseInt(args[3]) : 24;
        double[][] grid = points(pts);

        row("HARNESS", "start", "mode=" + mode, "threads=" + threads, "rounds=" + rounds,
                "points=" + pts, "cpus=" + Runtime.getRuntime().availableProcessors(),
                "psSize=" + psSize());

        if (mode.equals("all") || mode.equals("conc")) {
            Bundle shared = new Bundle(RecipeMorphCatalog.GALLERY_SEED);
            Obs[] ref = reference(shared, grid);
            runWorkers("shared-instance", threads, rounds, grid, ref, shared, false);
            runWorkers("independent-instances", threads, rounds, grid, ref, null, true);
            shared = null;
        }
        if (mode.equals("all") || mode.equals("lru")) stageLru();
        if (mode.equals("all") || mode.equals("seed")) stageSeeds();
        if (mode.equals("all") || mode.equals("cancel")) stageCancelRetry();
        if (mode.equals("all") || mode.equals("ps")) stagePs(8);
        if (mode.equals("all") || mode.equals("neg")) stageNegative();

        stagePs(1);
        row("VERDICT", "harness", failures == 0 ? "PASS" : "FAIL", "failures=" + failures,
                "psSizeFinal=" + psSize());
        java.nio.file.Path out = java.nio.file.Path.of(args.length > 4 ? args[4] : "conc-report.csv");
        java.nio.file.Files.writeString(out, CSV.toString());
        if (failures != 0) System.exit(2);
    }

    private ConcHarness() { }
}
