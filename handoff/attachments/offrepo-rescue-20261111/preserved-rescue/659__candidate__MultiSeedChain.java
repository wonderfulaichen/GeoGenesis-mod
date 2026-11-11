package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Multi-seed wiring verification for the full sea/land chain</b> (sandbox only).
 *
 * <pre>
 *   seed -> ContinentField.seed(seed)                  (continent / ocean edge source)
 *        -> RecipeMorphCatalog(seed)                    (18 recipe entries: seed + OFF_*)
 *        -> DoubleMorphEngine(..., seed)                (kernel perlin seeds: seed+77/44/55)
 *        -> LandRegionContext(catalog, scale)           (partition / mask / plateau coverage)
 *        -> DoubleBoundaryGeometry.of(context, ...)     (Voronoi geometry, partition seed + catalog seed)
 *        -> DoubleOceanAssemblyCandidate(..., seed + 3) (deep-ocean noise)
 *        -> .enableCoastalDetail(seed)                  (cliff / shallow detail noise)
 *        -> HeightCurve.heightFromE(e)
 * </pre>
 *
 * <p>Modes:</p>
 * <ul>
 *   <li>{@code full <seed> <outDir>} —— 1401² full-chain image, computed <b>forward</b> then
 *       <b>reverse pixel order on independently reconstructed objects</b>; both must be bit-identical.
 *       Writes {@code heights-<seed>.f64be}, its SHA-256, and {@code full-<seed>.log}.</li>
 *   <li>{@code sweep <outDir>} —— every seed in {@link #SEEDS}: 241² grid + far ring, finite check,
 *       per-stage digests, pairwise distinctness (negative control).</li>
 * </ul>
 *
 * <p>Nothing here writes to the repository or touches production geometry / cache / concurrency code.</p>
 */
public final class MultiSeedChain {

    /** Primary non-canonical seed (full 1401² image + forward/reverse/independent rebuild). */
    static final long FULL_SEED = 12345L;
    /** Seed set for the finite/exactness sweep and the negative control. */
    static final long[] SEEDS = {
            RecipeMorphCatalog.GALLERY_SEED, 12345L, 0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE
    };

    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    private static final StringBuilder log = new StringBuilder();
    private static int blockers;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String mode = args[0];
        String config = args[1];
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        if (mode.equals("full")) {
            long seed = Long.parseLong(args[3]);
            runFull(config, out, seed);
        } else if (mode.equals("sweep")) {
            runSweep(config, out);
        } else {
            throw new IllegalArgumentException("mode full|sweep");
        }
        String file = mode + ".log";
        Files.writeString(out.resolve(file), log.toString());
        System.out.print(log);
        System.out.println("BLOCKERS=" + blockers);
        System.exit(blockers == 0 ? 0 : 2);
    }

    // ============================================================ chain construction

    /** One fully independent, seed-wired chain. Rebuilt from scratch for every comparison. */
    static final class Chain {
        final ContinentField continent;
        final RecipeMorphCatalog catalog;
        final DoubleFullMorphLand land;
        final DoubleOceanAssemblyCandidate ocean;
        final HeightCurve curve;

        Chain(String configPath, long seed) throws Exception {
            try (CommentedFileConfig cfg = CommentedFileConfig.of(configPath, TomlFormat.instance())) {
                cfg.load();
                if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config needs correction");
                GeoGenesisConfig.SPEC.setConfig(cfg);
                var params = GeoGenesisConfig.INSTANCE.buildParams();
                curve = new HeightCurve(params, -64, 320);
                continent = new ContinentField(params);
                continent.seed(seed);
            }
            catalog = new RecipeMorphCatalog(seed);
            land = new DoubleFullMorphLand(catalog);
            ocean = new DoubleOceanAssemblyCandidate(land::e, continent::sample, seed + 3)
                    .enableCoastalDetail(seed);
        }

        double landE(double x, double z) { return land.e(x, z); }
        double worldE(double x, double z) { return ocean.e(x, z); }
        double height(double x, double z) { return curve.heightFromE(ocean.e(x, z)); }
    }

    // ============================================================ mode: full 1401²

    private static void runFull(String config, Path out, long seed) throws Exception {
        if (seed == RecipeMorphCatalog.GALLERY_SEED) {
            throw new IllegalArgumentException("full mode is for non-canonical seeds; canonical has its own arm");
        }
        Chain forward = new Chain(config, seed);
        Chain reverse = new Chain(config, seed);   // independent objects, same seed

        Path fwdFile = out.resolve("heights-" + seed + ".f64be");
        Path revFile = out.resolve("heights-" + seed + "-reverse.f64be");
        long t0 = System.nanoTime();
        double[] fwd = new double[SIZE * SIZE];
        write(fwdFile, forward, seed, false, fwd);
        log.append("forward rows=").append(SIZE).append(String.format(Locale.ROOT, " %.1fs%n",
                (System.nanoTime() - t0) / 1e9));
        t0 = System.nanoTime();
        double[] rev = new double[SIZE * SIZE];
        write(revFile, reverse, seed, true, rev);
        log.append("reverse rows=").append(SIZE).append(String.format(Locale.ROOT, " %.1fs%n",
                (System.nanoTime() - t0) / 1e9));

        int mismatches = 0, nonFinite = 0;
        for (int i = 0; i < fwd.length; i++) {
            if (!Double.isFinite(fwd[i])) nonFinite++;
            if (Double.doubleToLongBits(fwd[i]) != Double.doubleToLongBits(rev[i])) {
                if (mismatches < 8) {
                    log.append("  mismatch at pixel ").append(i).append(": forward=").append(fwd[i])
                            .append(" reverse=").append(rev[i]).append('\n');
                }
                mismatches++;
            }
        }
        String shaF = sha(fwdFile), shaR = sha(revFile);
        log.append("seed=").append(seed).append('\n');
        log.append("pixels=").append(fwd.length).append(" nonFinite=").append(nonFinite)
                .append(" forwardVsReverseMismatch=").append(mismatches).append('\n');
        log.append("shaForward=").append(shaF).append('\n');
        log.append("shaReverse=").append(shaR).append('\n');
        log.append("forward==reverse(bit)=").append(shaF.equals(shaR)).append('\n');
        if (nonFinite > 0) blockers++;
        if (mismatches > 0) blockers++;

        // A DIFFERENT seed must not reproduce this image (negative control at full-image level).
        long other = 1L;
        double[] alt = new double[SIZE * SIZE];
        Path altFile = out.resolve("heights-" + other + ".f64be");
        write(altFile, new Chain(config, other), other, false, alt);
        String shaA = sha(altFile);
        int differ = 0;
        for (int i = 0; i < fwd.length; i++) {
            if (Double.doubleToLongBits(fwd[i]) != Double.doubleToLongBits(alt[i])) differ++;
        }
        log.append("negativeControl seed=").append(other).append(" sha=").append(shaA)
                .append(" differingPixels=").append(differ)
                .append(" sameImage=").append(shaA.equals(shaF)).append('\n');
        if (shaA.equals(shaF) || differ == 0) blockers++;

        // Statistics so the image is auditable without a PNG viewer.
        log.append(coverage(fwd, seed));
    }

    /**
     * Renders one order (forward or reversed pixel <b>visitation</b>) on a fresh chain, then writes
     * the buffer in canonical pixel order. The comparison is therefore about the produced values,
     * not about the byte layout: both files must be byte-identical for the same seed.
     */
    private static void write(Path file, Chain chain, long seed, boolean reverse, double[] sink)
            throws Exception {
        int n = SIZE * SIZE;
        for (int k = 0; k < n; k++) {
            int index = reverse ? n - 1 - k : k;
            int j = index / SIZE, i = index % SIZE;
            double x = CX + (i - SIZE / 2.0) * STEP, z = CZ + (j - SIZE / 2.0) * STEP;
            sink[index] = chain.height(x, z);
        }
        try (DataOutputStream os = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
            for (int index = 0; index < n; index++) os.writeDouble(sink[index]);
        }
    }

    private static String coverage(double[] h, long seed) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
        int sea = 0, land = 0, nonFinite = 0;
        for (double v : h) {
            if (!Double.isFinite(v)) { nonFinite++; continue; }
            min = Math.min(min, v); max = Math.max(max, v); sum += v;
            if (v <= 62.0) sea++; else land++;
        }
        int n = h.length;
        return String.format(Locale.ROOT,
                "stats seed=%d min=%.3f max=%.3f mean=%.3f seaFrac=%.4f landFrac=%.4f nonFinite=%d%n",
                seed, min, max, sum / n, sea / (double) n, land / (double) n, nonFinite);
    }

    private static String sha(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        return java.util.HexFormat.of().formatHex(md.digest()).toUpperCase(Locale.ROOT);
    }

    // ============================================================ mode: sweep

    /** Far points must stay finite: the same coordinates the canonical far-block arm probes. */
    private static final double[][] FAR = {
            {1_875_000.5, 1_875_000.5}, {65_536.5, 65_536.5}, {262_144.5, 262_144.5},
            {1_048_576.5, 1_048_576.5}, {-1_875_000.5, 1_875_000.5}, {30_000_000.5, 30_000_000.5}
    };

    private static void runSweep(String config, Path out) throws Exception {
        int N = 241;
        double step = 25.0;
        Map<Long, String> chainDigest = new LinkedHashMap<>();
        Map<Long, String> stageDigest = new LinkedHashMap<>();

        for (long seed : SEEDS) {
            Chain c = new Chain(config, seed);
            Digest dChain = new Digest(), dLand = new Digest(), dContinent = new Digest();
            int nonFinite = 0, farFailures = 0;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    double x = (i - N / 2) * step + 0.25, z = (j - N / 2) * step + 0.75;
                    double h = c.height(x, z);
                    double e = c.worldE(x, z);
                    double le = c.landE(x, z);
                    double cn = c.continent.sample(x, z);
                    if (!Double.isFinite(h) || !Double.isFinite(e) || !Double.isFinite(le)
                            || !Double.isFinite(cn)) nonFinite++;
                    else { min = Math.min(min, h); max = Math.max(max, h); sum += h; }
                    dChain.add(h);
                    dLand.add(le);
                    dContinent.add(cn);
                }
            }
            StringBuilder far = new StringBuilder();
            for (double[] p : FAR) {
                try {
                    double h = c.height(p[0], p[1]);
                    double e = c.worldE(p[0], p[1]);
                    boolean ok = Double.isFinite(h) && Double.isFinite(e);
                    if (!ok) farFailures++;
                    far.append(String.format(Locale.ROOT, "    far %.1f,%.1f h=%.6g e=%.6g %s%n",
                            p[0], p[1], h, e, ok ? "ok" : "NON-FINITE"));
                } catch (RuntimeException ex) {
                    farFailures++;
                    far.append(String.format(Locale.ROOT, "    far %.1f,%.1f THROWS %s%n",
                            p[0], p[1], ex));
                }
            }
            int slots = c.catalog.slotCount();
            int slot0 = c.catalog.slotEntry(0);
            long partitionSeed = c.land.tileAt(0, 0).geometry().partitionSeed();
            chainDigest.put(seed, dChain.toString());
            stageDigest.put(seed, dLand + "/" + dContinent + "/" + partitionSeed + "/" + slot0 + "/" + slots);
            log.append(String.format(Locale.ROOT,
                    "seed=%-20d grid=%dx%d nonFinite=%d farFailures=%d min=%.3f max=%.3f mean=%.3f%n",
                    seed, N, N, nonFinite, farFailures, min, max, sum / (N * (double) N)));
            log.append("  chainSha=").append(dChain).append(" landSha=").append(dLand)
                    .append(" continentSha=").append(dContinent)
                    .append(" partitionSeed=").append(partitionSeed)
                    .append(" slot0=").append(slot0).append('\n');
            log.append(far);
            if (nonFinite > 0) blockers++;
            if (farFailures > 0) blockers++;
        }

        // Negative control: no two distinct seeds may share a full-chain digest.
        log.append("--- negative control: pairwise full-chain digest distinctness ---\n");
        List<Long> keys = new ArrayList<>(chainDigest.keySet());
        int collisions = 0;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                boolean same = chainDigest.get(keys.get(i)).equals(chainDigest.get(keys.get(j)));
                if (same) {
                    collisions++;
                    log.append("  COLLISION seed ").append(keys.get(i)).append(" vs ")
                            .append(keys.get(j)).append(" chain ").append(chainDigest.get(keys.get(i)))
                            .append(" stages ").append(stageDigest.get(keys.get(i))).append('\n');
                }
            }
        }
        log.append("pairwise=").append(keys.size() * (keys.size() - 1) / 2)
                .append(" collisions=").append(collisions).append('\n');
        if (collisions > 0) blockers++;

        Files.writeString(out.resolve("sweep-digests.csv"), csv(chainDigest, stageDigest));
    }

    private static String csv(Map<Long, String> chain, Map<Long, String> stage) {
        StringBuilder sb = new StringBuilder("seed,chainSha,landContinentPartitionStages\n");
        for (long seed : SEEDS) {
            sb.append(seed).append(',').append(chain.get(seed)).append(',')
                    .append(stage.get(seed)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Streaming digest. {@link #toString()} is memoised because {@link MessageDigest#digest()}
     * resets the instance: without the cache a second call would silently return SHA-256("").
     */
    private static final class Digest {
        private final MessageDigest md;
        private String hex;
        Digest() {
            try { md = MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        void add(double v) {
            if (hex != null) throw new IllegalStateException("digest already finalised");
            long b = Double.doubleToLongBits(v);
            for (int k = 7; k >= 0; k--) md.update((byte) (b >>> (8 * k)));
        }
        @Override public String toString() {
            if (hex == null) {
                byte[] h = md.digest();
                hex = java.util.HexFormat.of().formatHex(h).toUpperCase(Locale.ROOT);
            }
            return hex;
        }
    }

    private MultiSeedChain() { }
}
