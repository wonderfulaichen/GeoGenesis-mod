package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** M1a：0.30同源接线和真实高度，任何几何异常直接失败，不更换窗口。 */
public final class M1ThresholdHeightCheck {
    private static final long SEED = RegionalMorphGallery.SEED;
    private static final int LAND = RegionalMorphWorld.LAND_COUNT;
    private final List<String> rows = new ArrayList<>(List.of("seed,x,z,beforeE,afterE,y,landWeight,active,mask"));
    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
    private int checks, pureLand, pureSea, mixed, covered;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("outputDirectory frozenThresholdRoot");
        Path out = Path.of(args[0]); Files.createDirectory(out);
        var check = new M1ThresholdHeightCheck();
        try {
            check.frozen(Path.of(args[1]));
            check.wiring();
            check.preflight();
            check.seamsAndWindows();
            for (long seed : new long[]{SEED, 0, -419, 12345}) check.actual(seed);
            check.interleaved();
            check.require(check.pureLand > 0 && check.pureSea > 0 && check.mixed > 0 && check.covered > 0,
                    "missing actual branch coverage");
            Files.write(out.resolve("samples.csv"), check.rows);
            Files.writeString(out.resolve("check.txt"), "M1a PASS checks=" + check.checks
                    + " pureLand=" + check.pureLand + " pureSea=" + check.pureSea
                    + " mixed=" + check.mixed + " covered=" + check.covered + "\n");
            System.out.println(Files.readString(out.resolve("check.txt")));
        } catch (Throwable t) {
            Files.write(out.resolve("samples.csv"), check.rows);
            Files.writeString(out.resolve("check.txt"), "M1a FAIL checks=" + check.checks + " " + t + "\n");
            t.printStackTrace(); System.exit(1);
        }
    }

    private void frozen(Path root) throws Exception {
        Path local = root.resolve("shift030/local");
        byte[] side = Files.readAllBytes(local.resolve("side.u8"));
        byte[] type = Files.readAllBytes(local.resolve("type.u8"));
        ByteBuffer cells = ByteBuffer.wrap(Files.readAllBytes(local.resolve("cells.i64be")));
        var sampler = new M0DistributionSampler(SEED, .30);
        require(side.length == 1401 * 1401 && type.length == side.length, "frozen dimensions");
        for (int j = 0; j < 1401; j++) for (int i = 0; i < 1401; i++) {
            var p = sampler.sample(RegionalMorphGallery.X0 + i * 5, RegionalMorphGallery.Z0 + j * 5);
            int k = j * 1401 + i;
            require(p.cell().key() == cells.getLong(), "frozen cell " + k);
            require(p.recipe() == (type[k] & 255), "frozen type " + k);
            require((p.recipe() >= LAND ? 1 : 0) == side[k], "frozen side " + k);
        }
        require(!cells.hasRemaining(), "trailing cells");
        System.out.println("0.30 frozen local 1962801 pixels PASS");
    }

    private void wiring() {
        var distribution = new CoherentRegionDistribution(SEED, .30);
        var world = world(SEED, distribution);
        Object matcher = RecipeMorphCatalog.field(world, "matcher");
        require(RecipeMorphCatalog.field(matcher, "base") == distribution, "not the injected instance");
        boolean rejected = false;
        try { world(SEED, new CoherentRegionDistribution(0, .30)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "mixed seed accepted");
        var old = new ShallowRegionMatcher(SEED);
        var shifted = new ShallowRegionMatcher(distribution);
        var sampler = new M0DistributionSampler(SEED, .30);
        int changes = 0;
        for (int z = -1800; z <= 1800; z += 600) for (int x = -1800; x <= 1800; x += 600) {
            var cell = sampler.sample(x, z).cell();
            var m = shifted.match(cell);
            int base = distribution.choose(cell).recipe();
            require(m.before() == base, "matcher uses old base");
            require((base < LAND) == (m.after() < LAND), "S2 changed side");
            if (base < LAND) require(m.after() == base, "land changed by S2");
            else require(m.after() == base || m.after() == ShallowRegionMatcher.SHELF, "illegal sea replacement");
            if (old.match(cell).before() != base) changes++;
        }
        require(changes > 0, "half-wired zero-threshold negative control has no signal");
        System.out.println("same-instance wiring/seed guard/S2 side PASS changes=" + changes);
    }

    private void preflight() {
        var world = world(SEED, new CoherentRegionDistribution(SEED, .30));
        int count = 0;
        for (int z = FullMorphLand.tileIndex(RegionalMorphGallery.Z0);
             z <= FullMorphLand.tileIndex(RegionalMorphGallery.Z0 + 7000); z++) {
            for (int x = FullMorphLand.tileIndex(RegionalMorphGallery.X0);
                 x <= FullMorphLand.tileIndex(RegionalMorphGallery.X0 + 7000); x++) {
                world.tileAt(FullMorphLand.center(x), FullMorphLand.center(z)); count++;
            }
        }
        require(count > 0, "no preflight tiles");
        System.out.println("original-window full dependency preflight PASS tiles=" + count);
    }

    private void actual(long seed) {
        var shifted = world(seed, new CoherentRegionDistribution(seed, .30));
        var cold = world(seed, new CoherentRegionDistribution(seed, .30));
        var zero = world(seed, new CoherentRegionDistribution(seed));
        var legacy = seed == SEED ? new CoherentMorphWorld() : new CoherentMorphWorld(seed);
        List<double[]> points = new ArrayList<>(); List<CoherentMorphWorld.Pair> expected = new ArrayList<>();
        for (int z = -1800; z <= 1800; z += 300) for (int x = -1800; x <= 1800; x += 300) {
            var a = zero.sample(x, z); var b = legacy.sample(x, z); same(a, b);
            var s = shifted.sample(x, z); validate(s);
            double land = 0; for (int i = 0; i < LAND; i++) land += s.after()[i];
            boolean sea = false; for (int i = LAND; i < s.after().length; i++) sea |= s.after()[i] > 0;
            if (!sea) pureLand++; else if (land == 0) pureSea++; else mixed++;
            double mask = shifted.evaluator().originalLand().mask(x, z);
            if (land > 0 && mask > .55) covered++;
            double y = curve.heightFromE(s.afterE()); require(Double.isFinite(y), "nonfinite Y");
            rows.add(seed + "," + x + "," + z + "," + s.beforeE() + "," + s.afterE() + ","
                    + y + "," + land + "," + FullMorphLand.activeCount(s.after()) + "," + mask);
            points.add(new double[]{x, z}); expected.add(s);
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            var p = points.get(i); same(expected.get(i), cold.sample(p[0], p[1]));
        }
        System.out.println("actual 0.30 complete height/zero parity/reverse cold PASS seed=" + seed);
    }

    private void seamsAndWindows() {
        var world = world(SEED, new CoherentRegionDistribution(SEED, .30));
        int mixedSeams = 0;
        for (int axis = 0; axis < 2; axis++) for (double along : new double[]{-511, -.01, 0, .01, 511}) {
            double x = axis == 0 ? 0 : along, z = axis == 1 ? 0 : along;
            var a = world.tileAt(x - (axis == 0 ? .5 : 0), z - (axis == 1 ? .5 : 0));
            var b = world.tileAt(x + (axis == 0 ? .5 : 0), z + (axis == 1 ? .5 : 0));
            near(world.sample(a, x, z), world.sample(b, x, z));
            if (FullMorphLand.activeCount(world.sample(a, x, z).after()) > 1) mixedSeams++;
        }
        var tile = world.tileAt(512, 512);
        var context = new LandRegionContext(SEED, 600);
        var g = LocalBoundaryGeometry.of(context, 640, 416, 900, 4, .02);
        g.setMaxSeg(2);
        int[] recipes = new int[g.sites().size()];
        for (var site : g.sites()) recipes[site.index()] = world.recipe(site);
        var alternate = new CoherentMorphWorld.Tile(FullMorphLand.wrap(context.catalog(), g), recipes,
                new RecipeBoundaryWeights(g, recipes));
        for (int i = 0; i < 7; i++) {
            double x = 262 + i * 75, z = 362 + i * 40;
            near(world.sample(tile, x, z), world.sample(alternate, x, z));
        }
        require(mixedSeams > 0, "empty mixed seam samples");
        var remembered = world.sample(512, 512);
        for (int tx = -4; tx <= 5; tx++) world.sample(FullMorphLand.center(tx), 512);
        same(remembered, world.sample(512, 512));
        require(world.evaluator().originalLand().stats().tileEvictions() > 0, "no real tile eviction");
        System.out.println("0.30 forced seams/window/real tile eviction PASS mixed=" + mixedSeams);
    }

    private void interleaved() {
        long[] seeds = {SEED, 0, -419, 12345};
        CoherentMorphWorld[] worlds = new CoherentMorphWorld[seeds.length];
        CoherentMorphWorld.Pair[] before = new CoherentMorphWorld.Pair[seeds.length];
        for (int i = 0; i < seeds.length; i++) {
            worlds[i] = world(seeds[i], new CoherentRegionDistribution(seeds[i], .30));
            before[i] = worlds[i].sample(321, -147);
        }
        for (int i = seeds.length - 1; i >= 0; i--) {
            worlds[i].sample(-511, 383); same(before[i], worlds[i].sample(321, -147));
        }
        require(Arrays.stream(before).mapToLong(p -> Double.doubleToLongBits(p.afterE())).distinct().count() > 1,
                "seed sensitivity absent");
        System.out.println("0.30 interleaved seed isolation PASS");
    }

    private void near(CoherentMorphWorld.Pair a, CoherentMorphWorld.Pair b) {
        double max = 0;
        for (int i = 0; i < a.after().length; i++) {
            max = Math.max(max, Math.abs(a.before()[i] - b.before()[i]));
            max = Math.max(max, Math.abs(a.after()[i] - b.after()[i]));
        }
        require(a.winner() == b.winner() && max <= 2e-3
                && Math.abs(a.beforeE() - b.beforeE()) <= 2e-3
                && Math.abs(a.afterE() - b.afterE()) <= 2e-3, "seam/window mismatch max=" + max);
    }

    private static CoherentMorphWorld world(long seed, CoherentRegionDistribution distribution) {
        return new CoherentMorphWorld(new LandRegionContext(seed, 600), distribution);
    }

    private void validate(CoherentMorphWorld.Pair p) {
        require(Double.isFinite(p.beforeE()) && Double.isFinite(p.afterE()), "nonfinite e");
        for (double[] w : new double[][]{p.before(), p.after()}) {
            double sum = 0;
            for (double v : w) { require(Double.isFinite(v) && v >= 0, "invalid weight"); sum += v; }
            require(Math.abs(sum - 1) <= 1e-12, "weight sum");
        }
    }

    private void same(CoherentMorphWorld.Pair a, CoherentMorphWorld.Pair b) {
        require(Double.doubleToLongBits(a.beforeE()) == Double.doubleToLongBits(b.beforeE())
                && Double.doubleToLongBits(a.afterE()) == Double.doubleToLongBits(b.afterE())
                && a.winner() == b.winner() && Arrays.equals(a.before(), b.before())
                && Arrays.equals(a.after(), b.after()), "complete sample drift");
    }

    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
