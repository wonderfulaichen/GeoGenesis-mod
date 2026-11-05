package com.geogenesis.standalone;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;

/**
 * Stage A only: fixed, jittered centres and independent scale layers, with no coarse/fine feedback.
 * Reads the supplied, fully processed height field; never smooths or replaces that carrier.
 * This is bounded procedural shaping, not mass-conserving erosion or a drainage network.
 *
 * <p>The field must be deterministic, immutable for this instance's lifetime and safe for concurrent
 * reads (prepare may build rows in parallel). Only immutable centre descriptors are cached. Changing
 * the input requires a new instance, not just clearCache. All sums have a fixed row-major order.</p>
 *
 * <p>A fixed 5x5 stencil can alias high-frequency input. Neither continuous displacement nor these
 * local descriptors prove natural-looking, connected valleys or repair the carrier's own creases.</p>
 */
public final class AdaptiveSkeletonErosion {
    @FunctionalInterface
    public interface HeightField {
        double height(double x, double z);
    }

    /** One diagnostic layer or the four independent layers; lengths and units are caller-owned. */
    public record Settings(double[] wavelengths, double[] onset, double[] epsilon,
                           double chi, double verticalUnit) {
        public Settings {
            wavelengths = Objects.requireNonNull(wavelengths, "wavelengths").clone();
            onset = Objects.requireNonNull(onset, "onset").clone();
            epsilon = Objects.requireNonNull(epsilon, "epsilon").clone();
            int n = wavelengths.length;
            if ((n != 1 && n != 4) || onset.length != n || epsilon.length != n) {
                throw new IllegalArgumentException("matching arrays of length 1 or 4 required");
            }
            for (int j = 0; j < n; j++) {
                positive(wavelengths[j], "wavelength");
                positive(wavelengths[j] * 0.25, "template delta");
                positive(onset[j], "onset");
                positive(epsilon[j], "epsilon");
            }
            finite(chi, "chi");
            if (chi < 0.0) throw new IllegalArgumentException("chi must be nonnegative");
            positive(verticalUnit, "verticalUnit");
        }

        @Override
        public double[] wavelengths() {
            return wavelengths.clone();
        }

        @Override
        public double[] onset() {
            return onset.clone();
        }

        @Override
        public double[] epsilon() {
            return epsilon.clone();
        }
    }

    /** Activity/confidence use the same geometric weights as value; denominatorMin is min_j D_j. */
    public record Sample(double value, double activity, double confidence, double denominatorMin) {}

    public record Tensor(double xx, double xz, double zz) {}

    // Nearest centre: u <= 9/32, so B >= (23/32)^3 exp(-9/8); never clamp D to epsilon.
    static final double DENOMINATOR_LOWER_BOUND = 0.1205458548;
    // Beyond this, double cannot resolve adjacent cell centres and their half-spacing stencils.
    private static final double CELL_LIMIT = 0x1.0p52 - 4.0;
    private static final double TWO_PI = 2.0 * StrictMath.PI;
    private static final long X_SALT = 0x9e3779b97f4a7c15L;
    private static final long Z_SALT = 0xd1b54a32d192ed03L;
    private static final long SIGN_SALT = 0x94d049bb133111ebL;

    private record Key(int layer, long x, long z) {}

    private record Descriptor(double offsetX, double offsetZ, double axisX, double axisZ,
                              double activity, double confidence, double sign) {}

    private final long seed;
    private final HeightField field;
    private final Settings settings;
    private final ConcurrentHashMap<Key, Descriptor> cache = new ConcurrentHashMap<>();

    public AdaptiveSkeletonErosion(long seed, HeightField field, Settings settings) {
        this.seed = seed;
        this.field = Objects.requireNonNull(field, "field");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Gain 0.5, normalized across the configured layers, without inter-layer dependencies. */
    public Sample sample(double x, double z) {
        finite(x, "x");
        finite(z, "z");
        double value = 0.0, activity = 0.0, confidence = 0.0, weight = 0.0;
        double gain = 1.0, denominatorMin = Double.POSITIVE_INFINITY;
        for (int j = 0; j < settings.wavelengths.length; j++) {
            Sample s = sampleLayer(j, x, z);
            value += gain * s.value;
            activity += gain * s.activity;
            confidence += gain * s.confidence;
            denominatorMin = StrictMath.min(denominatorMin, s.denominatorMin);
            weight += gain;
            gain *= 0.5;
        }
        return new Sample(bounded(value / weight, -1.0, 1.0),
                bounded(activity / weight, 0.0, 1.0),
                bounded(confidence / weight, 0.0, 1.0), denominatorMin);
    }

    /** All 5x5 candidate cells, with descriptor construction only inside actual kernel support. */
    public Sample sampleLayer(int layer, double x, double z) {
        if (layer < 0 || layer >= settings.wavelengths.length) {
            throw new IllegalArgumentException("layer out of range: " + layer);
        }
        double lambda = settings.wavelengths[layer];
        double spacing = lambda * 0.5;
        long cellX = cell(x, spacing), cellZ = cell(z, spacing);
        // FMA removes the large common origin before adding small, local centre offsets.
        double localX = StrictMath.fma(-(double) cellX, spacing, x) / lambda;
        double localZ = StrictMath.fma(-(double) cellZ, spacing, z) / lambda;
        double sum = 0.0, activity = 0.0, confidence = 0.0, denominator = 0.0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Key key = new Key(layer, cellX + dx, cellZ + dz);
                Descriptor desc = cache.get(key);
                long hash = centreHash(key);
                double ox = desc == null ? offset(hash) : desc.offsetX;
                double oz = desc == null ? offset(mix(hash + Z_SALT)) : desc.offsetZ;
                double rx = localX - (dx + ox) * 0.5;
                double rz = localZ - (dz + oz) * 0.5;
                double u = rx * rx + rz * rz;
                if (u >= 1.0) continue;
                double b = kernel(u);
                if (desc == null) desc = descriptor(key, hash, ox, oz);
                denominator += b;
                activity += b * desc.activity;
                confidence += b * desc.confidence;
                if (desc.confidence == 0.0) continue;
                // r^T P_perp r = (r dot minorAxis)^2: no atan2 or subtractive cancellation.
                double projected = StrictMath.abs(rx * desc.axisX + rz * desc.axisZ);
                double carrier = StrictMath.cos(TWO_PI * projected);
                sum += b * desc.activity * desc.confidence * desc.sign * carrier;
            }
        }
        if (!Double.isFinite(denominator) || denominator < DENOMINATOR_LOWER_BOUND) {
            throw new IllegalStateException("incomplete geometric support: D=" + denominator);
        }
        return new Sample(bounded(sum / denominator, -1.0, 1.0),
                bounded(activity / denominator, 0.0, 1.0),
                bounded(confidence / denominator, 0.0, 1.0), denominator);
    }

    /**
     * 5x5 heights span +/-lambda/2; delta=lambda/4. At each inner 3x3 site the dimensionless
     * central slope is 2*(y_next-y_prev), y=H/verticalUnit. Average the nine outer products.
     * Unrepresentable coordinates, normalized heights or tensor energies are rejected explicitly.
     */
    public static Tensor analyze(HeightField field, double cx, double cz,
                                 double lambda, double verticalUnit) {
        Objects.requireNonNull(field, "field");
        positive(lambda, "lambda");
        positive(verticalUnit, "verticalUnit");
        double delta = lambda * 0.25;
        positive(delta, "template delta");
        double[] xs = stencilAxis(cx, delta), zs = stencilAxis(cz, delta);
        double[] y = new double[25];
        for (int iz = 0; iz < 5; iz++) {
            for (int ix = 0; ix < 5; ix++) {
                double h = field.height(xs[ix], zs[iz]);
                finite(h, "field height");
                y[iz * 5 + ix] = h / verticalUnit;
                finite(y[iz * 5 + ix], "normalized height");
            }
        }
        double xx = 0.0, xz = 0.0, zz = 0.0;
        for (int iz = 1; iz <= 3; iz++) {
            for (int ix = 1; ix <= 3; ix++) {
                int k = iz * 5 + ix;
                double gx = 2.0 * (y[k + 1] - y[k - 1]);
                double gz = 2.0 * (y[k + 5] - y[k - 5]);
                xx += (gx / 3.0) * (gx / 3.0);
                xz += (gx / 3.0) * (gz / 3.0);
                zz += (gz / 3.0) * (gz / 3.0);
            }
        }
        finite(xx, "tensor xx");
        finite(xz, "tensor xz");
        finite(zz, "tensor zz");
        finite(xx + zz, "tensor trace");
        return new Tensor(xx, xz, zz);
    }

    /** Carrier-independent envelope of the existing kernel coefficients, not mean(m)*mean(c). */
    public double layerCapacity(int layer, double x, double z) {
        if (layer < 0 || layer >= settings.wavelengths.length) {
            throw new IllegalArgumentException("layer out of range: " + layer);
        }
        double lambda = settings.wavelengths[layer], spacing = lambda * .5;
        long cellX = cell(x, spacing), cellZ = cell(z, spacing);
        double localX = StrictMath.fma(-(double) cellX, spacing, x) / lambda;
        double localZ = StrictMath.fma(-(double) cellZ, spacing, z) / lambda;
        double envelope = 0, denominator = 0;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            Key key = new Key(layer, cellX + dx, cellZ + dz);
            Descriptor desc = cache.get(key);
            long hash = centreHash(key);
            double ox = desc == null ? offset(hash) : desc.offsetX;
            double oz = desc == null ? offset(mix(hash + Z_SALT)) : desc.offsetZ;
            double rx = localX - (dx + ox) * .5, rz = localZ - (dz + oz) * .5;
            double u = rx * rx + rz * rz;
            if (u >= 1) continue;
            if (desc == null) desc = descriptor(key, hash, ox, oz);
            double b = kernel(u);
            denominator += b;
            envelope += b * desc.activity * desc.confidence;
        }
        if (!Double.isFinite(denominator) || denominator < DENOMINATOR_LOWER_BOUND) {
            throw new IllegalStateException("incomplete geometric support: D=" + denominator);
        }
        return bounded(envelope / denominator, 0, 1);
    }

    public long descriptorCount() {
        return cache.mappingCount();
    }

    /** Does not change seed/settings/input identity, and is not a barrier against concurrent queries. */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Builds only descriptors whose open support intersects the closed bbox, layer by layer.
     * No neighbour tiles or dependencies are recursively built. Rows may run in parallel, but each
     * descriptor's stencil and every subsequent query are summed in a fixed order. Storage is O(N).
     */
    public void prepare(double minX, double minZ, double maxX, double maxZ) {
        for (int layer = 0; layer < settings.wavelengths.length; layer++) {
            prepareLayer(layer, minX, minZ, maxX, maxZ);
        }
    }

    /** Builds support for just this original layer number, without preparing any other layer. */
    public void prepareLayer(int layer, double minX, double minZ, double maxX, double maxZ) {
        if (layer < 0 || layer >= settings.wavelengths.length) {
            throw new IllegalArgumentException("layer out of range: " + layer);
        }
        finite(minX, "minX");
        finite(minZ, "minZ");
        finite(maxX, "maxX");
        finite(maxZ, "maxZ");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("reversed bbox");
        double lambda = settings.wavelengths[layer], spacing = lambda * 0.5;
        long x0 = cell(minX, spacing) - 2, x1 = cell(maxX, spacing) + 2;
        long z0 = cell(minZ, spacing) - 2, z1 = cell(maxZ, spacing) + 2;
        double rows = (double) z1 - z0 + 1.0, columns = (double) x1 - x0 + 1.0;
        if (rows * columns > Long.MAX_VALUE) {
            throw new IllegalArgumentException("bbox has unrepresentable descriptor count");
        }
        final int j = layer;
        LongStream rowStream = LongStream.rangeClosed(z0, z1);
        if (rows >= 8.0 && rows * columns >= 4096.0) rowStream = rowStream.parallel();
        rowStream.forEach(z -> prepareRow(j, z, x0, x1, minX, minZ, maxX, maxZ));
    }

    private void prepareRow(int layer, long z, long x0, long x1,
                            double minX, double minZ, double maxX, double maxZ) {
        double lambda = settings.wavelengths[layer], spacing = lambda * 0.5;
        // Work relative to this row/cell, not a rounded absolute world-space centre.
        double loZ = StrictMath.fma(-(double) z, spacing, minZ) / lambda;
        double hiZ = StrictMath.fma(-(double) z, spacing, maxZ) / lambda;
        for (long x = x0; x <= x1; x++) {
            Key key = new Key(layer, x, z);
            if (cache.containsKey(key)) continue;
            long hash = centreHash(key);
            double ox = offset(hash), oz = offset(mix(hash + Z_SALT));
            double loX = StrictMath.fma(-(double) x, spacing, minX) / lambda;
            double hiX = StrictMath.fma(-(double) x, spacing, maxX) / lambda;
            double rx = distanceToInterval(ox * 0.5, loX, hiX);
            double rz = distanceToInterval(oz * 0.5, loZ, hiZ);
            if (rx * rx + rz * rz < 1.0) descriptor(key, hash, ox, oz);
        }
    }

    private Descriptor descriptor(Key key, long hash, double ox, double oz) {
        return cache.computeIfAbsent(key, ignored -> buildDescriptor(key, hash, ox, oz));
    }

    private Descriptor buildDescriptor(Key key, long hash, double ox, double oz) {
        double lambda = settings.wavelengths[key.layer], spacing = lambda * 0.5;
        double cx = StrictMath.fma((double) key.x, spacing, ox * spacing);
        double cz = StrictMath.fma((double) key.z, spacing, oz * spacing);
        Tensor j = analyze(field, cx, cz, lambda, settings.verticalUnit);
        double t = j.xx + j.zz, a = j.xx - j.zz, b = 2.0 * j.xz;
        double d = StrictMath.hypot(a, b);
        finite(d, "tensor eigenvalue gap");
        double activity = ratio(t, settings.onset[key.layer]);
        double sign = (mix(hash + SIGN_SALT) & 1L) == 0L ? -1.0 : 1.0;
        if (d == 0.0) return new Descriptor(ox, oz, 0.0, 0.0, activity, 0.0, sign);
        double confidence = confidence(d, t, settings.epsilon[key.layer], settings.chi);
        double na = a / d, nb = b / d;
        double axisX = na >= 0.0 ? -nb : 1.0 - na;
        double axisZ = na >= 0.0 ? 1.0 + na : -nb;
        double norm = StrictMath.hypot(axisX, axisZ);
        return new Descriptor(ox, oz, axisX / norm, axisZ / norm, activity, confidence, sign);
    }

    /** Algebraically d^4/(d^4+(epsilon+chi*t)^4), without overflowing fourth powers. */
    private static double confidence(double d, double t, double epsilon, double chi) {
        double scale = StrictMath.max(d, StrictMath.max(t, epsilon));
        double a = d / scale, b = epsilon / scale + chi * (t / scale);
        if (Double.isInfinite(b)) return 0.0;
        if (a >= b) return 1.0 / (1.0 + fourth(b / a));
        double q = fourth(a / b);
        return q / (1.0 + q);
    }

    private static double ratio(double a, double b) {
        if (a >= b) return 1.0 / (1.0 + b / a);
        double r = a / b;
        return r / (1.0 + r);
    }

    private static double fourth(double v) {
        double square = v * v;
        return square * square;
    }

    // Package-visible for the support/C2 check; u is squared radius in wavelength units.
    static double kernel(double u) {
        if (u >= 1.0) return 0.0;
        double v = 1.0 - u;
        return v * v * v * StrictMath.exp(-4.0 * u);
    }

    private static double[] stencilAxis(double centre, double delta) {
        finite(centre, "template centre");
        double[] axis = new double[5];
        for (int i = 0; i < 5; i++) {
            axis[i] = StrictMath.fma(i - 2.0, delta, centre);
            finite(axis[i], "template coordinate");
            if (i > 0 && !(axis[i] > axis[i - 1])) {
                throw new IllegalArgumentException("template delta below coordinate resolution");
            }
        }
        return axis;
    }

    private static long cell(double coordinate, double spacing) {
        finite(coordinate, "query coordinate");
        double c = StrictMath.floor(coordinate / spacing);
        if (!Double.isFinite(c) || c < -CELL_LIMIT || c > CELL_LIMIT) {
            throw new IllegalArgumentException("coordinate outside resolvable long-cell domain");
        }
        return (long) c;
    }

    private long centreHash(Key key) {
        return mix(seed ^ mix(X_SALT * (key.layer + 1L)) ^ mix(key.x + X_SALT)
                ^ Long.rotateLeft(mix(key.z + Z_SALT), 32));
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** Cell fraction [0.25,0.75), i.e. a fixed per-axis jitter in [-0.25,0.25). */
    private static double offset(long hash) {
        return 0.25 + (hash >>> 11) * 0x1.0p-54;
    }

    private static double distanceToInterval(double value, double lo, double hi) {
        return value < lo ? lo - value : value > hi ? value - hi : 0.0;
    }

    private static double bounded(double value, double lo, double hi) {
        return StrictMath.max(lo, StrictMath.min(hi, value));
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void positive(double value, String name) {
        finite(value, name);
        if (!(value > 0.0)) throw new IllegalArgumentException(name + " must be positive");
    }
}
