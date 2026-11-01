package com.geogenesis.worldgen.terrain.candidate;

import java.awt.geom.Line2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** S3 bounded diagnostics only: independent union-distance oracle, no production wiring or raster. */
public final class CoherentMorphCheck {
    private static final int COUNT = RegionalMorphWorld.COUNT;
    private static final int LAND = RegionalMorphWorld.LAND_COUNT;
    private static final double SEAM_TOL = 2e-3;
    private final List<String> report = new ArrayList<>();
    private final Map<String, List<String>> tables = new LinkedHashMap<>();
    private int checks, failures, actualPoints;
    private double actualWeightMax, oldQueryMax, evaluatorMax;
    private CoherentMorphWorld world;
    private LocalBoundaryGeometry fixture;

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: CoherentMorphCheck outDirectory [frozenS2Directory]");
            System.exit(1);
        }
        var check = new CoherentMorphCheck();
        Path out = Path.of(args[0]);
        Path frozen = args.length == 2 ? Path.of(args[1]) : Path.of("build/coherent-morph-s3/frozen-s2");
        try {
            Files.createDirectories(out);
            check.run(out, frozen);
        } catch (Throwable t) {
            System.err.println("COHERENT MORPH CHECK FAIL: " + t);
            System.exit(1);
        }
        if (check.failures != 0) System.exit(1);
    }

    private void run(Path out, Path frozen) throws Exception {
        report.add("Bounded synthetic/local samples; NOT an infinite-world or warp-injectivity proof.");
        report.add("Active support may stay identical. No gate requires active/onehot proportions to improve.");
        report.add("Internal segments are excluded from NEW distance tests; OLD test count is uninstrumented/unknown.");
        report.add("Gallery owns full raster/frozen pixel parity; this check reads sites.csv only.");
        stage(out, "synthetic-unions", this::syntheticUnions);
        stage(out, "band240-single-ease", this::band);
        stage(out, "indexed-vs-exhaustive", this::indexed);
        world = new CoherentMorphWorld();
        stage(out, "actual-forced-tile-seams", this::seams);
        stage(out, "actual-boundaries", this::actualBoundaries);
        stage(out, "frame-and-real-union", this::frameAndUnion);
        stage(out, "raster-probes", this::rasterProbes);
        stage(out, "signed-e-and-tiny-sea", this::engineRegressions);
        stage(out, "frozen-s2-identities", () -> frozenIdentities(frozen));
        report.add("actual geometry.query/oracle points=" + actualPoints + " maxNewWeightAbs=" + actualWeightMax
                + " maxOldQueryWeightAbs=" + oldQueryMax + " maxEvaluateAbs=" + evaluatorMax);
        report.add("world stats (this bounded check only):\n" + world.stats());
        report.add("checks=" + checks + " failures=" + failures);
        save(out);
        System.out.println("COHERENT MORPH CHECK " + (failures == 0 ? "PASS" : "FAIL")
                + " checks=" + checks + " failures=" + failures + " out=" + out.toAbsolutePath());
    }

    private interface Stage { void run() throws Exception; }

    private void stage(Path out, String name, Stage body) throws Exception {
        long start = System.nanoTime();
        try {
            body.run();
            report.add(name + " PASS elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
            System.out.println(name + " PASS");
        } catch (Throwable t) {
            failures++;
            String failure = name + " FAIL " + t;
            report.add(failure);
            System.err.println(failure);
        }
        save(out);
    }

    private void save(Path out) throws Exception {
        Files.write(out.resolve("check.txt"), report);
        for (var entry : tables.entrySet()) Files.write(out.resolve(entry.getKey()), entry.getValue());
    }

    private void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private List<String> table(String name, String header) {
        return tables.computeIfAbsent(name, key -> new ArrayList<>(List.of(header)));
    }

    /** Only this private disposable geometry's edge list is replaced, never a world/production tile. */
    private RecipeBoundaryWeights fixture(int[] ids, RecipeBoundaryWeights.Boundary... edges) {
        if (fixture == null) fixture = LocalBoundaryGeometry.of(
                CoherentRegionDistribution.microPartition(RegionalMorphGallery.SEED, 600), 0, 0, 160, 2, .02);
        fixture.edges().clear();
        for (var b : edges) {
            var a = new LocalBoundaryGeometry.Cell(b.a(), 0, b.a(), 0, b.a());
            var c = new LocalBoundaryGeometry.Cell(b.b(), 0, b.b(), 0, b.b());
            fixture.edges().add(new LocalBoundaryGeometry.Edge(fixture.edges().size(), a, c,
                    List.of(new double[]{b.ax(), b.az()}, new double[]{b.bx(), b.bz()}),
                    Math.hypot(b.bx() - b.ax(), b.bz() - b.az()), false));
        }
        return new RecipeBoundaryWeights(fixture, ids);
    }

    private static RecipeBoundaryWeights.Boundary edge(int a, int b, double x, double z, double u, double v) {
        return new RecipeBoundaryWeights.Boundary(a, b, x, z, u, v);
    }

    private void syntheticUnions() {
        var all = fixture(new int[]{0, 0, 0}, edge(0, 1, -40, -800, -40, 800),
                edge(1, 2, 40, -800, 40, 800));
        require(all.boundaries().isEmpty() && all.internalSegments() == 2, "all-A retained internal edges");
        for (double x : new double[]{-121, -40, 0, 40, 121}) onehot(all.weights(x, -17, 0), 0, "all-A");
        require(all.distanceTests() == 0, "all-A still performs distance tests");
        var aab = fixture(new int[]{0, 0, 1}, edge(0, 1, -40, -800, -40, 800),
                edge(1, 2, 40, -800, 40, 800));
        double[] support = aab.weights(-40, -17, 0);
        require(aab.internalSegments() == 1 && aab.boundaries().size() == 1, "A/A/B edge filtering");
        require(support[1] > 0, "A/A/B early-return erased B within 80 blocks");
        near(support[1], ease(-80), 1e-14, "A/A/B B-support");
        recordWeights("A/A/B", -40, -17, support, oracle(aab.boundaries(), -40, -17, 0));
        var aba = fixture(new int[]{0, 1, 0}, edge(0, 1, -40, -800, -40, 800),
                edge(1, 2, 40, -800, 40, 800));
        for (double x : new double[]{-160, -60, -40, -20, 0, 20, 40, 60, 160}) {
            int winner = Math.abs(x) <= 40 ? 1 : 0;
            recordWeights("A/B/A", x, -17, aba.weights(x, -17, winner), oracle(aba.boundaries(), x, -17, winner));
        }
        near(aba.weights(0, 0, 1)[0], ease(-40), 1e-14, "A/B/A counted both A components twice");
        var hole = fixture(new int[]{0, 1}, edge(0, 1, -40, -40, 40, -40),
                edge(0, 1, 40, -40, 40, 40), edge(0, 1, 40, 40, -40, 40), edge(0, 1, -40, 40, -40, -40));
        near(hole.weights(0, 0, 1)[0], ease(-40), 1e-14, "hole union counted four sides repeatedly");
        recordWeights("A-hole-B", 0, 0, hole.weights(0, 0, 1), oracle(hole.boundaries(), 0, 0, 1));
        triple();
        report.add("synthetic: all-A removed=2 tests=0; A/A/B BSupport=" + support[1]
                + "; A/B/A and closed hole use min distance to the same A union, not summed sides");
    }

    private void triple() {
        double h = Math.sqrt(3) * 500;
        var triple = fixture(new int[]{0, 1, 2}, edge(0, 1, 0, 0, 1000, 0),
                edge(1, 2, 0, 0, -500, h), edge(2, 0, 0, 0, -500, -h));
        for (int winner = 0; winner < 3; winner++) {
            double[] at = triple.weights(0, 0, winner);
            for (int i = 0; i < 3; i++) near(at[i], 1.0 / 3, 1e-14, "three-way vertex");
            recordWeights("triple-vertex", 0, 0, at, oracle(triple.boundaries(), 0, 0, winner));
        }
        for (int sector = 0; sector < 3; sector++) {
            double angle = Math.PI / 3 + sector * 2 * Math.PI / 3;
            int winner = new int[]{1, 2, 0}[sector];
            for (double radius : new double[]{1, 37, 110}) {
                double x = Math.cos(angle) * radius, z = Math.sin(angle) * radius;
                recordWeights("triple-sector", x, z, triple.weights(x, z, winner),
                        oracle(triple.boundaries(), x, z, winner));
            }
        }
    }

    /** Literal contract constants are intentional: importing the implementation's width would self-certify. */
    private void band() {
        require(RecipeBoundaryWeights.HALF == 120, "S3 half-band changed");
        var w = new RecipeBoundaryWeights(List.of(edge(0, 1, 0, -2000, 0, 2000)));
        var rows = table("band.csv", "x,actualB,independentB,doubleEaseB,absError");
        double worst = 0; int counterexamples = 0, points = 0;
        for (int k = -480; k <= 480; k++) {
            double x = k * .5, expected = ease(x);
            double actual = w.weights(x, -120, x <= 0 ? 0 : 1)[1];
            double twice = expected * expected * (3 - 2 * expected);
            worst = Math.max(worst, Math.abs(actual - expected));
            if (Math.abs(twice - expected) > 1e-3) counterexamples++;
            rows.add(x + "," + actual + "," + expected + "," + twice + "," + Math.abs(actual - expected));
            points++;
        }
        report.add("band points=" + points + " maxAbs=" + worst + " doubleEaseCounterexamples=" + counterexamples);
        require(worst <= 1e-14 && counterexamples > 0, "240-band/single-ease regression max=" + worst);
        for (double x : new double[]{-121, -120, 0, 120, 121})
            near(w.weights(x, 0, x <= 0 ? 0 : 1)[1], ease(x), 1e-14, "band anchor " + x);
        require(w.weights(-119.5, 0, 0)[1] > 0 && w.weights(119.5, 0, 1)[0] > 0, "band narrowed");
    }

    private static double ease(double signedDistance) {
        double t = Math.max(0, Math.min(1, (signedDistance + 120.0) / 240.0));
        return t * t * (3 - 2 * t);
    }

    /** No bins, bbox pruning, implementation distance/progress/normalize calls. */
    private static double[] oracle(List<RecipeBoundaryWeights.Boundary> edges, double x, double z, int winner) {
        double[] distance = new double[COUNT];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        for (var b : edges) {
            double d = Line2D.ptSegDist(b.ax(), b.az(), b.bx(), b.bz(), x, z);
            distance[b.a()] = Math.min(distance[b.a()], d);
            distance[b.b()] = Math.min(distance[b.b()], d);
        }
        double[] expected = new double[COUNT];
        double sum = 0;
        for (int i = 0; i < COUNT; i++) sum += expected[i] = ease(i == winner ? distance[i] : -distance[i]);
        for (int i = 0; i < COUNT; i++) expected[i] /= sum;
        return expected;
    }

    private void indexed() {
        List<RecipeBoundaryWeights.Boundary> edges = new ArrayList<>(List.of(
                edge(0, 1, -240, -360, -240, 360), edge(1, 2, -120, -120, 120, 120),
                edge(2, 0, -480, -240, -480, -240), edge(0, 2, 120, -480, -360, 240)));
        Random random = new Random(303120240L);
        for (int i = 0; i < 60; i++) {
            int a = random.nextInt(COUNT), b = (a + 1 + random.nextInt(COUNT - 1)) % COUNT;
            double x = random.nextDouble() * 2000 - 1000, z = random.nextDouble() * 2000 - 1000;
            edges.add(edge(a, b, x, z, x + random.nextDouble() * 300 - 150, z + random.nextDouble() * 300 - 150));
        }
        var indexed = new RecipeBoundaryWeights(edges);
        double worst = 0; int points = 0, negative = 0, active = 0;
        for (int i = 0; i < 1200; i++) {
            double x = i < 100 ? (i % 10 - 5) * 120.0 : random.nextDouble() * 2400 - 1200;
            double z = i < 100 ? (i / 10 - 5) * 120.0 : random.nextDouble() * 2400 - 1200;
            int winner = random.nextInt(COUNT);
            double[] got = indexed.weights(x, z, winner), expected = oracle(edges, x, z, winner);
            double error = maxDiff(got, expected); worst = Math.max(worst, error);
            if (x < 0 || z < 0) negative++;
            if (active(got) > 1) active++;
            if (i < 100) recordWeights("index-grid", x, z, got, expected);
            require(error <= 1e-9, "indexed/exhaustive at " + x + "," + z + " error=" + error);
            points++;
        }
        report.add("indexed Line2D exhaustive: segments=" + edges.size() + " points=" + points
                + " negativeCoordinatePoints=" + negative + " multiSupport=" + active + " maxAbs=" + worst
                + " newDistanceTests=" + indexed.distanceTests());
        require(points > 0 && negative > 0 && active > 0 && indexed.distanceTests() > 0, "empty index audit");
    }

    private void seams() {
        var rows = table("tile-seams.csv", "x,z,leftWinner,rightWinner,newWeightMax,newEAbs,oldWeightMax,oldEAbs");
        double worstW = 0, worstE = 0; int points = 0, blended = 0;
        for (double x : new double[]{0, 1024}) {
            for (int z = -960; z <= 960; z += 64) {
                var left = world.tileAt(x - .5, z); var right = world.tileAt(x + .5, z);
                require(left.original().tileX != right.original().tileX, "seam used same tile");
                var a = world.sample(left, x, z); var b = world.sample(right, x, z);
                checkActual(left, x, z, a); checkActual(right, x, z, b);
                double dw = maxDiff(a.after(), b.after()), de = Math.abs(a.afterE() - b.afterE());
                worstW = Math.max(worstW, dw); worstE = Math.max(worstE, de);
                if (active(a.after()) > 1 || active(b.after()) > 1) blended++;
                rows.add(x + "," + z + "," + a.winner() + "," + b.winner() + "," + dw + "," + de
                        + "," + maxDiff(a.before(), b.before()) + "," + Math.abs(a.beforeE() - b.beforeE()));
                require(a.winner() == b.winner(), "cross-tile winner drift at " + x + "," + z);
                require(dw <= SEAM_TOL && de <= SEAM_TOL, "forced seam " + x + "," + z + " weight=" + dw + " E=" + de);
                points++;
            }
        }
        report.add("forced same-point x=0/1024 seamPairs=" + points + " blendedPairs=" + blended
                + " maxNewWeightAbs=" + worstW + " maxNewEAbs=" + worstE + " tolerance=" + SEAM_TOL);
        require(points == 62 && blended > 0, "empty or pure-only seam audit");
    }

    private void checkActual(CoherentMorphWorld.Tile tile, double x, double z, CoherentMorphWorld.Pair p) {
        var q = tile.original().geometry().query(x, z);
        int winner = tile.recipes()[q.winnerIndex()];
        require(p.winner() == winner, "sample winner != geometry.query at " + x + "," + z);
        var edges = exterior(tile);
        double[] expected = oracle(edges, x, z, winner);
        actualWeightMax = Math.max(actualWeightMax, maxDiff(p.after(), expected));
        actualPoints++;
        recordWeights("actual-index", x, z, p.after(), expected);
        double[] before = new double[COUNT];
        for (int i = 0; i < q.weight.length; i++) before[tile.recipes()[i]] += q.weight[i];
        double sum = Arrays.stream(before).sum();
        for (int i = 0; i < COUNT; i++) before[i] /= sum;
        oldQueryMax = Math.max(oldQueryMax, maxDiff(p.before(), before));
        near(maxDiff(p.before(), before), 0, 1e-12, "old weights != independently merged geometry.query");
        double oldE = world.evaluator().evaluate(x, z, p.before());
        double newE = world.evaluator().evaluate(x, z, p.after());
        evaluatorMax = Math.max(evaluatorMax, Math.max(Math.abs(p.beforeE() - oldE), Math.abs(p.afterE() - newE)));
        near(p.beforeE(), oldE, 0, "old E bypassed evaluate");
        near(p.afterE(), newE, 0, "new E bypassed evaluate");
    }

    /** Independently rebuild exterior membership from real site labels, not boundary.boundaries(). */
    private static List<RecipeBoundaryWeights.Boundary> exterior(CoherentMorphWorld.Tile tile) {
        List<RecipeBoundaryWeights.Boundary> result = new ArrayList<>();
        for (var e : tile.original().geometry().edges()) {
            int a = tile.recipes()[e.a.index()], b = tile.recipes()[e.b.index()];
            if (a != b) for (int i = 1; i < e.wx.length; i++)
                result.add(edge(a, b, e.wx[i - 1], e.wz[i - 1], e.wx[i], e.wz[i]));
        }
        return result;
    }

    private void actualBoundaries() {
        var rows = table("real-edge-midpoints.csv", "x,z,recipeA,recipeB,internal,tileX,tileZ");
        int internal = 0, external = 0, far = 0, oldOnehot = 0;
        for (int tz : new int[]{-1, 0}) for (int tx : new int[]{-1, 0, 1}) {
            var tile = world.tileAt((tx + .5) * 1024, (tz + .5) * 1024);
            var outer = exterior(tile); long removed = 0;
            for (var e : tile.original().geometry().edges()) {
                int a = tile.recipes()[e.a.index()], b = tile.recipes()[e.b.index()];
                if (a == b) { internal++; removed += e.wx.length - 1; } else external++;
                int mid = e.wx.length / 2; double x = e.wx[mid], z = e.wz[mid];
                rows.add(x + "," + z + "," + a + "," + b + "," + (a == b) + "," + tx + "," + tz);
                if (a != b || far >= 16 || x < tx * 1024 || x >= (tx + 1) * 1024
                        || z < tz * 1024 || z >= (tz + 1) * 1024 || nearest(outer, x, z) < 120) continue;
                var p = world.sample(tile, x, z);
                checkActual(tile, x, z, p);
                onehot(p.after(), a, "real internal edge far from foreign boundary");
                if (active(p.before()) == 1) oldOnehot++;
                far++;
            }
            require(tile.boundary().internalSegments() == removed, "real internal segment count");
            require(tile.boundary().boundaries().size() == outer.size(), "real exterior segment count");
        }
        report.add("real edge midpoint records: internal=" + internal + " external=" + external
                + " farInternalSamples=" + far + " oldAlsoOnehot=" + oldOnehot
                + "; this CSV is edge evidence, NOT a raster/pixel mask or active-ratio claim");
        require(internal > 0 && external > 0 && far > 0, "empty real internal/external/onehot audit");
    }

    private static double nearest(List<RecipeBoundaryWeights.Boundary> edges, double x, double z) {
        double result = Double.POSITIVE_INFINITY;
        for (var b : edges) result = Math.min(result, Line2D.ptSegDist(b.ax(), b.az(), b.bx(), b.bz(), x, z));
        return result;
    }

    private void frameAndUnion() {
        int frames = 0, third = 0, pure = 0;
        double minGap = Double.POSITIVE_INFINITY;
        for (int[] t : new int[][]{{0,0},{-3,-2},{-2,-3}}) {
            var tile = world.tileAt((t[0] + .5) * 1024, (t[1] + .5) * 1024);
            var g = tile.original().geometry();
            int[] same = new int[tile.recipes().length];
            var allA = new RecipeBoundaryWeights(g, same);
            require(allA.boundaries().isEmpty() && allA.internalSegments() > 0, "real all-A not filtered");
            onehot(allA.weights(g.centerX(), g.centerZ(), 0), 0, "real all-A");
            for (var e : g.edges()) {
                if (e.frameTruncated) for (int k : new int[]{0, e.wx.length - 1}) {
                    double[] uv = g.warpForward(e.wx[k], e.wz[k]), box = g.warpBox();
                    double bd = Math.min(Math.min(Math.abs(uv[0]-box[0]), Math.abs(uv[0]-box[1])),
                            Math.min(Math.abs(uv[1]-box[2]), Math.abs(uv[1]-box[3])));
                    if (bd >= 1e-3) continue;
                    double gap = Math.max(Math.max(t[0]*1024-e.wx[k], e.wx[k]-(t[0]+1)*1024),
                            Math.max(t[1]*1024-e.wz[k], e.wz[k]-(t[1]+1)*1024));
                    require(gap > 121, "frame endpoint entered tile+120 support");
                    minGap = Math.min(minGap, gap); frames++;
                }
                if (tile.recipes()[e.a.index()] != tile.recipes()[e.b.index()]) continue;
                int k = e.wx.length / 2; double x = e.wx[k], z = e.wz[k];
                if (x < t[0]*1024 || x >= (t[0]+1)*1024 || z < t[1]*1024 || z >= (t[1]+1)*1024) continue;
                var p = world.sample(tile, x, z);
                if (active(p.after()) > 1) { checkActual(tile, x, z, p); third++; }
                else {
                    near(p.afterE(), world.evaluator().hardE(x, z, p.winner()), 0, "real onehot hardE"); pure++;
                }
            }
        }
        require(frames > 0 && third > 0 && pure > 0, "empty frame/third/onehot audit");
        report.add("frame endpoints=" + frames + " minGap=" + minGap + " thirdSupport=" + third + " hardOnehot=" + pure);
    }

    private void rasterProbes() {
        String header = "x,z,winner";
        for (int i = 0; i < COUNT; i++) header += ",w" + i;
        var rows = table("raster-probes.csv", header);
        int points = 0;
        for (int[] t : new int[][]{{0,0},{-3,-2},{-2,-3}}) {
            var tile = world.tileAt((t[0]+.5)*1024, (t[1]+.5)*1024);
            for (int j = 0; j < 9; j++) for (int i = 0; i < 9; i++) {
                double x = t[0]*1024 + 64 + i*112, z = t[1]*1024 + 64 + j*112;
                int winner = tile.recipes()[tile.original().winnerIndex(x,z)];
                double[] w = tile.boundary().weights(x,z,winner);
                String row = x + "," + z + "," + winner;
                for (double v : w) row += "," + v;
                rows.add(row); points++;
            }
        }
        report.add("independent raster probes=" + points + " (EDT cross-check belongs to main agent)");
    }

    private void frozenIdentities(Path frozen) throws Exception {
        var rows = new ArrayList<>(Files.readAllLines(frozen.resolve("sites.csv")));
        require(!rows.isEmpty() && rows.remove(0).startsWith("cell,cellX,cellZ,before,after,"), "frozen sites.csv header");
        Collections.reverse(rows);
        var evidence = table("frozen-identities.csv", "cell,expected,actual");
        int count = 0, changed = 0, sea = 0;
        for (String row : rows) {
            String[] v = row.split(","); long key = Long.parseLong(v[0]);
            require(key == (((long) Integer.parseInt(v[1]) << 32) ^ (Integer.parseInt(v[2]) & 0xffffffffL)), "frozen cell key");
            int expected = Integer.parseInt(v[4]);
            int actual = world.recipe(CoherentShallowGallery.cell(key));
            evidence.add(key + "," + expected + "," + actual);
            require(actual == expected, "S2 final identity drift cell=" + key + " expected=" + expected + " actual=" + actual);
            if (Integer.parseInt(v[3]) != expected) changed++;
            if (expected >= LAND) sea++;
            count++;
        }
        report.add("frozen S2=" + frozen.toAbsolutePath() + " identityRows=" + count + " S2ChangedRows=" + changed
                + " seaRows=" + sea + " mismatches=0; reverse traversal, no raster regenerated");
        require(count > 0 && changed > 0 && sea > 0, "vacuous frozen S2 identity audit");
    }

    private void engineRegressions() {
        long seed = RegionalMorphGallery.SEED;
        var catalog = new RecipeMorphCatalog(seed);
        var engine = new NodeMorphEngine(MorphCatalog.world(catalog,
                new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, true)), seed);
        var rows = table("engine-regressions.csv", "kind,x,z,recipe,seaWeight,actual,expected,absError");
        double worstSea = 0, worstTiny = 0; int negative = 0, tiny = 0;
        for (int i = 0; i < 12; i++) {
            double x = -640 + i * 113, z = 590 - i * 97;
            for (int recipe = LAND; recipe < COUNT; recipe++) {
                double[] w = new double[COUNT]; w[recipe] = 1;
                double got = world.evaluator().evaluate(x, z, w), expected = engine.sampleE(x, z, w);
                double error = Math.abs(got - expected); worstSea = Math.max(worstSea, error);
                rows.add("sea," + x + "," + z + "," + recipe + ",1," + got + "," + expected + "," + error);
                require(got < 0 && error == 0, "negative E clipped or sea evaluator drift"); negative++;
            }
            for (double epsilon : new double[]{1e-18, 1e-20, Double.MIN_VALUE}) {
                double[] w = new double[COUNT]; w[0] = 1; w[LAND] = epsilon;
                require(1 + epsilon == 1 && w[LAND] > 0, "tiny sea fixture is not a rounding trap");
                boolean rejected = false;
                try { engine.sampleLand(x, z, w); } catch (IllegalArgumentException expected) { rejected = true; }
                require(rejected, "tiny sea no longer distinguishes sampleLand from sampleE");
                double got = world.evaluator().evaluate(x, z, w);
                double expected = mixedExpected(engine, x, z, w);
                double error = Math.abs(got - expected); worstTiny = Math.max(worstTiny, error);
                rows.add("tinysea," + x + "," + z + ",0," + epsilon + "," + got + "," + expected + "," + error);
                near(got, expected, 1e-14, "tinysea mixed evaluation"); tiny++;
            }
        }
        report.add("engine regression negativeE=" + negative + " tinySea=" + tiny
                + " maxSeaAbs=" + worstSea + " maxTinyAbs=" + worstTiny);
        require(negative > 0 && tiny > 0, "empty engine regressions");
    }

    private double mixedExpected(NodeMorphEngine engine, double x, double z, double[] w) {
        double e = engine.sampleE(x, z, w);
        var land = world.evaluator().originalLand();
        if (land.alphaOf(land.maskValue(x, z)) > 0) {
            double[] pure = new double[COUNT]; pure[0] = 1;
            double region = engine.sampleLand(x, z, pure);
            e += landE(land.replayOverlay(region, x, z)) - landE(region);
        }
        return e;
    }

    private static double landE(double value) {
        return Math.max(CombineCandidate.GROUND_E + value * CombineCandidate.E_SPAN, 0);
    }

    private void recordWeights(String kind, double x, double z, double[] got, double[] expected) {
        double error = maxDiff(got, expected);
        table("weight-samples.csv", "kind,x,z,active,maxAbs,actual,independent")
                .add(kind + "," + x + "," + z + "," + active(got) + "," + error
                        + ",\"" + Arrays.toString(got) + "\",\"" + Arrays.toString(expected) + "\"");
        near(error, 0, 1e-9, kind + " independent union weights at " + x + "," + z);
        double sum = 0;
        for (double v : got) { require(Double.isFinite(v) && v >= 0 && v <= 1, kind + " invalid weight"); sum += v; }
        near(sum, 1, 1e-12, kind + " normalization");
    }

    private void onehot(double[] w, int id, String kind) {
        for (int i = 0; i < w.length; i++) near(w[i], i == id ? 1 : 0, 0, kind + " recipe=" + i);
    }

    private void near(double got, double expected, double tolerance, String message) {
        require(Double.isFinite(got) && Math.abs(got - expected) <= tolerance,
                message + " got=" + got + " expected=" + expected + " tolerance=" + tolerance);
    }

    private static double maxDiff(double[] a, double[] b) {
        if (a.length != b.length) throw new AssertionError("weight length mismatch");
        double result = 0;
        for (int i = 0; i < a.length; i++) result = Math.max(result, Math.abs(a[i] - b[i]));
        return result;
    }

    private static int active(double[] w) {
        int result = 0;
        for (double v : w) if (v > 0) result++;
        return result;
    }
}
