package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.AdaptiveSkeletonErosion;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Sample;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Tensor;
import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

/** Stage A: isolated full-input mountain type; no production wiring or layer inheritance. */
public final class AdaptiveSkeletonMountainProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final int SIZE = 769, PAD = 5, CALIBRATION = 8192;
    private static final int NATIVE_SIZE = 2 * (SIZE - 1) + 1 + 2 * PAD;
    private static final float OLD_COMP = 4e-4f;
    private static final double AMPLITUDE = .25, CHI = .25;
    private static final double SHELL = MorphCatalog.MOUNTAIN_AMP;
    private final MorphCatalog catalog = new MorphCatalog(SEED);
    private final MorphNoise.Node production = catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
    private final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphNoise.Node direction = MorphNoise.Perlin.ofScale(
            MorphCatalog.base(SEED + MorphCatalog.OFF_MOUNTAIN) + 3, 10, 1);
    private final double length, nativeStep, outputStep;
    private final int ox, oz, nx, nz;
    private final double[] waves = new double[4];
    private double[] nativeHeight, nativeE;
    private AdaptiveSkeletonErosion adaptive;
    private Settings settings;
    private final double[][] calPoints = new double[CALIBRATION][2];
    private final double[][] heights = new double[3][SIZE * SIZE];
    private final double[] raw = new double[SIZE * SIZE], originalE = new double[SIZE * SIZE];
    private final double[] adaptiveE = new double[SIZE * SIZE], oldMask = new double[SIZE * SIZE];
    private final double[] activity = new double[SIZE * SIZE], confidence = new double[SIZE * SIZE];
    private final double[] denominator = new double[SIZE * SIZE];
    private double prepareMillis, oldGridMillis, newGridMillis, refineMillis;
    private long preparedDescriptors;

    private AdaptiveSkeletonMountainProbe() {
        require(production instanceof MorphNoise.Warped, "production outer warp");
        MorphNoise.Warped warp = (MorphNoise.Warped) production;
        MorphNoise.Mul body = (MorphNoise.Mul) warp.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix modulation = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin scaler = (MorphNoise.Perlin) modulation.input();
        require(ridge.octaves() == 4 && scaler.octaves() == 4, "full ridge4/scaler4 required");
        require(warp.pow() == 150f && modulation.alpha() == .30f, "production parameters changed");
        length = 1.0 / ridge.frequency();
        nativeStep = length / 512;
        outputStep = 2 * nativeStep;
        ox = (int) Math.round(-3242.5 / outputStep) - SIZE / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - SIZE / 2;
        nx = 2 * ox - PAD;
        nz = 2 * oz - PAD;
        for (int j = 0; j < waves.length; j++) {
            double f = 21.7 + j * 1.2;
            waves[j] = (1 << Math.max(28 - (int) Math.floor(f), 0))
                    * nativeStep / (1 + f - Math.floor(f));
        }
        Random rng = new Random((long) (double) 0x4641535445524f53L);
        for (double[] p : calPoints) {
            double[] q = domain(16 * length + rng.nextInt((int) (64 * length)),
                    16 * length + rng.nextInt((int) (64 * length)));
            p[0] = q[0];
            p[1] = q[1];
        }
    }

    private double full(double x, double z) {
        return MorphNoiseEval.eval(production, x, z);
    }

    private double[] domain(double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
    }

    private void calibrate(Path out) throws IOException {
        double[] h = new double[CALIBRATION];
        for (int i = 0; i < h.length; i++) h[i] = full(calPoints[i][0], calPoints[i][1]);
        double vertical = std(h);
        double[] onset = new double[4], epsilon = new double[4];
        for (int j = 0; j < waves.length; j++) {
            double[] energy = new double[1024];
            for (int i = 0; i < energy.length; i++) {
                Tensor tensor = AdaptiveSkeletonErosion.analyze(this::full,
                        calPoints[i][0], calPoints[i][1], waves[j], vertical);
                energy[i] = tensor.xx() + tensor.zz();
            }
            Arrays.sort(energy);
            onset[j] = (energy[511] + energy[512]) * .5;
            require(onset[j] > 0 && Double.isFinite(onset[j]), "nonzero calibration energy");
            epsilon[j] = .001 * onset[j];
        }
        settings = new Settings(waves, onset, epsilon, CHI, vertical);
        adaptive = new AdaptiveSkeletonErosion(SEED, this::full, settings);
        json(out, "calibration.json", "\"V\":" + vertical + ",\"t_on\":" + array(onset)
                + ",\"epsilon\":" + array(epsilon) + ",\"chi\":" + CHI
                + ",\"heightSamples\":8192,\"tensorCentersPerLayer\":1024,\"k\":1"
                + ",\"rule\":\"V=raw std; onset=median tensor trace; epsilon=.001 onset; no image fit\""
                + ",\"domain\":\"[16L,80L)^2, canonical seed; old RNG cast preserved; original 2wu domain\""
                + ",\"newLayerFeedback\":false");
        System.out.printf(Locale.ROOT, "CALIBRATION V=%.12g onset=%s epsilon=%s k=1 A=.25%n",
                vertical, Arrays.toString(onset), Arrays.toString(epsilon));
    }

    private double nativeAt(int px, int pz) {
        int x = px - nx, z = pz - nz;
        if (nativeHeight != null && x >= 0 && z >= 0 && x < NATIVE_SIZE && z < NATIVE_SIZE) {
            return nativeHeight[z * NATIVE_SIZE + x];
        }
        return full(px * nativeStep, pz * nativeStep);
    }

    private void fields(int px, int pz, float[] target) {
        double center = nativeAt(px, pz), xp = nativeAt(px + 1, pz), xm = nativeAt(px - 1, pz);
        double zp = nativeAt(px, pz + 1), zm = nativeAt(px, pz - 1);
        target[0] = (float) (((xp - xm) / 2) * OLD_COMP);
        target[1] = (float) (((zp - zm) / 2) * OLD_COMP);
        target[2] = (float) ((xp + xm + zp + zm - 4 * center) * OLD_COMP);
    }

    private void oldGrid() {
        long start = System.nanoTime();
        nativeHeight = new double[NATIVE_SIZE * NATIVE_SIZE];
        IntStream.range(0, NATIVE_SIZE).parallel().forEach(z -> {
            for (int x = 0; x < NATIVE_SIZE; x++) {
                nativeHeight[z * NATIVE_SIZE + x] = full((nx + x) * nativeStep, (nz + z) * nativeStep);
            }
        });
        nativeE = new double[nativeHeight.length];
        IntStream.range(2, NATIVE_SIZE - 2).parallel().forEach(z -> {
            float[] e = new float[3];
            for (int x = 2; x < NATIVE_SIZE - 2; x++) {
                FastErosionFilter.erosion(nx + x, nz + z, this::fields, FastErosionFilter.ORIGINAL, e);
                nativeE[z * NATIVE_SIZE + x] = e[0];
            }
        });
        oldGridMillis = millis(start);
        System.out.printf(Locale.ROOT, "ORIGINAL native=%dx%d complete ridge4/scaler4 %.3f ms%n",
                NATIVE_SIZE, NATIVE_SIZE, oldGridMillis);
    }

    private double oldAt(double x, double z) {
        double sx = x / nativeStep, sz = z / nativeStep;
        int px = (int) Math.floor(sx), pz = (int) Math.floor(sz);
        int ix = px - nx, iz = pz - nz;
        require(ix >= 2 && iz >= 2 && ix + 1 < NATIVE_SIZE - 2 && iz + 1 < NATIVE_SIZE - 2,
                "old E must have true procedural halo");
        double tx = sx - px, tz = sz - pz;
        double lo = nativeE[iz * NATIVE_SIZE + ix]
                + (nativeE[iz * NATIVE_SIZE + ix + 1] - nativeE[iz * NATIVE_SIZE + ix]) * tx;
        double hi = nativeE[(iz + 1) * NATIVE_SIZE + ix]
                + (nativeE[(iz + 1) * NATIVE_SIZE + ix + 1] - nativeE[(iz + 1) * NATIVE_SIZE + ix]) * tx;
        return lo + (hi - lo) * tz;
    }

    private double initialOldMask(double x, double z) {
        float[] f = new float[3];
        fields((int) Math.floor(x / nativeStep), (int) Math.floor(z / nativeStep), f);
        double slope = StrictMath.hypot(f[0], f[1]) * 262144 * 2;
        double v = slope < .1 ? .5 * slope * slope / .1 : slope - .05;
        double t = 1 - Math.max(0, Math.min(1, v));
        return 1 - t * t;
    }

    private void mountainGrid(Path out) throws IOException {
        long start = System.nanoTime();
        adaptive.prepare(ox * outputStep - 2, oz * outputStep - 2,
                (ox + SIZE - 1) * outputStep + 2, (oz + SIZE - 1) * outputStep + 2);
        prepareMillis = millis(start);
        preparedDescriptors = adaptive.descriptorCount();
        start = System.nanoTime();
        IntStream.range(0, SIZE).parallel().forEach(z -> {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                double[] q = domain((ox + x) * outputStep, (oz + z) * outputStep);
                raw[i] = mountain.raw(q[0], q[1]);
                require(bits(raw[i], full(q[0], q[1])), "complete production raw identity");
                Sample sample = adaptive.sample(q[0], q[1]);
                originalE[i] = oldAt(q[0], q[1]);
                adaptiveE[i] = sample.value();
                heights[0][i] = raw[i] * SHELL;
                heights[1][i] = (raw[i] + AMPLITUDE * originalE[i]) * SHELL;
                heights[2][i] = (raw[i] + AMPLITUDE * sample.value()) * SHELL;
                oldMask[i] = initialOldMask(q[0], q[1]);
                activity[i] = sample.activity();
                confidence[i] = sample.confidence();
                denominator[i] = sample.denominatorMin();
                require(bits(heights[0][i], (raw[i] + 0 * sample.value()) * SHELL), "zero amplitude");
            }
        });
        newGridMillis = millis(start);
        require(adaptive.descriptorCount() == preparedDescriptors, "prepare missed query support");
        write(out.resolve("base-raw.f64be"), raw);
        for (int j = 0; j < 3; j++) write(out.resolve(arm(j) + "-height.f64be"), heights[j]);
        write(out.resolve("original-unitE.f64be"), originalE);
        write(out.resolve("adaptive-unitE.f64be"), adaptiveE);
        write(out.resolve("original-mask.f64be"), oldMask);
        write(out.resolve("adaptive-activity.f64be"), activity);
        write(out.resolve("adaptive-confidence.f64be"), confidence);
        write(out.resolve("adaptive-denominator.f64be"), denominator);
        System.out.printf(Locale.ROOT, "GRID %d samples raw identity PASS, prepare %.3f ms descriptors=%d sample %.3f ms%n",
                raw.length, prepareMillis, preparedDescriptors, newGridMillis);
    }

    private void replayChecks(Path out) throws IOException {
        int count = 128;
        AdaptiveSkeletonErosion cold = new AdaptiveSkeletonErosion(SEED, this::full, settings);
        int[] indices = new int[count];
        for (int j = 0; j < count; j++) indices[j] = Math.floorMod(j * 7919 + 31, raw.length);
        for (int j = count - 1; j >= 0; j--) checkReplay(cold, indices[j]);
        cold.clearCache();
        IntStream.range(0, count).parallel().forEach(j -> checkReplay(cold, indices[j]));
        json(out, "checks.json", "\"fullRawCompared\":" + raw.length
                + ",\"fullRawDifferentBits\":0,\"zeroAmplitudeDifferentBits\":0"
                + ",\"reverseColdCompared\":128,\"parallelAfterClearCompared\":128,\"replayDifferentBits\":0"
                + ",\"singleLayerSyntheticChecks\":\"AdaptiveSkeletonCheck main, logged separately\""
                + ",\"noCoarseFineFeedback\":true,\"mainSourceEdits\":false");
        System.out.println("CHECKS PASS complete raw, zero amplitude, reverse cold, parallel after clear");
    }

    private void checkReplay(AdaptiveSkeletonErosion candidate, int i) {
        double[] q = domain((ox + i % SIZE) * outputStep, (oz + i / SIZE) * outputStep);
        Sample s = candidate.sample(q[0], q[1]);
        require(bits(s.value(), adaptiveE[i]) && bits(s.activity(), activity[i])
                && bits(s.confidence(), confidence[i]) && bits(s.denominatorMin(), denominator[i]),
                "actual mountain replay bits");
    }

    private void refine(Path out) throws IOException {
        long start = System.nanoTime();
        int n = 129;
        int ix0 = (int) Math.floor(1900 / (outputStep * 2)) - 32;
        int iz0 = (int) Math.floor(1855 / (outputStep * 2)) - 32;
        double[][] h = new double[3][n * n];
        IntStream.range(0, n).parallel().forEach(z -> {
            for (int x = 0; x < n; x++) {
                double[] q = domain((2 * (ox + ix0) + x) * nativeStep,
                        (2 * (oz + iz0) + z) * nativeStep);
                double base = full(q[0], q[1]);
                h[0][z * n + x] = base * SHELL;
                h[1][z * n + x] = (base + AMPLITUDE * oldAt(q[0], q[1])) * SHELL;
                h[2][z * n + x] = (base + AMPLITUDE * adaptive.sample(q[0], q[1]).value()) * SHELL;
            }
        });
        for (int j = 0; j < 3; j++) {
            for (int z = 0; z < n; z += 2) for (int x = 0; x < n; x += 2) {
                require(bits(h[j][z * n + x], heights[j][(iz0 + z / 2) * SIZE + ix0 + x / 2]),
                        "refinement anchors must be bitwise identical");
            }
            write(out.resolve("refinement-" + arm(j) + "-height.f64be"), h[j]);
        }
        refineMillis = millis(start);
        json(out, "refinement.json", "\"size\":129,\"stepWu\":" + nativeStep
                + ",\"originLocalBlocks\":" + array(new double[]{ix0 * outputStep * 2, iz0 * outputStep * 2})
                + ",\"mainGridIndices\":[" + ix0 + "," + iz0 + "]"
                + ",\"coarseVertexDifferentBits\":0,\"verticesComparedPerArm\":4225"
                + ",\"oldNativeStepWu\":" + nativeStep + ",\"algorithmParametersChanged\":false");
        System.out.println("REFINEMENT PASS 3 x 4225 main-grid anchors; step halved, algorithm unchanged");
    }

    private void performance(Path out) throws IOException {
        double[][] points = new double[64][2];
        for (int i = 0; i < points.length; i++) points[i] = calPoints[2048 + i];
        AdaptiveSkeletonErosion bench = new AdaptiveSkeletonErosion(SEED, this::full, settings);
        long start = System.nanoTime();
        double sink = 0;
        for (double[] p : points) sink += bench.sample(p[0], p[1]).value();
        double coldMillis = millis(start);
        long coldCount = bench.descriptorCount();
        double[][] batches = new double[3][7];
        for (int batch = 0; batch < 10; batch++) {
            for (int kind = 0; kind < 3; kind++) {
                start = System.nanoTime();
                for (double[] p : points) {
                    sink += kind == 0 ? full(p[0], p[1]) : kind == 1
                            ? oldDirect(p[0], p[1]) : bench.sample(p[0], p[1]).value();
                }
                if (batch >= 3) batches[kind][batch - 3] = millis(start);
            }
        }
        for (double[] b : batches) Arrays.sort(b);
        require(Double.isFinite(sink), "performance sink finite");
        json(out, "performance.json", "\"batchPoints\":64,\"warmupBatches\":3,\"measuredBatches\":7"
                + ",\"completeRawMedianMs\":" + batches[0][3]
                + ",\"originalDirectMedianMs\":" + batches[1][3]
                + ",\"adaptiveWarmMedianMs\":" + batches[2][3]
                + ",\"adaptiveColdMs\":" + coldMillis + ",\"adaptiveColdDescriptors\":" + coldCount
                + ",\"adaptiveColdHeightCalls\":" + (25 * coldCount)
                + ",\"fullWindowPrepareMs\":" + prepareMillis + ",\"fullWindowDescriptors\":" + preparedDescriptors
                + ",\"originalNativeHeightAndEGridMs\":" + oldGridMillis
                + ",\"combinedThreeArmSamplingMs\":" + newGridMillis + ",\"refinementMs\":" + refineMillis
                + ",\"oldDirectMeaning\":\"full procedural derivatives; bilinear four native E queries, same adapter as grid\""
                + ",\"scope\":\"isolated CPU type-space, not chunk generation throughput or production memory benchmark\"");
        System.out.printf(Locale.ROOT, "PERF 64pts carrier/originalDirect/adaptiveWarm medianMs %.4f %.4f %.4f coldMs %.4f%n",
                batches[0][3], batches[1][3], batches[2][3], coldMillis);
    }

    private double oldDirect(double x, double z) {
        int px = (int) Math.floor(x / nativeStep), pz = (int) Math.floor(z / nativeStep);
        double tx = x / nativeStep - px, tz = z / nativeStep - pz;
        double[] e = new double[4];
        float[] s = new float[3];
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            FastErosionFilter.erosion(px + i, pz + j, this::fields, FastErosionFilter.ORIGINAL, s);
            e[2 * j + i] = s[0];
        }
        double lo = e[0] + (e[1] - e[0]) * tx, hi = e[2] + (e[3] - e[2]) * tx;
        return lo + (hi - lo) * tz;
    }

    private void sampling(Path out) throws IOException {
        json(out, "sampling.json", "\"size\":" + SIZE + ",\"originWu\":"
                + array(new double[]{ox * outputStep, oz * outputStep})
                + ",\"stepWu\":" + outputStep + ",\"nativeStepWu\":" + nativeStep
                + ",\"oldNativeStepWu\":" + nativeStep + ",\"spanWu\":" + ((SIZE - 1) * outputStep)
                + ",\"HS\":2,\"amplitude\":0.25,\"shellAmplitude\":" + SHELL + ",\"k\":1"
                + ",\"seed\":" + SEED + ",\"typeLengthWu\":" + length + ",\"wavelengthsWu\":" + array(waves)
                + ",\"baseDefinition\":\"catalog raw ridge4 x scaler4 x original warp350/150, same full field for derivatives\""
                + ",\"outerDomain\":\"unchanged 2wu direction domain before all three arms\""
                + ",\"newLayerFeedback\":false,\"actualMinecraftWorld\":false");
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        require(args.length == 1, "usage: <NEW output data directory>");
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        AdaptiveSkeletonMountainProbe p = new AdaptiveSkeletonMountainProbe();
        p.calibrate(out);
        p.oldGrid();
        p.mountainGrid(out);
        p.replayChecks(out);
        p.refine(out);
        p.performance(out);
        p.sampling(out);
        System.out.println("STAGE A PASS: complete input retained; k=1 all arms; diagnostics only; stop after images");
    }

    private static String arm(int j) { return new String[]{"carrier", "original", "adaptive"}[j]; }
    private static double millis(long start) { return (System.nanoTime() - start) / 1e6; }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static double std(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        double mean = sum / values.length, squares = 0;
        for (double v : values) squares += (v - mean) * (v - mean);
        return StrictMath.sqrt(squares / values.length);
    }
    private static String array(double[] values) { return Arrays.toString(values); }
    private static void json(Path out, String name, String body) throws IOException {
        Files.writeString(out.resolve(name), "{\n" + body + "\n}\n");
    }
    private static void write(Path file, double[] values) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (double v : values) {
                require(Double.isFinite(v), "nonfinite output: " + file);
                out.writeDouble(v);
            }
        }
    }
}
