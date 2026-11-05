package com.geogenesis.standalone;

import com.geogenesis.standalone.GullyJunctionConstraint.Geometry;
import com.geogenesis.standalone.GullyJunctionConstraint.Point;
import com.geogenesis.standalone.GullyJunctionConstraint.Status;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Independent JDK17 finite evidence of FINAL continuous H, never image interpolation or build evidence.
 * Geometry is deliberately the exact baseline arm, not a second terrain operation. s is the fixed
 * chart projection, NOT curve arc length. Increasing s is downstream, including both end fades.
 *
 * <p>Call write(out, c) before deciding the main probe's exit status with passed(c). Rejection and
 * query exceptions retain all sample rows (missing numbers are empty CSV cells / JSON null), but
 * can never certify the constrained arm. Neither method changes c or chooses a different constraint.
 * There is no global, unsampled, naturalness, hydrological discharge or strict-curvature proof.</p>
 */
public final class GullyJunctionEvidence {
    private static final String[] ROUTES = {"parent", "child-1", "child-2"};
    private static final String PROFILE_HEADER =
            "route,s_wu,world_x,world_z,baseline_H,geometry_H,constrained_H,target_H";
    private static final String CROSS_HEADER =
            "route,s_wu,offset_wu,world_x,world_z,baseline_H,geometry_H,constrained_H";
    private static final long MAX_GRID_NODES = 1_000_000;
    private static final Point MISSING = new Point(Double.NaN, Double.NaN);

    private GullyJunctionEvidence() {}

    /**
     * Exports Check's unchanged smooth-slope fixture, not a real-window or holdout certificate.
     * Its local ACCEPTED status does not imply that these stricter final-normal tests pass.
     * Call with a separate synthetic directory; no Check/main/renderer dependency is required.
     */
    public static void writeSynthetic(Path out) throws IOException {
        Geometry y = new Geometry(new Point(0, 0), 1, 0, 12, 8, 3, 2);
        GullyJunctionConstraint c = GullyJunctionConstraint.build(
                GullyJunctionEvidence::syntheticSlope, y, 0.25, 1);
        write(out, c);
        writeSyntheticGrid(out, c);
    }

    private static void writeSyntheticGrid(Path out, GullyJunctionConstraint c) throws IOException {
        try (BufferedWriter writer = csv(out, "height-grid.csv", "x,z,baseline_H,constrained_H")) {
            for (int row = 0; row <= 192; row++) for (int col = 0; col <= 256; col++) {
                double x = -14 + 24 * (col / 256.0), z = -6 + 12 * (row / 192.0);
                row(writer, x, z, c.baseline(x, z), c.height(x, z));
            }
        }
    }

    /** Exact slope fixture from GullyJunctionCheck, with the same true per-layer capacities. */
    private static GullyJunctionConstraint.State syntheticSlope(double s, double b) {
        double bump = (s + 5) / 1.2;
        double raw = 0.2 - 0.002 * s + 0.0004 * b * b
                + 0.007 * StrictMath.exp(-(bump * bump));
        double[] values = {-0.12 + 0.025 * StrictMath.sin(s / 11) * StrictMath.cos(b / 9),
                0.09 * StrictMath.cos(s / 9) * StrictMath.cos(b / 7),
                -0.06 * StrictMath.sin(s / 7) + 0.01 * StrictMath.cos(b / 8),
                0.04 * StrictMath.cos(s / 6) * StrictMath.cos(b / 6)};
        double[] caps = {0.3, 0.82 + 0.02 * StrictMath.cos(s / 9),
                0.63 + 0.01 * StrictMath.cos(b / 8), 0.47 + 0.01 * StrictMath.cos(s / 6)};
        return new GullyJunctionConstraint.State(raw, values, caps);
    }

    /** Writes fresh evidence in the caller's directory; does not delete anything on a failed test. */
    public static void write(Path out, GullyJunctionConstraint c) throws IOException {
        Objects.requireNonNull(c, "constraint");
        Files.createDirectories(Objects.requireNonNull(out, "out"));
        Report report;
        try (BufferedWriter profiles = csv(out, "profiles.csv", PROFILE_HEADER);
             BufferedWriter crosses = csv(out, "cross-sections.csv", CROSS_HEADER);
             BufferedWriter samples = csv(out, "bed-samples.csv",
                     "kind,route,s_wu,offset_wu,world_x,world_z,baseline_H,geometry_H,constrained_H");
             BufferedWriter normals = csv(out, "dense-normal-checks.csv",
                     "kind,route,s_wu,delta_wu,arm,passed,near_rise,side_rise,normal_gradient,"
                             + "estimated_center_offset_wu,sampled_center_offset_wu")) {
            report = new Run(c, profiles, crosses, samples, normals).evaluate();
        }
        Files.writeString(out.resolve("bed-validation.json"), json(report.json()) + "\n",
                StandardCharsets.UTF_8);
    }

    /** Re-samples without I/O or caching. Baseline failure is an informative negative control. */
    public static boolean passed(GullyJunctionConstraint c) {
        try {
            return new Run(Objects.requireNonNull(c, "constraint"), null, null, null, null)
                    .evaluate().passed();
        } catch (IOException impossible) {
            throw new AssertionError("no writers in passed", impossible);
        }
    }

    private record Heights(double baseline, double constrained) {
        double arm(int arm) { return arm == 0 ? baseline : constrained; }
    }

    private record Normal(boolean passed, double nearRise, double sideRise,
                          double gradient, double estimatedOffset, double sampledOffset) {}

    private static final class Longitudinal {
        long samples, reversalCount, nonfiniteCount;
        double maxRise;

        void add(double previous, double h, boolean first) {
            samples++;
            if (!Double.isFinite(h)) nonfiniteCount++;
            if (!first && Double.isFinite(previous) && Double.isFinite(h)) {
                double rise = h - previous;
                maxRise = StrictMath.max(maxRise, rise);
                if (rise > tolerance(h, previous)) reversalCount++;
            }
        }

        Map<String, Object> json(boolean eligible) {
            return map("passed", eligible && samples >= 771 && nonfiniteCount == 0
                    && reversalCount == 0, "samples", samples, "reversalCount", reversalCount,
                    "maxRise", maxRise, "nonfiniteCount", nonfiniteCount,
                    "includesBothFiniteGateEndpoints", true);
        }
    }

    private static final class Cross {
        long sections, lowPointFailures, fixedSections, denseSections, junctionExclusions;
        double minSideRise = Double.POSITIVE_INFINITY, minNearRise = Double.POSITIVE_INFINITY;
        double maxNormalGradient, maxEstimatedCenterOffset, maxSampledCenterOffset;

        void add(Normal n, boolean fixed) {
            sections++;
            if (fixed) fixedSections++; else denseSections++;
            if (!n.passed) lowPointFailures++;
            minNearRise = minimum(minNearRise, n.nearRise);
            minSideRise = minimum(minSideRise, n.sideRise);
            maxNormalGradient = maximum(maxNormalGradient, StrictMath.abs(n.gradient));
            maxEstimatedCenterOffset = maximum(maxEstimatedCenterOffset, StrictMath.abs(n.estimatedOffset));
            maxSampledCenterOffset = maximum(maxSampledCenterOffset, StrictMath.abs(n.sampledOffset));
        }

        Map<String, Object> json(boolean eligible) {
            return map("passed", eligible && sections > 0 && lowPointFailures == 0,
                    "sections", sections, "lowPointFailures", lowPointFailures,
                    "minSideRise", minSideRise, "minNearRise", minNearRise,
                    "fixedSectionsChecked", fixedSections, "denseSectionsChecked", denseSections,
                    "qSectionsExcludedFromNormalTest", junctionExclusions,
                    "maxNormalGradient", maxNormalGradient,
                    "maxEstimatedCenterOffsetWu", maxEstimatedCenterOffset,
                    "maxSampledCenterOffsetWu", maxSampledCenterOffset);
        }
    }

    private record Report(Map<String, Object> json, boolean passed) {}

    private static final class Run {
        final GullyJunctionConstraint c;
        final BufferedWriter profiles, crosses, samples, normals;
        final Longitudinal[] along = {new Longitudinal(), new Longitudinal()};
        final Cross[] cross = {new Cross(), new Cross()};
        final long[] queryFailures = new long[3];
        final List<String> errors = new ArrayList<>();
        boolean complete = true;

        Run(GullyJunctionConstraint c, BufferedWriter profiles, BufferedWriter crosses,
            BufferedWriter samples, BufferedWriter normals) {
            this.c = c;
            this.profiles = profiles;
            this.crosses = crosses;
            this.samples = samples;
            this.normals = normals;
        }

        Report evaluate() throws IOException {
            List<Map<String, Object>> junction = emptyJunctions();
            try {
                requireGeometry();
                for (int branch = 0; branch < 3; branch++) {
                    profile(branch);
                    fixedSections(branch);
                }
                junction = junction();
            } catch (RuntimeException failure) {
                complete = false;
                error("incomplete evidence: " + failure);
            }
            boolean accepted = c.status() == Status.ACCEPTED;
            Map<String, Object> baseline = arm(0, complete, junction.get(0));
            Map<String, Object> constrained = arm(1, complete && accepted, junction.get(1));
            boolean pass = Boolean.TRUE.equals(constrained.get("passed")) && queryFailures[2] == 0;
            Map<String, Object> result = map("status", c.status().name(), "accepted", accepted,
                    "pass", pass, "passed", pass, "samplingComplete", complete,
                    "constraintReason", c.reason(), "geometryEqualsBaseline", true,
                    "baseline", baseline, "geometry", baseline, "constrained", constrained,
                    "criteria", criteria(c), "baselineQueryFailures", queryFailures[0],
                    "constrainedQueryFailures", queryFailures[1], "targetQueryFailures", queryFailures[2],
                    "errors", errors, "limitations", "Finite samples only, not a global proof. "
                            + "No image interpolation, curve fitting, strict curvature test, receiver "
                            + "certification or flow simulation. Rejected constrained arm cannot pass.");
            return new Report(result, pass);
        }

        Map<String, Object> arm(int arm, boolean eligible, Map<String, Object> junction) {
            Map<String, Object> longitudinal = along[arm].json(eligible);
            Map<String, Object> transverse = cross[arm].json(eligible);
            boolean jp = eligible && Boolean.TRUE.equals(junction.get("passed"));
            junction.put("passed", jp);
            boolean pass = eligible && queryFailures[arm] == 0 && jp
                    && Boolean.TRUE.equals(longitudinal.get("passed"))
                    && Boolean.TRUE.equals(transverse.get("passed"));
            return map("passed", pass, "longitudinal", longitudinal, "cross", transverse,
                    "junction", junction);
        }

        void requireGeometry() {
            Geometry g = c.geometry();
            if (g == null || g.junction() == null || !(g.width() > 0)
                    || !(g.upstream() > g.width()) || !(g.downstream() > g.width())) {
                throw new IllegalArgumentException("no sampleable fixed Y geometry");
            }
            c.point(0, 0); // Also rejects nonfinite, unresolvable or invalid chart geometry.
        }

        void profile(int branch) throws IOException {
            Geometry g = c.geometry();
            double lo = branch == 0 ? 0 : -g.upstream();
            double hi = branch == 0 ? g.downstream() : 0;
            int n = segments(hi - lo, StrictMath.min(g.width() / 32, 0.25), 256);
            double[] previous = {Double.NaN, Double.NaN};
            for (int i = 0; i <= n; i++) {
                double s = station(lo, hi, i, n);
                Point p = point(branch, s);
                Heights h = sample("profile", branch, s, 0, p);
                double target = c.status() == Status.ACCEPTED
                        ? query(() -> c.targetBed(s), 2, "target " + ROUTES[branch] + " s=" + s)
                        : Double.NaN;
                row(profiles, ROUTES[branch], s, p.x(), p.z(), h.baseline, h.baseline,
                        h.constrained, target);
                for (int arm = 0; arm < 2; arm++) {
                    along[arm].add(previous[arm], h.arm(arm), i == 0);
                    previous[arm] = h.arm(arm);
                }
                checkNormal("dense", branch, s, p, h, false);
            }
        }

        void fixedSections(int branch) throws IOException {
            Geometry g = c.geometry();
            double[] stations = branch == 0 ? new double[]{0, g.width(), g.downstream() / 2}
                    : new double[]{-g.upstream() / 2, -g.width(), 0};
            int n = segments(2 * g.width(), StrictMath.min(g.width() / 32, 0.25), 64);
            if ((n & 1) != 0) n++;
            for (double s : stations) {
                Point center = point(branch, s), normal = normal(branch, s);
                for (int i = 0; i <= n; i++) {
                    double offset = station(-g.width(), g.width(), i, n);
                    Point p = shifted(center, normal, offset);
                    Heights h = sample("fixed-cross", branch, s, offset, p);
                    row(crosses, ROUTES[branch], s, offset, p.x(), p.z(),
                            h.baseline, h.baseline, h.constrained);
                }
                Heights h = sample("fixed-center", branch, s, 0, center);
                checkNormal("fixed", branch, s, center, h, true);
            }
        }

        void checkNormal(String kind, int branch, double s, Point p, Heights center,
                         boolean fixed) throws IOException {
            if (s == 0) {
                for (Cross metric : cross) metric.junctionExclusions++;
                return; // Shared q is checked by a 2D threshold flood, not a preferred normal.
            }
            Point normal = normal(branch, s);
            double mid = c.geometry().width() / 8;
            double delta = mid / 4; // Fixed resolvable scale, not an infinitesimal child-gap test.
            Heights[] near = new Heights[17];
            for (int k = -8; k <= 8; k++) {
                double offset = k * delta / 4;
                near[k + 8] = k == 0 ? center : sample(kind + "-normal", branch, s,
                        offset, shifted(p, normal, offset));
            }
            Heights left = sample(kind + "-side", branch, s, -mid, shifted(p, normal, -mid));
            Heights right = sample(kind + "-side", branch, s, mid, shifted(p, normal, mid));
            for (int arm = 0; arm < 2; arm++) {
                Normal test = assessNormal(center.arm(arm), near, left.arm(arm), right.arm(arm),
                        delta, arm, c.shell());
                cross[arm].add(test, fixed);
                row(normals, kind, ROUTES[branch], s, delta, arm == 0 ? "baseline" : "constrained",
                        test.passed, test.nearRise, test.sideRise, test.gradient,
                        test.estimatedOffset, test.sampledOffset);
            }
        }

        Point normal(int branch, double s) {
            Geometry g = c.geometry();
            double lo = branch == 0 ? 0 : -g.upstream(), hi = branch == 0 ? g.downstream() : 0;
            double ds = StrictMath.min(g.width() / 4096, (hi - lo) / 4096);
            Point a = point(branch, StrictMath.max(lo, s - ds));
            Point b = point(branch, StrictMath.min(hi, s + ds));
            double dx = b.x() - a.x(), dz = b.z() - a.z(), length = StrictMath.hypot(dx, dz);
            return length > 0 && Double.isFinite(length) ? new Point(-dz / length, dx / length) : MISSING;
        }

        Point point(int branch, double s) {
            try {
                return c.point(branch, s);
            } catch (RuntimeException failure) {
                error("point " + ROUTES[branch] + " s=" + s + ": " + failure);
                return MISSING;
            }
        }

        Heights sample(String kind, int branch, double s, double offset, Point p) throws IOException {
            String where = kind + " " + ROUTES[branch] + " s=" + s + " offset=" + offset;
            double b = query(() -> c.baseline(p.x(), p.z()), 0, where);
            double h = query(() -> c.height(p.x(), p.z()), 1, where);
            row(samples, kind, ROUTES[branch], s, offset, p.x(), p.z(), b, b, h);
            return new Heights(b, h);
        }

        double query(DoubleSupplier function, int arm, String where) {
            try {
                double value = function.getAsDouble();
                if (!Double.isFinite(value)) throw new IllegalStateException("nonfinite final value");
                return value;
            } catch (RuntimeException failure) {
                queryFailures[arm]++;
                error((arm == 0 ? "baseline " : arm == 1 ? "constrained " : "target ")
                        + where + ": " + failure);
                return Double.NaN;
            }
        }

        void error(String text) {
            if (errors.size() < 32) errors.add(text); // Counts remain exact; only example messages capped.
        }

        List<Map<String, Object>> junction() throws IOException {
            double[] sharedDifference = sharedQ();
            Grid grid = grid();
            if (grid == null) return emptyJunctions();
            Heights[] heights = new Heights[grid.nodes()];
            for (int i = 0; i < heights.length; i++) {
                double s = grid.s(i), b = grid.b(i);
                heights[i] = sample("junction-grid", 0, s, b, world(s, b));
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int arm = 0; arm < 2; arm++) {
                result.add(flood(grid, heights, arm, sharedDifference[arm]));
            }
            return result;
        }

        double[] sharedQ() throws IOException {
            double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int branch = 0; branch < 3; branch++) {
                Heights h = sample("shared-q", branch, 0, 0, point(branch, 0));
                for (int arm = 0; arm < 2; arm++) {
                    lo[arm] = StrictMath.min(lo[arm], h.arm(arm));
                    hi[arm] = StrictMath.max(hi[arm], h.arm(arm));
                }
            }
            return new double[]{hi[0] - lo[0], hi[1] - lo[1]};
        }

        Grid grid() {
            Geometry g = c.geometry();
            double child = -StrictMath.min(0.75 * g.upstream(), 3 * g.width());
            double parent = StrictMath.min(g.downstream() / 2, g.width());
            Point a = point(1, child);
            double radius = StrictMath.abs(chartB(a)) + g.width() / 2;
            double step = StrictMath.min(g.width() / 64, 0.125);
            double up = StrictMath.ceil((-child + g.width() / 4) / step);
            double down = StrictMath.ceil((parent + g.width() / 4) / step);
            double side = StrictMath.ceil(radius / step);
            double nodes = (up + down + 1) * (2 * side + 1);
            if (!Double.isFinite(nodes) || nodes > MAX_GRID_NODES || !(step > 0)) {
                complete = false;
                error("local junction grid exceeds 1000000 nodes or is unresolvable; not coarsened");
                return null;
            }
            return new Grid((int) up, (int) down, (int) side, step, child, parent);
        }

        Map<String, Object> flood(Grid grid, Heights[] heights, int arm, double difference)
                throws IOException {
            double q = heights[grid.q()].arm(arm);
            double allowance = StrictMath.max(StrictMath.abs(c.shell()) * 1e-4, 4096 * ulp(q));
            boolean[] low = new boolean[heights.length];
            for (int row = 0; row <= grid.up + grid.down; row++) {
                double s = grid.s(row * grid.columns());
                // Follow the actual centre-bed level; a legitimate upstream slope is above q.
                // Longitudinal reversals are independently checked, not hidden by this local band.
                double level = bedLevel(s, arm) + allowance;
                for (int col = 0; col < grid.columns(); col++) {
                    int i = row * grid.columns() + col;
                    double h = heights[i].arm(arm);
                    low[i] = Double.isFinite(h) && Double.isFinite(level) && h <= level;
                }
            }
            boolean[] reached = floodFill(grid, low);
            List<Object> anchors = new ArrayList<>();
            int connected = 0;
            for (int branch = 0; branch < 3; branch++) {
                Map<String, Object> anchor = anchor(grid, reached, branch, arm, allowance);
                anchors.add(anchor);
                if (Boolean.TRUE.equals(anchor.get("connected"))) connected++;
            }
            Point a = point(1, grid.child), b = point(2, grid.child);
            double separation = StrictMath.hypot(a.x() - b.x(), a.z() - b.z());
            boolean distinct = separation >= 2 * grid.step;
            long visited = 0;
            for (boolean value : reached) if (value) visited++;
            boolean finite = Arrays.stream(heights).allMatch(h -> Double.isFinite(h.arm(arm)));
            boolean connectivity = finite && distinct && connected == 3 && low[grid.q()];
            boolean shared = Double.isFinite(difference) && difference <= tolerance(q, q);
            return map("passed", connectivity && shared, "sharedQDifference", difference,
                    "lowSlotConnectivity", connectivity, "connectedAnchors", connected,
                    "anchors", anchors, "distinctChildAnchors", distinct,
                    "childAnchorSeparationWu", separation, "qLevel", q,
                    "thresholdAllowance", allowance, "thresholdAtQ", q + allowance,
                    "thresholdPolicy", "row-wise actual bed envelope + allowance; slopes checked separately",
                    "gridStepWu", grid.step, "gridNodes", heights.length,
                    "gridFinite", finite, "reachableNodes", visited,
                    "neighborhood", "4-neighbor, no diagonal corner crossing; exact q node");
        }

        Map<String, Object> anchor(Grid grid, boolean[] reached, int branch, int arm,
                                   double allowance) throws IOException {
            double s = branch == 0 ? grid.parent : grid.child;
            Point p = point(branch, s);
            double b = chartB(p);
            int index = grid.index(s, b);
            Point snapped = world(grid.s(index), grid.b(index));
            double length = StrictMath.hypot(p.x() - snapped.x(), p.z() - snapped.z());
            int n = segments(length, grid.step / 4, 1);
            boolean connector = true;
            double anchorH = Double.NaN;
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                Point at = new Point(p.x() + u * (snapped.x() - p.x()),
                        p.z() + u * (snapped.z() - p.z()));
                Heights h = sample("junction-anchor-connector", branch,
                        s + u * (grid.s(index) - s), b + u * (grid.b(index) - b), at);
                double value = h.arm(arm);
                if (i == 0) anchorH = value;
                double level = bedLevel(s + u * (grid.s(index) - s), arm) + allowance;
                connector &= Double.isFinite(value) && Double.isFinite(level) && value <= level;
            }
            return map("route", ROUTES[branch], "sWu", s, "chartBWu", b,
                    "worldX", p.x(), "worldZ", p.z(), "height", anchorH,
                    "gridIndex", index, "connectorPassed", connector,
                    "connected", connector && reached[index]);
        }

        double bedLevel(double s, int arm) throws IOException {
            Geometry g = c.geometry();
            int branch = s < 0 ? 1 : 0;
            double at = StrictMath.max(-g.upstream(), StrictMath.min(g.downstream(), s));
            Heights a = sample("junction-level", branch, at, 0, point(branch, at));
            if (s >= 0) return a.arm(arm);
            Heights b = sample("junction-level", 2, at, 0, point(2, at));
            return StrictMath.max(a.arm(arm), b.arm(arm));
        }

        double chartB(Point p) {
            Geometry g = c.geometry();
            return -(p.x() - g.junction().x()) * g.tz() + (p.z() - g.junction().z()) * g.tx();
        }

        Point world(double s, double b) {
            Geometry g = c.geometry();
            if (s == 0 && b == 0) return g.junction();
            return new Point(g.junction().x() + s * g.tx() - b * g.tz(),
                    g.junction().z() + s * g.tz() + b * g.tx());
        }
    }

    private record Grid(int up, int down, int side, double step, double child, double parent) {
        int columns() { return 2 * side + 1; }
        int nodes() { return (up + down + 1) * columns(); }
        int q() { return up * columns() + side; }
        double s(int index) { return (index / columns() - up) * step; }
        double b(int index) { return (index % columns() - side) * step; }
        int index(double s, double b) {
            int row = (int) StrictMath.round(s / step) + up;
            int col = (int) StrictMath.round(b / step) + side;
            if (row < 0 || row > up + down || col < 0 || col >= columns()) {
                throw new IllegalArgumentException("junction anchor outside local grid");
            }
            return row * columns() + col;
        }
    }

    private static boolean[] floodFill(Grid grid, boolean[] low) {
        boolean[] seen = new boolean[low.length];
        int[] queue = new int[low.length];
        int head = 0, tail = 0, cols = grid.columns();
        if (low[grid.q()]) {
            seen[grid.q()] = true;
            queue[tail++] = grid.q();
        }
        while (head < tail) {
            int i = queue[head++], col = i % cols;
            int[] neighbors = {col > 0 ? i - 1 : -1, col + 1 < cols ? i + 1 : -1,
                    i - cols, i + cols};
            for (int j : neighbors) {
                if (j >= 0 && j < low.length && low[j] && !seen[j]) {
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }
        }
        return seen;
    }

    private static Normal assessNormal(double h, Heights[] near, double left, double right,
                                       double delta, int arm, double shell) {
        double minus = near[4].arm(arm), plus = near[12].arm(arm);
        double nearRise = StrictMath.min(minus - h, plus - h);
        double sideRise = StrictMath.min(left - h, right - h);
        double eps = StrictMath.max(tolerance(h, h), StrictMath.abs(shell) * 1e-10);
        double gradient = (plus - minus) / (2 * delta);
        double curvatureNumerator = plus + minus - 2 * h;
        double estimate = curvatureNumerator > 0
                ? -(plus - minus) * delta / (2 * curvatureNumerator) : Double.NaN;
        double best = h, bestOffset = 0;
        boolean finite = Double.isFinite(h) && Double.isFinite(left) && Double.isFinite(right);
        for (int i = 0; i < near.length; i++) {
            double value = near[i].arm(arm);
            finite &= Double.isFinite(value);
            if (value < best) {
                best = value;
                bestOffset = (i - 8) * delta / 4;
            }
        }
        boolean passed = finite && Double.isFinite(delta) && delta > 0
                && nearRise > eps && sideRise > 4 * eps && best >= h - eps
                && Double.isFinite(estimate) && StrictMath.abs(estimate) <= delta / 4
                && StrictMath.abs(bestOffset) <= delta / 4;
        return new Normal(passed, nearRise, sideRise, gradient, estimate, bestOffset);
    }

    private static Map<String, Object> criteria(GullyJunctionConstraint c) {
        return map("heightSource", "direct c.baseline(x,z) and c.height(x,z), final shell-scaled H",
                "geometryArm", "exact baseline alias, geometry-only lines do not modify H",
                "sCoordinate", "fixed chart projection in world units; NOT true curve arc length",
                "targetH", "unmixed c.targetBed(s), NOT final faded H; empty unless ACCEPTED",
                "profileMinSamplesPerRoute", 257, "profileMaxStepWu",
                c.geometry() == null ? Double.NaN : StrictMath.min(c.geometry().width() / 32, 0.25),
                "fixedStations", "parent: [0,width,down/2]; children: [-up/2,-width,0]",
                "fixedCrossMinSamples", 65, "fixedCrossRange", "[-width,+width] actual curve normal",
                "normal", "symmetric point difference width/4096, one-sided at finite endpoints",
                "denseCross", "every profile station except q; plus all fixed stations except q",
                "nearDelta", "width/32 (sides at width/8); fixed finite resolution, not vanishing child gap",
                "normalGradient", "(H(+delta)-H(-delta))/(2*delta); constrained via estimated vertex",
                "centerOffset", "quadratic local estimate and 17 actual samples over +/-2delta; "
                        + "both <=delta/4; local minimum must not fall below center by tolerance",
                "positiveRise", "near >max(1024 ulp(max(1,abs(H))),abs(shell)*1e-10); "
                        + "both sides at 4delta >4 times this; no unique normal requirement at q",
                "longitudinal", "non-increasing final H, plateaus allowed; reversal tolerance 1024 ulp",
                "junction", "single local chart grid per arm, step=min(width/64,.125), "
                        + "row-wise actual centre-bed envelope+max(abs(shell)*1e-4,4096ulp(qLevel)); all 3 distinct-arm anchors "
                        + "must connect by finite 4-neighbor flood plus sampled anchor-to-node connectors",
                "gridLimit", MAX_GRID_NODES, "gridLimitFailure", "explicit incomplete failure, no coarsening",
                "rawEvidence", "bed-samples.csv includes ALL function evaluations used by tests; "
                        + "junction rows use s_wu and offset_wu as chart s,b, not route normal offset",
                "errorPolicy", "each failed/nonfinite query is empty CSV/null JSON and counted; "
                        + "up to 32 example messages; never replaced by a successful baseline value");
    }

    private static List<Map<String, Object>> emptyJunctions() {
        return List.of(map("passed", false, "sharedQDifference", Double.NaN,
                "lowSlotConnectivity", false, "connectedAnchors", 0),
                map("passed", false, "sharedQDifference", Double.NaN,
                        "lowSlotConnectivity", false, "connectedAnchors", 0));
    }

    private static Point shifted(Point p, Point n, double offset) {
        return new Point(p.x() + offset * n.x(), p.z() + offset * n.z());
    }

    private static int segments(double length, double step, int minimum) {
        double n = StrictMath.max(minimum, StrictMath.ceil(length / step));
        if (!Double.isFinite(n) || n >= Integer.MAX_VALUE || !(step > 0)) {
            throw new IllegalArgumentException("unrepresentable sample count; no undersampling fallback");
        }
        return (int) n;
    }

    private static double station(double lo, double hi, int i, int n) {
        if (i == 0) return lo;
        if (i == n) return hi;
        return lo + (hi - lo) * (i / (double) n);
    }

    private static double ulp(double h) {
        return Double.isFinite(h) ? StrictMath.ulp(StrictMath.max(1, StrictMath.abs(h))) : Double.NaN;
    }

    private static double tolerance(double a, double b) {
        return 1024 * StrictMath.max(ulp(a), ulp(b));
    }

    private static double minimum(double a, double b) {
        return Double.isFinite(b) ? StrictMath.min(a, b) : a;
    }

    private static double maximum(double a, double b) {
        return Double.isFinite(b) ? StrictMath.max(a, b) : a;
    }

    private static BufferedWriter csv(Path out, String name, String header) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(out.resolve(name), StandardCharsets.UTF_8);
        writer.write(header);
        writer.newLine();
        return writer;
    }

    private static void row(BufferedWriter writer, Object... cells) throws IOException {
        if (writer == null) return;
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) writer.write(',');
            Object cell = cells[i];
            if (cell instanceof Number n && !Double.isFinite(n.doubleValue())) continue;
            if (cell != null) writer.write(cell.toString()); // Only controlled comma-free labels/numbers.
        }
        writer.newLine();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number n) return Double.isFinite(n.doubleValue()) ? n.toString() : "null";
        if (value instanceof Map<?, ?> m) {
            List<String> fields = new ArrayList<>();
            m.forEach((key, item) -> fields.add(quote(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) items.add(json(item));
            return "[" + String.join(",", items) + "]";
        }
        return quote(value.toString());
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }
}
