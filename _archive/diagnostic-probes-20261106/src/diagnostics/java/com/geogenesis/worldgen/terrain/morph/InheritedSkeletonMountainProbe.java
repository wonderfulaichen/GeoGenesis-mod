package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.AdaptiveSkeletonErosion;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Sample;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;
import com.geogenesis.standalone.InheritedSkeletonErosion;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

/** Stage B only: full mountain input and frozen A parameters; coarse/fine guide inheritance. */
public final class InheritedSkeletonMountainProbe {
    private static final long CANONICAL = MorphCatalog.CANONICAL_SEED;
    private static final double AMPLITUDE = .25, BETA = 1, SHELL = MorphCatalog.MOUNTAIN_AMP;
    private static final double WEIGHT = 1.875;
    private static final double VERTICAL = .15751932417256156;
    private static final double[] ONSET = {1.271470535405616, .31930163808619716,
            .06546449723967929, .01166748617833733};
    private static final double[] EPSILON = {.001271470535405616, 3.193016380861972E-4,
            6.546449723967929E-5, 1.166748617833733E-5};
    private final long seed;
    private final int size, ox, oz;
    private final double length, nativeStep, step;
    private final double[] waves = new double[4];
    private final MorphNoise.Node production, direction;
    private final Settings settings;
    private final AdaptiveSkeletonErosion independent;
    private final InheritedSkeletonErosion inherited, zero;
    private final double[] base, carrier, aHeight, bHeight, zHeight, aE, bE, zE;
    private final double[] aActivity, bActivity, aConfidence, bConfidence, aDenominator, bDenominator;
    private final double[][] aLayers, bLayers;
    private double aPrepareMs, bPrepareMs, zeroPrepareMs, gridMs;
    private long aDescriptors, bDescriptors, zDescriptors;

    private InheritedSkeletonMountainProbe(long seed, int size) {
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
        require(warp.pow() == 150f && modulation.alpha() == .30f, "original warp and modulation");
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
        independent = new AdaptiveSkeletonErosion(seed, this::full, settings);
        inherited = new InheritedSkeletonErosion(seed, this::full, settings, AMPLITUDE, BETA);
        zero = new InheritedSkeletonErosion(seed, this::full, settings, AMPLITUDE, 0);
        int n = size * size;
        base = new double[n]; carrier = new double[n]; aHeight = new double[n]; bHeight = new double[n];
        zHeight = new double[n]; aE = new double[n]; bE = new double[n]; zE = new double[n];
        aActivity = new double[n]; bActivity = new double[n];
        aConfidence = new double[n]; bConfidence = new double[n];
        aDenominator = new double[n]; bDenominator = new double[n];
        aLayers = new double[4][n]; bLayers = new double[4][n];
    }

    private double full(double x, double z) { return MorphNoiseEval.eval(production, x, z); }

    private double[] coordinate(int i) {
        double x = (ox + i % size) * step, z = (oz + i / size) * step;
        double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(angle) * 2, z + Math.cos(angle) * 2};
    }

    private void prepare() {
        double x0 = ox * step - 2, z0 = oz * step - 2;
        double x1 = (ox + size - 1) * step + 2, z1 = (oz + size - 1) * step + 2;
        long start = System.nanoTime();
        independent.prepare(x0, z0, x1, z1);
        aPrepareMs = millis(start);
        aDescriptors = independent.descriptorCount();
        start = System.nanoTime();
        inherited.prepare(x0, z0, x1, z1);
        bPrepareMs = millis(start);
        bDescriptors = inherited.descriptorCount();
        start = System.nanoTime();
        zero.prepare(x0, z0, x1, z1);
        zeroPrepareMs = millis(start);
        zDescriptors = zero.descriptorCount();
        System.out.printf(Locale.ROOT, "PREPARE seed=%d A=%.3fms B=%.3fms zero=%.3fms descriptors=%d/%d/%d%n",
                seed, aPrepareMs, bPrepareMs, zeroPrepareMs, aDescriptors, bDescriptors, zDescriptors);
    }

    private void sampleGrid(Path out) throws IOException {
        long start = System.nanoTime();
        IntStream.range(0, size).parallel().forEach(z -> {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                double[] q = coordinate(i);
                base[i] = full(q[0], q[1]);
                Sample a = independent.sample(q[0], q[1]);
                Sample b = inherited.sample(q[0], q[1]);
                Sample v0 = zero.sample(q[0], q[1]);
                same(a, v0, "beta0 versus independent at real mountain query");
                carrier[i] = base[i] * SHELL;
                aE[i] = a.value(); bE[i] = b.value(); zE[i] = v0.value();
                aHeight[i] = (base[i] + AMPLITUDE * a.value()) * SHELL;
                bHeight[i] = (base[i] + AMPLITUDE * b.value()) * SHELL;
                zHeight[i] = (base[i] + AMPLITUDE * v0.value()) * SHELL;
                aActivity[i] = a.activity(); bActivity[i] = b.activity();
                aConfidence[i] = a.confidence(); bConfidence[i] = b.confidence();
                aDenominator[i] = a.denominatorMin(); bDenominator[i] = b.denominatorMin();
                require(bits(a.denominatorMin(), b.denominatorMin()), "geometry must remain unchanged");
                for (int j = 0; j < 4; j++) {
                    Sample aj = independent.sampleLayer(j, q[0], q[1]);
                    Sample bj = inherited.sampleLayer(j, q[0], q[1]);
                    aLayers[j][i] = aj.value(); bLayers[j][i] = bj.value();
                    if (j == 0) same(aj, bj, "coarsest layer must remain unchanged");
                }
            }
        });
        gridMs = millis(start);
        require(independent.descriptorCount() == aDescriptors, "A prepare support incomplete");
        require(inherited.descriptorCount() == bDescriptors, "B prepare dependency halo incomplete");
        require(zero.descriptorCount() == zDescriptors, "zero prepare support incomplete");
        write(out, "base-raw", base); write(out, "carrier-height", carrier);
        write(out, "independent-height", aHeight); write(out, "inherited-height", bHeight);
        write(out, "zero-height", zHeight); write(out, "independent-unitE", aE);
        write(out, "inherited-unitE", bE); write(out, "zero-unitE", zE);
        write(out, "independent-activity", aActivity); write(out, "inherited-activity", bActivity);
        write(out, "independent-confidence", aConfidence); write(out, "inherited-confidence", bConfidence);
        write(out, "independent-denominator", aDenominator); write(out, "inherited-denominator", bDenominator);
        for (int j = 0; j < 4; j++) {
            write(out, "independent-layer" + j + "-unitE", aLayers[j]);
            write(out, "inherited-layer" + j + "-unitE", bLayers[j]);
        }
        System.out.printf(Locale.ROOT, "GRID seed=%d samples=%d %.3fms beta0/layer0/geometric bits PASS%n",
                seed, base.length, gridMs);
    }

    private void replayChecks(Path out) throws IOException {
        int n = 24;
        InheritedSkeletonErosion cold = new InheritedSkeletonErosion(seed, this::full,
                settings, AMPLITUDE, BETA);
        for (int j = n - 1; j >= 0; j--) checkReplay(cold, Math.floorMod(j * 7919 + 31, base.length));
        cold.clearCache();
        IntStream.range(0, n).parallel().forEach(j ->
                checkReplay(cold, Math.floorMod(j * 7919 + 31, base.length)));
        boolean changed = false;
        for (int i = 0; i < base.length; i++) changed |= !bits(aE[i], bE[i]);
        require(changed, "inheritance must actually change finer layers");
        for (int j = 1; j < 4; j++) {
            boolean layerChanged = false;
            for (int i = 0; i < base.length; i++) layerChanged |= !bits(aLayers[j][i], bLayers[j][i]);
            require(layerChanged, "fine layer must read inherited guide: " + j);
        }
        json(out, "checks.json", "\"sampleCount\":" + base.length
                + ",\"betaZeroDifferentBits\":0,\"layerZeroDifferentBits\":0"
                + ",\"geometryDifferentBits\":0,\"finerLayersChanged\":true"
                + ",\"reverseColdCompared\":24,\"parallelAfterClearCompared\":24"
                + ",\"replayDifferentBits\":0,\"prepareQueryAddedDescriptors\":0"
                + ",\"amplitudeUnchanged\":true,\"mainSourceEdits\":false");
        System.out.println("CHECKS PASS beta0, unchanged layer0, finer layers changed, reverse/cache/parallel");
    }

    private void checkReplay(InheritedSkeletonErosion candidate, int i) {
        double[] q = coordinate(i);
        Sample b = candidate.sample(q[0], q[1]);
        require(bits(b.value(), bE[i]) && bits(b.activity(), bActivity[i])
                && bits(b.confidence(), bConfidence[i]) && bits(b.denominatorMin(), bDenominator[i]),
                "B actual mountain replay must be bitwise");
    }

    private void performance(Path out) throws IOException {
        int n = 32;
        double[][] points = new double[n][2];
        Random rng = new Random(0x42494e4845524954L);
        for (double[] p : points) {
            p[0] = 16 * length + rng.nextInt((int) (64 * length));
            p[1] = 16 * length + rng.nextInt((int) (64 * length));
        }
        AdaptiveSkeletonErosion a = new AdaptiveSkeletonErosion(seed, this::full, settings);
        InheritedSkeletonErosion b = new InheritedSkeletonErosion(seed, this::full, settings, AMPLITUDE, BETA);
        long start = System.nanoTime();
        double sink = 0;
        for (double[] p : points) sink += a.sample(p[0], p[1]).value();
        double coldA = millis(start);
        start = System.nanoTime();
        for (double[] p : points) sink += b.sample(p[0], p[1]).value();
        double coldB = millis(start);
        double[][] batches = new double[2][7];
        for (int batch = 0; batch < 10; batch++) {
            start = System.nanoTime();
            for (double[] p : points) sink += a.sample(p[0], p[1]).value();
            if (batch >= 3) batches[0][batch - 3] = millis(start);
            start = System.nanoTime();
            for (double[] p : points) sink += b.sample(p[0], p[1]).value();
            if (batch >= 3) batches[1][batch - 3] = millis(start);
        }
        for (double[] batch : batches) Arrays.sort(batch);
        require(Double.isFinite(sink), "benchmark sink finite");
        json(out, "performance.json", "\"batchPoints\":" + n
                + ",\"warmupBatches\":3,\"measuredBatches\":7"
                + ",\"independentWarmMedianMs\":" + batches[0][3]
                + ",\"inheritedWarmMedianMs\":" + batches[1][3]
                + ",\"independentColdMs\":" + coldA + ",\"inheritedColdMs\":" + coldB
                + ",\"independentColdDescriptors\":" + a.descriptorCount()
                + ",\"inheritedColdDescriptors\":" + b.descriptorCount()
                + ",\"independentPrepareMs\":" + aPrepareMs + ",\"inheritedPrepareMs\":" + bPrepareMs
                + ",\"independentFullDescriptors\":" + aDescriptors
                + ",\"inheritedFullDescriptors\":" + bDescriptors
                + ",\"zeroPrepareMs\":" + zeroPrepareMs + ",\"gridMs\":" + gridMs
                + ",\"scope\":\"pure CPU type-space; cold costs include cached finite DAG construction, not real chunks\"");
        System.out.printf(Locale.ROOT, "PERF %dpts A/B warmMedian %.4f/%.4fms cold %.4f/%.4fms%n",
                n, batches[0][3], batches[1][3], coldA, coldB);
    }

    private void metadata(Path out) throws IOException {
        json(out, "sampling.json", "\"size\":" + size + ",\"originWu\":"
                + Arrays.toString(new double[]{ox * step, oz * step}) + ",\"stepWu\":" + step
                + ",\"nativeStepWu\":" + nativeStep + ",\"spanWu\":" + ((size - 1) * step)
                + ",\"HS\":2,\"amplitude\":0.25,\"shellAmplitude\":" + SHELL
                + ",\"k\":1,\"beta\":1,\"seed\":" + seed + ",\"typeLengthWu\":" + length
                + ",\"wavelengthsWu\":" + Arrays.toString(waves)
                + ",\"baseDefinition\":\"unchanged ridge4 x scaler4 x original warp350/150\""
                + ",\"outerDomain\":\"unchanged 2wu direction-domain mapping\""
                + ",\"actualMinecraftWorld\":false");
        json(out, "settings.json", "\"V\":" + VERTICAL + ",\"t_on\":" + Arrays.toString(ONSET)
                + ",\"epsilon\":" + Arrays.toString(EPSILON) + ",\"chi\":0.25"
                + ",\"amplitude\":0.25,\"gain\":0.5,\"gainSum\":" + WEIGHT
                + ",\"beta\":1,\"k\":1,\"wavelengthsWu\":" + Arrays.toString(waves)
                + ",\"parameterSource\":\"A frozen calibration; NO recalibration for B or holdout\""
                + ",\"guide\":\"F_j=H0+beta*.25/1.875*sum_{k<j}(.5^k*E_k^B)\""
                + ",\"guideOnlyInheritance\":true,\"extraOutputDisplacement\":false");
    }

    private void run(Path out, boolean benchmark) throws IOException {
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        prepare();
        sampleGrid(out);
        replayChecks(out);
        metadata(out);
        if (benchmark) performance(out);
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        require(args.length == 1, "usage: <NEW data directory>");
        Path out = Path.of(args[0]);
        new InheritedSkeletonMountainProbe(CANONICAL, 769).run(out, true);
        new InheritedSkeletonMountainProbe(12345L, 257).run(out.resolve("holdout"), false);
        System.out.println("STAGE B PASS: unchanged full noise, settings, scales and output amplitude; diagnostics only");
    }

    private static double millis(long start) { return (System.nanoTime() - start) / 1e6; }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static void same(Sample a, Sample b, String message) {
        require(bits(a.value(), b.value()) && bits(a.activity(), b.activity())
                && bits(a.confidence(), b.confidence()) && bits(a.denominatorMin(), b.denominatorMin()), message);
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void json(Path out, String name, String body) throws IOException {
        Files.writeString(out.resolve(name), "{\n" + body + "\n}\n");
    }
    private static void write(Path out, String name, double[] values) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double value : values) {
                require(Double.isFinite(value), "nonfinite array " + name);
                stream.writeDouble(value);
            }
        }
    }
}
