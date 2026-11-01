package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 区域尺度实验相关回归；不重跑历史整图、广域票槽或水文门禁。 */
public final class RegionScaleCheck {
    private final RecipeMorphCatalog catalog = new RecipeMorphCatalog(RegionalMorphGallery.SEED);
    private final RegionalMorphWorld world = new RegionalMorphWorld(catalog, 600);
    private final List<String> report = new ArrayList<>();
    private double seamWeight, seamE;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        RegionScaleCheck c = new RegionScaleCheck();
        try {
            c.geometryAndIdentity();
            c.seams();
            c.endpointsAndRegressions();
            c.band();
            c.costs(out);
            c.report.add("PASS");
        } catch (Throwable e) {
            c.report.add("FAIL " + e);
            Files.write(out.resolve("check.txt"), c.report);
            e.printStackTrace();
            System.exit(1);
        }
        Files.write(out.resolve("check.txt"), c.report);
        System.out.println(String.join("\n", c.report));
    }

    private void geometryAndIdentity() {
        Random r = new Random(8317);
        int identities = 0, points = 0;
        for (int[] coord : new int[][]{{-2,-2},{-1,0},{0,0},{1,0},{0,1}}) {
            var a = world.tileAt(FullMorphLand.center(coord[0]), FullMorphLand.center(coord[1]));
            var other = new RegionalMorphWorld(new RecipeMorphCatalog(RegionalMorphGallery.SEED), 600);
            var geometry = LocalBoundaryGeometry.of(catalog.original(), FullMorphLand.center(coord[0] + 1),
                    FullMorphLand.center(coord[1]), FullMorphLand.QUERY_RADIUS, 4, .02, 600);
            geometry.setMaxSeg(2);
            var sites = geometry.sites();
            for (int i = sites.size() - 1; i >= 0; i--) other.choose(geometry, sites.get(i));
            for (var sa : a.original().geometry().sites()) for (var sb : sites) if (sa.key() == sb.key()) {
                var ca = world.choose(a.original().geometry(), sa);
                var cb = other.choose(geometry, sb);
                require(ca.equals(cb), "cross-window identity " + ca + " != " + cb);
                identities++;
            }
            for (int i = 0; i < 40; i++) {
                double x = a.original().centerX() + r.nextDouble() * 800 - 400;
                double z = a.original().centerZ() + r.nextDouble() * 800 - 400;
                var q = a.original().geometry().query(x, z);
                double[] weights = a.original().weights(x, z);
                require(Arrays.equals(weights, q.weight), "candidate bbox != exhaustive query");
                double sum = Arrays.stream(weights).sum();
                require(Math.abs(sum - 1) <= 1e-12, "site sum=" + sum);
                for (double v : weights) require(v >= 0 && Double.isFinite(v), "invalid weight");
                var sample = world.sample(a, x, z);
                require(Double.isFinite(sample.e()), "nonfinite output");
                require(Math.abs(Arrays.stream(sample.weights()).sum() - 1) <= 1e-12, "merged sum");
                points++;
            }
        }
        require(identities > 0, "identity test empty");
        report.add("600 geometry-query bitwise parity points=" + points + "; independent reverse identity pairs=" + identities);
    }

    private void seams() {
        int points = 0;
        for (boolean vertical : new boolean[]{true, false}) for (int ix = -2; ix <= 1; ix++) {
            int tx = vertical ? ix : -1, tz = vertical ? -1 : ix;
            var a = world.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
            var b = world.tileAt(FullMorphLand.center(tx + (vertical ? 1 : 0)),
                    FullMorphLand.center(tz + (vertical ? 0 : 1)));
            for (int k = -300; k <= 300; k += 30) {
                double x = vertical ? (tx + 1) * FullMorphLand.TILE_BLOCKS : a.original().centerX() + k;
                double z = vertical ? a.original().centerZ() + k : (tz + 1) * FullMorphLand.TILE_BLOCKS;
                var sa = world.sample(a, x, z); var sb = world.sample(b, x, z);
                seamE = Math.max(seamE, Math.abs(sa.e() - sb.e()));
                for (int j = 0; j < RegionalMorphWorld.COUNT; j++)
                    seamWeight = Math.max(seamWeight, Math.abs(sa.weights()[j] - sb.weights()[j]));
                points++;
            }
        }
        report.add("600 forced adjacent tile points=" + points + "; maxWeightDiff=" + seamWeight + "; maxEDiff=" + seamE);
        require(seamWeight <= 2e-3 && seamE <= 2e-3, "cross tile drift");
    }

    private void endpointsAndRegressions() throws Exception {
        NodeMorphEngine engine = new NodeMorphEngine(MorphCatalog.world(catalog,
                new OceanRecipeCatalog(RegionalMorphGallery.SEED + OceanRecipeCatalog.SEED_OFFSET, true)), RegionalMorphGallery.SEED);
        double worst = 0;
        for (double[] p : new double[][]{{-3500,-2200},{-1024,-1},{0,0},{712,-1800},{2500,3300}}) {
            for (int id = 0; id < RegionalMorphWorld.COUNT; id++) {
                double[] w = new double[RegionalMorphWorld.COUNT]; w[id] = 1;
                double expected = id < RegionalMorphWorld.LAND_COUNT
                        ? landE(world.originalLand().replayOverlay(engine.sampleLand(p[0], p[1], w), p[0], p[1]))
                        : engine.sampleE(p[0], p[1], w);
                worst = Math.max(worst, Math.abs(world.hardE(p[0], p[1], id) - expected));
            }
        }
        require(worst == 0, "pure endpoint/overlay drift=" + worst);
        double x = 0, z = 0;
        for (int i = -10; i <= 10; i++) if (world.originalLand().alphaOf(world.originalLand().maskValue(i * 700, 0)) > 0) {
            x = i * 700; break;
        }
        require(world.originalLand().alphaOf(world.originalLand().maskValue(x, z)) > 0, "no coverage trap witness");
        double[] w = new double[RegionalMorphWorld.COUNT]; w[0] = 1; w[18] = 1e-18;
        double[] landOnly = w.clone(); landOnly[18] = 0;
        double region = engine.sampleLand(x, z, landOnly);
        double delta = landE(world.originalLand().replayOverlay(region, x, z)) - landE(region);
        double expected = engine.sampleE(x, z, w) + delta;
        require(world.evaluate(x, z, w) == expected, "tiny ocean regression");
        boolean oldTrapFailed = false;
        try { engine.sampleLand(x, z, w); } catch (IllegalArgumentException e) { oldTrapFailed = true; }
        require(oldTrapFailed, "old sumLand==1 branch negative control failed to fail");
        var unit = RegionalMorphWorld.class.getDeclaredMethod("unit", int.class); unit.setAccessible(true);
        require((double) unit.invoke(null, Integer.MAX_VALUE) > .99, "31bit range regression");
        require(Integer.MAX_VALUE * 0x1.0p-32 < .5, "old hash negative control");
        report.add("23 onehot/overlay endpoints maxDiff=" + worst + "; tiny-sea and 31bit regressions PASS; old branch/hash controls detected");
    }

    private void band() {
        require(LocalBoundaryGeometry.BAND_TOTAL_BLOCKS == 240 && LocalBoundaryGeometry.BAND_HALF_BLOCKS == 120, "band changed");
        for (int s = -240; s <= 240; s++) {
            double t = Math.max(0, Math.min(1, .5 + s / 240.0));
            require(LocalBoundaryGeometry.progress(s) == t * t * (3 - 2 * t), "not single ease");
        }
        for (int tz = -2; tz <= 1; tz++) for (int tx = -2; tx <= 1; tx++) {
            var tile = world.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
            for (var e : tile.original().geometry().edges()) {
                if (e.wx.length < 10 || e.frameTruncated) continue;
                int m = e.wx.length / 2;
                double nx = -(e.wz[m + 1] - e.wz[m - 1]), nz = e.wx[m + 1] - e.wx[m - 1];
                double len = Math.hypot(nx, nz); if (!(len > 0)) continue;
                nx /= len; nz /= len;
                if (Math.hypot(e.wx[m] - tile.original().centerX(), e.wz[m] - tile.original().centerZ()) > 540) continue;
                int[] counts = new int[4]; double[] ss = {-125, -110, 110, 125};
                for (int k = 0; k < ss.length; k++) counts[k] = FullMorphLand.activeCount(
                        tile.original().weights(e.wx[m] + nx * ss[k], e.wz[m] + nz * ss[k]));
                if (!Arrays.equals(counts, new int[]{1,2,2,1})) continue;
                report.add("600 actual two-site normal: origin=" + e.wx[m] + "," + e.wz[m]
                        + "; offsets=" + Arrays.toString(ss) + "; active=" + Arrays.toString(counts)
                        + "; exact distance support +/-120, normal samples bracket saturation (not exact 240 normal-length claim)");
                return;
            }
        }
        throw new AssertionError("no actual two-site band witness");
    }

    private void costs(Path out) throws Exception {
        List<String> csv = new ArrayList<>(List.of("scale,tileX,tileZ,buildMillis,sites,segments"));
        for (int scale : new int[]{1200,600}) {
            RegionalMorphWorld w = new RegionalMorphWorld(new RecipeMorphCatalog(RegionalMorphGallery.SEED), scale);
            long sum = 0;
            for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                var t = w.tileAt(FullMorphLand.center(x), FullMorphLand.center(z)).original();
                sum += t.buildMillis;
                csv.add(scale + "," + x + "," + z + "," + t.buildMillis + "," + t.siteCount + "," + t.segmentCount);
            }
            report.add("scale=" + scale + " same9tiles geometryBuildMillis=" + sum);
        }
        Files.write(out.resolve("geometry-cost.csv"), csv);
    }

    private static double landE(double h) { return Math.max(CombineCandidate.GROUND_E + h * CombineCandidate.E_SPAN, 0); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
