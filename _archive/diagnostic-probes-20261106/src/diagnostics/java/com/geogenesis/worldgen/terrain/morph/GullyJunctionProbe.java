package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;
import com.geogenesis.standalone.GullyJunctionConstraint;
import com.geogenesis.standalone.GullyJunctionConstraint.Geometry;
import com.geogenesis.standalone.GullyJunctionConstraint.Point;
import com.geogenesis.standalone.GullyJunctionConstraint.State;
import com.geogenesis.standalone.GullyJunctionConstraint.Status;
import com.geogenesis.standalone.GullyJunctionEvidence;
import com.geogenesis.standalone.InheritedSkeletonErosion;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** C0: one prospectively selected local Y. Type-space diagnostic, NOT a Minecraft world. */
public final class GullyJunctionProbe {
    private static final double AMP = .25, SHELL = MorphCatalog.MOUNTAIN_AMP, NORM = 1.875;
    private static final double VERTICAL = .15751932417256156;
    private static final double[] ONSET = {1.271470535405616, .31930163808619716,
            .06546449723967929, .01166748617833733};
    private static final double[] EPSILON = {.001271470535405616, 3.193016380861972E-4,
            6.546449723967929E-5, 1.166748617833733E-5};
    // Frozen B star crops in LOCAL BLOCKS. Holdout uses its whole window, inset by one parent length.
    private static final double[][] STAR_CROPS = {{1700, 2100, 1650, 2050}, {200, 650, 1200, 1700}};
    private static final String RULE = "world-grid pitch=lambda1/2; crop order then z then x; "
            + "B-only Hessian transverse axis; fixed bounded refinement; five existing downstream "
            + "trough sections with both banks and descending minima; first receiver only; "
            + "never retry after C0 failure";
    private final long seed;
    private final int size, ox, oz;
    private final double length, nativeStep, step;
    private final double[] waves = new double[4];
    private final MorphNoise.Node production, direction;
    private final Settings settings;
    private final InheritedSkeletonErosion core;
    private final List<Candidate> candidates = new ArrayList<>();
    private final double[] raw, baseline, constrained, weight;
    private GullyJunctionConstraint connection;
    private Candidate selected;
    private double prepareMs, discoverMs, buildMs, gridMs;
    private long descriptors;
    private int zeroCompared, layerChecks, replayCompared;

    private record Candidate(String id, int crop, long cellX, long cellZ, Geometry geometry,
                             boolean receiver, String reason, double minimumRise,
                             double maximumMinRise) {}

    private GullyJunctionProbe(long seed, int size) {
        this.seed = seed;
        this.size = size;
        MorphCatalog catalog = new MorphCatalog(seed);
        production = catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Warped warp = (MorphNoise.Warped) production;
        MorphNoise.Mul body = (MorphNoise.Mul) warp.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix modulation = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin scaler = (MorphNoise.Perlin) modulation.input();
        require(ridge.octaves() == 4 && scaler.octaves() == 4, "complete ridge4/scaler4");
        require(warp.pow() == 150f && modulation.alpha() == .30f, "unchanged warp/modulation");
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(seed + 77) + 3, 10, 1);
        length = 1.0 / ridge.frequency();
        nativeStep = length / 512;
        step = 2 * nativeStep;
        ox = (int) Math.round(-3242.5 / step) - size / 2;
        oz = (int) Math.round(-3365.5 / step) - size / 2;
        for (int j = 0; j < 4; j++) {
            double f = 21.7 + j * 1.2;
            waves[j] = (1 << Math.max(28 - (int) Math.floor(f), 0))
                    * nativeStep / (1 + f - Math.floor(f));
        }
        settings = new Settings(waves, ONSET, EPSILON, .25, VERTICAL);
        core = newCore();
        raw = new double[size * size]; baseline = new double[raw.length];
        constrained = new double[raw.length]; weight = new double[raw.length];
    }

    private InheritedSkeletonErosion newCore() {
        return new InheritedSkeletonErosion(seed, this::full, settings, AMP, 1);
    }

    private double full(double x, double z) { return MorphNoiseEval.eval(production, x, z); }

    private Point domain(double x, double z) {
        double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new Point(x + Math.sin(angle) * 2, z + Math.cos(angle) * 2);
    }

    private State state(InheritedSkeletonErosion source, double x, double z) {
        Point q = domain(x, z);
        double[] values = new double[4], caps = new double[4];
        for (int j = 0; j < 4; j++) {
            values[j] = source.sampleLayer(j, q.x(), q.z()).value();
            caps[j] = source.layerCapacity(j, q.x(), q.z());
        }
        return new State(full(q.x(), q.z()), values, caps);
    }

    private double baselineAt(double x, double z) {
        Point q = domain(x, z);
        return (full(q.x(), q.z()) + AMP * core.sample(q.x(), q.z()).value()) * SHELL;
    }

    private static double compose(State state) {
        double sum = 0, gain = 1;
        for (double v : state.values()) { sum += gain * v; gain *= .5; }
        return (state.raw() + AMP * (sum / NORM)) * SHELL;
    }

    private void prepare() {
        long start = System.nanoTime();
        core.prepare(ox * step - 2, oz * step - 2,
                (ox + size - 1) * step + 2, (oz + size - 1) * step + 2);
        prepareMs = ms(start);
        System.out.printf(Locale.ROOT, "PREPARE seed=%d %.3fms descriptors=%d%n",
                seed, prepareMs, core.descriptorCount());
    }

    private Geometry geometry(Point q, double tx, double tz) {
        return new Geometry(q, tx, tz, waves[1], waves[1] / 2, waves[1] / 2, waves[2] / 4);
    }

    private double[][] crops() {
        if (size == 769) return STAR_CROPS;
        double inset = 2 * waves[1], span = (size - 1) * step * 2;
        return new double[][]{{inset, span - inset, inset, span - inset}};
    }

    private void discover() {
        long start = System.nanoTime();
        double pitch = waves[1] / 2;
        double[][] crops = crops();
        for (int c = 0; c < crops.length; c++) {
            double[] r = crops[c];
            long x0 = (long) Math.ceil((ox * step + r[0] / 2) / pitch);
            long x1 = (long) Math.floor((ox * step + r[1] / 2) / pitch);
            long z0 = (long) Math.ceil((oz * step + r[2] / 2) / pitch);
            long z1 = (long) Math.floor((oz * step + r[3] / 2) / pitch);
            for (long z = z0; z <= z1; z++) for (long x = x0; x <= x1; x++) {
                Candidate candidate = candidate(c, x, z, pitch);
                candidates.add(candidate);
                if (selected == null && candidate.receiver) selected = candidate;
            }
        }
        discoverMs = ms(start);
        if (selected == null) {
            Geometry fallback = geometry(new Point((ox + size / 2) * step, (oz + size / 2) * step), 1, 0);
            selected = new Candidate("NO_RECEIVER", -1, 0, 0, fallback, false,
                    "no existing B receiver meets the declared local trough test", Double.NaN, Double.NaN);
        }
        start = System.nanoTime();
        connection = selected.receiver
                ? GullyJunctionConstraint.build((x, z) -> state(core, x, z), selected.geometry, AMP, SHELL)
                : GullyJunctionConstraint.missingParent((x, z) -> state(core, x, z), selected.geometry,
                        AMP, SHELL, selected.reason);
        buildMs = ms(start);
        System.out.printf(Locale.ROOT, "C0 seed=%d candidates=%d receiver=%s status=%s reason=%s%n",
                seed, candidates.size(), selected.id, connection.status(), connection.reason());
    }

    private Candidate candidate(int crop, long x, long z, double pitch) {
        Point q = new Point(x * pitch, z * pitch);
        double d = waves[3] / 4, h = baselineAt(q.x(), q.z());
        double xx = (baselineAt(q.x() + d, q.z()) - 2 * h + baselineAt(q.x() - d, q.z())) / (d * d);
        double zz = (baselineAt(q.x(), q.z() + d) - 2 * h + baselineAt(q.x(), q.z() - d)) / (d * d);
        double xz = (baselineAt(q.x() + d, q.z() + d) - baselineAt(q.x() + d, q.z() - d)
                - baselineAt(q.x() - d, q.z() + d) + baselineAt(q.x() - d, q.z() - d)) / (4 * d * d);
        double theta = .5 * Math.atan2(2 * xz, xx - zz);
        double nx = Math.cos(theta), nz = Math.sin(theta), tx = -nz, tz = nx;
        q = refined(q, nx, nz, waves[2] / 4);
        if (baselineAt(q.x() + tx * d, q.z() + tz * d)
                > baselineAt(q.x() - tx * d, q.z() - tz * d)) { tx = -tx; tz = -tz; }
        Geometry g = geometry(q, tx, tz);
        String id = seed + ":c" + crop + ":" + x + ":" + z;
        double[] evidence = receiver(g);
        boolean pass = evidence[0] > 1e-10 && evidence[1] <= 1e-10;
        String reason = pass ? "ELIGIBLE_B_TROUGH" : (evidence[0] <= 1e-10
                ? "NO_TWO_BANK_TROUGH_IN_FIXED_TUBE" : "RECEIVER_MINIMA_REVERSE_DOWNSTREAM");
        return new Candidate(id, crop, x, z, g, pass, reason, evidence[0], evidence[1]);
    }

    /** B-only bounded minimization, fixed effort and bracket; never observes a C result. */
    private Point refined(Point center, double nx, double nz, double radius) {
        double lo = -radius, hi = radius, k = (Math.sqrt(5) - 1) / 2;
        double a = hi - k * (hi - lo), b = lo + k * (hi - lo);
        double fa = offsetHeight(center, nx, nz, a), fb = offsetHeight(center, nx, nz, b);
        for (int i = 0; i < 32; i++) {
            if (fa <= fb) {
                hi = b; b = a; fb = fa; a = hi - k * (hi - lo); fa = offsetHeight(center, nx, nz, a);
            } else {
                lo = a; a = b; fa = fb; b = lo + k * (hi - lo); fb = offsetHeight(center, nx, nz, b);
            }
        }
        double offset = (lo + hi) / 2;
        return new Point(center.x() + offset * nx, center.z() + offset * nz);
    }

    private double offsetHeight(Point p, double nx, double nz, double offset) {
        return baselineAt(p.x() + nx * offset, p.z() + nz * offset);
    }

    /** Evidence of an existing low channel in the fixed tube, NOT exact centreline minima. */
    private double[] receiver(Geometry g) {
        double minRise = Double.POSITIVE_INFINITY, maxRise = Double.NEGATIVE_INFINITY;
        double previous = Double.POSITIVE_INFINITY, nx = -g.tz(), nz = g.tx();
        for (int j = 0; j < 5; j++) {
            double s = g.downstream() * j / 4;
            Point p = new Point(g.junction().x() + s * g.tx(), g.junction().z() + s * g.tz());
            Point v = refined(p, nx, nz, g.width());
            double h = baselineAt(v.x(), v.z());
            double left = offsetHeight(p, nx, nz, -g.width());
            double right = offsetHeight(p, nx, nz, g.width());
            minRise = Math.min(minRise, Math.min(left, right) - h);
            if (j > 0) maxRise = Math.max(maxRise, h - previous);
            previous = h;
        }
        return new double[]{minRise, maxRise};
    }

    private void sampleGrid(Path out) throws IOException {
        long start = System.nanoTime();
        IntStream.range(0, size).parallel().forEach(row -> {
            for (int col = 0; col < size; col++) {
                int i = row * size + col;
                double x = (ox + col) * step, z = (oz + row) * step;
                State s = state(core, x, z);
                raw[i] = s.raw(); baseline[i] = compose(s);
                require(bits(baseline[i], baselineAt(x, z)), "B accumulation order changed");
                weight[i] = connection.weight(x, z);
                constrained[i] = connection.height(x, z);
                if (weight[i] == 0) require(bits(constrained[i], baseline[i]), "outside is not B");
            }
        });
        gridMs = ms(start);
        write(out, "base-raw", raw); write(out, "baseline-height", baseline);
        write(out, "geometry-height", baseline); write(out, "constrained-height", constrained);
        write(out, "connection-weight", weight);
        descriptors = core.descriptorCount();
        System.out.printf(Locale.ROOT, "GRID seed=%d n=%d %.3fms%n", seed, raw.length, gridMs);
    }

    private void controls(Path out) throws IOException {
        Geometry g = selected.geometry;
        for (int j = 0; j < 48; j++) {
            double s = -g.upstream() + (g.upstream() + g.downstream()) * ((j + .5) / 48);
            double n = (j % 7 - 3) * g.width() / 2;
            Point p = chartPoint(g, s, n);
            State state = state(core, p.x(), p.z());
            require(bits(connection.height(p.x(), p.z(), 0), compose(state)), "zero must be B");
            zeroCompared++;
            for (int k = 0; k < 4; k++) {
                double v = connection.layerValue(k, p.x(), p.z());
                require(Math.abs(v) <= state.capacities()[k], "layer capacity exceeded");
                if (k == 0) require(bits(v, state.values()[k]), "coarse layer changed");
                layerChecks++;
            }
        }
        replay();
        json(out, "checks.json", "\"passed\":true,\"zeroCompared\":" + zeroCompared
                + ",\"layerChecks\":" + layerChecks + ",\"layer0DifferentBits\":0"
                + ",\"replayCompared\":" + replayCompared + ",\"replayDifferentBits\":0"
                + ",\"coreCapacityIsKernelWeightedProduct\":true,\"budgetRecalibration\":false"
                + ",\"scope\":\"contracts only; accepted build is not final-bed or morphology acceptance\"");
    }

    private void replay() {
        Geometry g = selected.geometry;
        InheritedSkeletonErosion cold = newCore();
        GullyJunctionConstraint copy = selected.receiver
                ? GullyJunctionConstraint.build((x, z) -> state(cold, x, z), g, AMP, SHELL)
                : GullyJunctionConstraint.missingParent((x, z) -> state(cold, x, z), g, AMP, SHELL, selected.reason);
        require(copy.status() == connection.status() && copy.reason().equals(connection.reason()), "cold build differs");
        Point[] points = new Point[24];
        double[] expected = new double[24];
        for (int i = 0; i < points.length; i++) {
            points[i] = chartPoint(g, -g.upstream() + (g.upstream() + g.downstream()) * (i / 23.0),
                    (i % 5 - 2) * g.width() / 3);
            expected[i] = connection.height(points[i].x(), points[i].z());
        }
        for (int i = 23; i >= 0; i--) {
            require(bits(copy.height(points[i].x(), points[i].z()), expected[i]), "reverse cold differs");
            replayCompared++;
        }
        cold.clearCache();
        IntStream.range(0, 24).parallel().forEach(i -> require(
                bits(copy.height(points[i].x(), points[i].z()), expected[i]), "parallel after clear differs"));
        replayCompared += 24;
        cold.prepare(g.junction().x() - g.upstream() - 10, g.junction().z() - g.upstream() - 10,
                g.junction().x() + g.downstream() + 10, g.junction().z() + g.downstream() + 10);
        for (int i = 0; i < points.length; i++) {
            require(bits(copy.height(points[i].x(), points[i].z()), expected[i]), "overlap prepare differs");
            replayCompared++;
        }
    }

    private void junction(Path out) throws IOException {
        Geometry g = selected.geometry;
        StringBuilder routes = new StringBuilder("[");
        for (int branch = 0; branch < 3; branch++) {
            if (branch != 0) routes.append(',');
            routes.append("{\"id\":").append(quote(branch == 0 ? "parent" : "child-" + branch));
            routes.append(",\"parentId\":").append(branch == 0 ? "null" : quote("parent"));
            routes.append(",\"level\":").append(branch == 0 ? 1 : 2);
            routes.append(",\"parentArc\":0,\"sharedJunctionId\":").append(quote(selected.id));
            routes.append(",\"bedAtQ\":").append(number(connection.baseline(g.junction().x(), g.junction().z())));
            routes.append(",\"points\":[");
            for (int i = 0; i <= 64; i++) {
                if (i != 0) routes.append(',');
                double s = branch == 0 ? g.downstream() * i / 64 : -g.upstream() + g.upstream() * i / 64;
                routes.append(point(connection.point(branch, s)));
            }
            routes.append("]}");
        }
        routes.append(']');
        json(out, "junction.json", "\"status\":" + quote(connection.status().name())
                + ",\"reason\":" + quote(connection.reason()) + ",\"junction\":" + point(g.junction())
                + ",\"tangent\":" + Arrays.toString(new double[]{g.tx(), g.tz()})
                + ",\"geometry\":{\"upstream\":" + g.upstream() + ",\"downstream\":" + g.downstream()
                + ",\"spread\":" + g.spread() + ",\"width\":" + g.width() + "}"
                + ",\"routes\":" + routes + ",\"candidateCount\":" + candidates.size()
                + ",\"selectedId\":" + quote(selected.id) + ",\"receiverCertified\":" + selected.receiver
                + ",\"receiverMinimumBankRiseH\":" + number(selected.minimumRise)
                + ",\"receiverMaximumDownstreamMinRiseH\":" + number(selected.maximumMinRise)
                + ",\"selectionRule\":" + quote(RULE)
                + ",\"attemptedYCount\":1,\"coordinateSystem\":\"world wu before unchanged outer domain; no post-join warp\""
                + ",\"sCoordinate\":\"longitudinal chart projection, not curve arc length\""
                + ",\"localGraphHasUniqueParentAndNoCycle\":true,\"globalOutletProven\":false");
    }

    private void candidateLog(Path out) throws IOException {
        StringBuilder text = new StringBuilder("id,crop,cell_x,cell_z,q_x_wu,q_z_wu,eligible,reason,min_bank_rise_H,max_downstream_min_rise_H,selected,attempted_c0\n");
        for (Candidate c : candidates) text.append(c.id).append(',').append(c.crop).append(',')
                .append(c.cellX).append(',').append(c.cellZ).append(',').append(c.geometry.junction().x())
                .append(',').append(c.geometry.junction().z()).append(',').append(c.receiver)
                .append(',').append(c.reason).append(',').append(number(c.minimumRise)).append(',')
                .append(number(c.maximumMinRise)).append(',').append(c == selected).append(',')
                .append(c == selected).append('\n');
        Files.writeString(out.resolve("candidates.csv"), text);
    }

    private void metadata(Path out) throws IOException {
        json(out, "sampling.json", "\"size\":" + size + ",\"originWu\":"
                + Arrays.toString(new double[]{ox * step, oz * step}) + ",\"stepWu\":" + step
                + ",\"nativeStepWu\":" + nativeStep + ",\"spanWu\":" + ((size - 1) * step)
                + ",\"HS\":2,\"amplitude\":0.25,\"shellAmplitude\":" + SHELL
                + ",\"k\":1,\"beta\":1,\"seed\":" + seed + ",\"typeLengthWu\":" + length
                + ",\"wavelengthsWu\":" + Arrays.toString(waves)
                + ",\"baseDefinition\":\"unchanged ridge4 x scaler4 x original warp350/150\""
                + ",\"outerDomain\":\"unchanged 2wu direction-domain mapping\",\"actualMinecraftWorld\":false");
        json(out, "settings.json", "\"V\":" + VERTICAL + ",\"onset\":" + Arrays.toString(ONSET)
                + ",\"epsilon\":" + Arrays.toString(EPSILON) + ",\"chi\":0.25,\"amplitude\":0.25"
                + ",\"gain\":0.5,\"gainSum\":1.875,\"k\":1,\"beta\":1"
                + ",\"wavelengthsWu\":" + Arrays.toString(waves) + ",\"coarsestFrozen\":true"
                + ",\"recalibration\":false,\"fifthLayer\":false,\"selectionRule\":" + quote(RULE));
    }

    private void discoveryAssessment(Path out) throws IOException {
        boolean finalBed = GullyJunctionEvidence.passed(connection);
        json(out, "discovery-assessment.json", "\"status\":\"STOP_AT_C0\",\"singleYFinalBedPassed\":" + finalBed
                + ",\"automaticWorldDiscoveryImplemented\":false,\"infiniteWorldProven\":false"
                + ",\"reason\":" + quote(finalBed
                ? "finite one-Y numerical evidence only; visual confirmation precedes C1; no world-network work"
                : "C0 final-bed prerequisite failed; do not advance automatic world parent discovery")
                + ",\"windowOverlapScope\":\"same fixed descriptor and world coordinates, not discovery invariance\"");
    }

    private void performance(Path out) throws IOException {
        int n = 64;
        Geometry g = selected.geometry;
        double sink = 0;
        double[] b = new double[7], c = new double[7];
        for (int batch = 0; batch < 10; batch++) {
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Point p = chartPoint(g, -g.upstream() + (g.upstream() + g.downstream()) * i / n, 0);
                sink += baselineAt(p.x(), p.z());
            }
            double elapsedB = ms(start);
            start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Point p = chartPoint(g, -g.upstream() + (g.upstream() + g.downstream()) * i / n, 0);
                sink += connection.height(p.x(), p.z());
            }
            if (batch >= 3) { b[batch - 3] = elapsedB; c[batch - 3] = ms(start); }
        }
        require(Double.isFinite(sink), "performance sink");
        Arrays.sort(b); Arrays.sort(c);
        json(out, "performance.json", "\"batchPoints\":64,\"warmupBatches\":3,\"measuredBatches\":7"
                + ",\"baselineWarmMedianMs\":" + b[3] + ",\"constraintWarmMedianMs\":" + c[3]
                + ",\"prepareMs\":" + prepareMs + ",\"candidateDiscoveryMs\":" + discoverMs
                + ",\"constraintBuildMs\":" + buildMs + ",\"gridMs\":" + gridMs
                + ",\"descriptors\":" + descriptors + ",\"constraintStatus\":" + quote(connection.status().name())
                + ",\"scope\":\"pure CPU isolated C0; rejected-branch cost is not a successful constraint benchmark or MC performance\"");
    }

    private void run(Path out) throws IOException {
        require(!Files.exists(out), "refuse overwrite " + out);
        Files.createDirectories(out);
        prepare(); discover();
        candidateLog(out); junction(out); metadata(out);
        sampleGrid(out); controls(out);
        GullyJunctionEvidence.write(out, connection);
        discoveryAssessment(out); performance(out);
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        require(args.length == 1, "usage: <NEW data directory>");
        Path out = Path.of(args[0]);
        new GullyJunctionProbe(MorphCatalog.CANONICAL_SEED, 769).run(out);
        new GullyJunctionProbe(12345L, 257).run(out.resolve("holdout"));
        GullyJunctionEvidence.writeSynthetic(out.resolve("synthetic"));
        System.out.println("C0 EVIDENCE COMPLETE: statuses may be rejected; NOT morphology acceptance or production");
    }

    private static Point chartPoint(Geometry g, double s, double b) {
        return new Point(g.junction().x() + s * g.tx() - b * g.tz(),
                g.junction().z() + s * g.tz() + b * g.tx());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    private static String point(Point p) { return Arrays.toString(new double[]{p.x(), p.z()}); }
    private static String number(double v) { return Double.isFinite(v) ? Double.toString(v) : "null"; }
    private static double ms(long start) { return (System.nanoTime() - start) / 1e6; }
    private static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void json(Path out, String name, String body) throws IOException {
        Files.writeString(out.resolve(name), "{\n" + body + "\n}\n");
    }
    private static void write(Path out, String name, double[] values) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double v : values) {
                require(Double.isFinite(v), "nonfinite " + name);
                stream.writeDouble(v);
            }
        }
    }
}
