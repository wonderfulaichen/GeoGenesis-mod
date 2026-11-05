package com.geogenesis.standalone;

import com.geogenesis.standalone.AdaptiveSkeletonErosion.HeightField;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Sample;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Small, dependency-free stage-B checks. Compile only A, B and this check with javac --release 17.
 * No Minecraft, rendering, old data, calibration or large-cache capacity runs. Failure exits nonzero.
 * Synthetic inputs/settings verify the contract, not visual valley connectivity or mountain quality.
 */
public final class InheritedSkeletonCheck {
    private static final long SEED = 0x713af2058bce946dL;
    private static final double AMPLITUDE = 0.25;
    private static final double NORM = 1.875;
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final HeightField RAW = (x, z) -> 0.7 * StrictMath.sin(x / 17.0)
            + 0.3 * StrictMath.cos(z / 13.0) - 0.02 * StrictMath.abs(0.8 * x + 0.6 * z);

    private InheritedSkeletonCheck() {}

    public static void main(String[] args) {
        long start = System.nanoTime();
        try {
            if (args.length != 0) throw new IllegalArgumentException("this check takes no arguments");
            run("validation and extracted A prepareLayer", InheritedSkeletonCheck::checkPreparationApi);
            run("beta=0 / layer=0 / single-layer bitwise A", InheritedSkeletonCheck::checkAEquivalence);
            run("actual weighted guide, unchanged D and bounded gain", InheritedSkeletonCheck::checkFormula);
            run("flat exact zero and negative-cell continuity", InheritedSkeletonCheck::checkFlatAndSeams);
            run("acyclic cache, one stencil per descriptor, coarse stability", InheritedSkeletonCheck::checkDagCache);
            run("reverse / clear / parallel bitwise", InheritedSkeletonCheck::checkDeterminism);
            run("prepare dependency halo and hot-cache coverage", InheritedSkeletonCheck::checkPrepare);
            System.out.println("LIMIT: numerical contract only; no images, drainage topology or production changes.");
            System.out.printf(Locale.ROOT, "PASS checks=%d elapsed_ms=%.3f%n",
                    CHECKS.get(), (System.nanoTime() - start) / 1e6);
        } catch (Throwable failure) {
            System.err.println("FAIL after checks=" + CHECKS.get() + ": " + failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Settings settings(double... wavelengths) {
        double[] onset = new double[wavelengths.length], epsilon = new double[wavelengths.length];
        Arrays.fill(onset, 0.02);
        Arrays.fill(epsilon, 0.003);
        return new Settings(wavelengths, onset, epsilon, 0.15, 1.0);
    }

    private static Settings four() {
        return settings(32, 16, 8, 4);
    }

    private static InheritedSkeletonErosion erosion(HeightField raw, Settings settings, double beta) {
        return new InheritedSkeletonErosion(SEED, raw, settings, AMPLITUDE, beta);
    }

    private static void checkPreparationApi() {
        Settings s = four();
        InheritedSkeletonErosion b = erosion(RAW, s, 1);
        rejects(() -> erosion(null, s, 1));
        rejects(() -> erosion(RAW, null, 1));
        for (double v : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            rejects(() -> new InheritedSkeletonErosion(SEED, RAW, s, v, 1));
            rejects(() -> erosion(RAW, s, v));
        }
        for (int j : new int[]{-1, 4}) {
            rejects(() -> b.sampleLayer(j, 0, 0));
            rejects(() -> b.guideHeight(j, 0, 0));
            rejects(() -> b.descriptorCount(j));
            rejects(() -> new AdaptiveSkeletonErosion(SEED, RAW, s).prepareLayer(j, 0, 0, 1, 1));
        }
        rejects(() -> b.sample(Double.NaN, 0));
        rejects(() -> b.sample(0, Double.POSITIVE_INFINITY));
        rejects(() -> b.sample(Double.MAX_VALUE, 0));
        rejects(() -> b.guideHeight(0, Double.NaN, 0));
        rejects(() -> b.prepare(1, 0, -1, 0));
        rejects(() -> b.prepare(0, 0, 1, Double.NaN));
        AdaptiveSkeletonErosion all = new AdaptiveSkeletonErosion(SEED, RAW, s);
        AdaptiveSkeletonErosion byLayer = new AdaptiveSkeletonErosion(SEED, RAW, s);
        all.prepare(-5, -4, 3, 2);
        for (int j = 0; j < 4; j++) byLayer.prepareLayer(j, -5, -4, 3, 2);
        require(all.descriptorCount() == byLayer.descriptorCount(), "A prepare delegation footprint");
        long count = all.descriptorCount();
        for (double[] p : new double[][]{{-5, -4}, {3, 2}, {-0.1, 0}, {1.9, -2.1}}) {
            same(all.sample(p[0], p[1]), byLayer.sample(p[0], p[1]), "A extracted prepare");
        }
        require(all.descriptorCount() == count && byLayer.descriptorCount() == count,
                "A extracted prepare missed support");
        rejects(() -> all.prepareLayer(0, 1, 0, -1, 0));
        rejects(() -> all.prepareLayer(0, 0, 0, Double.NaN, 1));
    }

    private static void checkAEquivalence() {
        Settings s = four();
        AdaptiveSkeletonErosion a = new AdaptiveSkeletonErosion(SEED, RAW, s);
        InheritedSkeletonErosion disabled = erosion(RAW, s, 0), inherited = erosion(RAW, s, 1);
        AdaptiveSkeletonErosion singleA = new AdaptiveSkeletonErosion(SEED, RAW, settings(7.33));
        InheritedSkeletonErosion singleB = erosion(RAW, settings(7.33), 1);
        for (double[] p : points(40, 27)) {
            for (int j = 0; j < 4; j++) {
                same(a.sampleLayer(j, p[0], p[1]), disabled.sampleLayer(j, p[0], p[1]),
                        "beta=0 layer " + j);
                same(a.layerCapacity(j, p[0], p[1]), disabled.layerCapacity(j, p[0], p[1]),
                        "beta=0 true capacity " + j);
                same(RAW.height(p[0], p[1]), disabled.guideHeight(j, p[0], p[1]), "beta=0 guide");
            }
            same(a.sample(p[0], p[1]), disabled.sample(p[0], p[1]), "beta=0 combined");
            same(a.sampleLayer(0, p[0], p[1]), inherited.sampleLayer(0, p[0], p[1]), "layer=0");
            same(singleA.sample(p[0], p[1]), singleB.sample(p[0], p[1]), "single-layer normalization");
        }
        InheritedSkeletonErosion signedZero = erosion((x, z) -> -0.0, s, 0);
        for (int j = 0; j < 4; j++) same(-0.0, signedZero.guideHeight(j, 0, 0), "raw signed zero");
        same(-0.0, erosion((x, z) -> -0.0, s, 1).guideHeight(0, 0, 0), "j=0 direct raw");
        require(signedZero.descriptorCount() == 0, "disabled guide unnecessarily read coarse layers");
    }

    private static void checkFormula() {
        Settings s = four();
        AdaptiveSkeletonErosion a = new AdaptiveSkeletonErosion(SEED, RAW, s);
        InheritedSkeletonErosion b = erosion(RAW, s, 1);
        int[] changed = new int[4];
        for (double[] p : points(32, 21)) {
            Sample[] layers = new Sample[4];
            double sum = 0.0, gain = 1.0, prefixWeight = 0.0;
            for (int j = 0; j < 4; j++) {
                double base = RAW.height(p[0], p[1]);
                double expected = j == 0 ? base : base + 1.0 * AMPLITUDE / NORM * sum;
                double guide = b.guideHeight(j, p[0], p[1]);
                same(expected, guide, "guide amplitude/gain/full normalization j=" + j);
                require(StrictMath.abs(guide - base) <= AMPLITUDE / NORM * prefixWeight
                        + 16 * StrictMath.ulp(base), "coarse guide amplitude amplified");
                layers[j] = b.sampleLayer(j, p[0], p[1]);
                Sample independent = a.sampleLayer(j, p[0], p[1]);
                same(independent.denominatorMin(), layers[j].denominatorMin(), "D changed by inheritance");
                if (bits(independent.value()) != bits(layers[j].value())) changed[j]++;
                bounded(layers[j]);
                sum += gain * layers[j].value();
                prefixWeight += gain;
                gain *= 0.5;
            }
            Sample combined = b.sample(p[0], p[1]);
            same(combine(layers), combined, "fixed-order normalized channels");
            bounded(combined);
            require(StrictMath.abs(AMPLITUDE * combined.value()) <= AMPLITUDE, "final amplitude amplified");
        }
        require(changed[0] == 0, "coarsest output changed");
        for (int j = 1; j < 4; j++) require(changed[j] > 0, "inheritance did not affect fine layer " + j);
        System.out.println("  changed_value_samples_by_layer=" + Arrays.toString(changed) + " norm=" + NORM);
    }

    private static void checkFlatAndSeams() {
        InheritedSkeletonErosion flat = erosion((x, z) -> 7.125, four(), 1);
        for (double[] p : points(20, 13)) {
            for (Sample sample : snapshot(flat, p[0], p[1], 4)) {
                same(0.0, sample.value(), "flat value");
                same(0.0, sample.activity(), "flat activity");
                same(0.0, sample.confidence(), "flat confidence");
            }
            for (int j = 0; j < 4; j++) same(7.125, flat.guideHeight(j, p[0], p[1]), "flat guide");
        }
        Settings s = four();
        InheritedSkeletonErosion b = erosion(RAW, s, 1);
        double maxSmallJump = 0;
        for (int j = 0; j < 4; j++) {
            double lambda = s.wavelengths()[j];
            for (int axis = 0; axis < 2; axis++) {
                for (int k = -2; k <= 0; k++) {
                    double seam = k * lambda * 0.5, along = -1.137 + k * 0.713;
                    double previous = Double.POSITIVE_INFINITY;
                    for (double fraction : new double[]{1e-3, 1e-4, 1e-5}) {
                        double h = lambda * fraction;
                        Sample left = axis == 0 ? b.sampleLayer(j, seam - h, along)
                                : b.sampleLayer(j, along, seam - h);
                        Sample right = axis == 0 ? b.sampleLayer(j, seam + h, along)
                                : b.sampleLayer(j, along, seam + h);
                        bounded(left);
                        bounded(right);
                        double jump = difference(left, right);
                        require(jump <= 64 * fraction + 2e-12, "negative cell seam slope cap");
                        require(jump <= previous * 0.2 + 2e-12, "negative cell seam not converging");
                        previous = jump;
                        if (fraction == 1e-5) maxSmallJump = StrictMath.max(maxSmallJump, jump);
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "  flat=exact_zero negative_seams_max_small_jump=%.3e%n", maxSmallJump);
    }

    private static void checkDagCache() {
        AtomicLong reads = new AtomicLong();
        InheritedSkeletonErosion b = erosion(counted(reads), four(), 1);
        Sample coarse = b.sampleLayer(0, -2.75, 1.125);
        for (int j = 1; j < 4; j++) require(b.descriptorCount(j) == 0, "coarse read a finer cache");
        Sample middle = b.sampleLayer(1, -2.75, 1.125);
        require(b.descriptorCount(2) == 0 && b.descriptorCount(3) == 0, "middle read finer caches");
        b.sampleLayer(3, -2.75, 1.125);
        same(coarse, b.sampleLayer(0, -2.75, 1.125), "fine builds changed coarse output");
        same(middle, b.sampleLayer(1, -2.75, 1.125), "finer builds changed middle output");
        long[] counts = counts(b, 4);
        for (long count : counts) require(count > 0, "fine query omitted a coarse dependency");
        require(Arrays.stream(counts).sum() == b.descriptorCount(), "total/per-layer cache counts");
        require(reads.get() == 25 * b.descriptorCount(), "descriptor stencil rebuilt or raw reread");
        long coldReads = reads.get();
        for (int i = 0; i < 8; i++) snapshot(b, -2.75, 1.125, 4);
        sameCounts(counts, counts(b, 4), "hot cache tree expanded");
        require(reads.get() == coldReads, "hot pixels recomputed the descriptor tree");
        System.out.println("  descriptors_by_layer=" + Arrays.toString(counts) + " raw_reads=" + coldReads);
    }

    private static void checkDeterminism() {
        double[][] pts = points(28, 15);
        AtomicLong reads = new AtomicLong();
        InheritedSkeletonErosion serial = erosion(counted(reads), four(), 1);
        Sample[][] expected = new Sample[pts.length][];
        for (int i = 0; i < pts.length; i++) expected[i] = snapshot(serial, pts[i][0], pts[i][1], 4);
        long[] counts = counts(serial, 4);
        require(reads.get() == 25 * serial.descriptorCount(), "serial constructed descriptors twice");
        for (int i = pts.length - 1; i >= 0; i--) {
            same(expected[i], snapshot(serial, pts[i][0], pts[i][1], 4), "reverse hot");
        }
        sameCounts(counts, counts(serial, 4), "reverse hot footprint");
        serial.clearCache();
        require(serial.descriptorCount() == 0, "clear left cached descriptors");
        reads.set(0);
        for (int i = pts.length - 1; i >= 0; i--) {
            same(expected[i], snapshot(serial, pts[i][0], pts[i][1], 4), "reverse cold after clear");
        }
        sameCounts(counts, counts(serial, 4), "reverse cold footprint");
        require(reads.get() == 25 * serial.descriptorCount(), "cold replay rebuilt descriptors");
        AtomicLong parallelReads = new AtomicLong();
        InheritedSkeletonErosion parallel = erosion(counted(parallelReads), four(), 1);
        IntStream.range(0, pts.length * 3).parallel().forEach(i -> {
            int k = i % pts.length;
            same(expected[k], snapshot(parallel, pts[k][0], pts[k][1], 4), "parallel cold/hot");
        });
        sameCounts(counts, counts(parallel, 4), "parallel footprint");
        require(parallelReads.get() == 25 * parallel.descriptorCount(), "parallel duplicated construction");
        System.out.println("  samples=" + pts.length + " descriptors=" + serial.descriptorCount() + " bitwise=true");
    }

    private static void checkPrepare() {
        checkPrepareBox("uniform", four(), 1, -7.85, -6.25, 8.15, 7.5);
        Settings uneven = settings(89.7, 40.1, 17.3, 7.33);
        checkPrepareBox("nonuniform", uneven, 1, -7.85, -6.25, 8.15, 7.5);
        checkPrepareBox("nonuniform point", uneven, 1, -44.85, -20.05, -44.85, -20.05);
        checkPrepareBox("beta=0 unexpanded", uneven, 0, -7.85, -6.25, 8.15, 7.5);
        checkPrepareBox("single layer", settings(7.33), 1, -3.665, -3.665, 3.665, 3.665);
    }

    private static void checkPrepareBox(String label, Settings s, double beta,
                                        double minX, double minZ, double maxX, double maxZ) {
        int n = s.wavelengths().length;
        AtomicLong reads = new AtomicLong();
        InheritedSkeletonErosion prepared = erosion(counted(reads), s, beta);
        InheritedSkeletonErosion lazy = erosion(RAW, s, beta);
        prepared.prepare(minX, minZ, maxX, maxZ);
        long[] counts = counts(prepared, n);
        long beforeReads = reads.get();
        require(beforeReads == 25 * prepared.descriptorCount(), label + " prepare duplicated descriptors");
        checkFootprint(label, prepared, s, beta, minX, minZ, maxX, maxZ);
        for (int iz = 0; iz <= 8; iz++) {
            for (int ix = 0; ix <= 8; ix++) {
                double x = minX + (maxX - minX) * (ix / 8.0);
                double z = minZ + (maxZ - minZ) * (iz / 8.0);
                same(snapshot(lazy, x, z, n), snapshot(prepared, x, z, n), label + " prepared versus lazy");
            }
        }
        sameCounts(counts, counts(prepared, n), label + " hot bbox expanded cache");
        require(reads.get() == beforeReads, label + " hot bbox reread raw");
        prepared.prepare(minX, minZ, maxX, maxZ);
        sameCounts(counts, counts(prepared, n), label + " repeated prepare footprint");
        require(reads.get() == beforeReads, label + " repeated prepare rebuilt descriptors");
        prepared.clearCache();
        require(prepared.descriptorCount() == 0, label + " clear failed");
        prepared.prepare(minX, minZ, maxX, maxZ);
        sameCounts(counts, counts(prepared, n), label + " clear/reprepare footprint");
        same(snapshot(lazy, minX, minZ, n), snapshot(prepared, minX, minZ, n), label + " clear/reprepare");
        sameCounts(counts, counts(prepared, n), label + " clear/reprepare hot footprint");
        System.out.println("  " + label + " descriptors_by_layer=" + Arrays.toString(counts) + " hot_growth=0");
    }

    /** Independent positive suffix sums equal the all-k<j max recurrence for one or four layers. */
    private static void checkFootprint(String label, InheritedSkeletonErosion b, Settings s, double beta,
                                       double minX, double minZ, double maxX, double maxZ) {
        double[] lambda = s.wavelengths(), req = new double[lambda.length];
        if (beta != 0) {
            for (int j = lambda.length - 2; j >= 0; j--) req[j] = req[j + 1] + 1.5 * lambda[j + 1];
        }
        double scale = StrictMath.max(StrictMath.max(StrictMath.abs(minX), StrictMath.abs(maxX)),
                StrictMath.max(StrictMath.abs(minZ), StrictMath.abs(maxZ)));
        for (int j = 0; j < lambda.length; j++) scale = StrictMath.max(scale, StrictMath.max(lambda[j], req[j]));
        for (int j = 0; j < lambda.length; j++) {
            double margin = req[j];
            if (beta != 0) margin += (lambda.length - 1 - j) * 16.0 * StrictMath.ulp(scale);
            AdaptiveSkeletonErosion support = new AdaptiveSkeletonErosion(SEED, RAW, s);
            support.prepareLayer(j, minX - margin, minZ - margin, maxX + margin, maxZ + margin);
            require(support.descriptorCount() == b.descriptorCount(j), label + " halo footprint layer=" + j);
        }
    }

    private static HeightField counted(AtomicLong reads) {
        return (x, z) -> {
            reads.incrementAndGet();
            return RAW.height(x, z);
        };
    }

    private static long[] counts(InheritedSkeletonErosion b, int n) {
        long[] counts = new long[n];
        for (int j = 0; j < n; j++) counts[j] = b.descriptorCount(j);
        return counts;
    }

    private static Sample[] snapshot(InheritedSkeletonErosion b, double x, double z, int n) {
        Sample[] samples = new Sample[n + 1];
        for (int j = 0; j < n; j++) samples[j] = b.sampleLayer(j, x, z);
        samples[n] = b.sample(x, z);
        for (Sample sample : samples) bounded(sample);
        return samples;
    }

    private static Sample combine(Sample[] layers) {
        double value = 0.0, activity = 0.0, confidence = 0.0, weight = 0.0, gain = 1.0;
        double denominator = Double.POSITIVE_INFINITY;
        for (Sample s : layers) {
            value += gain * s.value();
            activity += gain * s.activity();
            confidence += gain * s.confidence();
            denominator = StrictMath.min(denominator, s.denominatorMin());
            weight += gain;
            gain *= 0.5;
        }
        return new Sample(StrictMath.max(-1.0, StrictMath.min(1.0, value / weight)),
                StrictMath.max(0.0, StrictMath.min(1.0, activity / weight)),
                StrictMath.max(0.0, StrictMath.min(1.0, confidence / weight)), denominator);
    }

    private static double[][] points(int n, double extent) {
        SplittableRandom random = new SplittableRandom(0x189bedL);
        double[][] pts = new double[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = random.nextDouble(-extent, extent);
            pts[i][1] = random.nextDouble(-extent, extent);
        }
        return pts;
    }

    private static void bounded(Sample s) {
        require(Double.isFinite(s.value()) && StrictMath.abs(s.value()) <= 1, "unbounded displacement");
        require(Double.isFinite(s.activity()) && s.activity() >= 0 && s.activity() <= 1, "unbounded activity");
        require(Double.isFinite(s.confidence()) && s.confidence() >= 0 && s.confidence() <= 1, "unbounded confidence");
        require(Double.isFinite(s.denominatorMin())
                && s.denominatorMin() >= AdaptiveSkeletonErosion.DENOMINATOR_LOWER_BOUND, "incomplete support");
    }

    private static void same(Sample[] a, Sample[] b, String label) {
        require(a.length == b.length, label + " channel count");
        for (int j = 0; j < a.length; j++) same(a[j], b[j], label + " layer/combined=" + j);
    }

    private static void same(Sample a, Sample b, String label) {
        same(a.value(), b.value(), label + " value");
        same(a.activity(), b.activity(), label + " activity");
        same(a.confidence(), b.confidence(), label + " confidence");
        same(a.denominatorMin(), b.denominatorMin(), label + " D");
    }

    private static void same(double a, double b, String label) {
        require(bits(a) == bits(b), label + ": " + a + " != " + b);
    }

    private static void sameCounts(long[] a, long[] b, String label) {
        require(Arrays.equals(a, b), label + ": " + Arrays.toString(a) + " != " + Arrays.toString(b));
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

    private static void rejects(Runnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            rejected = true;
        }
        require(rejected, "invalid input accepted");
    }

    private static void require(boolean condition, String label) {
        CHECKS.incrementAndGet();
        if (!condition) throw new AssertionError(label);
    }

    private static void run(String label, Runnable action) {
        long start = System.nanoTime();
        action.run();
        System.out.printf(Locale.ROOT, "PASS %s (%.3f ms)%n", label, (System.nanoTime() - start) / 1e6);
    }
}
