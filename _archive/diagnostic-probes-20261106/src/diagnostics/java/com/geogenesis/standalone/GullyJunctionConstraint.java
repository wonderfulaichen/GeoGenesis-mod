package com.geogenesis.standalone;

import java.util.Arrays;
import java.util.Objects;

/**
 * C0 only: one explicit, immutable Y in a fixed (t,n) chart; no parent discovery or MC dependency.
 * The caller supplies B values and A's actual per-layer sum(B*m*c)/D capacities, NOT a product of
 * averaged activity and confidence. The raw field, shell, layer zero and four-layer normalization
 * are frozen. Only layers 1..3 are replaced, inside their original individual bounds.
 *
 * <p>ACCEPTED means finite local checks passed, NOT a global proof or evidence that the baseline
 * receiver is a real valley. The real probe must supply that evidence. Profiles use C2 quintic
 * interpolation and may contain plateaus. Source/downstream end fades deliberately return to B.
 * An unsampled budget violation throws; queries never clip, re-fit, deepen or silently fall back.
 * The supplied field must be immutable, deterministic and thread-safe for this object's lifetime.</p>
 */
public class GullyJunctionConstraint {
    public record State(double raw, double[] values, double[] capacities) {
        public State {
            values = Objects.requireNonNull(values, "values").clone();
            capacities = Objects.requireNonNull(capacities, "capacities").clone();
            finite(raw, "raw");
            if (values.length != 4 || capacities.length != 4) {
                throw new IllegalArgumentException("exactly four B layers and capacities required");
            }
            for (int j = 0; j < 4; j++) {
                finite(values[j], "value");
                finite(capacities[j], "capacity");
                if (capacities[j] < 0 || capacities[j] > 1
                        || StrictMath.abs(values[j]) > capacities[j]) {
                    throw new IllegalArgumentException("invalid true capacity/value at layer " + j);
                }
            }
        }

        @Override public double[] values() { return values.clone(); }
        @Override public double[] capacities() { return capacities.clone(); }
    }

    @FunctionalInterface
    public interface Field { State at(double x, double z); }
    public record Point(double x, double z) {}

    /** Invalid inputs remain representable so build can report INVALID_GEOMETRY without modifying B. */
    public record Geometry(Point junction, double tx, double tz, double upstream,
                           double downstream, double spread, double width) {
        public Geometry {
            double length = StrictMath.hypot(tx, tz);
            if (Double.isFinite(length) && length > 0) {
                tx /= length;
                tz /= length;
            }
        }
    }

    public enum Status { ACCEPTED, INFEASIBLE_HEIGHT_BUDGET, INVALID_PARENT, INVALID_GEOMETRY }
    public record Interval(double lower, double upper) {}
    public record Evidence(long patchSamples, long routeSamples, long crossSections,
                           double minimumSideRise) {}

    private record Profile(double[] stations, double[] heights) {}
    private record Chart(double s, double b) {}
    private static final double AMPLITUDE = 0.25;
    private static final double NORMALIZATION = 1.875;
    private static final long MAX_PATCH_POINTS = 1_000_000;
    private static final Profile EMPTY = new Profile(new double[0], new double[0]);
    private static final Evidence NONE = new Evidence(0, 0, 0, Double.NaN);

    private final Field field;
    private final Geometry geometry;
    private final double amplitude;
    private final double shell;
    private final Status status;
    private final String reason;
    private final Profile profile;
    private final double bedAtQ;
    private final double transverseRise;
    private final Evidence evidence;

    private GullyJunctionConstraint(Field field, Geometry geometry, double amplitude, double shell,
                                     Status status, String reason, Profile profile, double bedAtQ,
                                     double transverseRise, Evidence evidence) {
        this.field = field;
        this.geometry = geometry;
        this.amplitude = amplitude;
        this.shell = shell;
        this.status = status;
        this.reason = reason;
        this.profile = profile;
        this.bedAtQ = bedAtQ;
        this.transverseRise = transverseRise;
        this.evidence = evidence;
    }

    /** Fixed amplitude is intentional: this experiment is not an amplitude/depth search. */
    public static GullyJunctionConstraint build(Field field, Geometry geometry,
                                                 double amplitude, double shell) {
        if (amplitude != AMPLITUDE) throw new IllegalArgumentException("C0 freezes amplitude=0.25");
        finite(shell, "shell");
        if (!(shell > 0)) throw new IllegalArgumentException("shell must be positive");
        GullyJunctionConstraint c = new GullyJunctionConstraint(field, geometry, amplitude, shell,
                Status.ACCEPTED, "pending local precheck", EMPTY, Double.NaN, Double.NaN, NONE);
        String invalid = invalidGeometry(geometry);
        if (invalid != null) return c.finish(Status.INVALID_GEOMETRY, invalid, NONE);
        if (field == null) return c.finish(Status.INVALID_PARENT, "missing baseline B field", NONE);
        Audit audit = new Audit();
        try {
            Point q = geometry.junction;
            State state = c.baselineState(q.x, q.z);
            double bed = c.compose(state, state.values);
            Profile beds = c.makeProfile(bed);
            // A single budget-derived choice, never scanned: at most radius/4 at a tube's rim.
            double rise = c.fineRadius(state) / 16.0;
            if (!(rise > 0) || !Double.isFinite(rise)) {
                throw budget("no resolvable fine-layer transverse budget at q");
            }
            c = new GullyJunctionConstraint(field, geometry, amplitude, shell, Status.ACCEPTED,
                    "pending local precheck", beds, bed, rise, NONE);
            c.precheckPatch(audit);
            c.precheckRoutes(audit);
            return c.finish(Status.ACCEPTED, "finite local budget/bed/side checks only; "
                    + "piecewise-quintic profile may plateau; baseline receiver evidence is caller-owned; "
                    + "end fades return to B; no global or unsampled guarantee", audit.snapshot());
        } catch (ConstraintFailure failure) {
            return c.finish(failure.status, failure.getMessage(), audit.snapshot());
        } catch (IllegalArgumentException | NullPointerException failure) {
            return c.finish(Status.INVALID_PARENT, "invalid baseline field: " + failure.getMessage(),
                    audit.snapshot());
        }
    }

    /** A missing/certification-failed receiver is explicit, while B remains available for evidence. */
    public static GullyJunctionConstraint missingParent(Field field, Geometry geometry,
                                                       double amplitude, double shell, String reason) {
        if (amplitude != AMPLITUDE || !(shell > 0) || !Double.isFinite(shell)) {
            throw new IllegalArgumentException("invalid frozen scale");
        }
        return new GullyJunctionConstraint(Objects.requireNonNull(field), geometry, amplitude, shell,
                Status.INVALID_PARENT, Objects.requireNonNull(reason), EMPTY, Double.NaN, Double.NaN, NONE);
    }

    public State baselineState(double x, double z) {
        finite(x, "x");
        finite(z, "z");
        return Objects.requireNonNull(field.at(x, z), "field returned null State");
    }

    public double baseline(double x, double z) {
        State s = baselineState(x, z);
        return compose(s, s.values);
    }

    public double height(double x, double z) { return height(x, z, 1.0); }

    /** Both signed zeros, rejection and outside support take the exact B arithmetic path. */
    public double height(double x, double z, double connectionWeight) {
        finite(connectionWeight, "connectionWeight");
        if (connectionWeight < 0 || connectionWeight > 1) {
            throw new IllegalArgumentException("connectionWeight must be in [0,1]");
        }
        State s = baselineState(x, z);
        double w = connectionWeight == 0 ? 0 : connectionWeight * weight(x, z);
        if (w == 0) return compose(s, s.values);
        double[] target = targetValues(s, targetAt(chart(x, z)), x, z);
        double[] values = s.values.clone();
        for (int j = 1; j < 4; j++) values[j] = mixLayer(s, target[j], j, w, x, z);
        return compose(s, values);
    }

    public double layerValue(int j, double x, double z) {
        checkLayer(j);
        State s = baselineState(x, z);
        double w = j == 0 ? 0 : weight(x, z);
        if (w == 0) return s.values[j];
        double[] target = targetValues(s, targetAt(chart(x, z)), x, z);
        return mixLayer(s, target[j], j, w, x, z);
    }

    /** Unmixed target metadata; outside the patch or after rejection this is the original B value. */
    public double targetLayerValue(int j, double x, double z) {
        checkLayer(j);
        State s = baselineState(x, z);
        if (j == 0 || weight(x, z) == 0) return s.values[j];
        return targetValues(s, targetAt(chart(x, z)), x, z)[j];
    }

    /** Full fine-layer interval with raw and E0 frozen, NOT a net activity/confidence proxy. */
    public Interval heightInterval(double x, double z) { return interval(baselineState(x, z)); }

    public double targetHeight(double x, double z) {
        if (weight(x, z) == 0) return baseline(x, z);
        State s = baselineState(x, z);
        double target = targetAt(chart(x, z));
        targetValues(s, target, x, z); // Metadata queries must not hide an infeasible target either.
        return target;
    }

    public double weight(double x, double z) {
        finite(x, "x");
        finite(z, "z");
        if (status != Status.ACCEPTED) return 0.0;
        Chart p = chart(x, z);
        return gate(p.s, p.b);
    }

    public Geometry geometry() { return geometry; }
    public Status status() { return status; }
    public String reason() { return reason; }
    public double amplitude() { return amplitude; }
    public double shell() { return shell; }
    public double bedAtQ() { return bedAtQ; }
    public double transverseRise() { return transverseRise; }
    public double precheckStep() { return geometry == null ? Double.NaN : geometry.width / 8.0; }
    public Evidence evidence() { return evidence; }
    public double[] profileStations() { return profile.stations.clone(); }
    public double[] profileHeights() { return profile.heights.clone(); }

    public int plateauIntervals() {
        int count = 0;
        for (int i = 1; i < profile.heights.length; i++) {
            if (profile.heights[i] == profile.heights[i - 1]) count++;
        }
        return count;
    }

    /** Branch 0 is the unique parent suffix; branches 1/2 share its q and last width of chart. */
    public Point point(int branch, double s) {
        finite(s, "s");
        if (geometry == null || invalidGeometry(geometry) != null) {
            throw new IllegalStateException("point unavailable: invalid geometry");
        }
        if (branch < 0 || branch > 2 || (branch == 0 && (s < 0 || s > geometry.downstream))
                || (branch != 0 && (s < -geometry.upstream || s > 0))) {
            throw new IllegalArgumentException("branch/s outside fixed Y domain");
        }
        double b = branch == 0 ? 0.0 : (branch == 1 ? offset(s) : -offset(s));
        return world(s, b);
    }

    /** Common bed of all three paths, in final shell-scaled height units. */
    public double targetBed(double s) {
        finite(s, "s");
        if (status != Status.ACCEPTED) throw new IllegalStateException("rejected: " + reason);
        if (s < -geometry.upstream || s > geometry.downstream) {
            throw new IllegalArgumentException("s outside profile");
        }
        int i = Arrays.binarySearch(profile.stations, s);
        if (i >= 0) return profile.heights[i];
        int upper = -i - 1, lower = upper - 1;
        double u = (s - profile.stations[lower])
                / (profile.stations[upper] - profile.stations[lower]);
        double blend = smooth5(u);
        return (1 - blend) * profile.heights[lower] + blend * profile.heights[upper];
    }

    private Profile makeProfile(double bed) {
        int up = segments(geometry.upstream, precheckStep());
        int down = segments(geometry.downstream, precheckStep());
        double[] stations = new double[up + down + 1], heights = new double[stations.length];
        stations[up] = 0;
        heights[up] = bed;
        double next = bed;
        for (int k = 1; k <= up; k++) {
            double s = -geometry.upstream * (k / (double) up);
            Point a = point(1, s), b = point(2, s);
            Interval ia = heightInterval(a.x, a.z), ib = heightInterval(b.x, b.z);
            double lower = StrictMath.max(ia.lower, ib.lower);
            double upper = StrictMath.min(ia.upper, ib.upper);
            double h = StrictMath.max(lower, next);
            if (h > upper) throw budget("child interval recurrence rejected at s=" + s
                    + " intersection=[" + lower + "," + upper + "] required=" + h);
            stations[up - k] = s;
            heights[up - k] = h;
            next = h;
        }
        double previous = bed;
        for (int k = 1; k <= down; k++) {
            double s = geometry.downstream * (k / (double) down);
            Point p = point(0, s);
            Interval range = heightInterval(p.x, p.z);
            double h = StrictMath.min(range.upper, previous);
            if (h < range.lower) throw budget("parent interval recurrence rejected at s=" + s
                    + " interval=" + range + " required=" + h);
            stations[up + k] = s;
            heights[up + k] = h;
            previous = h;
        }
        return new Profile(stations, heights);
    }

    private Interval interval(State s) {
        double lower = 0, upper = 0, gain = 0.5;
        for (int j = 1; j < 4; j++) {
            lower += gain * (-s.capacities[j] - s.values[j]);
            upper += gain * (s.capacities[j] - s.values[j]);
            gain *= 0.5;
        }
        double base = compose(s, s.values), scale = shell * amplitude / NORMALIZATION;
        double lo = base + scale * lower, hi = base + scale * upper;
        finite(lo, "lower interval");
        finite(hi, "upper interval");
        return new Interval(lo, hi);
    }

    /** Move from B toward the appropriate whole-interval endpoint, with one convex fraction. */
    private double[] targetValues(State s, double target, double x, double z) {
        Interval range = interval(s);
        if (!Double.isFinite(target) || target < range.lower || target > range.upper) {
            throw budget("height-budget query REJECTED (no clipping/fallback) at (" + x + "," + z
                    + "): target=" + target + " full fine interval=" + range);
        }
        double[] result = s.values.clone();
        double base = compose(s, s.values);
        if (target == base) return result; // In particular q is exactly B, not two independent digs.
        boolean raising = target > base;
        double endpoint = raising ? range.upper : range.lower;
        double alpha = (target - base) / (endpoint - base);
        if (!Double.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw budget("unresolvable fine-layer convex fraction at (" + x + "," + z + ")");
        }
        for (int j = 1; j < 4; j++) {
            double bound = raising ? s.capacities[j] : -s.capacities[j];
            result[j] = (1 - alpha) * s.values[j] + alpha * bound;
            checkBound(result[j], s.capacities[j], j, x, z);
        }
        return result;
    }

    private double mixLayer(State s, double target, int j, double w, double x, double z) {
        double value = w == 1 ? target : (1 - w) * s.values[j] + w * target;
        checkBound(value, s.capacities[j], j, x, z);
        return value;
    }

    private static void checkBound(double value, double cap, int j, double x, double z) {
        if (!Double.isFinite(value) || StrictMath.abs(value) > cap) {
            throw budget("layer-budget query REJECTED at (" + x + "," + z + ") layer=" + j
                    + " value=" + value + " capacity=" + cap + "; no clipping/fallback");
        }
    }

    private double compose(State s, double[] values) {
        double sum = 0.0, gain = 1.0;
        for (int j = 0; j < 4; j++) {
            sum += gain * values[j];
            gain *= 0.5;
        }
        // Keep B's division -> amplitude -> raw addition -> shell order, including zero gates.
        double unit = sum / NORMALIZATION;
        double height = (s.raw + amplitude * unit) * shell;
        finite(height, "composed height");
        return height;
    }

    private double fineRadius(State s) {
        double sum = 0, gain = 0.5;
        for (int j = 1; j < 4; j++) {
            sum += gain * s.capacities[j];
            gain *= 0.5;
        }
        return shell * amplitude / NORMALIZATION * sum;
    }

    private Chart chart(double x, double z) {
        double dx = x - geometry.junction.x, dz = z - geometry.junction.z;
        return new Chart(dx * geometry.tx + dz * geometry.tz,
                -dx * geometry.tz + dz * geometry.tx);
    }

    private Point world(double s, double b) {
        Point q = geometry.junction;
        if (s == 0 && b == 0) return q;
        if (b == 0) return new Point(q.x + s * geometry.tx, q.z + s * geometry.tz);
        return new Point(q.x + s * geometry.tx - b * geometry.tz,
                q.z + s * geometry.tz + b * geometry.tx);
    }

    private double offset(double s) {
        return geometry.spread * smooth5((-s - geometry.width)
                / (geometry.upstream - geometry.width));
    }

    private double gate(double s, double b) {
        if (!Double.isFinite(s) || !Double.isFinite(b)
                || s <= -geometry.upstream || s >= geometry.downstream) return 0;
        double along = smooth5((s + geometry.upstream) / geometry.width)
                * smooth5((geometry.downstream - s) / geometry.width);
        double a = offset(s);
        double first = lateral((b - a) / geometry.width);
        double second = lateral((b + a) / geometry.width);
        // Smooth union of two explicit charts, never a nearest-tube ownership switch.
        return along * (1 - (1 - first) * (1 - second));
    }

    private static double lateral(double b) { return smooth5(2.0 - StrictMath.abs(b)); }

    private double targetAt(Chart p) {
        double b = p.b / geometry.width, a = offset(p.s) / geometry.width;
        double left = (b - a) * (b - a), right = (b + a) * (b + a);
        double doubleSlot = left * right / (left + right + 1.0 / 256.0);
        // a is already exactly zero in [-width,0]; this blend therefore preserves both beds.
        double blend = smooth5((p.s + geometry.width) / geometry.width);
        double d2 = (1 - blend) * doubleSlot + blend * b * b;
        return targetBed(p.s) + transverseRise * d2;
    }

    /** Entire finite chart bbox at <=width/8, not just the three centre lines. */
    private void precheckPatch(Audit audit) {
        double span = geometry.upstream + geometry.downstream;
        double radius = geometry.spread + 2 * geometry.width;
        int ns = segments(span, precheckStep());
        int nb = segments(2 * radius, precheckStep());
        if ((nb & 1) != 0) nb++;
        for (int i = 0; i <= ns; i++) {
            double s = -geometry.upstream + span * (i / (double) ns);
            for (int k = 0; k <= nb; k++) {
                double b = -radius + 2 * radius * (k / (double) nb);
                double w = gate(s, b);
                if (w == 0) continue;
                Point p = world(s, b);
                State state = baselineState(p.x, p.z);
                double[] target = targetValues(state, targetAt(new Chart(s, b)), p.x, p.z);
                for (int j = 1; j < 4; j++) mixLayer(state, target[j], j, w, p.x, p.z);
                audit.patch++;
            }
        }
    }

    /** Sample actual final H, including end fades; cross sections use the fixed chart normal. */
    private void precheckRoutes(Audit audit) {
        for (int branch = 0; branch < 3; branch++) {
            double length = branch == 0 ? geometry.downstream : geometry.upstream;
            int count = 4 * segments(length, precheckStep());
            double previous = Double.POSITIVE_INFINITY;
            for (int i = 0; i <= count; i++) {
                double s = branch == 0 ? length * (i / (double) count)
                        : -length + length * (i / (double) count);
                Point p = point(branch, s);
                double h = height(p.x, p.z);
                if (i > 0 && h > previous + roundoff(h, previous)) {
                    throw budget("sampled final-bed reversal (fixed profile/fades), branch=" + branch
                            + " s=" + s + " previous=" + previous + " height=" + h);
                }
                previous = h;
                audit.route++;
                if (weight(p.x, p.z) == 1) precheckSides(branch, s, h, audit);
            }
        }
    }

    private void precheckSides(int branch, double s, double center, Audit audit) {
        double a = branch == 0 ? 0 : offset(s);
        double b = branch == 2 ? -a : a;
        double delta = a > 0 ? StrictMath.min(geometry.width / 4, a / 2) : geometry.width / 4;
        for (int sign : new int[]{-1, 1}) {
            Point side = world(s, b + sign * delta);
            double h = height(side.x, side.z), tolerance = roundoff(h, center);
            double expectedRise = targetAt(new Chart(s, b + sign * delta)) - targetBed(s);
            if (h < center - tolerance || (expectedRise > 8 * tolerance && !(h > center))) {
                throw budget("sampled final transverse low-point failed, branch=" + branch + " s=" + s);
            }
            audit.minimumRise = StrictMath.min(audit.minimumRise, h - center);
        }
        audit.cross++;
    }

    private static String invalidGeometry(Geometry g) {
        if (g == null || g.junction == null) return "missing geometry/junction";
        double[] inputs = {g.junction.x, g.junction.z, g.tx, g.tz, g.upstream,
                g.downstream, g.spread, g.width};
        for (double value : inputs) if (!Double.isFinite(value)) return "nonfinite geometry";
        if (!(g.width > 0) || !(g.upstream > g.width) || !(g.downstream > g.width)
                || !(g.spread > 0) || StrictMath.abs(StrictMath.hypot(g.tx, g.tz) - 1) > 1e-12) {
            return "require normalized tangent, upstream/downstream > width > 0, spread > 0";
        }
        double step = g.width / 8, span = g.upstream + g.downstream;
        double diameter = 2 * (g.spread + 2 * g.width);
        double ns = StrictMath.ceil(span / step), nb = StrictMath.ceil(diameter / step) + 1;
        if (!(step > 0) || !Double.isFinite(ns * nb) || (ns + 1) * (nb + 1) > MAX_PATCH_POINTS) {
            return "finite C0 patch exceeds fixed precheck limit or coordinate resolution";
        }
        double reach = span + diameter;
        if (!Double.isFinite(reach) || !Double.isFinite(StrictMath.abs(g.junction.x) + reach)
                || !Double.isFinite(StrictMath.abs(g.junction.z) + reach)
                || step <= 32 * StrictMath.ulp(StrictMath.max(StrictMath.abs(g.junction.x),
                        StrictMath.abs(g.junction.z)))) return "unresolvable chart coordinates";
        return null;
    }

    private GullyJunctionConstraint finish(Status result, String message, Evidence checked) {
        return new GullyJunctionConstraint(field, geometry, amplitude, shell, result, message,
                profile, bedAtQ, transverseRise, checked);
    }

    private static int segments(double length, double step) {
        return (int) StrictMath.max(1, StrictMath.ceil(length / step));
    }

    private static double smooth5(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * x * (10 + x * (-15 + 6 * x));
    }

    private static double roundoff(double a, double b) {
        return 128 * StrictMath.ulp(StrictMath.max(1, StrictMath.max(StrictMath.abs(a), StrictMath.abs(b))));
    }

    private static void checkLayer(int j) {
        if (j < 0 || j > 3) throw new IllegalArgumentException("layer must be 0..3");
    }

    private static void finite(double x, String name) {
        if (!Double.isFinite(x)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static ConstraintFailure budget(String message) {
        return new ConstraintFailure(Status.INFEASIBLE_HEIGHT_BUDGET, message);
    }

    private static final class ConstraintFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final Status status;
        ConstraintFailure(Status status, String message) { super(message); this.status = status; }
    }

    private static final class Audit {
        long patch, route, cross;
        double minimumRise = Double.POSITIVE_INFINITY;
        Evidence snapshot() {
            return new Evidence(patch, route, cross, cross == 0 ? Double.NaN : minimumRise);
        }
    }
}
