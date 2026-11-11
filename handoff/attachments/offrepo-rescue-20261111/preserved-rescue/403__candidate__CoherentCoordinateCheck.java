package com.geogenesis.worldgen.terrain.candidate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S4 read-only, bounded coordinate evidence. Never substitutes a multi-seed height chain. */
public final class CoherentCoordinateCheck {
    private static final long CANONICAL = 9139912035078620160L;
    private static final long[] SEEDS = {CANONICAL, 0, -419, 12345};
    private static final double SEAM_TOL = 2e-3;
    private static final float SCALE = 600;
    private static final int WINDOW_LIMIT = 20;
    private final Map<Long, LocalBoundaryGeometry> near = new LinkedHashMap<>();
    private final Map<Long, CoherentRegionDistribution> distributions = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private PrintWriter csv;
    private int failures, blocked, explicitWindows, matcherCalls, matcherBuilds;
    private int identities, microPoints, inverseCalls, seamPoints, heightPoints;

    public static void main(String[] args) {
        var check = new CoherentCoordinateCheck();
        int exit;
        try {
            if (args.length < 1 || args.length > 2 || args.length == 2 && !args[1].equals("s3-only"))
                throw new IllegalArgumentException("usage: CoherentCoordinateCheck NEW_OUTPUT_DIRECTORY [s3-only]");
            exit = check.run(Path.of(args[0]), args.length == 2);
        } catch (Throwable t) {
            t.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private int run(Path out, boolean s3Only) throws Exception {
        Path allowed = Path.of("build/coherent-stream-s4/coordinate").toAbsolutePath().normalize();
        if (!out.toAbsolutePath().normalize().startsWith(allowed) || Files.exists(out))
            throw new IllegalArgumentException("output must be NEW and inside " + allowed);
        Files.createDirectories(out);
        try (var writer = new PrintWriter(Files.newBufferedWriter(out.resolve("evidence.csv"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW), true)) {
            csv = writer;
            csv.println("stage,seed,x,z,status,input,output,exception_class,exception_exact,exception_stack");
            event("scope", 0, 0, 0, "INFO", "scale=600;radius=728;reqHalf=4;chord=.02;maxSeg=2;seamTol=.002",
                    "Finite evidence only; float step collapse alone is NOT FAIL; no production or default edits", null);
            if (s3Only) {
                event("coverage", CANONICAL, 1000000, 1000000, "BLOCKED", "supplement-only",
                        "Only requested actual S3 far point; earlier full evidence is separate, not re-run", null);
                completeS3(); writeSummary(out);
                return failures > 0 ? 1 : 2;
            }
            for (long seed : SEEDS) {
                distributions.put(seed, new CoherentRegionDistribution(seed));
                identity(seed);
                micro(seed);
                LocalBoundaryGeometry g = build(seed, 512, 512, "geometry-near");
                if (g != null) near.put(seed, g);
            }
            for (long seed : SEEDS) inverse(seed);
            for (long seed : SEEDS) seams(seed);
            farGeometry();
            // Real matcher calls, all near seeds before any far seed. No empty land shortcut counts.
            for (long seed : SEEDS) matcher(seed, false);
            for (long seed : SEEDS) matcher(seed, true);
            for (long seed : SEEDS) height(seed);
            completeS3();
            event("injectivity-limit", 0, 0, 0, "INFO", "finite initial guesses only",
                    "No global injectivity proof; no distinct converged roots found does not establish uniqueness", null);
            writeSummary(out);
        }
        return failures > 0 ? 1 : blocked > 0 ? 2 : 0;
    }

    private static List<double[]> points() {
        List<double[]> result = new ArrayList<>();
        result.add(new double[]{0, 0});
        result.add(new double[]{512, 512});
        result.add(new double[]{-512, -512});
        for (double r : new double[]{1e4, 1e5, 1e6, 1e7, 29998000}) {
            result.add(new double[]{r, 0}); result.add(new double[]{-r, 0});
            result.add(new double[]{0, r}); result.add(new double[]{0, -r});
            result.add(new double[]{r, -r}); result.add(new double[]{-r, r});
            result.add(new double[]{r, r}); result.add(new double[]{-r, -r});
        }
        return result;
    }

    private void identity(long seed) {
        var distribution = distributions.get(seed);
        var independent = new CoherentRegionDistribution(seed);
        var points = points();
        List<CoherentRegionDistribution.Choice> choices = new ArrayList<>();
        for (double[] p : points) {
            var a = distribution.anchor(p[0], p[1], 0);
            var b = distribution.anchor(p[0], p[1], 8);
            boolean same = sameAnchor(a, b);
            var choice = distribution.choice(a);
            choices.add(choice);
            event("macro-enumeration", seed, p[0], p[1], same ? "PASS" : "FAIL",
                    "extra=0 vs extra=8", "bounded=" + a + ";expanded=" + b + ";choice=" + choice, null);
            identities++;
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            double[] p = points.get(i);
            var reversed = independent.choice(independent.anchor(p[0], p[1], 0));
            event("macro-reverse-order", seed, p[0], p[1], choices.get(i).equals(reversed) ? "PASS" : "FAIL",
                    "new instance;reverse order;expected=" + choices.get(i), reversed.toString(), null);
        }
    }

    private static boolean sameAnchor(CoherentRegionDistribution.Anchor a, CoherentRegionDistribution.Anchor b) {
        return a.cellX() == b.cellX() && a.cellZ() == b.cellZ() && a.x() == b.x() && a.z() == b.z();
    }

    private void micro(long seed) {
        RegionPartition partition = CoherentRegionDistribution.microPartition(seed, SCALE);
        int siteSeed = (Integer) RecipeMorphCatalog.field(partition, "seed");
        var distribution = distributions.get(seed);
        List<CoherentRegionDistribution.Choice> choices = new ArrayList<>();
        List<LocalBoundaryGeometry.Cell> cells = new ArrayList<>();
        for (double[] p : points()) {
            try {
                double[] uv = forward(partition, p[0], p[1]);
                var small = floatWinner(siteSeed, uv, 1);
                var large = floatWinner(siteSeed, uv, 5);
                double[] site = CoherentRegionDistribution.site(siteSeed, small[0], small[1]);
                var cell = new LocalBoundaryGeometry.Cell(small[0], small[1], site[0], site[1], 0);
                cells.add(cell); choices.add(distribution.choose(cell));
                event("micro-candidates", seed, p[0], p[1], Arrays.equals(small, large) ? "PASS" : "FAIL",
                        "uv=" + Arrays.toString(uv) + ";radii=1,5;partition=" + partition.sample((float) p[0], (float) p[1]),
                        "small=" + Arrays.toString(small) + ";expanded=" + Arrays.toString(large)
                                + ";site=" + Arrays.toString(site) + ";choice=" + choices.get(choices.size() - 1), null);
                microPoints++;
            } catch (Throwable t) { exception("micro-candidates", seed, p[0], p[1], t); }
        }
        var independent = new CoherentRegionDistribution(seed);
        for (int i = cells.size() - 1; i >= 0; i--) {
            var c = cells.get(i); var actual = independent.choose(c);
            event("micro-identity-reverse", seed, c.vx() * SCALE, c.vz() * SCALE,
                    actual.equals(choices.get(i)) ? "PASS" : "FAIL", "cell=" + c + ";expected=" + choices.get(i),
                    actual.toString(), null);
        }
    }

    private static double[] forward(RegionPartition p, double x, double z) {
        var wx = (RefNoise.Node) RecipeMorphCatalog.field(p, "warpX");
        var wz = (RefNoise.Node) RecipeMorphCatalog.field(p, "warpZ");
        float frequency = (Float) RecipeMorphCatalog.field(p, "frequency");
        float fx = (float) x, fz = (float) z;
        return new double[]{(fx + (wx.compute(fx, fz) - .5f) * 200f) * frequency,
                (fz + (wz.compute(fx, fz) - .5f) * 200f) * frequency};
    }

    private static int[] floatWinner(int seed, double[] uv, int radius) {
        float u = (float) uv[0], v = (float) uv[1], best = Float.POSITIVE_INFINITY;
        int cx = RefNoise.floor(u), cz = RefNoise.floor(v), bx = 0, bz = 0;
        for (int z = cz - radius; z <= cz + radius; z++) for (int x = cx - radius; x <= cx + radius; x++) {
            float[] vec = RegionCellTable.CELL[RefNoise.hash2D(seed, x, z) & 255];
            float dx = x + vec[0] * .7f - u, dz = z + vec[1] * .7f - v;
            float potential = Math.abs(dx) + Math.abs(dz) + (dx * dx + dz * dz);
            if (potential < best) { best = potential; bx = x; bz = z; }
        }
        return new int[]{bx, bz};
    }

    private LocalBoundaryGeometry build(long seed, double x, double z, String stage) {
        if (explicitWindows >= WINDOW_LIMIT) {
            event(stage, seed, x, z, "BLOCKED", "windowLimit=" + WINDOW_LIMIT, "explicit window budget exhausted", null);
            return null;
        }
        explicitWindows++;
        String step = "constructor";
        long start = System.nanoTime();
        try {
            var g = LocalBoundaryGeometry.of(CoherentRegionDistribution.microPartition(seed, SCALE), x, z, 728, 4, .02);
            step = "setMaxSeg(2)"; g.setMaxSeg(2);
            event(stage, seed, x, z, g.maxWarpResidualBlocks() <= g.invertTolBlocks() ? "PASS" : "FAIL",
                    "radius=728;reqHalf=4;chord=.02;maxSeg=2;scale=600",
                    "sites=" + g.sites().size() + ";edges=" + g.edges().size() + ";residual="
                            + g.maxWarpResidualBlocks() + ";invertTol=" + g.invertTolBlocks()
                            + ";elapsedMs=" + (System.nanoTime() - start) / 1_000_000, null);
            return g;
        } catch (Throwable t) {
            event(stage + "/" + step, seed, x, z, "FAIL", "radius=728;reqHalf=4;chord=.02;maxSeg=2;scale=600",
                    "elapsedMs=" + (System.nanoTime() - start) / 1_000_000, t);
            return null;
        }
    }

    private void inverse(long seed) {
        var g = near.get(seed);
        if (g == null) {
            event("inverse", seed, 512, 512, "BLOCKED", "reuse near geometry", "near constructor failed; no substitute geometry", null);
            return;
        }
        for (double[] p : points()) {
            double[] uv = g.warpForward(p[0], p[1]);
            double[] dx = g.warpForward(p[0] + .05, p[1]), dz = g.warpForward(p[0], p[1] + .05);
            event("float-derivative-evidence", seed, p[0], p[1], "INFO", "h=.05;uv=" + Arrays.toString(uv),
                    "ulpX=" + Math.ulp((float) p[0]) + ";ulpZ=" + Math.ulp((float) p[1])
                            + ";castXUnchanged=" + ((float) (p[0] + .05) == (float) p[0])
                            + ";castZUnchanged=" + ((float) (p[1] + .05) == (float) p[1])
                            + ";forwardDx=" + Arrays.toString(dx) + ";forwardDz=" + Arrays.toString(dz), null);
            List<double[]> roots = new ArrayList<>();
            for (double[] guess : new double[][]{{p[0], p[1]}, {Double.NaN, Double.NaN}, {p[0] + 100, p[1] - 75}}) {
                double[] root = inverseOne(g, seed, p, uv, guess, "forward-target");
                if (root != null && root[2] <= g.invertTolBlocks()) roots.add(root);
            }
            inspectRoots(g, seed, p, uv, roots);
            // A boundary inverse must also handle targets not exactly on the float forward lattice.
            inverseOne(g, seed, p, new double[]{uv[0] + .173 / (double) SCALE, uv[1] - .119 / (double) SCALE},
                    new double[]{p[0], p[1]}, "off-lattice-target");
        }
    }

    private double[] inverseOne(LocalBoundaryGeometry g, long seed, double[] p, double[] uv,
                                double[] guess, String kind) {
        try {
            inverseCalls++;
            double[] root = g.warpInverse(uv[0], uv[1], guess[0], guess[1]);
            double[] check = g.warpForward(root[0], root[1]);
            double frequency = (Float) RecipeMorphCatalog.field(
                    CoherentRegionDistribution.microPartition(seed, SCALE), "frequency");
            double residual = Math.max(Math.abs(check[0] - uv[0]), Math.abs(check[1] - uv[1])) / frequency;
            boolean ok = Double.isFinite(root[2]) && Double.isFinite(residual)
                    && root[2] <= g.invertTolBlocks() && residual <= g.invertTolBlocks();
            event("inverse/" + kind, seed, p[0], p[1], ok ? "PASS" : "FAIL",
                    "target=" + Arrays.toString(uv) + ";guess=" + Arrays.toString(guess),
                    "root=" + Arrays.toString(root) + ";forwardRoot=" + Arrays.toString(check)
                            + ";recomputedResidual=" + residual + ";invertTol=" + g.invertTolBlocks(), null);
            return new double[]{root[0], root[1], Math.max(root[2], residual)};
        } catch (Throwable t) { exception("inverse/" + kind, seed, p[0], p[1], t); return null; }
    }

    private void inspectRoots(LocalBoundaryGeometry g, long seed, double[] p, double[] uv, List<double[]> roots) {
        for (int i = 0; i < roots.size(); i++) for (int j = i + 1; j < roots.size(); j++) {
            double[] a = roots.get(i), b = roots.get(j);
            double separation = Math.hypot(a[0] - b[0], a[1] - b[1]);
            double plateau = 4 * Math.max(Math.ulp((float) p[0]), Math.ulp((float) p[1]));
            if (separation <= .1) continue;
            boolean ambiguous = separation > Math.max(.1, plateau);
            event("inverse-distinct-preimages", seed, p[0], p[1], ambiguous ? "FAIL" : "INFO",
                    "target=" + Arrays.toString(uv) + ";invertTol=" + g.invertTolBlocks(),
                    "rootA=" + Arrays.toString(a) + ";rootB=" + Arrays.toString(b) + ";separation=" + separation
                            + ";floatPlateauScale=" + plateau + ";notAProofOfGlobalInjectivity", null);
        }
    }

    private void farGeometry() {
        double[][] cases = {{1e4, -1e4}, {-1e5, 1e5}, {1e6, -1e6}, {-1e7, 1e7},
                {29998000, -29998000}, {-29998000, 29998000}};
        for (int i = 0; i < cases.length; i++) {
            long seed = SEEDS[i % SEEDS.length]; double[] p = cases[i];
            var g = build(seed, p[0], p[1], "geometry-far");
            if (g != null) try {
                var q = g.query(p[0], p[1]);
                event("geometry-far-query", seed, p[0], p[1], "PASS", "center query",
                        "winner=" + q.cellX + "," + q.cellZ + ";weights=" + Arrays.toString(q.weight), null);
            } catch (Throwable t) { exception("geometry-far-query", seed, p[0], p[1], t); }
        }
    }

    private void seams(long seed) {
        var a = near.get(seed);
        var b = build(seed, 1536, 512, "geometry-adjacent");
        if (a == null || b == null) {
            event("seams", seed, 1024, 512, "BLOCKED", "centers=(512,512),(1536,512)",
                    "one or both actual constructors failed; no seam PASS", null);
            return;
        }
        var d = distributions.get(seed);
        int[] ar = recipes(a, d), br = recipes(b, d);
        var aw = new RecipeBoundaryWeights(a, ar); var bw = new RecipeBoundaryWeights(b, br);
        List<double[]> sample = new ArrayList<>();
        for (double z : new double[]{384, 512, 640}) sample.add(new double[]{1024, z});
        for (var e : a.edges()) {
            if (sample.size() >= 6 || e.wx.length == 0) break;
            int k = e.wx.length / 2; double x = e.wx[k], z = e.wz[k];
            if (Math.hypot(x - 512, z - 512) < 650 && Math.hypot(x - 1536, z - 512) < 650)
                sample.add(new double[]{x, z});
        }
        for (double[] p : sample) seamPoint(seed, a, b, aw, bw, ar, br, p);
    }

    private static int[] recipes(LocalBoundaryGeometry g, CoherentRegionDistribution d) {
        int[] ids = new int[g.sites().size()];
        for (var site : g.sites()) ids[site.index()] = d.choose(site).recipe();
        return ids;
    }

    private void seamPoint(long seed, LocalBoundaryGeometry a, LocalBoundaryGeometry b,
                           RecipeBoundaryWeights aw, RecipeBoundaryWeights bw, int[] ar, int[] br, double[] p) {
        try {
            var qa = a.query(p[0], p[1]); var qb = b.query(p[0], p[1]);
            double[] wa = aw.weights(p[0], p[1], ar[qa.winnerIndex()]);
            double[] wb = bw.weights(p[0], p[1], br[qb.winnerIndex()]);
            double recipeDiff = maxDiff(wa, wb);
            double distanceDiff = siteDiff(seed, p, a, b, qa, qb, true);
            double siteWeightDiff = siteDiff(seed, p, a, b, qa, qb, false);
            boolean same = qa.cellX == qb.cellX && qa.cellZ == qb.cellZ;
            event("seam-same-point", seed, p[0], p[1], same && recipeDiff <= SEAM_TOL
                            && distanceDiff <= SEAM_TOL && siteWeightDiff <= SEAM_TOL ? "PASS" : "FAIL",
                    "S1 labels only, NOT S2/S3 full height;seamTol=" + SEAM_TOL,
                    "winnerA=" + qa.cellX + "," + qa.cellZ + ";winnerB=" + qb.cellX + "," + qb.cellZ
                            + ";bandClippedDistanceMax=" + distanceDiff + ";siteWeightMax=" + siteWeightDiff
                            + ";recipeWeightMax=" + recipeDiff + ";recipeA=" + Arrays.toString(wa)
                            + ";recipeB=" + Arrays.toString(wb), null);
            seamPoints++;
        } catch (Throwable t) { exception("seam-same-point", seed, p[0], p[1], t); }
    }

    private double siteDiff(long seed, double[] p, LocalBoundaryGeometry a, LocalBoundaryGeometry b,
                            LocalBoundaryGeometry.Sample qa, LocalBoundaryGeometry.Sample qb, boolean distance) {
        Map<Long, Integer> ai = new LinkedHashMap<>(), bi = new LinkedHashMap<>();
        for (var s : a.sites()) ai.put(s.key(), s.index());
        for (var s : b.sites()) bi.put(s.key(), s.index());
        var keys = new java.util.LinkedHashSet<>(ai.keySet()); keys.addAll(bi.keySet());
        double max = 0;
        for (long key : keys) {
            Integer ia = ai.get(key), ib = bi.get(key);
            double rawA = ia == null ? Double.NaN : qa.signedDistance[ia];
            double rawB = ib == null ? Double.NaN : qb.signedDistance[ib];
            double va = distance ? band(rawA) : ia == null ? 0 : qa.weight[ia];
            double vb = distance ? band(rawB) : ib == null ? 0 : qb.weight[ib];
            max = Math.max(max, Math.abs(va - vb));
            event(distance ? "seam-cell-distance" : "seam-cell-weight", seed, p[0], p[1], "INFO",
                    "cellKey=" + key + ";indexA=" + ia + ";indexB=" + ib + ";bandClip=120",
                    "rawDistanceA=" + rawA + ";rawDistanceB=" + rawB + ";comparedA=" + va + ";comparedB=" + vb, null);
        }
        return max;
    }

    private static double band(double d) {
        // Missing/NaN cells cannot contribute inside the band; raw values remain in the CSV.
        return Double.isNaN(d) ? -120 : Math.max(-120, Math.min(120, d));
    }

    private void matcher(long seed, boolean far) {
        double x = far ? 1e7 : 0, z = far ? -1e7 : 0;
        String stage = "matcher-" + (far ? "far" : "near");
        LocalBoundaryGeometry.Cell cell = findSea(seed, x, z, stage);
        if (cell == null) {
            event(stage, seed, x, z, "BLOCKED", "finite 9x9 candidate cells", "no non-shelf sea cell found", null);
            return;
        }
        var matcher = new ShallowRegionMatcher(seed);
        double wx = cell.vx() * SCALE, wz = cell.vz() * SCALE;
        matcherCalls++;
        try {
            var result = matcher.match(cell);
            boolean valid = result.before() >= RegionalMorphWorld.LAND_COUNT
                    && result.before() != ShallowRegionMatcher.SHELF && result.boundarySegments() > 0
                    && matcher.builds() > 0 && result.after() >= RegionalMorphWorld.LAND_COUNT;
            event(stage, seed, wx, wz, valid ? "PASS" : "FAIL", "cell=" + cell + ";site=" + cell.vx() + "," + cell.vz(),
                    result + ";builds=" + matcher.builds() + ";cache=" + matcher.cachedPatches(), null);
        } catch (Throwable t) {
            event(stage, seed, wx, wz, "FAIL", "cell=" + cell + ";site=" + cell.vx() + "," + cell.vz(),
                    "completedPatches=" + matcher.builds() + ";failedPatchNotIncludedInBuilds", t);
        }
        matcherBuilds += (int) matcher.builds();
    }

    private LocalBoundaryGeometry.Cell findSea(long seed, double x, double z, String stage) {
        int siteSeed = CoherentRegionDistribution.base(seed) + 23;
        int cx = (int) Math.floor(x / SCALE), cz = (int) Math.floor(z / SCALE);
        var d = distributions.get(seed);
        for (int r = 0; r <= 4; r++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            double[] site = CoherentRegionDistribution.site(siteSeed, cx + dx, cz + dz);
            var cell = new LocalBoundaryGeometry.Cell(cx + dx, cz + dz, site[0], site[1], 0);
            var choice = d.choose(cell);
            event(stage + "/selection", seed, site[0] * SCALE, site[1] * SCALE, "INFO",
                    "cell=" + cell + ";searchCenter=" + x + "," + z + ";radiusCells<=4", choice.toString(), null);
            if (choice.recipe() >= RegionalMorphWorld.LAND_COUNT && choice.recipe() != ShallowRegionMatcher.SHELF)
                return cell;
        }
        return null;
    }

    private void height(long seed) {
        RegionalMorphWorld world;
        try {
            world = new RegionalMorphWorld(new RecipeMorphCatalog(seed), 600);
            event("height-constructor", seed, 0, 0, "PASS", "new RegionalMorphWorld(new RecipeMorphCatalog(seed),600)",
                    "constructed; sample support still requires actual queries", null);
        } catch (UnsupportedOperationException t) {
            event("height-constructor", seed, 0, 0, "BLOCKED", "new RegionalMorphWorld(new RecipeMorphCatalog(seed),600)",
                    "expected unsupported is NOT successful seed coverage; guard NOT bypassed", t);
            return;
        } catch (Throwable t) { exception("height-constructor", seed, 0, 0, t); return; }
        for (double[] p : new double[][]{{512, 512}, {1e7, -1e7}}) {
            if (explicitWindows >= WINDOW_LIMIT) {
                event("height-sample", seed, p[0], p[1], "BLOCKED", "windowLimit=" + WINDOW_LIMIT, "window budget exhausted", null);
                continue;
            }
            explicitWindows++;
            try {
                var sample = world.sample(p[0], p[1]);
                event("height-sample", seed, p[0], p[1], Double.isFinite(sample.e()) ? "PASS" : "FAIL",
                        "actual RegionalMorphWorld.sample;scale=600", "e=" + sample.e() + ";winner=" + sample.winnerRecipe()
                                + ";dominant=" + sample.dominant() + ";seaWeight=" + sample.seaWeight()
                                + ";active=" + sample.active() + ";weights=" + Arrays.toString(sample.weights()), null);
                heightPoints++;
            } catch (Throwable t) { exception("height-sample", seed, p[0], p[1], t); }
        }
    }

    private void completeS3() {
        double x = 1000000, z = 1000000;
        explicitWindows++;
        try {
            var world = new CoherentMorphWorld();
            var sample = world.sample(x, z);
            event("complete-S3-far", CANONICAL, x, z,
                    Double.isFinite(sample.beforeE()) && Double.isFinite(sample.afterE()) ? "PASS" : "FAIL",
                    "new CoherentMorphWorld().sample(1000000,1000000);original seed guard retained",
                    "beforeE=" + sample.beforeE() + ";afterE=" + sample.afterE() + ";winner=" + sample.winner()
                            + ";before=" + Arrays.toString(sample.before()) + ";after=" + Arrays.toString(sample.after()), null);
        } catch (Throwable t) {
            event("complete-S3-far", CANONICAL, x, z, "FAIL",
                    "new CoherentMorphWorld().sample(1000000,1000000);original seed guard retained", "", t);
        }
    }

    private void exception(String stage, long seed, double x, double z, Throwable t) {
        event(stage, seed, x, z, "FAIL", "see stage inputs and preceding evidence", "", t);
    }

    private static String stack(Throwable t) {
        var text = new StringWriter();
        t.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private void event(String stage, long seed, double x, double z, String status,
                       String input, String output, Throwable t) {
        if (status.equals("FAIL")) failures++;
        if (status.equals("BLOCKED")) blocked++;
        counts.merge(stage + "/" + status, 1, Integer::sum);
        csv.println(row(stage, seed, x, z, status, input, output,
                t == null ? "" : t.getClass().getName(), t == null ? "" : t.getMessage(), t == null ? "" : stack(t)));
        if (status.equals("FAIL") && !stage.startsWith("inverse/" ) || status.equals("BLOCKED"))
            System.out.println(stage + " " + status + " seed=" + seed + " x=" + x + " z=" + z + " " + (t == null ? output : t));
    }

    private void writeSummary(Path out) throws Exception {
        List<String> report = new ArrayList<>();
        report.add("S4=" + (failures > 0 ? "FAIL" : blocked > 0 ? "BLOCKED" : "PASS"));
        report.add("exit=" + (failures > 0 ? 1 : blocked > 0 ? 2 : 0) + " failures=" + failures + " blocked=" + blocked);
        report.add("seeds=" + Arrays.toString(SEEDS) + "; scale=600; macro=1200; radius=728; reqHalf=4; chord=.02; maxSeg=2; seamTol=.002");
        report.add("macroPoints=" + identities + " microPoints=" + microPoints + " inverseCalls=" + inverseCalls
                + " seamPoints=" + seamPoints + " heightPoints=" + heightPoints);
        report.add("explicitWindowsIncludingHeight=" + explicitWindows + " limit=" + WINDOW_LIMIT
                + " matcherCalls=" + matcherCalls + " matcherCompletedPatches=" + matcherBuilds);
        report.add("Matcher internal patch constructors are real/unmodified; builds() excludes a throwing patch. At most two actual sea cells/seed.");
        report.add("No production, defaults, bandwidth, terrain fixes, S3 chain duplication or unsupported-seed guard bypass.");
        report.add("Float .05 collapse is INFO only; FAIL requires actual inverse residual, geometry, identity or seam evidence.");
        report.add("Seams use S1 labels, NOT full S2/S3 heights. Signed distances compared only within existing +/-120 support; raw distances retained.");
        report.add("Finite points/windows do not prove injectivity or infinite-world correctness. BLOCKED is never S4 PASS.");
        counts.forEach((key, value) -> report.add(key + "=" + value));
        Files.write(out.resolve("check.txt"), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(String.join("\n", report));
        if (csv.checkError()) throw new IllegalStateException("CSV write failed");
    }

    private static String row(Object... values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) result.add("\"" + String.valueOf(value).replace("\"", "\"\"") + "\"");
        return String.join(",", result);
    }

    private static double maxDiff(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }
}
