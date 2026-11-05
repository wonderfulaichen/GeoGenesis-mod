package com.geogenesis.standalone;

import com.geogenesis.standalone.GullyJunctionConstraint.Field;
import com.geogenesis.standalone.GullyJunctionConstraint.Geometry;
import com.geogenesis.standalone.GullyJunctionConstraint.Interval;
import com.geogenesis.standalone.GullyJunctionConstraint.Point;
import com.geogenesis.standalone.GullyJunctionConstraint.State;
import com.geogenesis.standalone.GullyJunctionConstraint.Status;

import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * JDK17-only C0 synthetic checks. Compile/run just this file and GullyJunctionConstraint.java.
 * No A/B changes, Minecraft, real-window probe or renderer; any failed assertion exits nonzero.
 * These analytic synthetic states have explicit per-layer true bounds, not activity*confidence.
 */
public final class GullyJunctionCheck {
    private static final double AMP = 0.25;
    private static final double NORM = 1.875;
    private static final Geometry Y = new Geometry(new Point(0, 0), 1, 0, 12, 8, 3, 2);
    private static int checks;

    private GullyJunctionCheck() {}

    public static void main(String[] args) {
        long start = System.nanoTime();
        try {
            if (args.length != 0) throw new IllegalArgumentException("no arguments expected");
            run("validation / immutable capacities / statuses", GullyJunctionCheck::checkValidation);
            run("fixed C2 Y / finite C2 gate / shared q", GullyJunctionCheck::checkGeometry);
            run("smooth slope: chart-axis slots only, not full normal-bed acceptance", GullyJunctionCheck::checkSlope);
            run("independent final-bed counterexample stays red", GullyJunctionCheck::checkFinalBedCounterexample);
            run("full intervals / convex per-layer allocation / shell", GullyJunctionCheck::checkBudgets);
            run("zero / outside / frozen coarse bitwise B", GullyJunctionCheck::checkNegativeControls);
            run("ridge / mismatch / off-route capacity rejected unchanged", GullyJunctionCheck::checkRejections);
            run("unsampled violation throws, never clips or silently falls back", GullyJunctionCheck::checkUnsampled);
            run("negative coordinates / reverse / rebuild / parallel bitwise", GullyJunctionCheck::checkDeterminism);
            System.out.println("LIMIT: synthetic single-Y finite evidence only; no global proof, naturalness,"
                    + " real receiver certification, real-window rendering or production changes.");
            System.out.printf(Locale.ROOT, "PASS checks=%d elapsed_ms=%.3f%n", checks,
                    (System.nanoTime() - start) / 1e6);
        } catch (Throwable failure) {
            System.err.println("FAIL after checks=" + checks + ": " + failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static GullyJunctionConstraint build(Field field, Geometry geometry) {
        return GullyJunctionConstraint.build(field, geometry, AMP, 1);
    }

    /** A low parent on a smooth slope; the two nominal upstream lines are NOT existing low slots. */
    private static State slope(double s, double b) {
        double raw = 0.2 - 0.002 * s + 0.0004 * b * b
                + 0.007 * StrictMath.exp(-square((s + 5) / 1.2));
        double[] values = {-0.12 + 0.025 * StrictMath.sin(s / 11) * StrictMath.cos(b / 9),
                0.09 * StrictMath.cos(s / 9) * StrictMath.cos(b / 7),
                -0.06 * StrictMath.sin(s / 7) + 0.01 * StrictMath.cos(b / 8),
                0.04 * StrictMath.cos(s / 6) * StrictMath.cos(b / 6)};
        double[] caps = {0.3, 0.82 + 0.02 * StrictMath.cos(s / 9),
                0.63 + 0.01 * StrictMath.cos(b / 8), 0.47 + 0.01 * StrictMath.cos(s / 6)};
        return new State(raw, values, caps);
    }

    private static void checkValidation() {
        double[] values = {0.2, -0.3, 0.4, -0.1}, caps = {0.5, 0.6, 0.7, 0.8};
        State state = new State(0.5, values, caps);
        values[0] = 9;
        caps[1] = 0;
        state.values()[2] = 9;
        state.capacities()[3] = 0;
        same(state.values()[0], 0.2, "input value cloned");
        same(state.capacities()[1], 0.6, "input capacity cloned");
        same(state.values()[2], 0.4, "output values cloned");
        same(state.capacities()[3], 0.8, "output capacities cloned");
        invalid(() -> new State(0, new double[3], new double[4]));
        invalid(() -> new State(Double.NaN, new double[4], new double[4]));
        invalid(() -> new State(0, new double[]{0, 0.1, 0, 0}, new double[4]));
        invalid(() -> new State(0, new double[4], new double[]{0, -0.1, 0, 0}));
        invalid(() -> new State(0, new double[4], new double[]{0, 1.1, 0, 0}));
        invalid(() -> GullyJunctionConstraint.build(GullyJunctionCheck::slope, Y, 0.5, 1));
        invalid(() -> GullyJunctionConstraint.build(GullyJunctionCheck::slope, Y, AMP, 0));
        GullyJunctionConstraint missing = build(null, Y);
        require(missing.status() == Status.INVALID_PARENT, "missing parent field status");
        require(!missing.reason().isEmpty(), "missing parent reason");
        GullyJunctionConstraint bad = build(GullyJunctionCheck::slope,
                new Geometry(new Point(0, 0), 0, 0, 12, 8, 3, 2));
        require(bad.status() == Status.INVALID_GEOMETRY, "zero tangent status");
        same(bad.height(1, 2), bad.baseline(1, 2), "bad geometry remains B");
        require(build(GullyJunctionCheck::slope, new Geometry(new Point(0, 0), 1, 0,
                2, 8, 3, 2)).status() == Status.INVALID_GEOMETRY, "no split length rejected");
        require(build(GullyJunctionCheck::slope, new Geometry(new Point(0, 0), 1, 0,
                12, 8, 0, 2)).status() == Status.INVALID_GEOMETRY, "degenerate Y rejected");
        require(build(GullyJunctionCheck::slope, new Geometry(new Point(0, 0), 1, 0,
                1e9, 8, 3, 2)).status() == Status.INVALID_GEOMETRY, "unbounded precheck rejected");
        GullyJunctionConstraint c = accepted();
        invalid(() -> c.height(0, 0, -0.1));
        invalid(() -> c.height(0, 0, 1.1));
        invalid(() -> c.height(0, 0, Double.NaN));
        invalid(() -> c.layerValue(4, 0, 0));
        invalid(() -> c.layerValue(-1, 0, 0));
        invalid(() -> c.point(0, -1));
        invalid(() -> c.point(1, 1));
        invalid(() -> c.point(3, 0));
        invalid(() -> c.targetBed(-13));
        invalid(() -> c.height(Double.NaN, 0));
        double[] knots = c.profileHeights();
        knots[0] = 1e9;
        require(c.profileHeights()[0] != 1e9, "profile metadata cloned");
    }

    private static void checkGeometry() {
        GullyJunctionConstraint c = accepted();
        Geometry normalized = new Geometry(new Point(-17.5, -11.25), 3, 4, 12, 8, 3, 2);
        close(normalized.tx(), 0.6, 1e-16, "normalized tx");
        close(normalized.tz(), 0.8, 1e-16, "normalized tz");
        for (int branch = 0; branch < 3; branch++) {
            Point p = c.point(branch, 0);
            same(p.x(), Y.junction().x(), "shared q x");
            same(p.z(), Y.junction().z(), "shared q z");
        }
        for (double s : new double[]{-1.9, -1, -0.001, 0}) {
            Point a = c.point(1, s), b = c.point(2, s);
            same(a.x(), b.x(), "shared join chart x");
            same(a.z(), b.z(), "shared join chart b");
            same(a.x(), s, "straight tangent through join");
        }
        for (double h : new double[]{0.1, 0.01, 0.001}) {
            double far = c.point(1, -Y.width() - h).z();
            double near = c.point(1, -Y.width() - h / 10).z();
            require(far > 0 && near > 0 && near / far < 0.002, "C2 branch departure cubic scaling");
            double sourceFar = c.weight(-Y.upstream() + h, Y.spread());
            double sourceNear = c.weight(-Y.upstream() + h / 10, Y.spread());
            require(sourceNear > 0 && sourceNear / sourceFar < 0.002, "C2 source gate");
            double endFar = c.weight(Y.downstream() - h, 0);
            double endNear = c.weight(Y.downstream() - h / 10, 0);
            require(endNear > 0 && endNear / endFar < 0.002, "C2 downstream gate");
            double sideFar = c.weight(2, 2 * Y.width() - h);
            double sideNear = c.weight(2, 2 * Y.width() - h / 10);
            require(sideNear > 0 && sideNear / sideFar < 0.002, "C2 lateral gate");
        }
        same(c.weight(0, 0), 1, "q target is not diluted by background");
        same(c.weight(-12, 3), 0, "source gate zero");
        same(c.weight(8, 0), 0, "downstream gate zero");
        same(c.weight(2, 4), 0, "lateral gate zero");
        double previous = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 4000; i++) {
            double s = -12 + 20 * (i / 4000.0), h = c.targetBed(s);
            require(h <= previous + 2e-14, "common target profile monotone");
            previous = h;
        }
    }

    private static void checkSlope() {
        GullyJunctionConstraint c = accepted();
        int baselineCrossFailures = 0, finalCrossChecks = 0, baselineReversals = 0;
        double maxBedError = 0, minimumRise = Double.POSITIVE_INFINITY;
        for (int branch = 0; branch < 3; branch++) {
            double lo = branch == 0 ? 0 : -12, hi = branch == 0 ? 8 : 0;
            double last = Double.POSITIVE_INFINITY, lastB = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= 1600; i++) {
                double s = lo + (hi - lo) * (i / 1600.0);
                Point p = c.point(branch, s);
                double h = c.height(p.x(), p.z()), base = c.baseline(p.x(), p.z());
                require(h <= last + 3e-13, "final actual bed monotone incl fades branch=" + branch);
                if (base > lastB + 1e-9) baselineReversals++;
                last = h;
                lastB = base;
                if (c.weight(p.x(), p.z()) != 1) continue;
                maxBedError = StrictMath.max(maxBedError, StrictMath.abs(h - c.targetBed(s)));
                double a = StrictMath.abs(p.z());
                double delta = a > 0 ? StrictMath.min(0.5, a / 2) : 0.5;
                if (delta < 0.01) continue; // Dense tiny-split ties are checked by the build audit.
                double leftB = c.baseline(p.x(), p.z() - delta);
                double rightB = c.baseline(p.x(), p.z() + delta);
                if (!(leftB > base && rightB > base)) baselineCrossFailures++;
                double left = c.height(p.x(), p.z() - delta), right = c.height(p.x(), p.z() + delta);
                require(left > h && right > h, "both sides of actual slot must rise");
                minimumRise = StrictMath.min(minimumRise, StrictMath.min(left - h, right - h));
                finalCrossChecks++;
            }
        }
        require(baselineCrossFailures > 100, "geometry-only counterexample really fails transverse lows");
        require(baselineReversals > 0, "geometry-only actual baseline has an uphill obstruction");
        require(finalCrossChecks > 1000 && minimumRise > 0, "not just geometry/auxiliary lines");
        require(maxBedError < 2e-13, "final H actually reaches shared target bed");
        same(c.height(0, 0), c.baseline(0, 0), "bedAtQ exactly B");
        same(c.bedAtQ(), c.baseline(0, 0), "shared bedAtQ metadata");
        require(c.evidence().patchSamples() > 1000, "whole finite patch checked");
        require(c.evidence().routeSamples() > 500 && c.evidence().crossSections() > 100,
                "actual routes and sides audited");
        require(c.plateauIntervals() > 0 && c.reason().contains("plateau"), "plateaus disclosed");
        System.out.printf(Locale.ROOT, "  ACCEPTED baseline_cross_fail=%d baseline_reverse=%d "
                        + "actual_cross=%d min_side_rise=%.6g max_bed_error=%.3g plateaus=%d/%d%n",
                baselineCrossFailures, baselineReversals, finalCrossChecks, minimumRise, maxBedError,
                c.plateauIntervals(), c.profileHeights().length - 1);
        System.out.println("  evidence=" + c.evidence() + " step=" + c.precheckStep()
                + " transverseRise=" + c.transverseRise());
    }

    private static void checkFinalBedCounterexample() {
        GullyJunctionConstraint c = accepted();
        require(!GullyJunctionEvidence.passed(c),
                "chart-axis acceptance must not hide actual-normal/end-fade failures");
        System.out.println("  expected final-bed FAIL preserved: local ACCEPTED != full valley proof");
    }

    private static void checkBudgets() {
        GullyJunctionConstraint c = accepted();
        GullyJunctionConstraint scaled = GullyJunctionConstraint.build(GullyJunctionCheck::slope, Y, AMP, 128);
        require(scaled.status() == Status.ACCEPTED, "unchanged shell-scaled contract");
        int[] changed = new int[4];
        for (double[] p : points(1200)) {
            double x = p[0], z = p[1];
            State state = c.baselineState(x, z);
            double[] values = state.values(), caps = state.capacities();
            double base = formula(state, values, 1), loSum = 0, hiSum = 0, gain = 0.5;
            for (int j = 1; j < 4; j++) {
                loSum += gain * (-caps[j] - values[j]);
                hiSum += gain * (caps[j] - values[j]);
                gain *= 0.5;
            }
            Interval range = c.heightInterval(x, z);
            same(range.lower(), base + AMP / NORM * loSum, "full baseline-relative lower interval");
            same(range.upper(), base + AMP / NORM * hiSum, "full baseline-relative upper interval");
            double[] finalValues = new double[4];
            for (int j = 0; j < 4; j++) {
                finalValues[j] = c.layerValue(j, x, z);
                double target = c.targetLayerValue(j, x, z);
                require(StrictMath.abs(target) <= caps[j], "individual T bound");
                require(StrictMath.abs(finalValues[j]) <= caps[j], "individual E' bound");
                if (bits(finalValues[j]) != bits(values[j])) changed[j]++;
                if (j == 0) same(finalValues[j], values[j], "coarse layer exact");
                else if (c.weight(x, z) > 0) {
                    double targetH = c.targetHeight(x, z);
                    double end = targetH >= base ? range.upper() : range.lower();
                    double alpha = targetH == base ? 0 : (targetH - base) / (end - base);
                    double bound = targetH >= base ? caps[j] : -caps[j];
                    double expected = targetH == base ? values[j] : (1 - alpha) * values[j] + alpha * bound;
                    same(target, expected, "same convex interval fraction without rescale");
                    double w = c.weight(x, z);
                    same(finalValues[j], w == 1 ? target : (1 - w) * values[j] + w * target,
                            "E'=(1-w)B+wT");
                }
            }
            same(c.height(x, z), formula(state, finalValues, 1), "only four fixed weighted layers");
            close(scaled.height(x, z), 128 * c.height(x, z), 2e-11, "shell applied once");
            double w = 0.37 * c.weight(x, z);
            double[] partial = values.clone();
            for (int j = 1; j < 4; j++) partial[j] = w == 0 ? values[j]
                    : (1 - w) * values[j] + w * c.targetLayerValue(j, x, z);
            same(c.height(x, z, 0.37), formula(state, partial, 1), "connection weight only mixes layers");
        }
        require(changed[0] == 0, "no coarse changes");
        for (int j = 1; j < 4; j++) require(changed[j] > 0, "fine layer participates j=" + j);
        System.out.printf("  changed_by_layer=[%d,%d,%d,%d]; original capacities unchanged%n",
                changed[0], changed[1], changed[2], changed[3]);
    }

    private static void checkNegativeControls() {
        GullyJunctionConstraint c = accepted();
        for (double[] p : points(500)) {
            State state = slope(p[0], p[1]);
            double b = formula(state, state.values(), 1);
            same(c.height(p[0], p[1], 0.0), b, "+0 exact B");
            same(c.height(p[0], p[1], -0.0), b, "-0 exact B");
            same(c.layerValue(0, p[0], p[1]), state.values()[0], "coarse exact including negative coords");
            if (c.weight(p[0], p[1]) == 0) {
                same(c.height(p[0], p[1]), b, "outside exact B");
                for (int j = 0; j < 4; j++) {
                    same(c.layerValue(j, p[0], p[1]), state.values()[j], "outside exact layers");
                }
            }
        }
        for (double[] p : new double[][]{{-12, 3}, {8, 0}, {2, 4}, {-13, -3}, {9, 0}, {-100, -100}}) {
            same(c.weight(p[0], p[1]), 0, "closed support edge");
            same(c.height(p[0], p[1]), c.baseline(p[0], p[1]), "edge returns B bitwise");
        }
    }

    private static void checkRejections() {
        Field ridge = (s, b) -> shifted(s, b, 0.7 * StrictMath.exp(-square((s + 5) / 1.1)));
        Field mismatch = (s, b) -> shifted(s, b, -0.5 * quintic((-s - 1) / 5));
        Field offRoute = (s, b) -> shifted(s, b,
                0.5 * StrictMath.exp(-(square((s + 4) / 0.12) + square((b - 2.5) / 0.12))));
        rejectedUnchanged("high folded ridge", ridge, "recurrence");
        rejectedUnchanged("upstream endpoint height mismatch", mismatch, "recurrence");
        GullyJunctionConstraint rejected = rejectedUnchanged("off-route patch ridge", offRoute, "query REJECTED");
        require(rejected.evidence().patchSamples() > 0, "off-route fixed grid evidence");
        require(rejected.evidence().routeSamples() == 0, "off-route caught before line-only evidence");
        Field asymmetric = (s, b) -> shifted(s, b, 0.8 * StrictMath.tanh(b)
                * quintic((-s - 2) / 4));
        rejectedUnchanged("two child intervals do not intersect", asymmetric, "intersection=");
        Field noFine = (s, b) -> new State(0.2 - 0.002 * s, new double[4], new double[4]);
        rejectedUnchanged("zero fine capacity", noFine, "budget");
    }

    private static GullyJunctionConstraint rejectedUnchanged(String label, Field field, String reasonPart) {
        GullyJunctionConstraint c = build(field, Y);
        require(c.status() == Status.INFEASIBLE_HEIGHT_BUDGET, label + " must explicitly budget reject: " + c.reason());
        require(c.reason().contains(reasonPart), label + " reason evidence: " + c.reason());
        for (double[] p : points(150)) {
            State s = field.at(p[0], p[1]);
            same(c.height(p[0], p[1]), formula(s, s.values(), 1), label + " rejected B unchanged");
            same(c.weight(p[0], p[1]), 0, label + " rejected weight zero");
            for (int j = 0; j < 4; j++) {
                same(c.layerValue(j, p[0], p[1]), s.values()[j], label + " rejected layer unchanged");
            }
        }
        System.out.println("  REJECTED " + label + ": " + c.reason());
        return c;
    }

    private static void checkUnsampled() {
        double x = -3.137, z = 1.137;
        Field narrow = (s, b) -> shifted(s, b,
                0.5 * StrictMath.exp(-(square((s - x) / 1e-5) + square((b - z) / 1e-5))));
        GullyJunctionConstraint c = build(narrow, Y);
        require(c.status() == Status.ACCEPTED, "finite grid deliberately misses sub-grid spike: " + c.reason());
        boolean threw = false;
        try {
            c.height(x, z);
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("REJECTED")
                    && expected.getMessage().contains("no clipping/fallback"), "explicit query rejection");
            threw = true;
        }
        require(threw, "unsampled target must not clip or return baseline as a successful result");
        same(c.height(x, z, 0), c.baseline(x, z), "explicit zero still exact B at sub-grid violation");
        require(c.status() == Status.ACCEPTED, "query cannot mutate immutable local build evidence");
        System.out.println("  sub-grid spike: build locally ACCEPTED, exact query explicitly REJECTED; no global claim");
    }

    private static void checkDeterminism() {
        Geometry geometry = new Geometry(new Point(-17.5, -11.25), 3, 4, 12, 8, 3, 2);
        Field rotated = (x, z) -> {
            double dx = x - geometry.junction().x(), dz = z - geometry.junction().z();
            return slope(dx * geometry.tx() + dz * geometry.tz(),
                    -dx * geometry.tz() + dz * geometry.tx());
        };
        GullyJunctionConstraint a = build(rotated, geometry), b = build(rotated, geometry);
        require(a.status() == Status.ACCEPTED, "rotated negative-world patch accepted: " + a.reason());
        require(b.status() == a.status() && b.reason().equals(a.reason()), "rebuild status/reason");
        require(a.evidence().equals(b.evidence()), "rebuild finite evidence");
        double[][] points = points(400);
        double[][] expected = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            double s = points[i][0], n = points[i][1];
            double x = geometry.junction().x() + s * geometry.tx() - n * geometry.tz();
            double z = geometry.junction().z() + s * geometry.tz() + n * geometry.tx();
            points[i] = new double[]{x, z};
            expected[i] = snapshot(a, x, z);
        }
        for (int i = points.length - 1; i >= 0; i--) {
            sameSnapshot(snapshot(a, points[i][0], points[i][1]), expected[i], "reverse queries");
            sameSnapshot(snapshot(b, points[i][0], points[i][1]), expected[i], "rebuild reverse queries");
        }
        // No shared test counter writes inside parallel workers; assertions still propagate to main.
        IntStream.range(0, points.length).parallel().forEach(i -> {
            double[] actual = snapshot(b, points[i][0], points[i][1]);
            for (int j = 0; j < actual.length; j++) {
                if (bits(actual[j]) != bits(expected[i][j])) throw new AssertionError("parallel mismatch");
            }
        });
        checks++;
        System.out.println("  immutable core has no query-result cache; rotated negative-world samples="
                + points.length + " reverse/rebuild/parallel=bitwise");
    }

    private static double[] snapshot(GullyJunctionConstraint c, double x, double z) {
        return new double[]{c.baseline(x, z), c.height(x, z), c.weight(x, z),
                c.layerValue(0, x, z), c.layerValue(1, x, z), c.layerValue(2, x, z), c.layerValue(3, x, z)};
    }

    private static void sameSnapshot(double[] a, double[] b, String label) {
        for (int i = 0; i < a.length; i++) same(a[i], b[i], label);
    }

    private static GullyJunctionConstraint accepted() {
        GullyJunctionConstraint c = build(GullyJunctionCheck::slope, Y);
        require(c.status() == Status.ACCEPTED, "smooth-slope C0 unexpectedly rejected: " + c.reason());
        return c;
    }

    private static State shifted(double s, double b, double delta) {
        State original = slope(s, b);
        return new State(original.raw() + delta, original.values(), original.capacities());
    }

    private static double formula(State state, double[] values, double shell) {
        double sum = 0, gain = 1;
        for (int j = 0; j < 4; j++) { sum += gain * values[j]; gain *= 0.5; }
        double unit = sum / NORM;
        return (state.raw() + AMP * unit) * shell;
    }

    private static double[][] points(int count) {
        SplittableRandom random = new SplittableRandom(0x71d2ab35L);
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = random.nextDouble(-15, 11);
            points[i][1] = random.nextDouble(-8, 8);
        }
        return points;
    }

    private static double quintic(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * x * (10 + x * (-15 + 6 * x));
    }

    private static double square(double x) { return x * x; }
    private static long bits(double x) { return Double.doubleToRawLongBits(x); }
    private static void same(double a, double b, String label) { require(bits(a) == bits(b), label + ": " + a + " != " + b); }
    private static void close(double a, double b, double tolerance, String label) { require(StrictMath.abs(a - b) <= tolerance, label); }

    private static void invalid(Runnable action) {
        boolean threw = false;
        try { action.run(); } catch (IllegalArgumentException | NullPointerException expected) { threw = true; }
        require(threw, "invalid argument accepted");
    }

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void run(String label, Runnable action) {
        long start = System.nanoTime();
        action.run();
        System.out.printf(Locale.ROOT, "PASS %s (%.3f ms)%n", label, (System.nanoTime() - start) / 1e6);
    }
}
