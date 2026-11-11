package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * <b>Seed wiring gate</b>: prove the input seed reaches continent / geometry / recipe / ocean,
 * and that it is not just a label.
 *
 * <h3>Why two seed sets</h3>
 * <p>{@code CoherentRegionDistribution.base(seed) = (int)(seed ^ (seed >>> 32))} folds the seed to
 * 32 bits. Under that fold {@code 0} and {@code -1} both map to {@code 0}, and
 * {@code Long.MIN_VALUE} and {@code Long.MAX_VALUE} both map to {@code Integer.MIN_VALUE}. So the
 * "extrema" seeds are <b>not</b> a valid instrument for per-stage sensitivity: they collide by
 * construction. The gate therefore splits the claim in two:</p>
 * <ul>
 *   <li>{@link #SPREAD} — seeds with distinct {@code base(seed)}: <b>every</b> stage must move.
 *       This is the real proof that each link consumes the seed.</li>
 *   <li>{@link #EXTREMA} — the extrema set: only finite/no-throw is required here (that is what
 *       the user asked of them), and the {@code base()} collisions are <b>printed</b>, not hidden.</li>
 * </ul>
 */
public final class SeedWiringGate {

    /** Distinct base(seed) by construction; used to prove per-stage seed consumption. */
    static final long[] SPREAD = {RecipeMorphCatalog.GALLERY_SEED, 12345L, 0L, 1L, 2L, 3L, 99L};

    /** The extrema set the task asked to be finite over the whole chain. */
    static final long[] EXTREMA = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};

    private static final int N = 33;
    private static final double STEP = 61.0;

    private static int blockers;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String config = args[0];
        System.out.println("=== base(seed) fold, the whole reason this gate is split ===");
        for (long seed : allSeeds()) {
            System.out.printf(Locale.ROOT, "  seed=%-20d base=%d%n", seed,
                    CoherentRegionDistribution.base(seed));
        }

        System.out.println("=== SPREAD: every stage must move when base(seed) changes ===");
        Stage prev = null;
        long prevSeed = 0;
        int stagesChecked = 0;
        for (long seed : SPREAD) {
            Stage s = measure(config, seed);
            if (prev != null) {
                stagesChecked++;
                requireDifferent("continent", prevSeed, seed, prev.continent, s.continent);
                requireDifferent("recipe(landE)", prevSeed, seed, prev.landE, s.landE);
                requireDifferent("recipe(engine onehot)", prevSeed, seed, prev.engine, s.engine);
                requireDifferent("geometry(partitionSeed)", prevSeed, seed,
                        String.valueOf(prev.partitionSeed), String.valueOf(s.partitionSeed));
                requireDifferent("geometry(warp)", prevSeed, seed, prev.warp, s.warp);
                requireDifferent("ocean(deep)", prevSeed, seed, prev.oceanDeep, s.oceanDeep);
                requireDifferent("ocean(full chain)", prevSeed, seed, prev.chain, s.chain);
                // mask/coverage are derived from base(seed) only, so they must also move here.
                requireDifferent("mask(coverage blend)", prevSeed, seed, prev.mask, s.mask);
                requireDifferent("coverage(plateau)", prevSeed, seed, prev.coverage, s.coverage);
            }
            System.out.printf(Locale.ROOT,
                    "  seed=%-20d base=%-12d continent=%s landE=%s engine=%s partition=%d warp=%s"
                            + " oceanDeep=%s mask=%s coverage=%s chain=%s%n",
                    seed, CoherentRegionDistribution.base(seed),
                    s.continent, s.landE, s.engine, s.partitionSeed, s.warp,
                    s.oceanDeep, s.mask, s.coverage, s.chain);
            prev = s;
            prevSeed = seed;
        }
        System.out.println("  spreadPairs=" + stagesChecked + " stagesPerPair=9");

        System.out.println("=== EXTREMA: whole-chain finiteness + explicit base() collisions ===");
        Stage first = null;
        long firstSeed = 0;
        for (long seed : EXTREMA) {
            Stage s = measure(config, seed);
            require(s.nonFinite == 0, "non-finite values for seed " + seed);
            System.out.printf(Locale.ROOT,
                    "  seed=%-20d base=%-12d chain=%s nonFinite=%d%n", seed,
                    CoherentRegionDistribution.base(seed), s.chain, s.nonFinite);
        }

        System.out.println("=== NEGATIVE CONTROL: distinct seeds must not share a chain digest ===");
        int collisions = 0;
        java.util.List<Long> seeds = new java.util.ArrayList<>();
        java.util.List<Stage> stages = new java.util.ArrayList<>();
        for (long seed : allSeeds()) {
            if (!seeds.contains(seed)) {   // de-duplicate: SPREAD and EXTREMA overlap on 0/1
                seeds.add(seed);
                stages.add(measure(config, seed));
            }
        }
        for (int i = 0; i < seeds.size(); i++) {
            for (int j = i + 1; j < seeds.size(); j++) {
                long sa = seeds.get(i), sb = seeds.get(j);
                if (sa == sb) continue;                       // never compare a seed with itself
                Stage a = stages.get(i), b = stages.get(j);
                if (a.chain.equals(b.chain)) {
                    collisions++;
                    System.out.printf(Locale.ROOT, "  COLLISION %d vs %d chain=%s%n", sa, sb, a.chain);
                }
                // The base()-derived stages fold the seed to 32 bits, so equal base() values are
                // EXPECTED to share them. Report the fold instead of pretending it is a failure.
                if (CoherentRegionDistribution.base(sa) == CoherentRegionDistribution.base(sb)) {
                    System.out.printf(Locale.ROOT,
                            "  base-fold %d vs %d -> same base=%d; maskSame=%b coverageSame=%b"
                                    + " partitionSame=%b continentSame=%b chainSame=%b%n",
                            sa, sb, CoherentRegionDistribution.base(sa),
                            a.mask.equals(b.mask), a.coverage.equals(b.coverage),
                            a.partitionSeed == b.partitionSeed,
                            a.continent.equals(b.continent), a.chain.equals(b.chain));
                }
            }
        }
        System.out.println("  pairs=" + (seeds.size() * (seeds.size() - 1) / 2)
                + " distinctSeeds=" + seeds.size() + " collisions=" + collisions);
        require(collisions == 0, "two distinct seeds produced the same chain digest");

        System.out.println("BLOCKERS=" + blockers);
        System.exit(blockers == 0 ? 0 : 2);
    }

    // ---------------------------------------------------------------- measurement

    private record Stage(String continent, String landE, String engine, long partitionSeed,
                         String warp, String oceanDeep, String mask, String coverage,
                         String chain, int nonFinite) { }

    private static Stage measure(String config, long seed) throws Exception {
        ContinentField continent;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(config, TomlFormat.instance())) {
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            continent = new ContinentField(GeoGenesisConfig.INSTANCE.buildParams());
            continent.seed(seed);
        }
        RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);
        DoubleOceanAssemblyCandidate chain =
                new DoubleOceanAssemblyCandidate(land::e, continent::sample, seed + 3)
                        .enableCoastalDetail(seed);
        DoubleOceanAssemblyCandidate deepOnly =
                new DoubleOceanAssemblyCandidate((x, z) -> 0.0, (x, z) -> -1.0, seed + 3);
        DoubleMorphEngine engine = new DoubleMorphEngine(DoubleLandRecipe.all(cat), cat.seed());
        double[] onehot = new double[cat.size()];
        onehot[15] = 1;

        Digest dContinent = new Digest(), dLand = new Digest(), dEngine = new Digest(),
                dWarp = new Digest(), dDeep = new Digest(), dMask = new Digest(),
                dCov = new Digest(), dChain = new Digest();
        int nonFinite = 0;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                double x = (i - N / 2) * STEP + 0.25, z = (j - N / 2) * STEP + 0.75;
                double c = continent.sample(x, z);
                double le = land.e(x, z);
                double en = engine.sampleLand(x, z, onehot);
                double dp = deepOnly.e(x, z);
                double mk = land.mask(x, z);
                double cv = land.oldCoverageHeight(x, z);
                double ch = chain.e(x, z);
                for (double v : new double[]{c, le, en, dp, mk, cv, ch}) {
                    if (!Double.isFinite(v)) nonFinite++;
                }
                dContinent.add(c);
                dLand.add(le);
                dEngine.add(en);
                dDeep.add(dp);
                dMask.add(mk);
                dCov.add(cv);
                dChain.add(ch);
                if (i % 8 == 0 && j % 8 == 0) {
                    double[] w = land.tileAt(x, z).geometry().warpForward(x, z);
                    dWarp.add(w[0]);
                    dWarp.add(w[1]);
                }
            }
        }
        long partitionSeed = land.tileAt(0, 0).geometry().partitionSeed();
        return new Stage(dContinent.toString(), dLand.toString(), dEngine.toString(), partitionSeed,
                dWarp.toString(), dDeep.toString(), dMask.toString(), dCov.toString(),
                dChain.toString(), nonFinite);
    }

    private static long[] allSeeds() {
        long[] all = new long[SPREAD.length + EXTREMA.length];
        System.arraycopy(SPREAD, 0, all, 0, SPREAD.length);
        System.arraycopy(EXTREMA, 0, all, SPREAD.length, EXTREMA.length);
        return all;
    }

    // ---------------------------------------------------------------- assertions

    private static void requireDifferent(String stage, long a, long b, long va, long vb) {
        requireDifferent(stage, a, b, String.valueOf(va), String.valueOf(vb));
    }

    private static void requireDifferent(String stage, long a, long b, String va, String vb) {
        if (va.equals(vb)) {
            blockers++;
            System.out.printf(Locale.ROOT,
                    "  FAIL %s did not move between seed %d and %d (value %s)%n", stage, a, b, va);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            blockers++;
            System.out.println("  FAIL " + message);
        }
    }

    private static final class Digest {
        private final MessageDigest md;
        private String hex;
        Digest() {
            try { md = MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        void add(double v) {
            if (hex != null) throw new IllegalStateException("digest finalised");
            long b = Double.doubleToLongBits(v);
            for (int k = 7; k >= 0; k--) md.update((byte) (b >>> (8 * k)));
        }
        @Override public String toString() {
            if (hex == null) hex = java.util.HexFormat.of().formatHex(md.digest())
                    .substring(0, 12).toUpperCase(Locale.ROOT);
            return hex;
        }
    }

    private SeedWiringGate() { }
}
