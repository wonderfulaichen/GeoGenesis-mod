package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

/** Full signal shaping experiment. All calibration is OFFLINE, never tied to a render window. */
public final class FastErosionShapingProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final float BASE_AMP = 4e-4f;
    private static final int N = 769, CAL_COUNT = 8192;
    // Offline type calibration [16L,80L]^2, 8192 samples. Never estimated by the sampler.
    private static final double FIXED_F = 0.3308539225627752;
    private static final double FIXED_K = 1.520820748360881;
    private final long seed;
    private final MorphCatalog catalog;
    private final MorphRecipe mountain;
    private final double mainScale, step;
    private double f, k;

    private FastErosionShapingProbe(long seed, double f, double k) {
        this.seed = seed; this.f = f; this.k = k;
        catalog = new MorphCatalog(seed);
        mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
        MorphNoise.Warped w = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) ((MorphNoise.Mul) w.input()).a();
        mainScale = 1.0 / ridge.frequency();
        // Retain preceding scale experiment's physical sampling, not its mistaken wavelength claim.
        step = mainScale / (1165.0 / 9.0);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 2) throw new IllegalArgumentException("calibrate <new-dir> | render <new-dir> <constants.properties>");
        Path out = Path.of(args[1]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        if (args[0].equals("calibrate")) {
            new FastErosionShapingProbe(SEED, 1, 1).calibrate(out);
        } else if (args[0].equals("render") && args.length == 3) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(args[2]))) { p.load(r); }
            double f = Double.parseDouble(p.getProperty("f")), k = Double.parseDouble(p.getProperty("k"));
            require(bits(f, FIXED_F) && bits(k, FIXED_K), "constants differ from locked TYPE values");
            FastErosionShapingProbe probe = new FastErosionShapingProbe(SEED, f, k);
            probe.render(out);
            probe.holdouts(out);
        } else throw new IllegalArgumentException("unknown mode");
    }

    private double raw(double px, double pz) { return mountain.raw(px * step, pz * step); }

    private double[] shapeDerivatives(double px, double pz) {
        double c = raw(px, pz), xp = raw(px + 1, pz), xm = raw(px - 1, pz);
        double zp = raw(px, pz + 1), zm = raw(px, pz - 1);
        return new double[]{(xp - xm) / 2, (zp - zm) / 2, xp + xm + zp + zm - 4 * c};
    }

    private void fields(int px, int pz, float[] out) {
        double[] d = shapeDerivatives(px, pz);
        for (int i = 0; i < 3; i++) out[i] = (float) (d[i] * f * BASE_AMP);
    }

    private double integerE(int px, int pz) {
        float[] e = new float[3];
        FastErosionFilter.erosion(px, pz, this::fields, FastErosionFilter.ORIGINAL, e);
        return e[0];
    }

    /** Same bilinear integer-filter adapter as previous experiments; absolute world-zero phase. */
    private double erosion(double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double tx = px - ix, tz = pz - iz;
        double a = integerE(ix, iz), b = integerE(ix + 1, iz);
        double c = integerE(ix, iz + 1), d = integerE(ix + 1, iz + 1);
        double lo = a + (b - a) * tx, hi = c + (d - c) * tx;
        return lo + (hi - lo) * tz;
    }

    private double shaped(double x, double z) {
        return k * (f * mountain.raw(x, z) + .25 * erosion(x / step, z / step));
    }

    /** Fixed disjoint TYPE calibration domain [16L,80L]^2. Not the negative display window. */
    private double[][] calibrationPoints(int count) {
        Random r = new Random(0x4641535445524f53L);
        double[][] pts = new double[count][2];
        for (double[] p : pts) {
            p[0] = 16 * mainScale / step + r.nextInt((int) (64 * mainScale / step));
            p[1] = 16 * mainScale / step + r.nextInt((int) (64 * mainScale / step));
        }
        return pts;
    }

    private void calibrate(Path out) throws Exception {
        double proto = prototypeSlope(out);
        double[][] pts = calibrationPoints(CAL_COUNT);
        double[] slope = new double[pts.length], src = new double[pts.length], sum = new double[pts.length];
        double[] base = new double[pts.length], inc = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            double[] d = shapeDerivatives(pts[i][0], pts[i][1]);
            slope[i] = StrictMath.hypot(d[0], d[1]);
        }
        double type = median(slope);
        f = Math.min(1, proto / type);
        // k measured at exact production-warped coordinates, with the SAME bilinear adapter as render.
        MorphNoise.Node direction = direction();
        for (int i = 0; i < pts.length; i++) {
            double x = pts[i][0] * step, z = pts[i][1] * step;
            double[] p = domain(direction, x, z);
            src[i] = mountain.raw(p[0], p[1]);
            base[i] = f * src[i]; inc[i] = .25 * erosion(p[0] / step, p[1] / step);
            sum[i] = base[i] + inc[i];
        }
        k = std(src) / std(sum);
        write(out, "type-slopes", slope); write(out, "raw", src);
        write(out, "base", base); write(out, "erosion-increment", inc); write(out, "shape-out", sum);
        Files.writeString(out.resolve("constants.properties"), "# Offline fixed TYPE constants; never recompute per window\nf="
                + f + "\nk=" + k + "\n");
        Files.writeString(out.resolve("calibration.json"), String.format(Locale.ROOT,
                "{\"seed\":%d,\"count\":%d,\"protoSlopeMedian\":%.17g,\"shapeSlopeMedian\":%.17g,"
                + "\"f\":%.17g,\"k\":%.17g,\"rawStd\":%.17g,\"shapeOutStd\":%.17g,\"replayedStd\":%.17g,"
                + "\"baseStd\":%.17g,\"incrementStd\":%.17g,\"erosionStepWu\":%.17g}\n",
                seed, pts.length, proto, type, f, k, std(src), std(sum), std(sum) * k,
                std(base), std(inc), step));
        Files.writeString(out.resolve("calibration-method.txt"),
                "shape=actual catalog raw; NO mean/std normalization of shape. No mean subtracted anywhere.\n"
                + "OFFLINE domain [16L,80L]^2, canonical seed, 8192 java.util.Random integer sample points.\n"
                + "Random seed=0x4641535445524f53L. Display negative window excluded.\n"
                + "s_proto=median analytic gradient of original uncompressed demo base on approved full frame.\n"
                + "Demo baseDerivatives divided by original float BASE_AMP recovers uncompressed gradient.\n"
                + "s_type=median centered gradient of type raw, per integer sample, same O(1) height units.\n"
                + "f=min(1,s_proto/s_type), fixed offline. Filter gets f*raw*4e-4f and its derivatives.\n"
                + "k=std(original raw)/std(f*raw+.25E) on same fixed TYPE calibration domain at production\n"
                + "direction-warped points, matches render bilinear E adapter. k is linear replay only,\n"
                + "NOT dividing shape by std; fixed constants are loaded before render and never recomputed.\n"
                + "Statistics use population std, centered ONLY for measurement, not synthesis.\n"
                + "Sample spacing retained L/(1165/9); note actual demo BASE_FREQ=9 is a bit shift (512),\n"
                + "so this inherited horizontal assumption is NOT a proven original wavelength match.\n");
        System.out.printf("CALIBRATION f=%.17g k=%.17g protoSlope=%.17g typeSlope=%.17g%n", f, k, proto, type);
    }

    private static double prototypeSlope(Path out) throws Exception {
        Class<?> demo = Class.forName("com.geogenesis.standalone.FastErosionOriginalDemo");
        Method base = demo.getDeclaredMethod("baseDerivatives", int.class, int.class, float[].class);
        Method gx = demo.getDeclaredMethod("gridX", float.class), gz = demo.getDeclaredMethod("gridZ", float.class);
        base.setAccessible(true); gx.setAccessible(true); gz.setAccessible(true);
        double[] slopes = new double[1165 * 654]; float[] d = new float[3];
        for (int j = 0; j < 654; j++) for (int i = 0; i < 1165; i++) {
            base.invoke(null, (int) gx.invoke(null, i + .5f), (int) gz.invoke(null, 653 - j + .5f), d);
            slopes[j * 1165 + i] = StrictMath.hypot(d[0] / (double) BASE_AMP, d[1] / (double) BASE_AMP);
        }
        write(out, "prototype-uncompressed-slopes", slopes);
        return median(slopes);
    }

    private MorphNoise.Node direction() {
        int m = MorphCatalog.base(seed + 77);
        return MorphNoise.Perlin.ofScale(m + 3, 10, 1);
    }

    private static double[] domain(MorphNoise.Node direction, double x, double z) {
        double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(angle) * 2, z + Math.cos(angle) * 2};
    }

    private MorphKernel shapingKernel() {
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        MorphRecipe original = recipes[MorphCatalog.IDX_MOUNTAIN];
        recipes[MorphCatalog.IDX_MOUNTAIN] = new MorphRecipe() {
            @Override public double raw(double x, double z) { return shaped(x, z); }
            @Override public double amplitude(double x, double z) { return original.amplitude(x, z); }
            @Override public double bias(double x, double z) { return original.bias(x, z); }
            @Override public Role role() { return original.role(); }
            @Override public boolean clamped() { return original.clamped(); }
        };
        // Entire old fancy erosion branch OFF; raw wrapper shapes directly, NO .65 mixing.
        return new MorphKernel(recipes, seed, false);
    }

    private void render(Path out) throws Exception {
        double outputStep = mainScale / 256;
        int ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        int oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        MorphKernel aKernel = new MorphKernel(MorphLandRecipe.all(catalog), seed, true);
        MorphKernel bKernel = shapingKernel();
        MorphLandField prod = new MorphLandField(catalog);
        double[] w = aKernel.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
        double[][] a = new double[8][N * N];
        String[] names = {"A", "B", "raw-shell", "base-shell", "erosion-shell", "input-slope", "initial-mask", "input-lap"};
        MorphNoise.Node direction = direction();
        float[] d = new float[3];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int at = j * N + i; double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
                a[0][at] = aKernel.sampleLand(x, z, w);
                require(bits(a[0][at], prod.heightForMerged(x, z, w)), "A production mismatch");
                double[] p = domain(direction, x, z);
                double shape = mountain.raw(p[0], p[1]), amp = mountain.amplitude(p[0], p[1]);
                double e = erosion(p[0] / step, p[1] / step);
                a[1][at] = bKernel.sampleLand(x, z, w);
                require(bits(a[1][at], k * (f * shape + .25 * e) * amp), "shaping inserted at wrong stage");
                a[2][at] = shape * amp;
                a[3][at] = k * f * shape * amp;
                a[4][at] = k * .25 * e * amp;
                int px = (int) Math.floor(p[0] / step), pz = (int) Math.floor(p[1] / step);
                fields(px, pz, d);
                float s = (float) StrictMath.hypot(d[0], d[1]) * 262144f * 2f;
                a[5][at] = s;
                float v = s < .1f ? .5f * s * s / .1f : s - .05f;
                float t = 1f - Math.max(0f, Math.min(1f, v));
                a[6][at] = 1f - t * t;
                a[7][at] = d[2] * 262144f / 25f;
            }
            if (j % 128 == 0) System.out.println("shaping row=" + j);
        }
        for (int t = 128; t >= 0; t--) {
            int at = (int) ((long) t * (N * N - 1) / 128);
            double x = (ox + at % N) * outputStep, z = (oz + at / N) * outputStep;
            require(bits(a[1][at], bKernel.sampleLand(x, z, w)), "query-order drift");
        }
        for (int i = 0; i < a.length; i++) write(out, names[i], a[i]);
        Files.writeString(out.resolve("sampling.json"), String.format(Locale.ROOT,
                "{\"seed\":%d,\"size\":%d,\"stepWu\":%.17g,\"spanWu\":%.17g,\"originWu\":[%.17g,%.17g],"
                + "\"erosionStepWu\":%.17g,\"f\":%.17g,\"k\":%.17g,\"groundE\":0.02,\"eSpan\":1.5,\"branchWeight\":1}\n",
                seed, N, outputStep, (N - 1) * outputStep, ox * outputStep, oz * outputStep, step, f, k));
        Files.writeString(out.resolve("verification-java.txt"), "PASS all 591361 A samples bit-identical production onehot\n"
                + "PASS all B samples bit-identical k*(f*raw+.25*E)*shell at production warped coordinates\n"
                + "B uses real kernel with shaped raw wrapper and old mountainTexture=false; no .65 branch\n"
                + "Original direction warp and .7 shell retained. No window statistics read by sampler.\n"
                + "Input mask diagnostics use lower native integer neighbour of actual warped point; first octave only.\n");
    }

    /** Fixed holdout sample domains, no recalibration. Finite evidence, not global invariance. */
    private void holdouts(Path out) throws Exception {
        StringBuilder csv = new StringBuilder("seed,count,rawStd,replayedStd,ratio,fIfReestimated,kIfReplayedWithFixedF,maskFullFraction\n");
        long[] seeds = {SEED, 12345L, -7654321L, 987654321L};
        for (long s : seeds) {
            FastErosionShapingProbe p = new FastErosionShapingProbe(s, f, k);
            Random r = new Random(0x484f4c444f55544cL);
            int n = 2048; double[] raw = new double[n], result = new double[n], sl = new double[n];
            int saturated = 0; MorphNoise.Node dir = p.direction();
            for (int i = 0; i < n; i++) {
                // Negative [-(80L),-(16L)] square disjoint from calibration and displayed window.
                double x = -(16 + 64 * r.nextDouble()) * p.mainScale;
                double z = -(16 + 64 * r.nextDouble()) * p.mainScale;
                double[] pt = domain(dir, x, z);
                raw[i] = p.mountain.raw(pt[0], pt[1]); result[i] = p.shaped(pt[0], pt[1]);
                double[] d = p.shapeDerivatives(pt[0] / p.step, pt[1] / p.step);
                sl[i] = StrictMath.hypot(d[0], d[1]);
                if (sl[i] * f * BASE_AMP * 262144 * 2 >= 1.05) saturated++;
            }
            csv.append(s).append(',').append(n).append(',').append(std(raw)).append(',').append(std(result))
                    .append(',').append(std(result) / std(raw)).append(',')
                    .append(Math.min(1, prototypeSlopeValue() / median(sl))).append(',')
                    .append(k * std(raw) / std(result)).append(',').append(saturated / (double) n).append('\n');
        }
        Files.writeString(out.resolve("holdouts.csv"), csv);
    }

    private static double prototypeSlopeValue() { return .7133510112762451 / (262144 * 2.0 * BASE_AMP); }
    private static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static double median(double[] a) {
        double[] b = a.clone(); Arrays.sort(b); int n = b.length;
        return n % 2 == 0 ? (b[n / 2 - 1] + b[n / 2]) / 2 : b[n / 2];
    }
    private static double std(double[] a) {
        double mean = 0; for (double x : a) mean += x; mean /= a.length;
        double var = 0; for (double x : a) var += (x - mean) * (x - mean);
        return StrictMath.sqrt(var / a.length);
    }
    private static void write(Path out, String name, double[] a) throws Exception {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double x : a) { require(Double.isFinite(x), "nonfinite " + name); d.writeDouble(x); }
        }
    }
}
