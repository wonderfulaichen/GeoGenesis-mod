package com.geogenesis.standalone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S0/S1-only full-field tracing and bounded subtractive cuts; no Minecraft dependencies.
 * All descent evidence is finite sampling, never a proof for arbitrary high frequencies.
 * No smoothing, constant target bed, outlet carving, seed discovery or path merging.
 */
public final class NoiseFollowingErosion {
    private NoiseFollowingErosion() {}

    @FunctionalInterface
    public interface HeightField {
        double height(double x, double z);
    }

    public record Point(double x, double z) {
        public Point {
            finite(x, "x");
            finite(z, "z");
        }
    }

    public record Settings(double radius, double depth, double maxStep, double minStep,
                           double maxLength, double gradientStep) {
        public Settings {
            positive(radius, "radius");
            finite(depth, "depth");
            if (depth < 0) throw new IllegalArgumentException("Negative depth");
            positive(maxStep, "maxStep");
            positive(minStep, "minStep");
            positive(maxLength, "maxLength");
            positive(gradientStep, "gradientStep");
            if (minStep > maxStep) throw new IllegalArgumentException("minStep > maxStep");
            if (Math.ceil(maxLength / minStep) > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too many finite-resolution steps");
            }
        }

        public Settings() {
            this(8, .02 / 1.5, 1, .0625, 160, .25);
        }

        public static Settings defaults() {
            return new Settings();
        }
    }

    /** Immutable geometry; status describes tracing, not acceptance of the final eroded bed. */
    public record Trace(String id, List<Point> points, List<Double> arc,
                        String status, double length) {
        public Trace {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            points = List.copyOf(points);
            arc = List.copyOf(arc);
            finite(length, "length");
            if (points.isEmpty() || points.size() != arc.size() || length < 0) {
                throw new IllegalArgumentException("Invalid trace sizes/length");
            }
            if (arc.get(0) != 0.0 || arc.get(arc.size() - 1) != length) {
                throw new IllegalArgumentException("Arc must start at zero and end at length");
            }
            for (int i = 1; i < points.size(); i++) {
                double increment = arc.get(i) - arc.get(i - 1);
                double geometric = separation(points.get(i - 1), points.get(i));
                finite(arc.get(i), "arc");
                if (!(increment > 0) || Math.abs(increment - geometric)
                        > 1e-9 * Math.max(1, geometric)) {
                    throw new IllegalArgumentException("Arc is not cumulative geometric length");
                }
            }
        }
    }

    /** Fixed caller-supplied seed; caller owns discovery/order. Never walks on a cut field. */
    public static Trace trace(HeightField field, String id, double x, double z, Settings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(settings, "settings");
        List<Point> points = new ArrayList<>();
        List<Double> arc = new ArrayList<>();
        Point current = new Point(x, z);
        points.add(current);
        arc.add(0.0);
        double length = 0;
        int maxSteps = (int) Math.ceil(settings.maxLength() / settings.minStep());
        for (int count = 0; count < maxSteps; count++) {
            double remaining = settings.maxLength() - length;
            if (remaining <= 1e-12 * settings.maxLength()) {
                return finish(id, points, arc, "TRUNCATED", length);
            }
            Point next = advance(field, current, Math.min(settings.maxStep(), remaining), settings);
            if (next == null) {
                return finish(id, points, arc, localStatus(field, current, settings), length);
            }
            double travelled = separation(current, next);
            if (!(travelled > 0)) {
                return finish(id, points, arc, "NO_DESCENT_AT_RESOLUTION", length);
            }
            length += travelled;
            points.add(next);
            arc.add(length);
            current = next;
        }
        return finish(id, points, arc, "TRUNCATED", length);
    }

    private static Trace finish(String id, List<Point> points, List<Double> arc,
                                String status, double length) {
        return new Trace(id, points, arc, status, length);
    }

    private static Point advance(HeightField field, Point p, double initial, Settings settings) {
        Point downhill = downhill(field, p, settings.gradientStep());
        double step = initial;
        int refinement = 0;
        while (true) {
            int samples = 16 + 8 * Math.min(refinement, 16);
            Point corrected = heun(field, p, downhill, step, settings.gradientStep());
            if (corrected != null && descends(field, p, corrected, samples)) return corrected;
            Point candidate = directionalSearch(field, p, downhill, step, samples);
            if (candidate != null) return candidate;
            if (step <= settings.minStep()) return null;
            step = Math.max(settings.minStep(), step * .5);
            refinement++;
        }
    }

    /** Heun on the normalized negative gradient (arc-length parameterization). */
    private static Point heun(HeightField field, Point p, Point d, double step, double delta) {
        if (d == null) return null;
        Point predictor = move(p, d, step);
        Point endDirection = downhill(field, predictor, delta);
        if (endDirection == null) return predictor;
        Point corrected = unit(d.x() + endDirection.x(), d.z() + endDirection.z());
        return corrected == null ? null : move(p, corrected, step);
    }

    /** Deterministic angular probes are accepted ONLY after actual full-H descent checks. */
    private static Point directionalSearch(HeightField field, Point p, Point d,
                                           double step, int samples) {
        double base = d == null ? 0 : Math.atan2(d.z(), d.x());
        for (int i = 0; i < 32; i++) {
            int offset = i == 0 ? 0 : (i % 2 == 1 ? (i + 1) / 2 : -i / 2);
            double angle = base + offset * (Math.PI / 16);
            Point q = new Point(p.x() + step * Math.cos(angle), p.z() + step * Math.sin(angle));
            if (descends(field, p, q, samples)) return q;
        }
        return null;
    }

    private static boolean descends(HeightField field, Point a, Point b, int samples) {
        double first = sample(field, a.x(), a.z());
        double previous = first;
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double h = sample(field, a.x() + t * (b.x() - a.x()), a.z() + t * (b.z() - a.z()));
            if (h > previous) return false;
            previous = h;
        }
        return previous < first;
    }

    /** FLAT means equal finite local probes, NOT a proof of an open flat neighborhood. */
    private static String localStatus(HeightField field, Point p, Settings settings) {
        double center = sample(field, p.x(), p.z());
        double[] radii = {settings.minStep() / 4, settings.minStep(), settings.gradientStep()};
        for (double radius : radii) {
            for (int i = 0; i < 32; i++) {
                double angle = i * Math.PI / 16;
                double h = sample(field, p.x() + radius * Math.cos(angle),
                        p.z() + radius * Math.sin(angle));
                if (h != center) return "NO_DESCENT_AT_RESOLUTION";
            }
        }
        return "FLAT";
    }

    private static Point downhill(HeightField field, Point p, double delta) {
        double dx = (sample(field, p.x() - delta, p.z())
                - sample(field, p.x() + delta, p.z())) / (2 * delta);
        double dz = (sample(field, p.x(), p.z() - delta)
                - sample(field, p.x(), p.z() + delta)) / (2 * delta);
        return unit(dx, dz);
    }

    private static Point unit(double x, double z) {
        double magnitude = Math.hypot(x, z);
        if (!Double.isFinite(magnitude)) throw new IllegalArgumentException("Nonfinite gradient");
        return magnitude == 0 ? null : new Point(x / magnitude, z / magnitude);
    }

    private static Point move(Point p, Point direction, double step) {
        return new Point(p.x() + step * direction.x(), p.z() + step * direction.z());
    }

    public static Point pointAt(Trace trace, double s) {
        finite(s, "s");
        if (s <= 0 || trace.length() == 0) return trace.points().get(0);
        if (s >= trace.length()) return trace.points().get(trace.points().size() - 1);
        int low = 0;
        int high = trace.arc().size() - 1;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (trace.arc().get(middle) <= s) low = middle;
            else high = middle;
        }
        double t = (s - trace.arc().get(low)) / (trace.arc().get(high) - trace.arc().get(low));
        Point a = trace.points().get(low);
        Point b = trace.points().get(high);
        return new Point(a.x() + t * (b.x() - a.x()), a.z() + t * (b.z() - a.z()));
    }

    /** Squared point-to-segment distance, including zero-length segments. */
    public static double distance2(Point p, Point a, Point b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double square = dx * dx + dz * dz;
        double t = square == 0 ? 0 : ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / square;
        t = Math.max(0, Math.min(1, t));
        double ex = p.x() - (a.x() + t * dx);
        double ez = p.z() - (a.z() + t * dz);
        return ex * ex + ez * ez;
    }

    public static double distance2(double x, double z, double ax, double az, double bx, double bz) {
        return distance2(new Point(x, z), new Point(ax, az), new Point(bx, bz));
    }

    private static double separation(Point a, Point b) {
        return Math.hypot(b.x() - a.x(), b.z() - a.z());
    }

    /**
     * Continuous bounded max-envelope, not nearest-segment-dependent depth.
     * For each trace: depth * K(distance to ENTIRE polyline) * spatial endpoint fades.
     * Endpoint fades use Euclidean distance, not nearest-point arc: on a straight end they
     * match arc fades; on a U-turn they can suppress later passages near the same endpoint.
     * K/fades are C2 as scalar profiles, but min-distance/max aggregation is only C0 in
     * general. No claim of C1 across medial axes, or of monotone final beds at fade tails.
     */
    public static final class Cut {
        private final HeightField field;
        private final Settings settings;
        private final List<Support> supports;

        public Cut(HeightField field, List<Trace> traces, Settings settings) {
            this.field = Objects.requireNonNull(field, "field");
            this.settings = Objects.requireNonNull(settings, "settings");
            List<Support> bounds = new ArrayList<>();
            for (Trace trace : List.copyOf(traces)) bounds.add(bounds(trace, settings.radius()));
            supports = List.copyOf(bounds);
        }

        public double height(double x, double z) {
            return height(x, z, 1);
        }

        /** Nonnegative multipliers saturate at the approved absolute depth budget. */
        public double height(double x, double z, double depthMultiplier) {
            finite(depthMultiplier, "depthMultiplier");
            if (depthMultiplier < 0) throw new IllegalArgumentException("Negative multiplier");
            double baseline = sample(field, x, z);
            if (depthMultiplier == 0) return baseline;
            double d = displacement(x, z);
            if (d == 0) return baseline; // Also preserve signed zero bit-for-bit.
            d = Math.min(settings.depth(), d * depthMultiplier);
            return d == 0 ? baseline : baseline - d;
        }

        /** Actual nonnegative D; overlapping traces use max, never a sum. */
        public double displacement(double x, double z) {
            Point p = new Point(x, z);
            if (settings.depth() == 0) return 0;
            double weight = 0;
            double radius2 = settings.radius() * settings.radius();
            for (Support support : supports) {
                Trace trace = support.trace();
                if (trace.length() == 0 || !support.contains(p)) continue;
                double nearest2 = Double.POSITIVE_INFINITY;
                for (int i = 1; i < trace.points().size(); i++) {
                    nearest2 = Math.min(nearest2, distance2(p, trace.points().get(i - 1),
                            trace.points().get(i)));
                }
                if (nearest2 >= radius2) continue;
                double u = Math.max(0, 1 - nearest2 / radius2);
                weight = Math.max(weight, u * u * u * endpointWeight(trace, p));
            }
            return settings.depth() * Math.min(1, weight);
        }

        /** Actual spatial end weight evaluated at the point of geometric arc s. */
        public double pathWeight(Trace trace, double s) {
            return trace.length() == 0 ? 0 : endpointWeight(trace, pointAt(trace, s));
        }

        public static Point pointAt(Trace trace, double s) {
            return NoiseFollowingErosion.pointAt(trace, s);
        }

        private double endpointWeight(Trace trace, Point p) {
            Point start = trace.points().get(0);
            Point end = trace.points().get(trace.points().size() - 1);
            return smooth5(separation(p, start) / settings.radius())
                    * smooth5(separation(p, end) / settings.radius());
        }

        private static Support bounds(Trace trace, double radius) {
            double minX = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (Point p : trace.points()) {
                minX = Math.min(minX, p.x());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxZ = Math.max(maxZ, p.z());
            }
            finite(minX - radius, "expanded minX");
            finite(minZ - radius, "expanded minZ");
            finite(maxX + radius, "expanded maxX");
            finite(maxZ + radius, "expanded maxZ");
            return new Support(trace, minX - radius, minZ - radius, maxX + radius, maxZ + radius);
        }
    }

    private record Support(Trace trace, double minX, double minZ, double maxX, double maxZ) {
        private boolean contains(Point p) {
            return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
        }
    }

    private static double smooth5(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return Math.max(0, Math.min(1, t * t * t * (10 + t * (-15 + 6 * t))));
    }

    private static double sample(HeightField field, double x, double z) {
        finite(x, "x");
        finite(z, "z");
        double value = field.height(x, z);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite full height");
        return value;
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite " + name);
    }

    private static void positive(double value, String name) {
        finite(value, name);
        if (!(value > 0)) throw new IllegalArgumentException("Nonpositive " + name);
    }
}
