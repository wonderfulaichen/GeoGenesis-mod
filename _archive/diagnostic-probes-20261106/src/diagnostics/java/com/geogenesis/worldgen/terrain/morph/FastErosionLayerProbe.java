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

/**
 * Single-variable comparison: how much base spectrum is kept before erosion shapes it.
 *
 * <p>Only the base construction differs between arms. Erosion input compression, ORIGINAL parameters,
 * the .25 share, the ×k replay, the .7 shell, the sample spacing, the window and arm A are identical.</p>
 */
public final class FastErosionLayerProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final float BASE_AMP = 4e-4f;
    private static final int N = 769, CAL_COUNT = 8192;
    /** Same offline domain as the previous shaping round; never the display window. */
    private static final double RANDOM_SEED = 0x4641535445524f53L;

    private final long seed;
    private final MorphCatalog catalog;
    private final MorphRecipe mountain;
    private final MorphNoise.Node productionRaw, l1, l2;
    private final double mainScale, step;

    private FastErosionLayerProbe(long seed) {
        this.seed = seed;
        catalog = new MorphCatalog(seed);
        mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
        productionRaw = catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        // Real recipe structure: Warped(Mul(PerlinRidge, AlphaMix(Perlin)), seed=b+2)
        MorphNoise.Warped warped = (MorphNoise.Warped) productionRaw;
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix scaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin scalerNoise = (MorphNoise.Perlin) scaler.input();
        require(ridge.octaves() == 4 && ridge.frequency() > 0, "unexpected ridge");
        require(scalerNoise.octaves() == 4, "unexpected scaler");
        mainScale = 1.0 / ridge.frequency();

        // L1: the 610wu ridge only, ONE octave, no scaler at all. Keeps the warp below.
        MorphNoise.Node r1 = new MorphNoise.PerlinRidge(ridge.seed(), ridge.frequency(), 1,
                ridge.lacunarity(), ridge.gain());
        l1 = MorphNoise.warp(r1, warped.wx(), warped.wz(), warped.pow());

        // L2: two ridge octaves times the two-octave scaler modulation (original depth 0.30).
        MorphNoise.Node r2 = new MorphNoise.PerlinRidge(ridge.seed(), ridge.frequency(), 2,
                ridge.lacunarity(), ridge.gain());
        MorphNoise.Node s2 = new MorphNoise.AlphaMix(
                new MorphNoise.Perlin(scalerNoise.seed(), scalerNoise.frequency(), 2,
                        scalerNoise.lacunarity(), scalerNoise.gain()),
                scaler.alpha());
        l2 = MorphNoise.warp(new MorphNoise.Mul(r2, s2), warped.wx(), warped.wz(), warped.pow());

        // Spacing inherited unchanged from the preceding scale experiment.
        step = mainScale / (1165.0 / 9.0);
        this.ridgeOctaves = ridge.octaves();
        this.scalerOctaves = scalerNoise.octaves();
        this.warpPower = warped.pow();
    }

    private final int ridgeOctaves, scalerOctaves;
    private final float warpPower;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 3) throw new IllegalArgumentException(
                "calibrate <outDir> <L1|L2> | render <outDir> <constants-map.properties>");
        Path out = Path.of(args[1]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionLayerProbe probe = new FastErosionLayerProbe(SEED);
        if (args[0].equals("calibrate")) {
            probe.calibrate(out, args[2]);
        } else if (args[0].equals("render")) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(args[2]))) { p.load(r); }
            probe.render(out, p);
        } else throw new IllegalArgumentException("unknown mode");
    }

    private double base(String arm, double x, double z) {
        return MorphNoiseEval.eval(arm.equals("L1") ? l1 : l2, x, z);
    }

    private double[] shapeDerivatives(String arm, double px, double pz) {
        double c = base(arm, px * step, pz * step);
        double xp = base(arm, (px + 1) * step, pz * step), xm = base(arm, (px - 1) * step, pz * step);
        double zp = base(arm, px * step, (pz + 1) * step), zm = base(arm, px * step, (pz - 1) * step);
        return new double[]{(xp - xm) / 2, (zp - zm) / 2, xp + xm + zp + zm - 4 * c};
    }

    private double f, k;
    private double fields(String arm, int px, int pz, float[] out) {
        double[] d = shapeDerivatives(arm, px, pz);
        for (int i = 0; i < 3; i++) out[i] = (float) (d[i] * f * BASE_AMP);
        return d[2];
    }

    private double integerE(String arm, int px, int pz) {
        float[] e = new float[3];
        FastErosionFilter.erosion(px, pz, (i, j, dst) -> fields(arm, i, j, dst),
                FastErosionFilter.ORIGINAL, e);
        return e[0];
    }

    private double erosion(String arm, double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double tx = px - ix, tz = pz - iz;
        double a = integerE(arm, ix, iz), b = integerE(arm, ix + 1, iz);
        double c = integerE(arm, ix, iz + 1), d = integerE(arm, ix + 1, iz + 1);
        double lo = a + (b - a) * tx, hi = c + (d - c) * tx;
        return lo + (hi - lo) * tz;
    }

    private double shaped(String arm, double x, double z) {
        return k * (f * base(arm, x, z) + .25 * erosion(arm, x / step, z / step));
    }

    private double[][] calibrationPoints(int count) {
        Random r = new Random((long) RANDOM_SEED);
        double[][] pts = new double[count][2];
        for (double[] p : pts) {
            p[0] = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            p[1] = 16 * mainScale + r.nextInt((int) (64 * mainScale));
        }
        return pts;
    }

    private void calibrate(Path out, String arm) throws Exception {
        double proto = prototypeSlope(out);
        double[][] pts = calibrationPoints(CAL_COUNT);
        double[] slope = new double[pts.length], src = new double[pts.length];
        double[] baseV = new double[pts.length], inc = new double[pts.length], sum = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            double[] d = shapeDerivatives(arm, pts[i][0] / step, pts[i][1] / step);
            slope[i] = StrictMath.hypot(d[0], d[1]);
        }
        f = Math.min(1, proto / median(slope));
        MorphNoise.Node direction = direction();
        for (int i = 0; i < pts.length; i++) {
            double[] p = domain(direction, pts[i][0], pts[i][1]);
            src[i] = mountain.raw(p[0], p[1]);
            baseV[i] = f * base(arm, p[0], p[1]);
            inc[i] = .25 * erosion(arm, p[0] / step, p[1] / step);
            sum[i] = baseV[i] + inc[i];
        }
        k = std(src) / std(sum);
        write(out, "type-slopes", slope); write(out, "production-raw", src);
        write(out, "base", baseV); write(out, "erosion-increment", inc); write(out, "shape-out", sum);
        Files.writeString(out.resolve("constants.properties"),
                "# Offline " + arm + " constants; never recomputed per window\narm=" + arm
                        + "\nf=" + f + "\nk=" + k + "\n");
        Files.writeString(out.resolve("calibration.json"), String.format(Locale.ROOT,
                "{\"arm\":\"%s\",\"seed\":%d,\"count\":%d,\"protoSlopeMedian\":%.17g,"
                        + "\"baseSlopeMedian\":%.17g,\"f\":%.17g,\"k\":%.17g,\"productionRawStd\":%.17g,"
                        + "\"shapeOutStd\":%.17g,\"replayedStd\":%.17g,\"baseStd\":%.17g,"
                        + "\"incrementStd\":%.17g,\"erosionStepWu\":%.17g}\n",
                arm, seed, pts.length, proto, median(slope), f, k, std(src), std(sum),
                std(sum) * k, std(baseV), std(inc), step));
        Files.writeString(out.resolve("layers.txt"), describe(arm));
        System.out.printf("CALIBRATION %s f=%.17g k=%.17g baseSlope=%.17g%n", arm, f, k, median(slope));
    }

    private String describe(String arm) {
        String kept = arm.equals("L1")
                ? "PerlinRidge(scale=610wu, octaves=1, lac=2.35, gain=1.15); NO scaler modulation"
                : "PerlinRidge(scale=610wu, octaves=2) x AlphaMix(Perlin(scale=180wu, octaves=2), .30)";
        return "PRODUCTION RAW: ridge octaves=" + ridgeOctaves + ", scaler octaves=" + scalerOctaves
                + ", warpPerlin(pow=" + warpPower + ")\n"
                + "  ridge scale=610wu lac=2.35 gain=1.15; scaler scale=180wu alpha=.30\n"
                + "  => finest retained ridge wavelength ~610/2.35^3="
                + String.format(Locale.ROOT, "%.4f", 610 / Math.pow(2.35, ridgeOctaves - 1)) + "wu\n"
                + "  => finest retained scaler wavelength ~180/2^3="
                + String.format(Locale.ROOT, "%.4f", 180 / Math.pow(2, scalerOctaves - 1)) + "wu\n"
                + arm + " BASE: " + kept + "\n"
                + (arm.equals("L1")
                ? "  dropped vs production: ridge octaves 2..4 (wavelengths ~259.6/110.5/47.0wu) and ALL\n"
                + "  4 scaler octaves (~180/90/45/22.5wu). Base is the single 610wu ridge.\n"
                : "  dropped vs production: ridge octaves 3..4 (~110.5/47.0wu) and scaler octaves 3..4\n"
                + "  (~45/22.5wu). Base keeps 610/259.6wu ridges and 180/90wu modulation.\n")
                + "  KEPT IN BOTH: the original warpPerlin(pow=" + warpPower + ") so L1/L2 differ from\n"
                + "  production only in ridge/scaler octave count, not in warp.\n"
                + "  Multiplicative scaler keeps its depth .30 (not re-tuned for fewer octaves).\n";
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
        return MorphNoise.Perlin.ofScale(MorphCatalog.base(seed + 77) + 3, 10, 1);
    }

    private static double[] domain(MorphNoise.Node direction, double x, double z) {
        double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(angle) * 2, z + Math.cos(angle) * 2};
    }

    private MorphKernel shapingKernel(String arm) {
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        MorphRecipe original = recipes[MorphCatalog.IDX_MOUNTAIN];
        recipes[MorphCatalog.IDX_MOUNTAIN] = new MorphRecipe() {
            @Override public double raw(double x, double z) { return shaped(arm, x, z); }
            @Override public double amplitude(double x, double z) { return original.amplitude(x, z); }
            @Override public double bias(double x, double z) { return original.bias(x, z); }
            @Override public Role role() { return original.role(); }
            @Override public boolean clamped() { return original.clamped(); }
        };
        return new MorphKernel(recipes, seed, false);
    }

    private void render(Path out, Properties p) throws Exception {
        String[] arms = {"L1", "L2"};
        MorphKernel aKernel = new MorphKernel(MorphLandRecipe.all(catalog), seed, true);
        MorphLandField prod = new MorphLandField(catalog);
        double[] w = aKernel.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
        double outputStep = mainScale / 256;
        int ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        int oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        MorphNoise.Node direction = direction();

        double[] fUsed = new double[arms.length], kUsed = new double[arms.length];
        double[] a = new double[N * N], rawShell = new double[N * N];
        double[][][] armsOut = new double[arms.length][8][N * N];
        String[] names = {"B", "base-shell", "erosion-shell", "input-slope", "initial-mask", "input-lap", "layer-base", "diff-to-raw"};
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int at = j * N + i; double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
                a[at] = aKernel.sampleLand(x, z, w);
                require(bits(a[at], prod.heightForMerged(x, z, w)), "A production mismatch");
                rawShell[at] = mountain.raw(x, z) * mountain.amplitude(x, z);
            }
            if (j % 128 == 0) System.out.println("A row=" + j);
        }
        for (int s = 0; s < arms.length; s++) {
            String arm = arms[s];
            Properties c = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(p.getProperty(arm)))) { c.load(r); }
            require(arm.equals(c.getProperty("arm")), "constants arm mismatch");
            this.f = Double.parseDouble(c.getProperty("f"));
            this.k = Double.parseDouble(c.getProperty("k"));
            require(f > 0 && f <= 1 && k > 0, "invalid constants");
            fUsed[s] = f; kUsed[s] = k;
            MorphKernel kKernel = shapingKernel(arm);
            float[] d = new float[3];
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    int at = j * N + i; double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
                    double[] pt = domain(direction, x, z);
                    double b = base(arm, pt[0], pt[1]), amp = mountain.amplitude(pt[0], pt[1]);
                    double e = erosion(arm, pt[0] / step, pt[1] / step);
                    armsOut[s][0][at] = kKernel.sampleLand(x, z, w);
                    require(bits(armsOut[s][0][at], k * (f * b + .25 * e) * amp), "insertion site mismatch");
                    armsOut[s][1][at] = k * f * b * amp;
                    armsOut[s][2][at] = k * .25 * e * amp;
                    armsOut[s][6][at] = b * amp;
                    armsOut[s][7][at] = armsOut[s][0][at] - rawShell[at];
                    int px = (int) Math.floor(pt[0] / step), pz = (int) Math.floor(pt[1] / step);
                    fields(arm, px, pz, d);
                    float sv = (float) StrictMath.hypot(d[0], d[1]) * 262144f * 2f;
                    armsOut[s][3][at] = sv;
                    float v = sv < .1f ? .5f * sv * sv / .1f : sv - .05f;
                    float t = 1f - Math.max(0f, Math.min(1f, v));
                    armsOut[s][4][at] = 1f - t * t;
                    // d[] is already scaled by f*BASE_AMP: the same quantity the filter consumes.
                    armsOut[s][5][at] = d[2] * 262144f / 25f;
                }
                if (j % 128 == 0) System.out.println(arm + " row=" + j);
            }
            for (int t = 96; t >= 0; t--) {
                int at = (int) ((long) t * (N * N - 1) / 96);
                double x = (ox + at % N) * outputStep, z = (oz + at / N) * outputStep;
                require(bits(armsOut[s][0][at], kKernel.sampleLand(x, z, w)), "query-order drift");
            }
            for (int i = 0; i < names.length; i++) write(out, arm + "-" + names[i], armsOut[s][i]);
        }
        write(out, "A", a); write(out, "raw-shell", rawShell);
        Files.writeString(out.resolve("sampling.json"), String.format(Locale.ROOT,
                "{\"seed\":%d,\"size\":%d,\"stepWu\":%.17g,\"spanWu\":%.17g,\"originWu\":[%.17g,%.17g],"
                        + "\"erosionStepWu\":%.17g,\"groundE\":0.02,\"eSpan\":1.5,\"arms\":[\"A\",\"L1\",\"L2\"],"
                        + "\"l1\":{\"f\":%s,\"k\":%s},\"l2\":{\"f\":%s,\"k\":%s}}\n",
                seed, N, outputStep, (N - 1) * outputStep, ox * outputStep, oz * outputStep, step,
                fUsed[0], kUsed[0], fUsed[1], kUsed[1]));
        Files.writeString(out.resolve("verification-java.txt"),
                "PASS all 591361 A samples bit-identical production onehot\n"
                        + "PASS both arms bit-identical k*(f*base+.25E)*shell at production warped coordinates\n"
                        + "PASS constants loaded only from offline calibration files; no window statistic\n"
                        + "L1/L2 differ from production raw only in ridge/scaler octave count (warp preserved)\n"
                        + "Input mask uses lower native integer neighbour of warped point; first octave only.\n");
    }

    private static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static double median(double[] a) {
        double[] b = a.clone(); Arrays.sort(b); int n = b.length;
        return n % 2 == 0 ? (b[n / 2 - 1] + b[n / 2]) / 2 : b[n / 2];
    }
    private static double std(double[] a) {
        double m = 0; for (double x : a) m += x; m /= a.length;
        double v = 0; for (double x : a) v += (x - m) * (x - m);
        return StrictMath.sqrt(v / a.length);
    }
    private static void write(Path out, String name, double[] a) throws Exception {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double x : a) { require(Double.isFinite(x), "nonfinite " + name); d.writeDouble(x); }
        }
    }
}
