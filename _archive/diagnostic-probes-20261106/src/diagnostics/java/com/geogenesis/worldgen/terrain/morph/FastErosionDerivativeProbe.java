package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Diagnostics-only derivative adapters. Never changes noise, ORIGINAL or the erosion core. */
public final class FastErosionDerivativeProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final int N = 769, CAL_COUNT = 8192, RADIUS = 4;
    private static final float BASE_AMP = 4e-4f;
    private static final double F = 1.0;
    private final MorphCatalog catalog = new MorphCatalog(SEED);
    private final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphNoise.Node l2, direction;
    private final double mainScale, step, outputStep;
    private final int ox, oz, ex, ez, width, height;
    private Grid grid;
    private int mode;
    private double replay;
    private long directionFallbacks;

    private FastErosionDerivativeProbe() {
        MorphNoise.Warped w = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) w.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix scalar = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) scalar.input();
        l2 = MorphNoise.warp(new MorphNoise.Mul(new MorphNoise.PerlinRidge(ridge.seed(),
                ridge.frequency(), 2, ridge.lacunarity(), ridge.gain()),
                new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                        p.lacunarity(), p.gain()), scalar.alpha())), w.wx(), w.wz(), w.pow());
        mainScale = 1.0 / ridge.frequency(); step = mainScale / 512; outputStep = mainScale / 256;
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step);
        ez = (int) Math.floor(oz * outputStep / step);
        width = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1;
        height = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1;
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: NEW-output-dir");
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        new FastErosionDerivativeProbe().run(out);
    }

    private double base(int x, int z) { return MorphNoiseEval.eval(l2, x * step, z * step); }

    /** Central +/-1 SAMPLE, exactly the current B float narrowing and arithmetic order. */
    private void direct(int x, int z, boolean nine, float[] out) {
        double c = base(x, z), xp = base(x + 1, z), xm = base(x - 1, z);
        double zp = base(x, z + 1), zm = base(x, z - 1);
        double lap = xp + xm + zp + zm - 4 * c;
        if (nine) lap = (4 * (xp + xm + zp + zm) + base(x + 1, z + 1)
                + base(x + 1, z - 1) + base(x - 1, z + 1) + base(x - 1, z - 1) - 20 * c) / 6;
        out[0] = (float) (((xp - xm) / 2) * F * BASE_AMP);
        out[1] = (float) (((zp - zm) / 2) * F * BASE_AMP);
        out[2] = (float) (lap * F * BASE_AMP);
    }

    /** Gaussian circular-vector mean; preserve local slope magnitude and five-point Laplacian. */
    private void smooth(int x, int z, float[] center, float[] out) {
        double vx = 0, vz = 0;
        float[] d = new float[3];
        for (int j = -RADIUS; j <= RADIUS; j++) for (int i = -RADIUS; i <= RADIUS; i++) {
            if (grid == null) direct(x + i, z + j, false, d);
            else grid.basic(x + i, z + j, false, d);
            double mag = StrictMath.hypot(d[0], d[1]);
            double w = StrictMath.exp(-(i * i + j * j) / 8.0); // sigma=R/2=2 samples
            if (mag > 0) { vx += w * d[0] / mag; vz += w * d[1] / mag; }
        }
        double norm = StrictMath.hypot(vx, vz), mag = StrictMath.hypot(center[0], center[1]);
        if (norm < 1e-6) {
            directionFallbacks++;
            out[0] = center[0]; out[1] = center[1];
        } else {
            out[0] = (float) (mag * vx / norm); out[1] = (float) (mag * vz / norm);
        }
        out[2] = center[2];
    }

    private void fields(int x, int z, float[] dst) {
        if (grid != null && grid.has(x, z)) {
            grid.get(x, z, mode, dst); return;
        }
        direct(x, z, mode == 1, dst);
        if (mode == 2) { float[] c = dst.clone(); smooth(x, z, c, dst); }
    }

    /** Same nearest-lattice calibration adapter as current B, not bilinear in calibration. */
    private double calibrate(Path out, String arm) throws Exception {
        Random random = new Random((long) (double) 0x4641535445524f53L);
        double[] src = new double[CAL_COUNT], b = new double[CAL_COUNT], inc = new double[CAL_COUNT];
        double[] sum = new double[CAL_COUNT]; float[] e = new float[3];
        for (int i = 0; i < CAL_COUNT; i++) {
            double x = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double z = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double[] p = domain(x, z);
            src[i] = mountain.raw(p[0], p[1]);
            b[i] = F * MorphNoiseEval.eval(l2, p[0], p[1]);
            FastErosionFilter.erosion((int) Math.round(p[0] / step), (int) Math.round(p[1] / step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            inc[i] = .25 * e[0]; sum[i] = b[i] + inc[i];
        }
        double k = std(src) / std(sum);
        write(out, arm + "-calibration-raw", src); write(out, arm + "-calibration-sum", sum);
        Files.writeString(out.resolve(arm + "-calibration.json"), String.format(Locale.ROOT,
                "{\"arm\":\"%s\",\"count\":%d,\"f\":1,\"k\":%.17g,\"rawStd\":%.17g,\"sumStd\":%.17g}\n",
                arm, CAL_COUNT, k, std(src), std(sum)));
        System.out.println(arm + " calibrated k=" + k);
        return k;
    }

    private void run(Path out) throws Exception {
        String[] arms = {"S1", "S2", "S3"}; double[] ks = new double[3];
        for (mode = 0; mode < 3; mode++) ks[mode] = calibrate(out, arms[mode]);
        grid = new Grid(ex - 4, ez - 4, width + 8, height + 8);
        grid.prepare();
        write(out, "native-aspect", grid.aspect());
        write(out, "native-lap", grid.lap()); write(out, "native-mask", grid.mask());
        write(out, "native-base", grid.values);
        // Actual one-sample raw and circular aspect differences; no output-grid resampling.
        for (mode = 0; mode < 3; mode++) {
            String arm = arms[mode]; replay = ks[mode];
            final double[] erosion = new double[grid.count];
            final int selectedMode = mode;
            java.util.stream.IntStream.range(2, grid.h - 2).parallel().forEach(j -> {
                float[] e = new float[3];
                for (int i = 2; i < grid.w - 2; i++) {
                    FastErosionFilter.erosion(grid.x0 + i, grid.z0 + j, this::fields,
                            FastErosionFilter.ORIGINAL, e);
                    erosion[j * grid.w + i] = e[0];
                }
            });
            require(selectedMode == mode, "mode changed during parallel render");
            render(out, arm, erosion);
            System.out.println(arm + " rendered");
        }
        Files.writeString(out.resolve("sampling.json"), String.format(Locale.ROOT,
                "{\"size\":%d,\"stepWu\":%.17g,\"spanWu\":%.17g,\"originWu\":[%.17g,%.17g],"
                + "\"erosionStepWu\":%.17g,\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],"
                + "\"smoothRadiusSamples\":%d,\"smoothSigmaSamples\":2,\"fallbacks\":%d,"
                + "\"k\":[%.17g,%.17g,%.17g]}\n", N, outputStep, (N - 1) * outputStep,
                ox * outputStep, oz * outputStep, step, grid.w, grid.h, grid.x0, grid.z0,
                RADIUS, directionFallbacks, ks[0], ks[1], ks[2]));
    }

    private void render(Path out, String arm, double[] erosion) throws Exception {
        MorphKernel original = new MorphKernel(MorphLandRecipe.all(catalog), SEED, true);
        MorphLandField production = new MorphLandField(catalog);
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        recipes[MorphCatalog.IDX_MOUNTAIN] = new MorphRecipe() {
            @Override public double raw(double x, double z) {
                return replay * (F * MorphNoiseEval.eval(l2, x, z) + .25 * interpolate(erosion, x / step, z / step));
            }
            @Override public double amplitude(double x, double z) { return mountain.amplitude(x, z); }
            @Override public double bias(double x, double z) { return mountain.bias(x, z); }
            @Override public Role role() { return Role.MOUNTAIN; }
        };
        MorphKernel shaped = new MorphKernel(recipes, SEED, false);
        double[] weights = original.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
        String[] names = {"height", "erosion", "mask", "aspect", "lap", "sample-x", "sample-z"};
        double[][] arr = new double[names.length][N * N]; float[] d = new float[3];
        double[] a = new double[N * N], raw = new double[N * N];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i; double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
            if (mode == 0) {
                a[at] = original.sampleLand(x, z, weights);
                require(bits(a[at], production.heightForMerged(x, z, weights)), "A production mismatch");
                raw[at] = mountain.raw(x, z) * mountain.amplitude(x, z);
            }
            double[] p = domain(x, z);
            double e = interpolate(erosion, p[0] / step, p[1] / step);
            arr[0][at] = shaped.sampleLand(x, z, weights);
            require(bits(arr[0][at], replay * (F * MorphNoiseEval.eval(l2, p[0], p[1]) + .25 * e)
                    * mountain.amplitude(p[0], p[1])), "wrong insertion");
            arr[1][at] = replay * .25 * e * mountain.amplitude(p[0], p[1]);
            int px = (int) Math.floor(p[0] / step), pz = (int) Math.floor(p[1] / step);
            fields(px, pz, d);
            arr[2][at] = mask(d); arr[3][at] = StrictMath.atan2(d[1], d[0]);
            arr[4][at] = d[2] * 262144f / 25f; arr[5][at] = px; arr[6][at] = pz;
        }
        for (int t = 128; t >= 0; t--) {
            int at = (int) ((long) t * (N * N - 1) / 128);
            require(bits(arr[0][at], shaped.sampleLand((ox + at % N) * outputStep,
                    (oz + at / N) * outputStep, weights)), "query order drift");
        }
        for (int i = 0; i < names.length; i++) write(out, arm + "-" + names[i], arr[i]);
        if (mode == 0) { write(out, "A", a); write(out, "production-raw", raw); }
    }

    private double[] domain(double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
    }

    private double interpolate(double[] v, double x, double z) {
        int px = (int) Math.floor(x), pz = (int) Math.floor(z);
        double tx = x - px, tz = z - pz;
        int i = px - grid.x0, j = pz - grid.z0;
        double a = v[j * grid.w + i], b = v[j * grid.w + i + 1];
        double c = v[(j + 1) * grid.w + i], d = v[(j + 1) * grid.w + i + 1];
        double lo = a + (b - a) * tx, hi = c + (d - c) * tx;
        return lo + (hi - lo) * tz;
    }

    private final class Grid {
        final int x0, z0, w, h, count;
        final double[] values;
        final float[][] gx = new float[3][], gz = new float[3][], lap = new float[2][];
        Grid(int x, int z, int w, int h) {
            x0 = x; z0 = z; this.w = w; this.h = h; count = w * h; values = new double[count];
            for (int i = 0; i < 3; i++) { gx[i] = new float[count]; gz[i] = new float[count]; }
            lap[0] = new float[count]; lap[1] = new float[count];
        }
        boolean has(int x, int z) { return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h; }
        void prepare() {
            java.util.stream.IntStream.range(0, h).parallel().forEach(j -> {
                for (int i = 0; i < w; i++) values[j * w + i] = base(x0 + i, z0 + j);
            });
            for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                int at = j * w + i; double c = val(i, j), xp = val(i + 1, j), xm = val(i - 1, j);
                double zp = val(i, j + 1), zm = val(i, j - 1);
                gx[0][at] = (float) (((xp - xm) / 2) * F * BASE_AMP);
                gz[0][at] = (float) (((zp - zm) / 2) * F * BASE_AMP);
                lap[0][at] = (float) ((xp + xm + zp + zm - 4 * c) * F * BASE_AMP);
                lap[1][at] = (float) (((4 * (xp + xm + zp + zm) + val(i + 1, j + 1)
                        + val(i + 1, j - 1) + val(i - 1, j + 1) + val(i - 1, j - 1) - 20 * c) / 6) * F * BASE_AMP);
                gx[1][at] = gx[0][at]; gz[1][at] = gz[0][at];
            }
            float[] center = new float[3], dst = new float[3];
            for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                basic(x0 + i, z0 + j, false, center);
                smooth(x0 + i, z0 + j, center, dst);
                gx[2][j * w + i] = dst[0]; gz[2][j * w + i] = dst[1];
            }
        }
        double val(int i, int j) { return i >= 0 && j >= 0 && i < w && j < h ? values[j * w + i] : base(x0 + i, z0 + j); }
        void basic(int x, int z, boolean nine, float[] d) {
            if (!has(x, z)) { direct(x, z, nine, d); return; }
            int at = (z - z0) * w + x - x0;
            d[0] = gx[0][at]; d[1] = gz[0][at]; d[2] = lap[nine ? 1 : 0][at];
        }
        void get(int x, int z, int m, float[] d) {
            int at = (z - z0) * w + x - x0;
            d[0] = gx[m][at]; d[1] = gz[m][at]; d[2] = lap[m == 1 ? 1 : 0][at];
        }
        double[] aspect() {
            double[] a = new double[count]; for (int i = 0; i < count; i++) a[i] = StrictMath.atan2(gz[0][i], gx[0][i]); return a;
        }
        double[] lap() {
            double[] a = new double[count]; for (int i = 0; i < count; i++) a[i] = lap[0][i] * 262144f / 25f; return a;
        }
        double[] mask() {
            double[] a = new double[count]; float[] d = new float[3];
            for (int i = 0; i < count; i++) { d[0] = gx[0][i]; d[1] = gz[0][i]; a[i] = FastErosionDerivativeProbe.mask(d); } return a;
        }
    }

    private static double mask(float[] d) {
        float s = (float) StrictMath.hypot(d[0], d[1]) * 262144f * 2f;
        float v = s < .1f ? .5f * s * s / .1f : s - .05f;
        float t = 1f - Math.max(0f, Math.min(1f, v)); return 1f - t * t;
    }
    private static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static double std(double[] a) {
        double m = 0; for (double x : a) m += x; m /= a.length;
        double v = 0; for (double x : a) v += (x - m) * (x - m); return StrictMath.sqrt(v / a.length);
    }
    private static void write(Path out, String name, double[] a) throws Exception {
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double x : a) { require(Double.isFinite(x), "nonfinite " + name); s.writeDouble(x); }
        }
    }
}
