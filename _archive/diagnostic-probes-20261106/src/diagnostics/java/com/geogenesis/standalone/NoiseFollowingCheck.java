package com.geogenesis.standalone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;

/** Standalone synthetic contracts, not acceptance of real terrain or of complete drainage. */
public final class NoiseFollowingCheck {
    private static final Settings SETTINGS = Settings.defaults();
    private static int assertions;
    private static int finalBedFailedTraces;

    private NoiseFollowingCheck() {}

    public static void main(String[] args) {
        try {
            tracingChecks();
            ridgeChecks();
            cutChecks();
            continuityChecks();
            stateChecks();
            tailChecks();
            System.out.printf(Locale.ROOT,
                    "CONTRACT PASS assertions=%d; EFFECT_REPORT finalH_failed_traces=%d "
                            + "(includes required negative control; not overall effect PASS)%n",
                    assertions, finalBedFailedTraces);
            System.out.println("LIMIT: finite full-H samples cannot prove monotonicity between samples, "
                    + "nor certify a true sink. FLAT is finite equal probes only; "
                    + "NO_DESCENT_AT_RESOLUTION is not an outlet proof; TRUNCATED is not validend.");
            System.out.println("LIMIT: Euclidean endpoint fades are continuous but can suppress U-return "
                    + "passages; distance/max joins need not be C1. Short paths remain diagnostic. "
                    + "Real-field finer final-bed validation belongs to the main probe.");
        } catch (RuntimeException | AssertionError failure) {
            System.err.println("CONTRACT FAIL: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void tracingChecks() {
        HeightField slope = (x, z) -> -.01 * x + .002 * z;
        Trace a = checkedTrace("slope", slope, 0, 0, SETTINGS);
        require(a.status().equals("TRUNCATED"), "Slope must reach computation cutoff");
        require(Math.abs(a.length() - 160) < 1e-8, "Slope length cutoff");
        require(a.equals(NoiseFollowingErosion.trace(slope, "slope", 0, 0, SETTINGS)), "Determinism");
        require(a.equals(NoiseFollowingErosion.trace(slope, "slope", 0, 0, SETTINGS)), "Cold repeat");
        HeightField curved = (x, z) -> .002 * square(z - 2 * Math.sin(x / 12)) - .005 * x;
        Trace valley = checkedTrace("curvedvalley", curved, 0, 4, SETTINGS);
        Settings half = new Settings(8, .02 / 1.5, .5, .0625, 160, .25);
        Trace refined = checkedTrace("curvedvalley-half", curved, 0, 4, half);
        double maximum = geometricDifference(valley, refined);
        System.out.printf(Locale.ROOT, "STABILITY curvedvalley maxArcMatchedDistance=%.9g "
                + "(geometry only; no effect-based selection)%n", maximum);
        require(maximum < 1, "Synthetic curved-valley shrink stability");
        HeightField saddle = (x, z) -> .001 * (z * z - x * x);
        Trace saddleTrace = checkedTrace("saddle", saddle, 0, 0, SETTINGS);
        require(saddleTrace.length() > 0, "Zero-gradient saddle needs measured descending direction");
        HeightField bowl = (x, z) -> .001 * (x * x + z * z);
        Trace stopped = checkedTrace("bowl", bowl, 2, 1, SETTINGS);
        require(stopped.status().equals("NO_DESCENT_AT_RESOLUTION"), "Bowl finite stop");
        Trace flat = checkedTrace("flat", (x, z) -> 7, 0, 0, SETTINGS);
        require(flat.status().equals("FLAT") && flat.length() == 0, "No arbitrary flat walk");
        Trace shortTrace = NoiseFollowingErosion.trace(slope, "short", 0, 0,
                new Settings(8, .02 / 1.5, 1, .0625, .03, .25));
        require(shortTrace.status().equals("TRUNCATED"), "Sub-minStep remainder is cutoff");
        require(shortTrace.length() <= .03 + 1e-12, "Short trace remains bounded");
        System.out.println("SHORT diagnostic length=" + shortTrace.length()
                + " status=" + shortTrace.status() + " (not a successful full channel)");
    }

    private static void ridgeChecks() {
        HeightField ridge = (x, z) -> -.001 * x + .01 * z * z
                + .03 * Math.exp(-square((x - 3) / .2));
        require(ridge.height(6, 0) < ridge.height(0, 0), "Ridge negative-control lower far endpoint");
        require(ridge.height(3, 0) > ridge.height(0, 0), "Ridge interior exceeds source");
        Trace trace = checkedTrace("ridge-barrier", ridge, 0, 0, SETTINGS);
        require(trace.points().stream().allMatch(p -> p.x() < 3), "No crossing sampled barrier");
        require(trace.status().equals("NO_DESCENT_AT_RESOLUTION"), "Barrier finite stop");
        HeightField narrow = (x, z) -> -.01 * x + .1 * z * z
                + .04 * Math.exp(-square((x - .5) / .04));
        Trace reduced = checkedTrace("shrink-ridge", narrow, 0, 0, SETTINGS);
        require(reduced.points().size() > 1, "Shrink finds pre-barrier descent");
        require(reduced.arc().get(1) < SETTINGS.maxStep() - 1e-10, "Rejected segment forces shrink");
        require(reduced.points().stream().allMatch(p -> p.x() < .5), "Shrink must not cross ridge");
        HeightField folded = (x, z) -> .05 * Math.abs(z) - .005 * x;
        Trace crease = checkedTrace("crease-valley", folded, 0, 0, SETTINGS);
        require(crease.length() > 159, "Actual downhill travel along unsmoothed crease");
        // An adversarial ridge BETWEEN the first step's 16 validation positions is missed.
        HeightField aliased = (x, z) -> -.01 * x + .05 * Math.exp(-square((x - .03125) / .001));
        Trace missed = NoiseFollowingErosion.trace(aliased, "finite-sampling-negative", 0, 0,
                new Settings(8, .02 / 1.5, 1, .0625, 1, .25));
        int violations = ascendingSamples(aliased, missed, .00047);
        require(violations > 0, "Finer negative control must expose hidden high-frequency ridge");
        System.out.println("EXPECTED FINITE-SAMPLING FAILURE narrow-between-samples rises=" + violations
                + "; sampled tracer is NOT a global monotonicity proof");
    }

    private static void cutChecks() {
        HeightField field = (x, z) -> -.01 * x;
        Trace trace = line("cut-line", new Point(0, 0), new Point(40, 0));
        Cut cut = new Cut(field, List.of(trace), SETTINGS);
        Cut duplicate = new Cut(field, List.of(trace, trace), SETTINGS);
        Cut zero = new Cut(field, List.of(trace), new Settings(8, 0, 1, .0625, 160, .25));
        Cut empty = new Cut(field, List.of(), SETTINGS);
        for (int ix = -20; ix <= 100; ix++) {
            for (int iz = -20; iz <= 20; iz++) {
                double x = ix * .5;
                double z = iz * .5;
                double original = field.height(x, z);
                double d = cut.displacement(x, z);
                double h = cut.height(x, z);
                require(d >= 0 && d <= SETTINGS.depth(), "Bounded nonnegative D");
                require(h <= original, "Subtractive only");
                require(original - h <= SETTINGS.depth() + 4 * Math.ulp(original), "Actual H budget");
                require(bits(h, original - d), "Actual final H is H0-D");
                require(bits(h, duplicate.height(x, z)), "Max overlap not additive");
                require(bits(zero.height(x, z), original), "Zero settings bit identity");
                require(bits(cut.height(x, z, 0), original), "Zero multiplier bit identity");
                require(bits(empty.height(x, z), original), "Empty cut bit identity");
                require(cut.height(x, z, 100) >= original - SETTINGS.depth(), "Multiplier cap");
                if (Math.abs(z) >= 8 || x <= -8 || x >= 48) {
                    require(d == 0 && bits(h, original), "Outside support EXACT baseline");
                }
            }
        }
        require(bits(cut.height(0, 0), field.height(0, 0)), "Source exact baseline signed zero");
        require(cut.pathWeight(trace, 0) == 0 && cut.pathWeight(trace, 40) == 0, "Endpoint zeros");
        require(cut.pathWeight(trace, 20) == 1, "Interior full depth weight");
        require(Math.abs(cut.pathWeight(trace, 4) - .5) < 1e-14, "Smooth5 source fade");
        require(Math.abs(cut.pathWeight(trace, 36) - .5) < 1e-14, "Smooth5 downstream fade");
        require(Math.abs(cut.displacement(20, 4) / SETTINGS.depth() - Math.pow(.75, 3)) < 1e-14,
                "Compact cubic kernel");
        require(NoiseFollowingErosion.distance2(new Point(2, 3), new Point(0, 0),
                new Point(4, 0)) == 9, "Public point-segment distance2");
        System.out.println("CUT zero/outside exact, budget, max-overlap, endpoint/kernel contracts PASS");
    }

    private static void continuityChecks() {
        Trace elbow = polyline("elbow", List.of(new Point(0, 0), new Point(20, 0), new Point(20, 20)));
        Cut cut = new Cut((x, z) -> -.01 * (x + z), List.of(elbow), SETTINGS);
        double epsilon = 1e-7;
        require(Math.abs(cut.displacement(20 - epsilon, epsilon)
                - cut.displacement(20 + epsilon, -epsilon)) < 1e-8, "Shared vertex continuity");
        Trace hairpin = polyline("U-return", List.of(new Point(0, 0), new Point(30, 0),
                new Point(30, 4), new Point(0, 4)));
        Cut uCut = new Cut((x, z) -> -.001 * x, List.of(hairpin), SETTINGS);
        require(Math.abs(uCut.displacement(10, 2 - epsilon)
                - uCut.displacement(10, 2 + epsilon)) < 1e-8, "Equidistant nonadjacent segment continuity");
        require(uCut.pathWeight(hairpin, 60) < 1, "U-return endpoint fade limitation remains visible");
        require(Math.abs(cut.displacement(10, 8 - epsilon)
                - cut.displacement(10, 8 + epsilon)) < 1e-12, "Compact boundary continuity");
        Trace second = line("cross", new Point(20, -20), new Point(20, 20));
        Cut forward = new Cut((x, z) -> -.01 * x, List.of(elbow, second), SETTINGS);
        Cut reverse = new Cut((x, z) -> -.01 * x, List.of(second, elbow), SETTINGS);
        for (int i = -10; i <= 30; i++) {
            require(bits(forward.height(i, i * .3), reverse.height(i, i * .3)), "Cut order independence");
        }
        System.out.println("CONTINUITY shared vertex, equidistant segments, compact boundary PASS (C0 only)");
    }

    private static void stateChecks() {
        List<Point> points = new ArrayList<>(List.of(new Point(0, 0), new Point(2, 0)));
        List<Double> arc = new ArrayList<>(List.of(0.0, 2.0));
        Trace trace = new Trace("immutable", points, arc, "TRUNCATED", 2);
        points.clear();
        arc.clear();
        require(trace.points().size() == 2 && trace.arc().size() == 2, "Defensive copies");
        boolean immutable = false;
        try {
            trace.points().add(new Point(3, 0));
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        require(immutable, "Immutable trace points");
        immutable = false;
        try {
            trace.arc().set(0, 2.0);
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        require(immutable, "Immutable trace arc");
        require(Cut.pointAt(trace, 1).equals(new Point(1, 0)), "Accessible arc interpolation");
        Trace zero = new Trace("zero", List.of(new Point(0, 0)), List.of(0.0), "FLAT", 0);
        Cut zeroCut = new Cut((x, z) -> 1, List.of(zero), SETTINGS);
        require(zeroCut.displacement(0, 0) == 0, "Zero-length cut is zero");
        require(zero.status().equals("FLAT") && zero.length() == 0, "Zero length diagnostic retained");
        require(zeroCut.pathWeight(zero, 0) == 0, "Zero-length weight zero");
    }

    private static void tailChecks() {
        Trace trace = line("tail-control", new Point(0, 0), new Point(40, 0));
        HeightField steep = (x, z) -> -.01 * x;
        HeightField shallow = (x, z) -> -.00001 * x;
        int good = finalBedReport("steep-tail", steep, trace);
        int bad = finalBedReport("failtail-shallow", shallow, trace);
        require(good == 0, "Steep final bed should be nonincreasing in finite validation");
        require(bad > 0, "MUST detect shallow-tail final-H adverse slope");
        System.out.println("EXPECTED EFFECT FAILURE failtail-shallow detected; CONTRACT PASS "
                + "does not mean every channel has a valid end");
    }

    private static int finalBedReport(String id, HeightField original, Trace trace) {
        Cut cut = new Cut(original, List.of(trace), SETTINGS);
        int rises = ascendingSamples(cut::height, trace, .0073);
        if (rises > 0) finalBedFailedTraces++;
        System.out.printf(Locale.ROOT, "FINAL_H %s status=%s length=%.9g rises=%d effect=%s%n",
                id, trace.status(), trace.length(), rises, rises == 0 ? "SAMPLED_NONINCREASING" : "FAIL");
        return rises;
    }

    private static Trace checkedTrace(String id, HeightField field, double x, double z, Settings settings) {
        Trace trace = NoiseFollowingErosion.trace(field, id, x, z, settings);
        require(trace.length() <= settings.maxLength() + 1e-8, id + " max length");
        require(trace.points().size() <= Math.ceil(settings.maxLength() / settings.minStep()) + 1,
                id + " maxSteps");
        int rises = ascendingSamples(field, trace, .0073);
        require(rises == 0, id + " finer original-H rises=" + rises);
        System.out.printf(Locale.ROOT, "TRACE %s status=%s length=%.9g points=%d finerH0Rises=%d%n",
                id, trace.status(), trace.length(), trace.points().size(), rises);
        finalBedReport(id, field, trace);
        return trace;
    }

    /** Uniform fine arc grid plus every vertex; deliberately not the tracer's sample grid. */
    private static int ascendingSamples(HeightField field, Trace trace, double spacing) {
        double previous = field.height(trace.points().get(0).x(), trace.points().get(0).z());
        int violations = 0;
        double grid = spacing * .37;
        int vertex = 1;
        while (grid < trace.length() || vertex < trace.arc().size()) {
            double s = vertex < trace.arc().size() ? trace.arc().get(vertex) : Double.POSITIVE_INFINITY;
            if (grid < trace.length() && grid < s) {
                s = grid;
                grid += spacing;
            } else {
                vertex++;
            }
            Point p = NoiseFollowingErosion.pointAt(trace, s);
            double h = field.height(p.x(), p.z());
            if (h > previous + 1e-12) violations++;
            previous = h;
        }
        return violations;
    }

    private static double geometricDifference(Trace a, Trace b) {
        double maximum = 0;
        for (double s = 0; s <= Math.min(a.length(), b.length()); s += .37) {
            Point p = NoiseFollowingErosion.pointAt(a, s);
            Point q = NoiseFollowingErosion.pointAt(b, s);
            maximum = Math.max(maximum, Math.hypot(p.x() - q.x(), p.z() - q.z()));
        }
        return maximum;
    }

    private static Trace line(String id, Point start, Point end) {
        return polyline(id, List.of(start, end));
    }

    private static Trace polyline(String id, List<Point> points) {
        List<Double> arc = new ArrayList<>();
        arc.add(0.0);
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            length += Math.hypot(b.x() - a.x(), b.z() - a.z());
            arc.add(length);
        }
        return new Trace(id, points, arc, "TRUNCATED", length);
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static double square(double x) {
        return x * x;
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
