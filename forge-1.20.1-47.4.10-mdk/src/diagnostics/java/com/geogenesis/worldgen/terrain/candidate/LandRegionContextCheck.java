package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** N1 only: real seeded contexts, unchanged float geometry, bounded evidence, no production edits. */
public final class LandRegionContextCheck {
    private static final long[] SEEDS = {0L, -419L, 12345L};
    private static final float SCALE = 600f;
    private static final long ORDER_SEED = 0x4e314f52444552L;
    private static final int SUPPLEMENT_LIMIT = 192;
    private final Map<String, List<String>> tables = new LinkedHashMap<>();
    private final List<String> report = new ArrayList<>();
    private final List<Run> runs = new ArrayList<>();
    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
    private Path out;
    private String mode;
    private int checks, failures, comparisons;

    public static void main(String[] args) {
        var check = new LandRegionContextCheck();
        try {
            check.initialize(args);
            check.run();
        } catch (Throwable t) {
            check.failures++;
            check.report.add("FATAL " + t);
            t.printStackTrace();
        }
        try {
            if (check.out != null) check.save();
        } catch (Throwable t) {
            check.failures++;
            t.printStackTrace();
        }
        System.out.println("N1 " + check.mode + " " + (check.failures == 0 ? "PASS" : "FAIL")
                + " checks=" + check.checks + " failures=" + check.failures);
        if (check.failures != 0) System.exit(1);
    }

    private void initialize(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("canonical") || args[0].equals("multiseed")))
            throw new IllegalArgumentException("usage: LandRegionContextCheck canonical|multiseed outputDirectory");
        mode = args[0];
        Path requested = Path.of(args[1]);
        if (Files.exists(requested)) {
            try (var entries = Files.list(requested)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("output must be new or empty");
            }
        }
        Files.createDirectories(requested);
        out = requested;
        report.add("N0-N1 only; scale=600; unchanged float forward/inverse geometry; no -D overrides set.");
        report.add("Main grid: [-1800,1800]^2 step=300 (169 actual S3 samples per seed, not endpoints).");
        report.add("Supplement: finite rings radius=2400/3600/4800/6000 step=600; at most 192 actual queries/seed.");
        report.add("Oracles: canonical catalog.original() reflection; other seeds private independentCombine(long).");
        report.add("JVM input arguments=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.add("Synthetic mask branches are separate from natural mask and actual S3 land/sea/mixed counts.");
        report.add("Scheduling prerequisite (main agent): canonical small check PASS and two full Gallery SHA matches before multiseed.");
        report.add("This check does not generate/verify those full rasters; multiseed is a separately invoked mode, never automatic.");
        report.add("Finite near-window evidence is NOT an infinite-world, injectivity, far-coordinate or raster proof.");
    }

    private void run() throws Exception {
        stage("legacy-negative-controls", this::negativeControls);
        if (mode.equals("canonical")) {
            stage("canonical-context", this::canonicalContext);
            stage("canonical-S3-scale-guard", this::canonicalScaleGuard);
            stage("canonical-actual-S3", this::canonicalSamples);
            stage("canonical-coverage-provenance", this::canonicalCoverageProvenance);
        } else {
            for (long seed : SEEDS) stage("multiseed-" + seed, () -> seededSamples(seed));
            stage("cross-seed-interleaved", this::interleaved);
            stage("same-point-seed-sensitivity", this::seedSensitivity);
        }
    }

    private void canonicalContext() throws Exception {
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        for (float scale : new float[]{1200f, SCALE}) {
            var context = new LandRegionContext(seed, scale);
            require(context.seed() == seed, "canonical context ignored requested seed");
            sameFloat(context.scale(), scale, "canonical requested scale");
            var real = new RecipeMorphCatalog(seed).original();
            verifyContext(context, real, "gallery");
        }
    }

    private void canonicalScaleGuard() throws Exception {
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        new CoherentMorphWorld(new LandRegionContext(seed, SCALE));
        for (float scale : new float[]{1200f, Math.nextDown(SCALE), Math.nextUp(SCALE)}) {
            var context = new LandRegionContext(seed, scale);
            boolean rejected = false;
            try {
                new CoherentMorphWorld(context);
            } catch (IllegalArgumentException expected) {
                rejected = true;
                table("scale-guards.csv", "seed,scale,exception,message").add(csv(seed, scale,
                        expected.getClass().getName(), expected.getMessage()));
            }
            require(rejected, "S3 accepted non-600 context scale=" + scale);
        }
    }

    private void canonicalCoverageProvenance() {
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        var contextual = new FullMorphLand(new LandRegionContext(seed, SCALE)).stats();
        var legacy = new FullMorphLand(new RecipeMorphCatalog(seed), SCALE).stats();
        table("coverage-provenance.csv", "path,oldCoverageFromReflection,expected").add(
                csv("context", contextual.oldCoverageFromReflection(), false));
        table("coverage-provenance.csv", "path,oldCoverageFromReflection,expected").add(
                csv("legacy", legacy.oldCoverageFromReflection(), true));
        require(!contextual.oldCoverageFromReflection(), "context coverage must not claim reflection provenance");
        require(legacy.oldCoverageFromReflection(), "legacy reflected coverage must retain honest provenance");
    }

    private void negativeControls() throws Exception {
        for (long seed : SEEDS) {
            require(seed != RecipeMorphCatalog.GALLERY_SEED, "negative-control seed is canonical");
            var catalog = new RecipeMorphCatalog(seed);
            rejects("original", seed, catalog::original);
            rejects("FullMorphLand(catalog)", seed, () -> new FullMorphLand(catalog));
            rejects("FullMorphLand(catalog,600)", seed, () -> new FullMorphLand(catalog, SCALE));
        }
    }

    private void rejects(String name, long seed, Action action) throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
            table("guards.csv", "seed,constructor,exception,message").add(csv(seed, name,
                    expected.getClass().getName(), expected.getMessage()));
        }
        require(rejected, "legacy guard lost: " + name + " seed=" + seed);
    }

    /** No context constructor, catalog endpoints or context mask is used to build the oracle. */
    private static CombineCandidate independent(long seed) throws Exception {
        Method builder = RecipeMorphCatalogCheck.class.getDeclaredMethod("independentCombine", long.class);
        builder.setAccessible(true);
        return (CombineCandidate) builder.invoke(null, seed);
    }

    private void verifyContext(LandRegionContext c, CombineCandidate real, String source) throws Exception {
        require(c.seed() == c.catalog().seed(), "context/catalog seed");
        require(c.catalog().slotCount() == 45, "context slot count");
        verifySlots(c, real, source);
        RegionPartition expected = ((RegionPartition) read(real, "partition")).withScale(c.scale());
        verifyPartitionFields(c.partition(), expected, c.seed(), c.scale());
        float low = constant("MTN_BLEND_LOW"), high = constant("MTN_BLEND_HIGH");
        sameFloat(c.blendLow(), low, "blendLow");
        sameFloat(c.blendHigh(), high, "blendHigh");
        var land = new FullMorphLand(c);
        var mask = (RefNoise.Node) read(read(real, "blender"), "mask");
        var coverage = ((CombineCandidate.Family[]) read(real, "unmixable"))[0];
        require(c.mask() != mask && c.oldCoverage() != coverage, "oracle must be independent objects");
        int[] natural = new int[3];
        List<Point> points = oraclePoints();
        for (Point p : points) verifyContextPoint(c, expected, real, land, mask, coverage, p, natural);
        table("mask-counts.csv", "seed,scale,points,low,middle,high,source").add(csv(c.seed(), c.scale(),
                points.size(), natural[0], natural[1], natural[2], source));
        report.add("natural mask seed=" + c.seed() + " scale=" + c.scale() + " low/mid/high=" + Arrays.toString(natural));
        require(natural[0] + natural[1] + natural[2] == points.size(), "mask count completeness");
        syntheticOverlay(c, land, low, high);
    }

    private void verifySlots(LandRegionContext c, CombineCandidate real, String source) throws Exception {
        Object[] slots = (Object[]) read(real, "slots");
        Object[] composites = (Object[]) read(real, "composites");
        Object[] unmixable = (Object[]) read(real, "unmixable");
        var realCats = (CombineCandidate.Category[]) read(real, "slotCats");
        var cats = c.slotCategories();
        require(slots.length == 45 && cats.length == 45 && composites.length == 15
                && unmixable.length == 3, "independent 45/15/3 structure");
        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < composites.length; i++) ids.put(composites[i], i);
        for (int i = 0; i < unmixable.length; i++) ids.put(unmixable[i], i + composites.length);
        for (int i = 0; i < slots.length; i++) {
            Integer entry = ids.get(slots[i]);
            require(entry != null && c.catalog().slotEntry(i) == entry, "real slot identity " + i);
            require(cats[i] == realCats[i], "real slot category " + i);
            table("slots.csv", "seed,scale,source,slot,entry,category").add(csv(c.seed(), c.scale(),
                    source, i, entry, cats[i]));
        }
        var untouched = cats.clone();
        Arrays.fill(cats, null);
        var next = c.slotCategories();
        require(next != cats && Arrays.equals(next, untouched), "slotCategories leaks its mutable array");
        table("clone-isolation.csv", "seed,scale,slots,allSlotsIsolated")
                .add(csv(c.seed(), c.scale(), next.length, true));
        verifyComposites(c, composites);
    }

    private void verifyComposites(LandRegionContext c, Object[] composites) throws Exception {
        int count = 0;
        for (int k = 5; k < 15; k++) {
            var entry = c.catalog().entry(k);
            var real = (CombineCandidate.Family) composites[k];
            var selector = selectorOf(real);
            require(entry.isComposite() && selector != null, "missing real composite " + k);
            for (Point p : compositePoints()) {
                float s = selector.compute((float) p.x(), (float) p.z());
                sameFloat(entry.selector().compute((float) p.x(), (float) p.z()), s, "selector " + k);
                sameBits(entry.alpha((float) p.x(), (float) p.z()),
                        RefNoise.mapClamped(s, .375f, .625f), "composite alpha " + k);
                sameBits(entry.reference(p.x(), p.z()), real.height01(p.x(), p.z()), "composite " + k);
            }
            table("composites.csv", "seed,scale,entry,points,bitwise").add(csv(c.seed(), c.scale(),
                    k, compositePoints().size(), true));
            count++;
        }
        require(count == 10, "ten real composites not checked");
    }

    private static RefNoise.Node selectorOf(Object composite) throws Exception {
        for (Field f : composite.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.get(composite) instanceof RefNoise.Node node) return node;
        }
        return null;
    }

    private void verifyPartitionFields(RegionPartition got, RegionPartition expected, long seed, float scale)
            throws Exception {
        require(got != expected, "partition oracle aliases context");
        for (String name : new String[]{"seed", "regionScale", "frequency", "idMode", "idNoise", "anisoX", "anisoZ"}) {
            Object a = read(got, name), b = read(expected, name);
            require(java.util.Objects.equals(a, b), "partition field " + name + " actual=" + a + " expected=" + b);
            table("partition-fields.csv", "seed,scale,field,actual,expected").add(csv(seed, scale, name, a, b));
        }
        for (String axis : new String[]{"warpX", "warpZ"}) {
            Object a = read(got, axis), b = read(expected, axis);
            require(a != b && a.getClass() == b.getClass(), "independent warp " + axis);
            for (String field : new String[]{"seed", "frequency", "lacunarity", "gain", "octaves", "min", "max"}) {
                Object av = read(a, field), bv = read(b, field);
                require(av.equals(bv), "warp field " + axis + "." + field);
                table("partition-fields.csv", "seed,scale,field,actual,expected")
                        .add(csv(seed, scale, axis + "." + field, av, bv));
            }
        }
    }

    private void verifyContextPoint(LandRegionContext c, RegionPartition expected, CombineCandidate real,
                                   FullMorphLand land, RefNoise.Node mask, CombineCandidate.Family coverage,
                                   Point p, int[] counts) throws Exception {
        double[] a = forward(c.partition(), p), b = forward(expected, p);
        sameArray(a, b, "float forward");
        var actual = c.partition().sample((float) p.x(), (float) p.z());
        var reference = expected.sample((float) p.x(), (float) p.z());
        sameFloat(actual.regionId(), reference.regionId(), "regionId");
        sameFloat(actual.edge(), reference.edge(), "edge");
        float select = mask.compute((float) p.x(), (float) p.z());
        require(select >= 0 && select <= 1, "mask outside [0,1]");
        sameFloat(c.mask().compute((float) p.x(), (float) p.z()), select, "mask");
        sameFloat(land.maskValue(p.x(), p.z()), select, "FullMorphLand mask");
        double old = coverage.height01(p.x(), p.z());
        sameBits(c.oldCoverage().height01(p.x(), p.z()), old, "old 1.05 plateau");
        sameBits(land.oldCoverageHeight(p.x(), p.z()), old, "FullMorphLand old plateau");
        double region = real.debugRegionHeight(p.x(), p.z());
        double overlay = overlay(region, old, select, constant("MTN_BLEND_LOW"), constant("MTN_BLEND_HIGH"));
        sameBits(real.debugBlenderApply(p.x(), p.z()), overlay, "oracle vs actual Blender.apply");
        sameBits(land.replayOverlay(region, p.x(), p.z()), overlay, "natural replayOverlay");
        int branch = select < c.blendLow() ? 0 : select > c.blendHigh() ? 2 : 1;
        counts[branch]++;
        table("context-points.csv", "seed,scale,x,z,u,v,regionId,edge,mask,oldPlateau,overlay,branch")
                .add(csv(c.seed(), c.scale(), p.x(), p.z(), a[0], a[1], actual.regionId(), actual.edge(),
                        select, old, overlay, branch));
    }

    private void syntheticOverlay(LandRegionContext c, FullMorphLand land, float low, float high) {
        float[] selectors = {0f, Math.nextDown(low), low, Math.nextUp(low),
                low + (high - low) * .37f, Math.nextDown(high), high, Math.nextUp(high), 1f};
        int[] branches = new int[3];
        int roundingTraps = 0;
        for (float s : selectors) for (double region : new double[]{-.01987654321, .123456789012345, .777777777777}) {
            double old = .613579246801357;
            double expected = overlay(region, old, s, low, high);
            double actual = land.overlay(region, s, old);
            sameBits(actual, expected, "synthetic overlay s=" + s);
            float alpha = s < low ? 0f : s > high ? 1f : (s - low) / (high - low);
            sameFloat(land.alphaOf(s), alpha, "synthetic alpha");
            int branch = s < low ? 0 : s > high ? 2 : 1;
            branches[branch]++;
            if (s > low && s < high) {
                double wrong = region + (old - region) * (((double) s - low) / ((double) high - low));
                if (!bitsEqual(expected, wrong)) roundingTraps++;
            }
            table("synthetic-overlay.csv", "seed,scale,select,selectBits,region,oldPlateau,branch,alpha,expectedBits,actualBits")
                    .add(csv(c.seed(), c.scale(), s, Integer.toHexString(Float.floatToRawIntBits(s)), region,
                            old, branch, alpha, hex(expected), hex(actual)));
        }
        sameBits(land.overlay(.123456789, Math.nextDown(low), Double.NaN), .123456789, "low must ignore old NaN");
        sameBits(land.overlay(Double.NaN, Math.nextUp(high), .625), .625, "high must ignore region NaN");
        require(Double.isNaN(land.overlay(.125, low, Double.NaN)), "LOW equality must interpolate, not return region");
        require(Double.isNaN(land.overlay(Double.NaN, high, .625)), "HIGH equality must interpolate, not return old");
        require(branches[0] > 0 && branches[1] > 0 && branches[2] > 0 && roundingTraps > 0,
                "synthetic float branches lack teeth");
        report.add("synthetic overlay seed=" + c.seed() + " scale=" + c.scale()
                + " low/mid/high=" + Arrays.toString(branches) + " double-alpha counterexamples=" + roundingTraps);
    }

    private void canonicalSamples() throws Exception {
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        var c = new LandRegionContext(seed, SCALE);
        var current = new CoherentMorphWorld(c);
        var legacy = new CoherentMorphWorld();
        var truth = new Oracle(new RecipeMorphCatalog(seed).original(), seed);
        var points = grid();
        var snapshots = new ArrayList<Snapshot>();
        int[] branches = new int[3];
        int multi = 0, overlays = 0;
        for (Point p : points) {
            Snapshot got = capture(current, c, p);
            Snapshot old = capture(legacy, c, p);
            compare("canonical-old-S3", seed, p, old, got);
            compareLand(current.evaluator().originalLand().sample(p.x(), p.z()),
                    legacy.evaluator().originalLand().sample(p.x(), p.z()), p);
            auditSample(current, c, truth, p, got, branches, "canonical");
            if (active(got.pair().after()) > 1) multi++;
            if (got.mask() >= c.blendLow()) overlays++;
            snapshots.add(got);
        }
        require(multi > 0 && overlays > 0, "canonical actual samples only exercised trivial paths");
        report.add("canonical real S3 points=" + points.size() + " multiSupport=" + multi + " overlayPoints=" + overlays);
        branchReport(seed, branches, points.size());
        beforeBranchReport(seed, snapshots);
        var run = new Run(seed, c, points, snapshots, branches);
        verifyOrders(run, current);
        report.add("canonical context world stats:\n" + current.stats());
    }

    private void compareLand(FullMorphLand.Sample a, FullMorphLand.Sample b, Point p) {
        sameBits(a.regionHeight(), b.regionHeight(), "FullMorph region " + p);
        sameBits(a.coveredHeight(), b.coveredHeight(), "FullMorph covered " + p);
        sameBits(a.e(), b.e(), "FullMorph e " + p);
        sameBits(a.mask(), b.mask(), "FullMorph mask " + p);
        sameFloat(a.alpha(), b.alpha(), "FullMorph alpha " + p);
        require(a.dominantEntry() == b.dominantEntry() && a.activeEntries() == b.activeEntries(), "FullMorph labels");
        sameArray(a.weight(), b.weight(), "FullMorph weights " + p);
    }

    private void seededSamples(long seed) throws Exception {
        var c = new LandRegionContext(seed, SCALE);
        require(c.seed() == seed, "context ignored requested seed=" + seed);
        sameFloat(c.scale(), SCALE, "requested context scale");
        CombineCandidate real = independent(seed);
        verifyContext(c, real, "independent-families-CombineCandidate");
        var truth = new Oracle(real, seed);
        var world = new CoherentMorphWorld(c);
        var points = grid();
        var snapshots = new ArrayList<Snapshot>();
        int[] branches = new int[3];
        for (Point p : points) {
            Snapshot sample = capture(world, c, p);
            auditSample(world, c, truth, p, sample, branches, "grid");
            snapshots.add(sample);
        }
        supplement(world, c, truth, points, snapshots, branches);
        branchReport(seed, branches, points.size());
        beforeBranchReport(seed, snapshots);
        require(allBranches(branches), "seed=" + seed + " actual S3 missing land/sea/mixed " + Arrays.toString(branches));
        require(snapshots.stream().anyMatch(s -> active(s.pair().after()) > 1), "no real multi-support sample");
        var run = new Run(seed, c, points, snapshots, branches);
        verifyOrders(run, world);
        runs.add(run);
        report.add("seed=" + seed + " real world stats:\n" + world.stats());
    }

    private void supplement(CoherentMorphWorld world, LandRegionContext c, Oracle truth, List<Point> points,
                            List<Snapshot> samples, int[] branches) throws Exception {
        int queried = 0;
        for (int radius : new int[]{2400, 3600, 4800, 6000}) {
            for (int v = -radius; v <= radius; v += 600) {
                for (Point p : List.of(new Point(v, -radius), new Point(radius, v),
                        new Point(-v, radius), new Point(-radius, -v))) {
                    if (allBranches(branches) || queried >= SUPPLEMENT_LIMIT) return;
                    if (points.contains(p)) continue;
                    Snapshot s = capture(world, c, p);
                    auditSample(world, c, truth, p, s, branches, "supplement");
                    points.add(p);
                    samples.add(s);
                    queried++;
                }
            }
        }
    }

    private void branchReport(long seed, int[] branches, int points) {
        table("actual-branches.csv", "seed,points,afterLand,afterSea,afterMixed")
                .add(csv(seed, points, branches[0], branches[1], branches[2]));
        report.add("actual S3 seed=" + seed + " points=" + points + " after land/sea/mixed=" + Arrays.toString(branches));
    }

    private void beforeBranchReport(long seed, List<Snapshot> snapshots) {
        int[] counts = new int[3];
        for (Snapshot snapshot : snapshots) counts[branch(snapshot.pair().before())]++;
        table("actual-before-branches.csv", "seed,points,beforeLand,beforeSea,beforeMixed")
                .add(csv(seed, snapshots.size(), counts[0], counts[1], counts[2]));
        report.add("actual S3 before seed=" + seed + " land/sea/mixed=" + Arrays.toString(counts));
    }

    private void auditSample(CoherentMorphWorld world, LandRegionContext c, Oracle truth, Point p,
                             Snapshot s, int[] branches, String source) throws Exception {
        var pair = s.pair();
        validWeights(pair.before(), "before");
        validWeights(pair.after(), "after");
        require(pair.winner() >= 0 && pair.winner() < RegionalMorphWorld.COUNT, "invalid winner");
        sameBits(pair.beforeE(), expectedE(truth, p, pair.before()), "independent beforeE " + p);
        sameBits(pair.afterE(), expectedE(truth, p, pair.after()), "independent afterE " + p);
        sameFloat(s.mask(), truth.mask().compute((float) p.x(), (float) p.z()), "actual sample mask");
        sameBits(s.coverage(), truth.coverage().height01(p.x(), p.z()), "actual sample old plateau");
        sameArray(s.forward(), forward(truth.partition().withScale(SCALE), p), "actual geometry float forward");
        var tile = world.tileAt(p.x(), p.z());
        var geometry = tile.original().geometry();
        require(geometry.partitionSeed() == (Integer) read(truth.partition(), "seed"), "geometry seed");
        var expectedPartition = truth.partition().withScale(SCALE).sample((float) p.x(), (float) p.z());
        var geometryPartition = geometry.referenceSample(p.x(), p.z());
        sameFloat(geometryPartition.regionId(), expectedPartition.regionId(), "geometry regionId");
        sameFloat(geometryPartition.edge(), expectedPartition.edge(), "geometry edge");
        require(pair.winner() == tile.recipes()[tile.original().winnerIndex(p.x(), p.z())], "S3 winner wiring");
        int branch = branch(pair.after());
        branches[branch]++;
        double beforeY = curve.heightFromE(pair.beforeE()), afterY = curve.heightFromE(pair.afterE());
        require(Double.isFinite(beforeY) && Double.isFinite(afterY), "nonfinite HeightCurve");
        if (branch == 1) require(pair.afterE() < 0, "pure sea clipped to nonnegative e");
        table("samples.csv", "seed,source,x,z,winnerCell,winner,beforeE,afterE,beforeY,afterY,branch,mask,oldPlateau,beforeBits,afterBits,beforeWeights,afterWeights")
                .add(csv(c.seed(), source, p.x(), p.z(), s.cell(), pair.winner(), pair.beforeE(), pair.afterE(),
                        beforeY, afterY, branch, s.mask(), s.coverage(), hex(pair.beforeE()), hex(pair.afterE()),
                        Arrays.toString(pair.before()), Arrays.toString(pair.after())));
    }

    /** Independent evaluator: fresh recipe engine, real Combine mask/old family, no context overlay. */
    private double expectedE(Oracle truth, Point p, double[] weights) {
        double landWeight = 0;
        boolean landOnly = true;
        for (int i = 0; i < RegionalMorphWorld.LAND_COUNT; i++) landWeight += weights[i];
        for (int i = RegionalMorphWorld.LAND_COUNT; i < weights.length; i++) if (weights[i] > 0) landOnly = false;
        float mask = truth.mask().compute((float) p.x(), (float) p.z());
        float lo = constant("MTN_BLEND_LOW"), hi = constant("MTN_BLEND_HIGH");
        if (landOnly) {
            double h = truth.engine().sampleLand(p.x(), p.z(), weights);
            return landE(overlay(h, truth.coverage().height01(p.x(), p.z()), mask, lo, hi));
        }
        double e = truth.engine().sampleE(p.x(), p.z(), weights);
        float alpha = mask < lo ? 0 : mask > hi ? 1 : (mask - lo) / (hi - lo);
        if (landWeight > 0 && alpha > 0) {
            double[] only = new double[weights.length];
            System.arraycopy(weights, 0, only, 0, RegionalMorphWorld.LAND_COUNT);
            normalizeOracle(only);
            double h = truth.engine().sampleLand(p.x(), p.z(), only);
            double covered = overlay(h, truth.coverage().height01(p.x(), p.z()), mask, lo, hi);
            e += landWeight * (landE(covered) - landE(h));
        }
        return e;
    }

    private static void normalizeOracle(double[] values) {
        double sum = 0;
        for (double value : values) sum += value;
        if (!(sum > 0)) throw new AssertionError("oracle empty land support");
        int max = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] /= sum;
            if (values[i] > values[max]) max = i;
        }
        for (int round = 0; round < 4; round++) {
            double total = 0;
            for (double value : values) total += value;
            if (total == 1) return;
            values[max] += 1 - total;
        }
    }

    private void verifyOrders(Run run, CoherentMorphWorld warm) throws Exception {
        int n = run.points().size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.reverse(order);
        replay("warm-reverse", run, warm, run.context(), order);
        Collections.shuffle(order, new Random(ORDER_SEED));
        replay("warm-fixed-shuffle", run, warm, run.context(), order);
        var cold = new CoherentMorphWorld(run.seed());
        var coldContext = (LandRegionContext) read(cold.evaluator().originalLand(), "context");
        require(coldContext != run.context() && coldContext.catalog() != run.context().catalog()
                && coldContext.mask() != run.context().mask() && coldContext.partition() != run.context().partition()
                && coldContext.oldCoverage() != run.context().oldCoverage(),
                "same-seed cold world shares context state");
        require(((Map<?, ?>) read(cold, "tiles")).isEmpty()
                && ((Map<?, ?>) read(cold, "identities")).isEmpty(), "cold world already has sample caches");
        require(coldContext.seed() == run.seed(), "long constructor ignores seed");
        replay("cold-independent-fixed-shuffle", run, cold, coldContext, order);
        report.add("seed=" + run.seed() + " forward/reverse/fixed-shuffle/cold full point sets=" + n);
    }

    private void replay(String stage, Run run, CoherentMorphWorld world, LandRegionContext context, List<Integer> order)
            throws Exception {
        for (int index : order) {
            Point p = run.points().get(index);
            compare(stage, run.seed(), p, run.samples().get(index), capture(world, context, p));
        }
    }

    private void interleaved() throws Exception {
        require(runs.size() == SEEDS.length, "interleaving requires three successful complete seed runs");
        var worlds = new ArrayList<CoherentMorphWorld>();
        var contexts = new ArrayList<LandRegionContext>();
        int max = 0;
        for (Run run : runs) {
            var c = new LandRegionContext(run.seed(), SCALE);
            require(c != run.context() && c.catalog() != run.context().catalog(), "interleaved cold context aliased");
            contexts.add(c);
            worlds.add(new CoherentMorphWorld(c));
            max = Math.max(max, run.points().size());
        }
        // Every seed's worlds exist before any query. Alternate seed order and point order each round.
        for (int round = 0; round < max; round++) {
            for (int k = 0; k < runs.size(); k++) {
                int s = (round + k) % runs.size();
                Run run = runs.get(s);
                if (round >= run.points().size()) continue;
                int index = run.points().size() - 1 - round;
                Point p = run.points().get(index);
                Snapshot actual = capture(worlds.get(s), contexts.get(s), p);
                compare("cold-cross-seed-interleaved", run.seed(), p, run.samples().get(index), actual);
            }
        }
        // Revisit one actual witness from every branch after the other seeds have populated their caches.
        for (int s = 0; s < runs.size(); s++) for (int branch = 0; branch < 3; branch++) {
            Run run = runs.get(s);
            int index = witness(run, branch);
            Point p = run.points().get(index);
            compare("interleaved-branch-revisit", run.seed(), p, run.samples().get(index),
                    capture(worlds.get(s), contexts.get(s), p));
        }
        report.add("Cross-seed isolation: three independent live contexts; all points interleaved, then each branch revisited.");
    }

    private int witness(Run run, int wanted) {
        for (int i = 0; i < run.samples().size(); i++) if (branch(run.samples().get(i).pair().after()) == wanted) return i;
        throw new AssertionError("no actual branch witness " + wanted + " seed=" + run.seed());
    }

    private void seedSensitivity() {
        require(runs.size() == 3, "seed sensitivity requires all three runs");
        for (int a = 0; a < runs.size(); a++) for (int b = a + 1; b < runs.size(); b++) {
            int labels = 0, weights = 0, heights = 0, masks = 0;
            for (int i = 0; i < 169; i++) {
                Snapshot x = runs.get(a).samples().get(i), y = runs.get(b).samples().get(i);
                require(runs.get(a).points().get(i).equals(runs.get(b).points().get(i)), "different seed test coordinates");
                if (x.pair().winner() != y.pair().winner()) labels++;
                if (!arrayBitsEqual(x.pair().after(), y.pair().after())) weights++;
                if (!bitsEqual(x.pair().afterE(), y.pair().afterE())) heights++;
                if (Float.floatToRawIntBits(x.mask()) != Float.floatToRawIntBits(y.mask())) masks++;
            }
            table("seed-sensitivity.csv", "seedA,seedB,samePoints,winnerDifferences,weightDifferences,eDifferences,maskDifferences")
                    .add(csv(runs.get(a).seed(), runs.get(b).seed(), 169, labels, weights, heights, masks));
            require(labels > 0 && weights > 0 && heights > 0 && masks > 0, "seed ignored or canonical arm reused");
        }
    }

    private Snapshot capture(CoherentMorphWorld world, LandRegionContext c, Point p) throws Exception {
        var pair = world.sample(p.x(), p.z());
        var tile = world.tileAt(p.x(), p.z());
        int winner = tile.original().winnerIndex(p.x(), p.z());
        long cell = tile.original().geometry().sites().get(winner).key();
        var sample = tile.original().geometry().referenceSample(p.x(), p.z());
        var copy = new CoherentMorphWorld.Pair(pair.beforeE(), pair.afterE(), pair.winner(),
                pair.before().clone(), pair.after().clone());
        var land = world.evaluator().originalLand();
        return new Snapshot(copy, cell, land.maskValue(p.x(), p.z()), land.oldCoverageHeight(p.x(), p.z()),
                sample.regionId(), sample.edge(), tile.original().geometry().warpForward(p.x(), p.z()));
    }

    private void compare(String stage, long seed, Point p, Snapshot expected, Snapshot actual) {
        boolean same = expected.cell() == actual.cell() && expected.pair().winner() == actual.pair().winner()
                && bitsEqual(expected.pair().beforeE(), actual.pair().beforeE())
                && bitsEqual(expected.pair().afterE(), actual.pair().afterE())
                && arrayBitsEqual(expected.pair().before(), actual.pair().before())
                && arrayBitsEqual(expected.pair().after(), actual.pair().after())
                && Float.floatToRawIntBits(expected.mask()) == Float.floatToRawIntBits(actual.mask())
                && bitsEqual(expected.coverage(), actual.coverage())
                && Float.floatToRawIntBits(expected.regionId()) == Float.floatToRawIntBits(actual.regionId())
                && Float.floatToRawIntBits(expected.edge()) == Float.floatToRawIntBits(actual.edge())
                && arrayBitsEqual(expected.forward(), actual.forward());
        table("comparisons.csv", "stage,seed,x,z,winnerExpected,winnerActual,eExpectedBits,eActualBits,bitwise")
                .add(csv(stage, seed, p.x(), p.z(), expected.pair().winner(), actual.pair().winner(),
                        hex(expected.pair().afterE()), hex(actual.pair().afterE()), same));
        comparisons++;
        require(same, stage + " seed=" + seed + " point=" + p + " expected=" + expected + " actual=" + actual);
    }

    private void validWeights(double[] values, String name) {
        require(values.length == RegionalMorphWorld.COUNT, name + " weight length");
        double sum = 0;
        for (double v : values) {
            require(Double.isFinite(v) && v >= 0 && v <= 1, name + " invalid weight " + v);
            sum += v;
        }
        require(Math.abs(sum - 1) <= 1e-12, name + " weight sum=" + sum);
    }

    private static int branch(double[] weights) {
        boolean land = false, sea = false;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            if (i < RegionalMorphWorld.LAND_COUNT) land = true; else sea = true;
        }
        if (!land && !sea) throw new AssertionError("empty actual support");
        return land && sea ? 2 : land ? 0 : 1;
    }

    private static boolean allBranches(int[] branches) {
        return branches[0] > 0 && branches[1] > 0 && branches[2] > 0;
    }

    private static int active(double[] values) {
        int n = 0;
        for (double v : values) if (v > 0) n++;
        return n;
    }

    private static List<Point> grid() {
        var points = new ArrayList<Point>();
        for (int z = -1800; z <= 1800; z += 300)
            for (int x = -1800; x <= 1800; x += 300) points.add(new Point(x, z));
        return points;
    }

    private static List<Point> compositePoints() {
        var points = new ArrayList<Point>();
        for (int i = 0; i < 32; i++) points.add(new Point(-1733.125 + i * 113.375, 1631.75 - i * 97.125));
        return points;
    }

    private static List<Point> oraclePoints() {
        var points = grid();
        points.addAll(compositePoints());
        var random = new Random(0x4e314d41534bL);
        for (int i = 0; i < 512; i++) points.add(new Point(random.nextDouble() * 16000 - 8000,
                random.nextDouble() * 16000 - 8000));
        return points;
    }

    private static double[] forward(RegionPartition partition, Point p) throws Exception {
        var wx = (RefNoise.Node) read(partition, "warpX");
        var wz = (RefNoise.Node) read(partition, "warpZ");
        float f = (Float) read(partition, "frequency");
        float x = (float) p.x(), z = (float) p.z();
        return new double[]{(x + (wx.compute(x, z) - .5f) * 200f) * f,
                (z + (wz.compute(x, z) - .5f) * 200f) * f};
    }

    private static double overlay(double region, double old, float select, float low, float high) {
        if (select < low) return region;
        if (select > high) return old;
        float alpha = (select - low) / (high - low);
        return region + (old - region) * alpha;
    }

    private static double landE(double height) {
        return Math.max(CombineCandidate.GROUND_E + height * CombineCandidate.E_SPAN, 0.0);
    }

    private static float constant(String name) {
        try {
            Field f = CombineCandidate.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("independent Combine constant " + name, e);
        }
    }

    private static Object read(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // Lambda/inner implementation fields are read only; never mutate the oracle.
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private void sameFloat(float actual, float expected, String message) {
        require(Float.isFinite(actual) && Float.isFinite(expected)
                && Float.floatToRawIntBits(actual) == Float.floatToRawIntBits(expected),
                message + " actual=" + actual + " expected=" + expected);
    }

    private void sameBits(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Double.isFinite(expected) && bitsEqual(actual, expected),
                message + " actual=" + actual + "/" + hex(actual) + " expected=" + expected + "/" + hex(expected));
    }

    private void sameArray(double[] actual, double[] expected, String message) {
        require(actual.length == expected.length, message + " length");
        for (int i = 0; i < actual.length; i++) sameBits(actual[i], expected[i], message + " index=" + i);
    }

    private static boolean bitsEqual(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static boolean arrayBitsEqual(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!bitsEqual(a[i], b[i])) return false;
        return true;
    }

    private static String hex(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private List<String> table(String name, String header) {
        return tables.computeIfAbsent(name, ignored -> new ArrayList<>(List.of(header)));
    }

    private static String csv(Object... values) {
        var row = new ArrayList<String>();
        for (Object v : values) row.add("\"" + String.valueOf(v).replace("\"", "\"\"") + "\"");
        return String.join(",", row);
    }

    private void stage(String name, Action action) throws Exception {
        long start = System.nanoTime();
        try {
            action.run();
            report.add(name + " PASS elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            failures++;
            report.add(name + " FAIL " + t);
            t.printStackTrace();
        }
        save();
    }

    private void save() throws Exception {
        for (var entry : tables.entrySet()) Files.write(out.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        var summary = new ArrayList<>(report);
        summary.add("mode=" + mode + " status=" + (failures == 0 ? "PASS" : "FAIL")
                + " checks=" + checks + " failures=" + failures + " bitwiseComparisons=" + comparisons);
        Files.write(out.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private interface Action { void run() throws Exception; }
    private record Point(double x, double z) { }
    private record Snapshot(CoherentMorphWorld.Pair pair, long cell, float mask, double coverage,
                            float regionId, float edge, double[] forward) { }
    private record Run(long seed, LandRegionContext context, List<Point> points, List<Snapshot> samples,
                       int[] branches) { }
    private record Oracle(RefNoise.Node mask, CombineCandidate.Family coverage, RegionPartition partition,
                          NodeMorphEngine engine) {
        Oracle(CombineCandidate real, long seed) throws Exception {
            this((RefNoise.Node) read(read(real, "blender"), "mask"),
                    ((CombineCandidate.Family[]) read(real, "unmixable"))[0],
                    (RegionPartition) read(real, "partition"),
                    new NodeMorphEngine(MorphCatalog.world(new RecipeMorphCatalog(seed),
                            new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, true)), seed));
        }
    }
}
