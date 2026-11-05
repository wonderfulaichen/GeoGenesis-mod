package com.geogenesis.standalone;

import com.geogenesis.standalone.AdaptiveSkeletonErosion.HeightField;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Sample;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Tensor;

import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Dependency-free, Java 17 stage-A numerical checks. Writes no files, needs no Minecraft classes.
 * Compile only this file and AdaptiveSkeletonErosion.java with javac --release 17, then run main.
 * Any failure exits nonzero. Synthetic settings below are NOT a mountain calibration or image gate.
 * Fixed-stencil aliasing is an explicit counterexample, not evidence of adapting to all inputs.
 */
public final class AdaptiveSkeletonCheck {
    private static final long SEED = 0x713af2058bce946dL;
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final HeightField SLOPE = (x, z) -> 0.125 * x - 0.0625 * z;
    private static final HeightField CARRIER = (x, z) -> 0.7 * StrictMath.sin(x / 17.0)
            + 0.3 * StrictMath.cos(z / 13.0) - 0.02 * StrictMath.abs(0.8 * x + 0.6 * z);

    private record Case(String name, HeightField field) {}

    private AdaptiveSkeletonCheck() {}

    public static void main(String[] args) {
        long start = System.nanoTime();
        try {
            if (args.length != 0) throw new IllegalArgumentException("this check takes no arguments");
            run("settings/input boundaries", AdaptiveSkeletonCheck::checkValidation);
            run("5x5 tensor and directional energy", AdaptiveSkeletonCheck::checkTensor);
            run("flat exact zero and geometric masks", AdaptiveSkeletonCheck::checkFlatAndSlope);
            run("hard ridges/rotations/saddle/terraces", AdaptiveSkeletonCheck::checkShapes);
            run("support C2", AdaptiveSkeletonCheck::checkKernel);
            run("negative-cell boundary continuity", AdaptiveSkeletonCheck::checkContinuity);
            run("four-layer gain and no feedback", AdaptiveSkeletonCheck::checkLayers);
            run("true kernel capacity and unchanged queries", AdaptiveSkeletonCheck::checkCapacity);
            run("reverse/clear/parallel/prepare bitwise", AdaptiveSkeletonCheck::checkDeterminism);
            run("long coordinates and unit similarity", AdaptiveSkeletonCheck::checkCoordinatesAndUnits);
            run("prepare approximately 300k descriptors", AdaptiveSkeletonCheck::checkPrepareCapacity);
            run("honest high-frequency blind spot", AdaptiveSkeletonCheck::checkAliasing);
            System.out.println("LIMIT: independent layers only; no coarse/fine feedback or drainage topology.");
            System.out.println("LIMIT: immutable/thread-safe input required; double resolution bounds the domain.");
            System.out.println("LIMIT: continuity checks do not establish visual quality or remove input creases.");
            System.out.printf(Locale.ROOT, "PASS checks=%d elapsed_ms=%.3f%n",
                    CHECKS.get(), (System.nanoTime() - start) / 1e6);
        } catch (Throwable failure) {
            System.err.println("FAIL after checks=" + CHECKS.get() + ": " + failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Settings one(double lambda) {
        return new Settings(new double[]{lambda}, new double[]{0.02}, new double[]{0.003}, 0.15, 1.0);
    }

    private static Settings four() {
        return new Settings(new double[]{32, 16, 8, 4}, new double[]{0.02, 0.02, 0.02, 0.02},
                new double[]{0.003, 0.003, 0.003, 0.003}, 0.15, 1.0);
    }

    private static void checkValidation() {
        double[] wavelengths = {8}, onset = {0.02}, epsilon = {0.003};
        Settings s = new Settings(wavelengths, onset, epsilon, 0.15, 1);
        wavelengths[0] = onset[0] = epsilon[0] = 999;
        require(s.wavelengths()[0] == 8 && s.onset()[0] == 0.02 && s.epsilon()[0] == 0.003,
                "Settings must clone constructor arrays");
        s.wavelengths()[0] = s.onset()[0] = s.epsilon()[0] = -1;
        require(s.wavelengths()[0] == 8 && s.onset()[0] > 0 && s.epsilon()[0] > 0,
                "Settings accessors must not leak mutable arrays");
        rejects(() -> new Settings(new double[0], new double[0], new double[0], 0, 1));
        rejects(() -> new Settings(new double[]{8, 4}, new double[]{1, 1}, new double[]{1, 1}, 0, 1));
        rejects(() -> new Settings(new double[]{8}, new double[]{1, 1}, new double[]{1}, 0, 1));
        rejects(() -> new Settings(null, new double[]{1}, new double[]{1}, 0, 1));
        for (double v : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            rejects(() -> new Settings(new double[]{v}, new double[]{1}, new double[]{1}, 0, 1));
            rejects(() -> new Settings(new double[]{8}, new double[]{v}, new double[]{1}, 0, 1));
            rejects(() -> new Settings(new double[]{8}, new double[]{1}, new double[]{v}, 0, 1));
            rejects(() -> new Settings(new double[]{8}, new double[]{1}, new double[]{1}, 0, v));
        }
        rejects(() -> one(Double.MIN_VALUE));
        rejects(() -> new Settings(new double[]{8}, new double[]{1}, new double[]{1}, -1, 1));
        rejects(() -> new Settings(new double[]{8}, new double[]{1}, new double[]{1}, Double.NaN, 1));
        rejects(() -> new AdaptiveSkeletonErosion(SEED, null, s));
        rejects(() -> new AdaptiveSkeletonErosion(SEED, SLOPE, null));
        AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, SLOPE, s);
        rejects(() -> e.sample(Double.NaN, 0));
        rejects(() -> e.sample(0, Double.NEGATIVE_INFINITY));
        rejects(() -> e.sample(Double.MAX_VALUE, 0));
        rejects(() -> e.sampleLayer(-1, 0, 0));
        rejects(() -> e.sampleLayer(1, 0, 0));
        rejects(() -> e.prepare(1, 0, -1, 0));
        rejects(() -> e.prepare(0, 0, 1, Double.NaN));
        rejects(() -> AdaptiveSkeletonErosion.analyze(SLOPE, 1e20, 0, 1, 1));
        rejects(() -> AdaptiveSkeletonErosion.analyze(SLOPE, 0, 0, 0, 1));
        rejects(() -> AdaptiveSkeletonErosion.analyze((x, z) -> Double.NaN, 0, 0, 8, 1));
        rejects(() -> AdaptiveSkeletonErosion.analyze((x, z) -> Double.MAX_VALUE, 0, 0, 8, 0.5));
        rejects(() -> AdaptiveSkeletonErosion.analyze((x, z) -> x * 1e200, 0, 0, 8, 1));
        Tensor largeFlat = AdaptiveSkeletonErosion.analyze((x, z) -> Double.MAX_VALUE, 0, 0, 8, 1);
        require(largeFlat.xx() == 0 && largeFlat.xz() == 0 && largeFlat.zz() == 0,
                "finite constant at largest height should remain flat");
        Settings hugeThreshold = new Settings(new double[]{8}, new double[]{Double.MAX_VALUE},
                new double[]{Double.MAX_VALUE}, Double.MAX_VALUE, 1);
        bounded(new AdaptiveSkeletonErosion(SEED, SLOPE, hugeThreshold).sample(0, 0));
    }

    private static void checkTensor() {
        AtomicInteger calls = new AtomicInteger();
        HeightField tracked = (x, z) -> {
            int i = calls.getAndIncrement();
            require(x == 3 + ((i % 5) - 2) * 2 && z == -5 + ((i / 5) - 2) * 2,
                    "5x5 template coordinates/order changed");
            return SLOPE.height(x, z);
        };
        Tensor j = AdaptiveSkeletonErosion.analyze(tracked, 3, -5, 8, 2);
        require(calls.get() == 25, "tensor must read exactly 25 heights");
        near(j.xx(), 0.25, 1e-15, "slope xx");
        near(j.xz(), -0.125, 1e-15, "slope xz");
        near(j.zz(), 0.0625, 1e-15, "slope zz");
        HeightField ridge = (x, z) -> -StrictMath.abs(x);
        Tensor r = AdaptiveSkeletonErosion.analyze(ridge, 0, 0, 8, 1);
        near(r.xx(), 128.0 / 3.0, 1e-13, "ridge opposing slopes retain energy");
        require(r.xz() == 0 && r.zz() == 0, "axis ridge transverse energy");
        double angle = 0.43, ca = StrictMath.cos(angle), sa = StrictMath.sin(angle);
        HeightField rotated = (x, z) -> -StrictMath.abs(ca * x + sa * z);
        Tensor rr = AdaptiveSkeletonErosion.analyze(rotated, 0, 0, 8, 1);
        double trace = rr.xx() + rr.zz(), gap = StrictMath.hypot(rr.xx() - rr.zz(), 2 * rr.xz());
        require(trace > 1 && gap / trace > 0.75, "rotated ridge must not cancel or become isotropic");
        Tensor saddle = AdaptiveSkeletonErosion.analyze((x, z) -> (x * x - z * z) / 64, 0, 0, 8, 1);
        require(saddle.xx() == saddle.zz() && saddle.xz() == 0, "centred saddle exact isotropy");
        require(saddle.xx() > 0, "isotropic saddle can have energy without a direction");
        System.out.printf(Locale.ROOT, "  ridge_trace=%.9f rotated_gap/trace=%.9f saddle_gap=0%n",
                r.xx(), gap / trace);
    }

    private static void checkFlatAndSlope() {
        Settings settings = four();
        AdaptiveSkeletonErosion flat = new AdaptiveSkeletonErosion(SEED, (x, z) -> 7.125, settings);
        AdaptiveSkeletonErosion slope = new AdaptiveSkeletonErosion(SEED, SLOPE, settings);
        double rms = 0, minimumD = Double.POSITIVE_INFINITY;
        for (double[] p : points(81, 50)) {
            Sample f = flat.sample(p[0], p[1]), s = slope.sample(p[0], p[1]);
            require(bits(f.value()) == bits(0) && bits(f.activity()) == bits(0)
                    && bits(f.confidence()) == bits(0), "flat output/masks must be exact +0");
            require(bits(f.denominatorMin()) == bits(s.denominatorMin()), "D must be input-independent");
            bounded(s);
            require(s.activity() > 0.8 && s.confidence() > 0.99, "planar slope unexpectedly inactive");
            for (int layer = 0; layer < 4; layer++) {
                Sample a = slope.sampleLayer(layer, p[0], p[1]);
                Sample b = flat.sampleLayer(layer, p[0], p[1]);
                require(b.value() == 0 && b.activity() == 0 && b.confidence() == 0, "flat layer");
                require(bits(a.denominatorMin()) == bits(b.denominatorMin()), "layer D is geometric");
                double lambda = settings.wavelengths()[layer];
                double t = lambda * lambda * (0.125 * 0.125 + 0.0625 * 0.0625);
                double expectedM = t / (t + 0.02), q = 0.003 / t + 0.15;
                near(a.activity(), expectedM, 3e-14, "activity must not be divided away");
                near(a.confidence(), 1 / (1 + q * q * q * q), 3e-14, "confidence geometric mean");
            }
            rms += s.value() * s.value();
            minimumD = StrictMath.min(minimumD, s.denominatorMin());
        }
        rms = StrictMath.sqrt(rms / 81);
        require(rms > 0.01, "active slope displacement collapsed");
        System.out.printf(Locale.ROOT, "  flat=exact_zero slope_rms=%.9f min_D=%.9f bound=%.10f%n",
                rms, minimumD, AdaptiveSkeletonErosion.DENOMINATOR_LOWER_BOUND);
    }

    private static void checkShapes() {
        double ca = StrictMath.cos(0.43), sa = StrictMath.sin(0.43);
        Case[] cases = {
            new Case("hard_ridge", (x, z) -> -StrictMath.abs(x) * 0.1),
            new Case("rotated_ridge", (x, z) -> -StrictMath.abs(ca * x + sa * z) * 0.1),
            new Case("quarter_turn_ridge", (x, z) -> -StrictMath.abs(z) * 0.1),
            new Case("saddle", (x, z) -> (x * x - z * z) / 512),
            new Case("terraces", (x, z) -> StrictMath.floor((x + 0.4 * z) / 5) * 0.2),
        };
        double minimumD = Double.POSITIVE_INFINITY;
        for (Case test : cases) {
            AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, test.field, four());
            double activity = 0, confidence = 0, sumSquares = 0;
            for (double[] p : points(121, 36)) {
                Sample s = e.sample(p[0], p[1]);
                bounded(s);
                double base = test.field.height(p[0], p[1]), zeroAmplitude = base + 0.0 * s.value();
                require(bits(base) == bits(zeroAmplitude), "zero-amplitude combination changed carrier");
                activity += s.activity();
                confidence += s.confidence();
                sumSquares += s.value() * s.value();
                minimumD = StrictMath.min(minimumD, s.denominatorMin());
            }
            require(activity / 121 > 0.05, test.name + " exited almost everywhere");
            System.out.printf(Locale.ROOT, "  %s rms=%.8f activity_mean=%.8f confidence_mean=%.8f%n",
                    test.name, StrictMath.sqrt(sumSquares / 121), activity / 121, confidence / 121);
        }
        System.out.printf(Locale.ROOT, "  finite_bounded_min_D=%.9f zero_amplitude=bitwise%n", minimumD);
    }

    private static void checkKernel() {
        require(AdaptiveSkeletonErosion.kernel(0) == 1, "kernel centre");
        for (double u : new double[]{1, StrictMath.nextUp(1.0), 2, Double.POSITIVE_INFINITY}) {
            require(bits(AdaptiveSkeletonErosion.kernel(u)) == bits(0), "support must be exact zero");
        }
        double oldValue = 1, oldFirst = 1, oldSecond = 1;
        double value = 0, first = 0, second = 0;
        for (double h : new double[]{1e-2, 1e-3, 1e-4}) {
            value = AdaptiveSkeletonErosion.kernel((1 - h) * (1 - h));
            double twice = AdaptiveSkeletonErosion.kernel((1 - 2 * h) * (1 - 2 * h));
            first = value / h;
            second = StrictMath.abs(twice - 2 * value) / (h * h);
            require(value >= 0 && value < oldValue * 0.002, "C0 convergence at support");
            require(first < oldFirst * 0.02, "C1 convergence at support");
            require(second < oldSecond * 0.2, "C2 convergence at support");
            oldValue = value;
            oldFirst = first;
            oldSecond = second;
        }
        System.out.printf(Locale.ROOT, "  edge_h=1e-4 |B|=%.3e left_d1=%.3e left_d2=%.3e%n",
                value, first, second);
    }

    private static void checkContinuity() {
        double lambda = 8;
        AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, CARRIER, one(lambda));
        double maxSmallJump = 0;
        for (int axis = 0; axis < 2; axis++) {
            for (int k = -5; k <= 3; k++) {
                double seam = k * lambda * 0.5, along = 1.137 + k * 0.713;
                double previous = Double.POSITIVE_INFINITY;
                for (double h : new double[]{8e-3, 8e-4, 8e-5}) {
                    Sample a = axis == 0 ? e.sample(seam - h, along) : e.sample(along, seam - h);
                    Sample b = axis == 0 ? e.sample(seam + h, along) : e.sample(along, seam + h);
                    double jump = difference(a, b);
                    require(jump <= 64 * h / lambda + 2e-13, "cell seam exceeds slope cap");
                    require(jump <= previous * 0.2 + 2e-12, "cell seam jump does not converge");
                    if (h == 8e-5) maxSmallJump = StrictMath.max(maxSmallJump, jump);
                    previous = jump;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  axes=2 seams=18 negative_and_zero=true max_small_jump=%.3e%n",
                maxSmallJump);
    }

    private static void checkLayers() {
        AtomicLong reads = new AtomicLong();
        HeightField counted = (x, z) -> {
            reads.incrementAndGet();
            return CARRIER.height(x, z);
        };
        AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, counted, four());
        Sample fineFirst = e.sampleLayer(3, -2.75, 1.125);
        require(e.descriptorCount() > 0 && e.descriptorCount() <= 25, "single fine layer descriptor budget");
        require(reads.get() == 25 * e.descriptorCount(), "fine query recursively built dependencies");
        Sample[] layers = new Sample[4];
        double value = 0, activity = 0, confidence = 0, denominator = Double.POSITIVE_INFINITY;
        double gain = 1, weight = 0;
        for (int j = 0; j < 4; j++) {
            layers[j] = e.sampleLayer(j, -2.75, 1.125);
            value += gain * layers[j].value();
            activity += gain * layers[j].activity();
            confidence += gain * layers[j].confidence();
            denominator = StrictMath.min(denominator, layers[j].denominatorMin());
            weight += gain;
            gain *= 0.5;
        }
        require(reads.get() == 25 * e.descriptorCount(), "descriptor reads are not one 5x5 stencil each");
        long before = reads.get();
        same(new Sample(value / weight, activity / weight, confidence / weight, denominator),
                e.sample(-2.75, 1.125), "four-layer gain 0.5 normalization");
        same(fineFirst, e.sampleLayer(3, -2.75, 1.125), "fine layer changed after coarse construction");
        require(reads.get() == before, "hot sampling recomputed height dependencies");
        AdaptiveSkeletonErosion single = new AdaptiveSkeletonErosion(SEED, CARRIER, one(8));
        same(single.sampleLayer(0, -3.2, 7.1), single.sample(-3.2, 7.1), "single layer normalization");
        System.out.printf(Locale.ROOT, "  gain_sum=%.3f descriptors=%d height_reads=%d feedback=none%n",
                weight, e.descriptorCount(), reads.get());
    }

    private static void checkCapacity() {
        AdaptiveSkeletonErosion slope = new AdaptiveSkeletonErosion(SEED, SLOPE, four());
        AdaptiveSkeletonErosion rough = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
        AdaptiveSkeletonErosion flat = new AdaptiveSkeletonErosion(SEED, (x, z) -> 7, four());
        rejects(() -> slope.layerCapacity(-1, 0, 0));
        rejects(() -> slope.layerCapacity(4, 0, 0));
        for (double[] p : points(48, 29)) for (int j = 0; j < 4; j++) {
            Sample s = slope.sampleLayer(j, p[0], p[1]);
            double cap = slope.layerCapacity(j, p[0], p[1]);
            near(cap, s.activity() * s.confidence(), 4e-14, "constant-slope product exact envelope");
            Sample before = rough.sampleLayer(j, p[0], p[1]);
            double real = rough.layerCapacity(j, p[0], p[1]);
            require(real >= 0 && real <= 1 && Math.abs(before.value()) <= real,
                    "true kernel-weighted envelope bounds value");
            same(before, rough.sampleLayer(j, p[0], p[1]), "capacity query cannot alter sample");
            require(bits(flat.layerCapacity(j, p[0], p[1])) == bits(0), "flat capacity exact zero");
        }
    }

    private static void checkDeterminism() {
        double[][] pts = points(96, 53);
        AdaptiveSkeletonErosion serial = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
        Sample[] expected = new Sample[pts.length];
        for (int i = 0; i < pts.length; i++) expected[i] = serial.sample(pts[i][0], pts[i][1]);
        long count = serial.descriptorCount();
        for (int i = pts.length - 1; i >= 0; i--) {
            same(expected[i], serial.sample(pts[i][0], pts[i][1]), "reverse hot");
        }
        require(serial.descriptorCount() == count, "hot reverse expanded descriptor cache");
        serial.clearCache();
        require(serial.descriptorCount() == 0, "clearCache did not clear descriptors");
        for (int i = pts.length - 1; i >= 0; i--) {
            same(expected[i], serial.sample(pts[i][0], pts[i][1]), "reverse after clear");
        }
        require(serial.descriptorCount() == count, "reverse cold descriptor set changed");
        AdaptiveSkeletonErosion parallel = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
        IntStream.range(0, pts.length * 4).parallel().forEach(i -> {
            int k = i % pts.length;
            same(expected[k], parallel.sample(pts[k][0], pts[k][1]), "parallel cold/hot");
        });
        require(parallel.descriptorCount() == count, "parallel descriptor set changed");
        IntStream.range(0, pts.length * 3).parallel().forEach(i -> {
            if (i % 71 == 0) parallel.clearCache();
            int k = i % pts.length;
            same(expected[k], parallel.sample(pts[k][0], pts[k][1]), "parallel concurrent clear");
        });
        AdaptiveSkeletonErosion prepared = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
        prepared.prepare(-53, -53, 53, 53);
        long preparedCount = prepared.descriptorCount();
        for (int i = 0; i < pts.length; i++) {
            same(expected[i], prepared.sample(pts[i][0], pts[i][1]), "prepared replay");
        }
        require(prepared.descriptorCount() == preparedCount, "prepare missed bbox support");
        prepared.prepare(-53, -53, 53, 53);
        require(prepared.descriptorCount() == preparedCount, "repeated prepare expanded cache");
        checkPointPrepare();
        AdaptiveSkeletonErosion other = new AdaptiveSkeletonErosion(SEED + 1, CARRIER, four());
        require(difference(expected[0], other.sample(pts[0][0], pts[0][1])) > 1e-8, "seed ignored");
        System.out.printf(Locale.ROOT, "  samples=%d lazy_descriptors=%d bbox_descriptors=%d bitwise=true%n",
                pts.length, count, preparedCount);
    }

    private static void checkPointPrepare() {
        for (double[] p : new double[][]{{-0.1, -4.0}, {0, 0}, {9.17, -13.81}}) {
            AdaptiveSkeletonErosion lazy = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
            Sample expected = lazy.sample(p[0], p[1]);
            AdaptiveSkeletonErosion prepared = new AdaptiveSkeletonErosion(SEED, CARRIER, four());
            prepared.prepare(p[0], p[1], p[0], p[1]);
            long n = prepared.descriptorCount();
            require(n == lazy.descriptorCount(), "point prepare built kernels outside support");
            same(expected, prepared.sample(p[0], p[1]), "point prepare");
            require(n == prepared.descriptorCount(), "point prepare omitted support");
        }
    }

    private static void checkCoordinatesAndUnits() {
        AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, SLOPE, one(8));
        boolean distinguished = false;
        double far = (1L << 34) * 4.0;
        for (int i = 0; i < 12; i++) {
            double x = i * 0.271, z = -3.1 + i * 0.093;
            Sample near = e.sample(x, z), positive = e.sample(far + x, z), negative = e.sample(-far + x, z);
            bounded(positive);
            bounded(negative);
            same(positive, e.sample(far + x, z), "far positive replay");
            same(negative, e.sample(-far + x, z), "far negative replay");
            distinguished |= bits(near.denominatorMin()) != bits(positive.denominatorMin());
        }
        require(distinguished, "long cell coordinates aliased to 32-bit keys");
        HeightField wave = (x, z) -> 0.6 * StrictMath.sin(x / 23) + 0.4 * StrictMath.cos(z / 19);
        AdaptiveSkeletonErosion base = new AdaptiveSkeletonErosion(SEED, wave, four());
        for (double scale : new double[]{8, 3.7}) {
            double vertical = scale == 8 ? 32 : 0.23;
            Settings scaled = scaled(four(), scale, vertical);
            HeightField converted = (x, z) -> vertical * wave.height(x / scale, z / scale);
            AdaptiveSkeletonErosion units = new AdaptiveSkeletonErosion(SEED, converted, scaled);
            double localError = 0, farError = 0, farTolerance = 0;
            for (double[] p : points(48, 40)) {
                Sample a = base.sample(p[0], p[1]);
                Sample b = units.sample(p[0] * scale, p[1] * scale);
                localError = StrictMath.max(localError, difference(a, b));
                near(difference(a, b), 0, 3e-12, "local unit similarity");
                double x = far + p[0], z = -far * 0.5 + p[1];
                a = base.sample(x, z);
                b = units.sample(x * scale, z * scale);
                double tolerance = 128 * StrictMath.ulp(StrictMath.max(StrictMath.abs(x), StrictMath.abs(z)))
                        / 4 + 3e-12;
                farError = StrictMath.max(farError, difference(a, b));
                farTolerance = StrictMath.max(farTolerance, tolerance);
                near(difference(a, b), 0, tolerance, "far unit similarity (ulp-scaled tolerance)");
            }
            System.out.printf(Locale.ROOT, "  horizontal_scale=%.1f vertical_scale=%.2f local_error=%.3e"
                    + " far_error=%.3e far_tolerance=%.3e%n", scale, vertical, localError, farError, farTolerance);
        }
        System.out.printf(Locale.ROOT, "  long_cell_magnitude=%d positive_negative=true no_int_alias=true%n",
                1L << 34);
    }

    private static Settings scaled(Settings s, double horizontal, double vertical) {
        double[] wavelengths = s.wavelengths();
        for (int i = 0; i < wavelengths.length; i++) wavelengths[i] *= horizontal;
        return new Settings(wavelengths, s.onset(), s.epsilon(), s.chi(), s.verticalUnit() * vertical);
    }

    private static void checkPrepareCapacity() {
        HeightField field = (x, z) -> x * 0.003 + z * 0.002;
        AdaptiveSkeletonErosion e = new AdaptiveSkeletonErosion(SEED, field, one(2));
        long start = System.nanoTime();
        e.prepare(0, 0, 544, 544);
        double millis = (System.nanoTime() - start) / 1e6;
        long descriptors = e.descriptorCount();
        require(descriptors >= 295_000 && descriptors <= 305_000, "prepare coverage not approximately 300k");
        AdaptiveSkeletonErosion lazy = new AdaptiveSkeletonErosion(SEED, field, one(2));
        for (double[] p : new double[][]{{0, 0}, {544, 544}, {0, 544}, {544, 0},
                {2.75, 400.125}, {272, 272}, {543.999, 71.234}}) {
            Sample prepared = e.sample(p[0], p[1]);
            bounded(prepared);
            same(lazy.sample(p[0], p[1]), prepared, "parallel row prepare versus lazy");
        }
        require(e.descriptorCount() == descriptors, "large prepare omitted bbox support");
        System.out.printf(Locale.ROOT, "  descriptors=%d prepare_ms=%.3f ms_per_descriptor=%.6f"
                + " (cheap synthetic field; NOT mountain cost)%n", descriptors, millis, millis / descriptors);
        e.clearCache();
        require(e.descriptorCount() == 0, "large descriptor cache clear");
    }

    private static void checkAliasing() {
        double lambda = 8, delta = lambda / 4;
        HeightField highFrequency = (x, z) -> StrictMath.cos(2 * StrictMath.PI * x / delta);
        Tensor blind = AdaptiveSkeletonErosion.analyze(highFrequency, 0, 0, lambda, 1);
        Tensor differentStep = AdaptiveSkeletonErosion.analyze(highFrequency, 0, 0, lambda * 0.875, 1);
        require(highFrequency.height(0, 0) - highFrequency.height(delta / 2, 0) > 1.9,
                "alias counterexample must have nonzero input amplitude");
        require(blind.xx() + blind.zz() == 0, "known high-frequency alias no longer reproduced");
        require(differentStep.xx() + differentStep.zz() > 0.1, "different template spacing control failed");
        System.out.printf(Locale.ROOT, "  LIMIT: period=lambda/4 cosine has trace=%.1f at this fixed stencil;"
                + " alternate_lambda=7 trace=%.9f. No second stencil is enabled; no universal adaptation claim.%n",
                blind.xx() + blind.zz(), differentStep.xx() + differentStep.zz());
    }

    private static double[][] points(int n, double extent) {
        SplittableRandom random = new SplittableRandom(0x189bedL);
        double[][] points = new double[n][2];
        for (int i = 0; i < n; i++) {
            points[i][0] = random.nextDouble(-extent, extent);
            points[i][1] = random.nextDouble(-extent, extent);
        }
        return points;
    }

    private static void bounded(Sample sample) {
        require(Double.isFinite(sample.value()) && StrictMath.abs(sample.value()) <= 1,
                "nonfinite/unbounded displacement: " + sample);
        require(Double.isFinite(sample.activity()) && sample.activity() >= 0 && sample.activity() <= 1,
                "nonfinite/unbounded activity: " + sample);
        require(Double.isFinite(sample.confidence()) && sample.confidence() >= 0 && sample.confidence() <= 1,
                "nonfinite/unbounded confidence: " + sample);
        require(Double.isFinite(sample.denominatorMin())
                && sample.denominatorMin() >= AdaptiveSkeletonErosion.DENOMINATOR_LOWER_BOUND,
                "geometric denominator below bound: " + sample);
    }

    private static void same(Sample a, Sample b, String label) {
        require(bits(a.value()) == bits(b.value()) && bits(a.activity()) == bits(b.activity())
                && bits(a.confidence()) == bits(b.confidence())
                && bits(a.denominatorMin()) == bits(b.denominatorMin()), label + ": " + a + " != " + b);
    }

    private static double difference(Sample a, Sample b) {
        return StrictMath.max(StrictMath.max(StrictMath.abs(a.value() - b.value()),
                        StrictMath.abs(a.activity() - b.activity())),
                StrictMath.max(StrictMath.abs(a.confidence() - b.confidence()),
                        StrictMath.abs(a.denominatorMin() - b.denominatorMin())));
    }

    private static long bits(double value) {
        return Double.doubleToLongBits(value);
    }

    private static void near(double value, double expected, double tolerance, String label) {
        require(Double.isFinite(value) && StrictMath.abs(value - expected) <= tolerance,
                label + ": " + value + " != " + expected + " tolerance=" + tolerance);
    }

    private static void rejects(Runnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            rejected = true;
        }
        require(rejected, "invalid input was silently accepted");
    }

    private static void require(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static void run(String label, Runnable action) {
        long start = System.nanoTime();
        action.run();
        System.out.printf(Locale.ROOT, "PASS %s (%.3f ms)%n", label, (System.nanoTime() - start) / 1e6);
    }
}
