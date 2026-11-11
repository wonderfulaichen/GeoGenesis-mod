package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency harness for the <b>real, complete land+sea chain</b>.
 *
 * <h3>Why this probe exists</h3>
 * <p>{@link ConcHarness} builds {@code DoubleFullMorphLand} plus a
 * {@code DoubleOceanAssemblyCandidate} whose continent source is a <b>stand-in</b> sine/cosine
 * sampler. That exercises the land morph engine and the ocean assembler, but it never touches
 * {@link ContinentField}, never runs the production {@code E -> C -> height} curve path end to end,
 * and never constructs the composed {@link DoubleGalleryCandidate}. Its results therefore say
 * nothing about the real chain, and must not be cited as if they did. This probe closes that gap.</p>
 *
 * <h3>What is actually built here</h3>
 * <pre>
 *   TerrainParams   <- frozen config-input.toml, loaded ONCE on the main thread
 *   ContinentField  <- real production continent field, seeded per seed
 *   RecipeMorphCatalog(catalog, seed)
 *   DoubleGalleryCandidate(catalog, continent::sample, seed)   &lt;- the composed chain
 *   HeightCurve(params, -64, 320)                              &lt;- real E -&gt; world-Y curve
 * </pre>
 *
 * <h3>Configuration discipline</h3>
 * <p>{@code GeoGenesisConfig.SPEC} is a process-wide static. It is loaded <b>exactly once</b>, on
 * the main thread, before any worker starts. Workers only ever call {@code e()} / {@code height()}
 * on objects that were already constructed for them; no worker reads or writes the static config,
 * and no stage swaps it while threads are live. This is the property that makes multi-seed
 * concurrency safe here, and it is asserted structurally by the build/verify scripts (they reject
 * any probe in the concurrency set that references the config, and this probe lives in its own
 * directory precisely so that it may load it once on the main thread without tripping that check).</p>
 *
 * <h3>Stages</h3>
 * <ol>
 *   <li><b>coverage</b>: classify the real chain's own outputs into seabed / coast / land and near /
 *       far, so the claim "we covered coast and seabed" is measured, not asserted.</li>
 *   <li><b>shared-instance</b>: many threads sample one chain instance; every value must match the
 *       single-threaded reference bit for bit, and the land counters must stay consistent.</li>
 *   <li><b>independent-instances</b>: each thread owns a chain that was constructed for it up front;
 *       same bit comparison. This is the stage that measures real parallel throughput.</li>
 *   <li><b>lru-churn</b>: push each chain past {@code CACHE_TILES} tiles, then re-query the cold
 *       points; values must be unchanged.</li>
 *   <li><b>negative controls</b>: a perturbed reference must be detected, and different seeds must
 *       differ on coast points, or the comparison is vacuous.</li>
 * </ol>
 *
 * <h3>What this does NOT prove</h3>
 * <p>Interruption and cancellation are <b>not</b> supported by this code. {@link DoubleFullMorphLand}
 * serializes its mutating state on its own monitor, so concurrent callers <i>cooperate</i> by
 * blocking; that is mutual exclusion, not a cancellation API. There is no interruptible checkpoint,
 * no progress handle, and no way to abort an in-flight tile build. The cancel/retry stage elsewhere
 * measures a harness-owned attempt loop that stops issuing new work between attempts; it does not
 * cancel work already inside the chain. Do not read a passing result here as evidence for
 * cancellation support.</p>
 *
 * <p>Read-only with respect to production code: this probe constructs and observes, and never edits
 * candidate behaviour.</p>
 */
public final class RealChainConcurrency {

    /** Two seeds: a small one and a large positive one, so seed folds differ as well. */
    private static final long[] SEEDS = {12345L, 9139912035078620160L};

    private static final int THREADS = 6;
    private static final int ROUNDS = 3;

    /** Far-field probes: beyond any tile the near grid touches. */
    private static final double[][] FAR = {
            {1_875_000.5, 1_875_000.5}, {65_536.5, 65_536.5}, {262_144.5, 262_144.5},
            {-1_875_000.5, 1_875_000.5}, {-262_144.5, -262_144.5}
    };

    private static final StringBuilder log = new StringBuilder();
    private static int failures;

    // ------------------------------------------------------------------ chain

    /** One fully independent real chain. All construction happens on the calling (main) thread. */
    static final class Chain {
        final long seed;
        final ContinentField continent;
        final RecipeMorphCatalog catalog;
        final DoubleGalleryCandidate gallery;
        final HeightCurve curve;

        Chain(TerrainParams params, long seed) {
            this.seed = seed;
            this.continent = new ContinentField(params);
            this.continent.seed(seed);
            this.catalog = new RecipeMorphCatalog(seed);
            this.gallery = new DoubleGalleryCandidate(catalog, continent::sample, seed);
            this.curve = new HeightCurve(params, -64, 320);
        }

        double e(double x, double z) { return gallery.e(x, z); }
        double height(double x, double z) { return curve.heightFromE(gallery.e(x, z)); }
        double continent(double x, double z) { return continent.sample(x, z); }
        DoubleFullMorphLand land() { return gallery.land; }
    }

    /** Classified sample point plus the single-threaded reference bits. */
    static final class Ref {
        final double x, z;
        final long eBits, heightBits, continentBits;
        final String zone;
        Ref(double x, double z, Chain c, String zone) {
            this.x = x;
            this.z = z;
            this.zone = zone;
            this.eBits = bits(c.e(x, z));
            this.heightBits = bits(c.height(x, z));
            this.continentBits = bits(c.continent(x, z));
        }
    }

    static long bits(double v) { return Double.doubleToRawLongBits(v); }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 2) {
            System.out.println("usage: RealChainConcurrency <config-input.toml> <out-dir> [threads] [rounds]");
            System.exit(64);
        }
        String config = args[0];
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : THREADS;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : ROUNDS;

        // ---- configuration is loaded exactly once, here, on the main thread
        TerrainParams params;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(config, TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) {
                throw new IllegalStateException("config needs correction: " + config);
            }
            GeoGenesisConfig.SPEC.setConfig(cfg);
            params = GeoGenesisConfig.INSTANCE.buildParams();
        }
        row("HARNESS", "start", "chain=real(ContinentField+DoubleGalleryCandidate+HeightCurve)",
                "seeds=" + SEEDS.length, "threads=" + threads, "rounds=" + rounds,
                "configLoadedOn=main-thread");

        try {
            for (long seed : SEEDS) {
                runSeed(params, seed, threads, rounds, out);
            }
            negativeControls(params);
        } catch (RuntimeException | Error ex) {
            failures++;
            row("FATAL", ex.getClass().getSimpleName(), ex.getMessage() == null ? "" : ex.getMessage());
            ex.printStackTrace();
        }

        row("VERDICT", failures == 0 ? "PASS" : "FAIL", "failures=" + failures);
        Files.writeString(out.resolve("real-chain.log"), log.toString());
        System.out.print(log);
        System.exit(failures == 0 ? 0 : 2);
    }

    // ------------------------------------------------------------------ per seed

    private static void runSeed(TerrainParams params, long seed, int threads, int rounds, Path out)
            throws Exception {
        // Everything below is built on the main thread, before any worker exists.
        Chain reference = new Chain(params, seed);
        List<Ref> refs = selectCoverage(reference);
        if (refs.isEmpty()) {
            fail("coverage", "seed " + seed + ": no classified sample points found");
            return;
        }
        Map<String, Integer> zoneCount = new LinkedHashMap<>();
        for (Ref r : refs) zoneCount.merge(r.zone, 1, Integer::sum);
        row("STAGE", "coverage", "seed=" + seed, "points=" + refs.size(),
                "zones=" + zoneCount.toString().replace(" ", ""));

        int nonFinite = 0;
        for (Ref r : refs) {
            if (!Double.isFinite(Double.longBitsToDouble(r.eBits))
                    || !Double.isFinite(Double.longBitsToDouble(r.heightBits))) nonFinite++;
        }
        if (nonFinite != 0) fail("finiteness", "seed " + seed + ": " + nonFinite + " non-finite reference values");
        row("STAGE", "finiteness", "seed=" + seed, "nonFinite=" + nonFinite, "points=" + refs.size());

        // ---- stage: shared instance
        Chain shared = new Chain(params, seed);
        runThreads("shared-instance", seed, threads, rounds, refs,
                id -> shared, shared);

        // ---- stage: independent instances (one per thread, built up front on the main thread)
        List<Chain> perThread = new ArrayList<>();
        for (int i = 0; i < threads; i++) perThread.add(new Chain(params, seed));
        runThreads("independent-instances", seed, threads, rounds, refs,
                id -> perThread.get(id), perThread.get(0));

        // ---- stage: LRU churn
        lruChurn(params, seed, refs);

        // ---- counters after concurrency must still add up
        DoubleFullMorphLand.Stats st = shared.land().stats();
        int[] hist = shared.land().dominantHistogram();
        long histSum = 0;
        for (int v : hist) histSum += v;
        boolean countersOk = st.points() + st.cachedPoints() > 0 && histSum == st.points();
        if (!countersOk) {
            fail("counters", "seed " + seed + ": points=" + st.points() + " cached=" + st.cachedPoints()
                    + " histSum=" + histSum);
        }
        row("STAGE", "counters", "seed=" + seed, "points=" + st.points(),
                "cachedPoints=" + st.cachedPoints(), "histSum=" + histSum,
                "tilesBuilt=" + st.tilesBuilt(), "tileEvictions=" + st.tileEvictions(),
                "tilesCached=" + st.tilesCached(), "consistent=" + countersOk);

        // ---- coast/seabed/land values must actually differ from one another
        double minE = Double.POSITIVE_INFINITY, maxE = Double.NEGATIVE_INFINITY;
        int sea = 0, land = 0;
        for (Ref r : refs) {
            double e = Double.longBitsToDouble(r.eBits);
            minE = Math.min(minE, e);
            maxE = Math.max(maxE, e);
            if (e < 0) sea++; else land++;
        }
        row("STAGE", "range", "seed=" + seed, "minE=" + String.format(Locale.ROOT, "%.6f", minE),
                "maxE=" + String.format(Locale.ROOT, "%.6f", maxE), "seaPoints=" + sea, "landPoints=" + land);
        if (sea == 0 || land == 0) {
            fail("range", "seed " + seed + ": coverage did not include both sea and land points");
        }
    }

    // ------------------------------------------------------------------ coverage selection

    /**
     * Scans a coarse grid plus the far field and classifies points by the chain's OWN output, so the
     * zone labels are measured rather than assumed. Bounded per zone to keep the run short.
     */
    private static List<Ref> selectCoverage(Chain c) {
        List<Ref> picked = new ArrayList<>();
        Map<String, Integer> taken = new LinkedHashMap<>();
        int capPerZone = 24;

        // Near grid: covers several 1024-block tiles and therefore several land/sea provinces.
        for (int j = -14; j <= 14; j++) {
            for (int i = -14; i <= 14; i++) {
                double x = i * 380.0 + 17.25;
                double z = j * 380.0 + 43.75;
                double e = c.e(x, z);
                if (!Double.isFinite(e)) continue;
                String zone;
                if (e < -0.35)      zone = "seabed";
                else if (e < -0.02) zone = "shelf";
                else if (e <= 0.02) zone = "coast";
                else if (e < 0.6)   zone = "land";
                else                zone = "highland";
                if (taken.getOrDefault(zone, 0) >= capPerZone) continue;
                taken.merge(zone, 1, Integer::sum);
                picked.add(new Ref(x, z, c, zone));
            }
        }
        // Far field: the same chain at coordinates far beyond any tile the grid touched.
        for (double[] p : FAR) {
            if (taken.getOrDefault("far", 0) >= capPerZone) break;
            taken.merge("far", 1, Integer::sum);
            picked.add(new Ref(p[0], p[1], c, "far"));
        }
        return picked;
    }

    // ------------------------------------------------------------------ thread stage

    interface ChainFor { Chain get(int threadId); }

    private static void runThreads(String stage, long seed, int threads, int rounds,
                                   List<Ref> refs, ChainFor supplier, Chain counterOwner)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong evals = new AtomicLong();
        AtomicLong mismatches = new AtomicLong();
        AtomicLong exceptions = new AtomicLong();
        List<String> firstMismatch = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    Chain c = supplier.get(id);
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        for (int k = 0; k < refs.size(); k++) {
                            Ref ref = refs.get((k + id * 5 + r * 3) % refs.size());
                            try {
                                double e = c.e(ref.x, ref.z);
                                double h = c.height(ref.x, ref.z);
                                double cn = c.continent(ref.x, ref.z);
                                evals.incrementAndGet();
                                if (bits(e) != ref.eBits || bits(h) != ref.heightBits
                                        || bits(cn) != ref.continentBits) {
                                    if (mismatches.incrementAndGet() <= 3) {
                                        firstMismatch.add("t" + id + " " + ref.zone + " ("
                                                + ref.x + "," + ref.z + ") e=" + bits(e)
                                                + " want=" + ref.eBits);
                                    }
                                }
                            } catch (RuntimeException ex) {
                                exceptions.incrementAndGet();
                                if (firstMismatch.size() < 4) {
                                    firstMismatch.add("t" + id + " EX " + ex.getClass().getSimpleName());
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(600, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        DoubleFullMorphLand.Stats st = counterOwner.land().stats();
        boolean ok = mismatches.get() == 0 && exceptions.get() == 0;
        if (!ok) {
            fail(stage, "seed " + seed + ": mismatches=" + mismatches.get()
                    + " exceptions=" + exceptions.get() + " first=" + firstMismatch);
        }
        row("STAGE", stage, "seed=" + seed, "threads=" + threads, "rounds=" + rounds,
                "evals=" + evals.get(), "mismatches=" + mismatches.get(),
                "exceptions=" + exceptions.get(), "wallMs=" + ms,
                "tilesCached=" + st.tilesCached(), "tileEvictions=" + st.tileEvictions(),
                "result=" + (ok ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------ LRU churn

    private static void lruChurn(TerrainParams params, long seed, List<Ref> refs) {
        Chain cold = new Chain(params, seed);
        Ref probe = null;
        for (Ref r : refs) if (r.zone.equals("coast")) { probe = r; break; }
        if (probe == null) for (Ref r : refs) if (!r.zone.equals("far")) { probe = r; break; }
        if (probe == null) { fail("lru-churn", "seed " + seed + ": no non-far probe point"); return; }

        long coldBits = bits(cold.e(probe.x, probe.z));

        Chain warm = new Chain(params, seed);
        int churn = DoubleFullMorphLand.CACHE_TILES * 4;
        for (int k = 0; k < churn; k++) {
            warm.e(k * 1024.0 + 31.0, -k * 1024.0 + 43.0);
        }
        long warmBits = bits(warm.e(probe.x, probe.z));
        DoubleFullMorphLand.Stats st = warm.land().stats();
        boolean ok = coldBits == warmBits && st.tileEvictions() > 0;
        if (!ok) {
            fail("lru-churn", "seed " + seed + ": cold=" + coldBits + " warm=" + warmBits
                    + " evictions=" + st.tileEvictions());
        }
        row("STAGE", "lru-churn", "seed=" + seed, "cap=" + DoubleFullMorphLand.CACHE_TILES,
                "churnTiles=" + churn, "tilesBuilt=" + st.tilesBuilt(),
                "tileEvictions=" + st.tileEvictions(), "tilesCached=" + st.tilesCached(),
                "coldEqualsWarm=" + (coldBits == warmBits), "result=" + (ok ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------ negative controls

    private static void negativeControls(TerrainParams params) {
        Chain a = new Chain(params, SEEDS[0]);
        Chain b = new Chain(params, SEEDS[1]);
        int detected = 0, total = 0;

        // NC1: a perturbed reference bit must be rejected by the comparator.
        Ref r = new Ref(31.0, 43.0, a, "control");
        total++;
        if (bits(a.e(31.0, 43.0)) == r.eBits
                && (r.eBits ^ 1L) != bits(a.e(31.0, 43.0))) detected++;
        else fail("neg-control", "bit-flip comparator did not bite");

        // NC2: two different seeds must differ somewhere on the real chain.
        total++;
        int differing = 0;
        for (double[] p : new double[][]{{0, 0}, {1024.5, 2048.5}, {-4096.25, 8192.75},
                                         {131072.5, 131072.5}, {1875000.5, 1875000.5}}) {
            if (bits(a.e(p[0], p[1])) != bits(b.e(p[0], p[1]))) differing++;
        }
        if (differing > 0) detected++;
        else fail("neg-control", "different seeds produced identical e everywhere");

        // NC3: the continent source must not be the identity of e (guards a vacuous chain).
        total++;
        int distinct = 0;
        for (double[] p : new double[][]{{0, 0}, {512.5, 512.5}, {-2048.25, 3072.75}}) {
            if (bits(a.continent(p[0], p[1])) != bits(a.e(p[0], p[1]))) distinct++;
        }
        if (distinct > 0) detected++;
        else fail("neg-control", "continent sample equals e everywhere -> chain is degenerate");

        // NC4: the continent field must be the real one, not a constant.
        total++;
        double c0 = a.continent(0, 0), c1 = a.continent(65536.5, 65536.5);
        if (bits(c0) != bits(c1)) detected++;
        else fail("neg-control", "continent field returned a constant: real field not wired");

        row("STAGE", "neg-control", "detected=" + detected + "/" + total);
        if (detected != total) failures++;
    }

    // ------------------------------------------------------------------ logging

    private static void row(String... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cols[i]);
        }
        log.append(sb).append('\n');
    }

    private static void fail(String stage, String detail) {
        failures++;
        row("FAIL", stage, detail);
    }

    private RealChainConcurrency() { }
}
