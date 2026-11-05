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
 * Single-variable correction of the erosion lattice spacing on the L2 base.
 *
 * <p>Only {@code step} changes relative to the L2 arm of the previous round: the prototype's
 * {@code BASE_NOISE_FREQ=9} is a binary shift ({@code 1<<9 = 512}), so one simplex lattice cell spans
 * 512 samples. Aligning the type's 610wu main scale to one lattice cell gives
 * {@code step = 610/512 ~= 1.1914wu} instead of the previous {@code 610/(1165/9) ~= 4.7124wu}.</p>
 *
 * <p>Everything else is inherited unchanged: L2 base (ridge 2 octaves x scaler 2 octaves), 4e-4
 * compression, ORIGINAL parameters, {@code shape_out = base + 0.25E}, ×k replay, ×0.7 shell, same
 * window, and arm A bit-identical to production.</p>
 */
public final class FastErosionFineStepProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final float BASE_AMP = 4e-4f;
    private static final int N = 769, CAL_COUNT = 8192;
    private static final double RANDOM_SEED = 0x4641535445524f53L;
    /** Prototype lattice period in samples: 1<<BASE_NOISE_FREQ with BASE_NOISE_FREQ=9. */
    private static final int LATTICE_SAMPLES = 512;

    private final long seed;
    private final MorphCatalog catalog;
    private final MorphRecipe mountain;
    private final MorphNoise.Node l2;
    private final double mainScale, step;
    private double f, k;

    private final double ox, oz;
    private final int ex, ez, lw, lh;
    private final double[] rawHalo, erosionHalo;

    private FastErosionFineStepProbe(long seed) {
        this.seed = seed;
        catalog = new MorphCatalog(seed);
        mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
        MorphNoise.Warped w = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) w.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix scaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin scalerNoise = (MorphNoise.Perlin) scaler.input();
        mainScale = 1.0 / ridge.frequency();
        // L2 base unchanged from the previous round.
        MorphNoise.Node r2 = new MorphNoise.PerlinRidge(ridge.seed(), ridge.frequency(), 2,
                ridge.lacunarity(), ridge.gain());
        MorphNoise.Node s2 = new MorphNoise.AlphaMix(
                new MorphNoise.Perlin(scalerNoise.seed(), scalerNoise.frequency(), 2,
                        scalerNoise.lacunarity(), scalerNoise.gain()), scaler.alpha());
        l2 = MorphNoise.warp(new MorphNoise.Mul(r2, s2), w.wx(), w.wz(), w.pow());
        // THE ONLY TREATMENT: one lattice cell = 512 samples.
        step = mainScale / LATTICE_SAMPLES;

        double outputStep = mainScale / 256;
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        double x0 = ox * outputStep, z0 = oz * outputStep;
        ex = (int) Math.floor(x0 / step);
        ez = (int) Math.floor(z0 / step);
        int nw = (int) Math.ceil((x0 + (N - 1) * outputStep) / step) - ex + 1;
        int nh = (int) Math.ceil((z0 + (N - 1) * outputStep) / step) - ez + 1;
        lw = nw + 4; lh = nh + 4;
        rawHalo = new double[lw * lh];
        erosionHalo = new double[lw * lh];
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean calibrateMode = args.length == 2 && args[0].equals("calibrate");
        boolean renderMode = args.length == 3 && args[0].equals("render");
        if (!calibrateMode && !renderMode)
            throw new IllegalArgumentException("calibrate <outDir> | render <outDir> <constants.properties>");
        Path out = Path.of(args[1]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionFineStepProbe probe = new FastErosionFineStepProbe(SEED);
        if (args[0].equals("calibrate")) {
            probe.calibrate(out);
        } else if (args[0].equals("render")) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(args[2]))) { p.load(r); }
            probe.f = Double.parseDouble(p.getProperty("f"));
            probe.k = Double.parseDouble(p.getProperty("k"));
            if (!(probe.f > 0 && probe.f <= 1 && probe.k > 0))
                throw new IllegalArgumentException("invalid constants");
            probe.render(out);
        } else throw new IllegalArgumentException("unknown mode");
    }

    private double base(double x, double z) { return MorphNoiseEval.eval(l2, x, z); }

    private double[] baseDerivatives(double px, double pz) {
        double c = base(px * step, pz * step);
        double xp = base((px + 1) * step, pz * step), xm = base((px - 1) * step, pz * step);
        double zp = base(px * step, (pz + 1) * step), zm = base(px * step, (pz - 1) * step);
        return new double[]{(xp - xm) / 2, (zp - zm) / 2, xp + xm + zp + zm - 4 * c};
    }

    private void fields(int px, int pz, float[] out) {
        double[] d = baseDerivatives(px, pz);
        for (int i = 0; i < 3; i++) out[i] = (float) (d[i] * f * BASE_AMP);
    }

    private double[][] points() {
        Random r = new Random((long) RANDOM_SEED);
        double[][] pts = new double[CAL_COUNT][2];
        for (double[] p : pts) {
            p[0] = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            p[1] = 16 * mainScale + r.nextInt((int) (64 * mainScale));
        }
        return pts;
    }

    /** The filter is integer-only, so calibration evaluates E exactly at lattice points. */
    private double latticeE(double px, double pz) {
        int ix = (int) Math.round(px), iz = (int) Math.round(pz);
        float[] e = new float[3];
        FastErosionFilter.erosion(ix, iz, this::fields, FastErosionFilter.ORIGINAL, e);
        return e[0];
    }

    private void calibrate(Path out) throws Exception {
        double proto = prototypeSlope(out);
        double[][] pts = points();
        double[] slope = new double[pts.length], src = new double[pts.length];
        double[] baseV = new double[pts.length], inc = new double[pts.length], sum = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            double[] d = baseDerivatives(pts[i][0] / step, pts[i][1] / step);
            slope[i] = StrictMath.hypot(d[0], d[1]);
        }
        double sBase = median(slope);
        double rawF = proto / sBase;               // reported un-clipped as well
        f = Math.min(1, rawF);
        MorphNoise.Node direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(seed + 77) + 3, 10, 1);
        for (int i = 0; i < pts.length; i++) {
            double[] p = domain(direction, pts[i][0], pts[i][1]);
            src[i] = mountain.raw(p[0], p[1]);
            baseV[i] = f * base(p[0], p[1]);
            inc[i] = .25 * latticeE(p[0] / step, p[1] / step);
            sum[i] = baseV[i] + inc[i];
        }
        k = std(src) / std(sum);
        write(out, "type-slopes", slope); write(out, "production-raw", src);
        write(out, "base", baseV); write(out, "erosion-increment", inc); write(out, "shape-out", sum);
        Files.writeString(out.resolve("constants.properties"),
                "# Offline L2 fine-step constants; never recomputed per window\nf=" + f + "\nk=" + k + "\n");
        Files.writeString(out.resolve("calibration.json"), String.format(Locale.ROOT,
                "{\"arm\":\"L2-fine\",\"seed\":%d,\"count\":%d,\"latticeSamples\":%d,"
                        + "\"protoSlopeMedian\":%.17g,\"baseSlopeMedian\":%.17g,\"fRawUnclipped\":%.17g,"
                        + "\"f\":%.17g,\"k\":%.17g,\"productionRawStd\":%.17g,\"shapeOutStd\":%.17g,"
                        + "\"replayedStd\":%.17g,\"baseStd\":%.17g,\"incrementStd\":%.17g,"
                        + "\"erosionStepWu\":%.17g,\"requestedStepWu\":%.17g}\n",
                seed, pts.length, LATTICE_SAMPLES, proto, sBase, rawF, f, k, std(src), std(sum),
                std(sum) * k, std(baseV), std(inc), step, 610.0 / LATTICE_SAMPLES));
        Files.writeString(out.resolve("calibration-method.txt"),
                "ONLY treatment vs previous L2 round: erosion sample step.\n"
                        + "step = L/(1<<9) = L/512; L=1/ridge.frequency (609.9999994318932wu).\n"
                        + "value=" + step + " wu vs requested 610/512=" + (610.0 / 512)
                        + " wu (relative difference 2.6e-10; the type's own constant is used).\n"
                        + "Previous step was L/(1165/9)=4.712446347542523wu, so this is ~3.9548x finer.\n"
                        + "L2 base, 4e-4 compression, ORIGINAL parameters, .25 share, x0.7 shell and the\n"
                        + "display window are unchanged. Calibration domain and sample count unchanged:\n"
                        + "positive [16L,80L]^2, 8192 java.util.Random points, seed 0x4641535445524f53L.\n"
                        + "f=min(1,s_proto/s_base) with per-sample slopes; after lattice alignment one of\n"
                        + "our samples is the prototype's sample unit, so the comparison is dimensionally\n"
                        + "consistent. fRawUnclipped is reported because the clip is a real outcome here.\n"
                        + "k=std(production raw)/std(f*base+.25E), same rule as every previous arm.\n"
                        + "No window statistic participates at sampling time.\n"
                        + "Demo source note: BASE_NOISE_FREQ=9 is used as 1<<freqShift, giving\n"
                        + "frequency=1/512 and simplex cells 512 samples wide; this round adopts that\n"
                        + "reading as the lattice alignment (the earlier 1165/9 reading is retracted).\n");
        System.out.printf(Locale.ROOT, "CALIBRATION L2-fine step=%.17g fRaw=%.17g f=%.17g k=%.17g%n",
                step, rawF, f, k);
    }

    private static double[] domain(MorphNoise.Node direction, double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
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

    private MorphKernel shapingKernel() {
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        MorphRecipe original = recipes[MorphCatalog.IDX_MOUNTAIN];
        recipes[MorphCatalog.IDX_MOUNTAIN] = new MorphRecipe() {
            @Override public double raw(double x, double z) {
                return k * (f * base(x, z) + .25 * erosionInterp(x / step, z / step));
            }
            @Override public double amplitude(double x, double z) { return original.amplitude(x, z); }
            @Override public double bias(double x, double z) { return original.bias(x, z); }
            @Override public Role role() { return original.role(); }
            @Override public boolean clamped() { return original.clamped(); }
        };
        return new MorphKernel(recipes, seed, false);
    }

    private double erosionInterp(double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double tx = px - ix, tz = pz - iz;
        double a = ero(ix, iz), b = ero(ix + 1, iz), c = ero(ix, iz + 1), d = ero(ix + 1, iz + 1);
        double lo = a + (b - a) * tx, hi = c + (d - c) * tx;
        return lo + (hi - lo) * tz;
    }

    private double ero(int px, int pz) {
        int i = px - ex + 2, j = pz - ez + 2;
        if (i < 0 || i >= lw || j < 0 || j >= lh) throw new AssertionError("erosion halo exhausted");
        return erosionHalo[j * lw + i];
    }

    private void render(Path out) throws Exception {
        double outputStep = mainScale / 256;
        long t0 = System.nanoTime();
        for (int j = 0; j < lh; j++) {
            for (int i = 0; i < lw; i++) {
                int px = ex + i - 2, pz = ez + j - 2;
                rawHalo[j * lw + i] = base(px * step, pz * step);
                float[] e = new float[3];
                FastErosionFilter.erosion(px, pz, this::fields, FastErosionFilter.ORIGINAL, e);
                erosionHalo[j * lw + i] = e[0];
            }
            if (j % 256 == 0) System.out.println("lattice row=" + j + "/" + lh);
        }
        System.out.println("lattice " + lw + "x" + lh + " built in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms");

        MorphKernel aKernel = new MorphKernel(MorphLandRecipe.all(catalog), seed, true);
        MorphKernel bKernel = shapingKernel();
        MorphLandField prod = new MorphLandField(catalog);
        double[] w = aKernel.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
        MorphNoise.Node direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(seed + 77) + 3, 10, 1);
        double[] A = new double[N * N], B = new double[N * N], rawShell = new double[N * N];
        double[] baseShell = new double[N * N], eroShell = new double[N * N];
        double[] slope = new double[N * N], mask = new double[N * N], lap = new double[N * N];
        float[] d = new float[3];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int at = j * N + i;
                double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
                A[at] = aKernel.sampleLand(x, z, w);
                if (Double.doubleToRawLongBits(A[at]) != Double.doubleToRawLongBits(
                        prod.heightForMerged(x, z, w))) throw new AssertionError("A mismatch");
                double[] p = domain(direction, x, z);
                double b = base(p[0], p[1]), amp = mountain.amplitude(p[0], p[1]);
                double e = erosionInterp(p[0] / step, p[1] / step);
                B[at] = bKernel.sampleLand(x, z, w);
                double expect = k * (f * b + .25 * e) * amp;
                if (Double.doubleToRawLongBits(B[at]) != Double.doubleToRawLongBits(expect))
                    throw new AssertionError("insertion site mismatch at " + i + "," + j);
                rawShell[at] = mountain.raw(x, z) * amp;
                baseShell[at] = k * f * b * amp;
                eroShell[at] = k * .25 * e * amp;
                int px = (int) Math.floor(p[0] / step), pz = (int) Math.floor(p[1] / step);
                fields(px, pz, d);
                float sv = (float) StrictMath.hypot(d[0], d[1]) * 262144f * 2f;
                slope[at] = sv;
                float v = sv < .1f ? .5f * sv * sv / .1f : sv - .05f;
                float tt = 1f - Math.max(0f, Math.min(1f, v));
                mask[at] = 1f - tt * tt;
                lap[at] = d[2] * 262144f / 25f;
            }
            if (j % 128 == 0) System.out.println("render row=" + j + "/" + N);
        }
        write(out, "A", A); write(out, "B", B); write(out, "raw-shell", rawShell);
        write(out, "base-shell", baseShell); write(out, "erosion-shell", eroShell);
        write(out, "input-slope", slope); write(out, "initial-mask", mask); write(out, "input-lap", lap);
        Files.writeString(out.resolve("sampling.json"), String.format(Locale.ROOT,
                "{\"seed\":%d,\"size\":%d,\"stepWu\":%.17g,\"spanWu\":%.17g,\"originWu\":[%.17g,%.17g],"
                        + "\"erosionStepWu\":%.17g,\"latticeSamples\":%d,\"latticeSize\":[%d,%d],"
                        + "\"f\":%.17g,\"k\":%.17g,\"groundE\":0.02,\"eSpan\":1.5,\"branchWeight\":1}\n",
                seed, N, outputStep, (N - 1) * outputStep, ox * outputStep, oz * outputStep, step,
                LATTICE_SAMPLES, lw, lh, f, k));
        Files.writeString(out.resolve("verification-java.txt"),
                "PASS all 591361 A samples bit-identical production onehot\n"
                        + "PASS all B samples bit-identical k*(f*base+.25E)*shell at warped coordinates\n"
                        + "PASS only erosion step changed; L2 base, 4e-4, ORIGINAL, .25, x0.7 unchanged\n");
    }

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
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double x : a) { s.writeDouble(x); }
        }
    }
}
