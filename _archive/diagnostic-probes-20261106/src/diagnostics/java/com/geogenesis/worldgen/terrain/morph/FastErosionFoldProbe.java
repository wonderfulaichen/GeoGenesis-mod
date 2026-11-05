package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * F: Laplacian estimated at stripe scale (gradient untouched).
 * G: ridge fold variants, replicated chain, negative control V0 must reproduce B bitwise.
 *
 * <p>Read-only reuse of the production node graph: the L2 warp/scaler are evaluated by
 * {@link MorphNoiseEval}; only the ridge loop is re-implemented here so a single fold expression can
 * be swapped. Nothing under {@code src/main} is modified.</p>
 */
public final class FastErosionFoldProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769;
    static final float BASE_AMP = 4e-4f;
    static final int CAL_COUNT = 8192;
    static final double OLD_K = .8205431216912392;
    static final String[] ARMS = {"B", "F1", "F3", "V1", "V2"};

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node l2, scaler, direction;
    final MorphNoise.PerlinRidge ridge;
    final MorphNoise.Warped warped;
    final double mainScale, step, outputStep, rFold;
    final int ox, oz, ex, ez, w, h;

    FastErosionFoldProbe() {
        warped = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        MorphNoise.PerlinRidge productionRidge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix productionScaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) productionScaler.input();
        mainScale = 1.0 / productionRidge.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        // L2 BASE = 2 ridge octaves x 2 scaler octaves, as in every preceding arm.
        ridge = new MorphNoise.PerlinRidge(productionRidge.seed(), productionRidge.frequency(), 2,
                productionRidge.lacunarity(), productionRidge.gain());
        scaler = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), productionScaler.alpha());
        l2 = MorphNoise.warp(new MorphNoise.Mul(ridge, scaler), warped.wx(), warped.wz(), warped.pow());
        // r: rounded-abs slope change per sample <= 20%  =>  r >= 5*delta_n.
        // delta_n at the FINEST ridge octave = 1.5 (Perlin gradient bound) * stepWu * lac^(oct-1) / mainScale.
        rFold = 5.0 * 1.5 * step * Math.pow(ridge.lacunarity(), ridge.octaves() - 1) / mainScale;
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step) - 4;
        ez = (int) Math.floor(oz * outputStep / step) - 4;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + 4;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + 4;
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
    }

    // ---------------------------------------------------------------- ridge chain
    /** mode 0=V0 original, 1=V1 square, 2=V2 rounded. Everything else is byte-for-byte the double chain. */
    double fold(double n, int mode) {
        return switch (mode) {
            case 1 -> 1 - n * n;
            case 2 -> 1 - (StrictMath.sqrt(n * n + rFold * rFold) - rFold);
            default -> 1 - Math.abs(n);
        };
    }

    /** {@link MorphNoiseEval#ridge} verbatim with one substituted fold expression. */
    double ridgeValue(double x, double z, int mode) {
        int oct = ridge.octaves(), seed = ridge.seed();
        double freq = ridge.frequency(), lac = ridge.lacunarity(), gain = ridge.gain();
        double max = ridge.max();
        float[] spectral = ridge.spectral();
        x *= freq;
        z *= freq;
        double amp = 2, value = 0, weight = 1;
        for (int i = 0; i < oct; i++) {
            double s = fold(MorphNoiseEval.sample(seed + i, x, z), mode);
            s *= s;
            s *= weight;
            weight = MorphNoiseEval.clamp(s * amp, 0, 1);
            value += s * spectral[i];
            x *= lac;
            z *= lac;
            amp *= gain;
        }
        return MorphNoiseEval.map(value, 0, max);
    }

    /** L2 = Warped( Mul(ridge, scaler) , wx, wz, pow ), same operation order as the evaluator. */
    double foldedL2(double x, double z, int mode) {
        double dx = MorphNoiseEval.eval(warped.wx(), x, z);
        double dz = MorphNoiseEval.eval(warped.wz(), x, z);
        double px = x + (dx - .5) * warped.pow();
        double pz = z + (dz - .5) * warped.pow();
        return ridgeValue(px, pz, mode) * MorphNoiseEval.eval(scaler, px, pz);
    }

    double base(double x, double z, String arm) {
        return arm.startsWith("V") ? foldedL2(x, z, arm.equals("V1") ? 1 : 2)
                : MorphNoiseEval.eval(l2, x, z);
    }

    // ---------------------------------------------------------------- per-arm state
    int armIndex;
    String arm;
    double k;
    double[] baseGrid, gxGrid, gzGrid, lapGrid;
    double[] lapSmoothed;
    int smoothRadius;

    void prepare() {
        baseGrid = new double[w * h];
        gxGrid = new double[w * h];
        gzGrid = new double[w * h];
        lapGrid = new double[w * h];
        java.util.stream.IntStream.range(0, h).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) baseGrid[j * w + i] = base((ex + i) * step, (ez + j) * step, arm);
        });
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            double c = val(i, j), xp = val(i + 1, j), xm = val(i - 1, j);
            double zp = val(i, j + 1), zm = val(i, j - 1);
            gxGrid[at] = (xp - xm) / 2;
            gzGrid[at] = (zp - zm) / 2;
            lapGrid[at] = xp + xm + zp + zm - 4 * c;
        }
        if (arm.equals("F1") || arm.equals("F3")) {
            smoothRadius = arm.equals("F1") ? 1 : 3;
            lapSmoothed = smoothField(lapGrid, smoothRadius);
        } else {
            lapSmoothed = null;
            smoothRadius = 0;
        }
    }

    double val(int i, int j) {
        if (i >= 0 && j >= 0 && i < w && j < h) return baseGrid[j * w + i];
        return base((ex + i) * step, (ez + j) * step, arm);
    }

    /** Normalized Gaussian, sigma=R/2. method A(smooth h then 5-point) is mathematically identical. */
    double[] smoothField(double[] src, int radius) {
        double sigma = radius / 2.0;
        double[] out = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            double sum = 0, acc = 0;
            for (int b = -radius; b <= radius; b++) for (int a = -radius; a <= radius; a++) {
                double wt = StrictMath.exp(-(a * a + b * b) / (2 * sigma * sigma));
                int x = i + a, z = j + b;
                double v = (x >= 0 && z >= 0 && x < w && z < h) ? src[z * w + x] : lapProcedural(x, z);
                acc += wt * v;
                sum += wt;
            }
            out[j * w + i] = acc / sum;
        }
        return out;
    }

    double lapProcedural(int i, int j) {
        return val(i + 1, j) + val(i - 1, j) + val(i, j + 1) + val(i, j - 1) - 4 * val(i, j);
    }

    /** Same kernel outside the cached window, so calibration sees the identical operator. */
    double lapSmoothProcedural(int i, int j, int radius) {
        double sigma = radius / 2.0, acc = 0, sum = 0;
        for (int b = -radius; b <= radius; b++) for (int a = -radius; a <= radius; a++) {
            double wt = StrictMath.exp(-(a * a + b * b) / (2 * sigma * sigma));
            acc += wt * lapProcedural(i + a, j + b);
            sum += wt;
        }
        return acc / sum;
    }

    void fields(int x, int z, float[] d) {
        int i = x - ex, j = z - ez;
        boolean inside = i >= 0 && j >= 0 && i < w && j < h;
        double lap;
        if (inside) lap = lapSmoothed != null ? lapSmoothed[j * w + i] : lapGrid[j * w + i];
        else if (lapSmoothed != null) lap = lapSmoothProcedural(i, j, smoothRadius);
        else lap = lapProcedural(i, j);
        double gx, gz;
        if (inside) {
            gx = gxGrid[j * w + i];
            gz = gzGrid[j * w + i];
        } else {
            gx = (val(i + 1, j) - val(i - 1, j)) / 2;
            gz = (val(i, j + 1) - val(i, j - 1)) / 2;
        }
        d[0] = (float) (gx * BASE_AMP);
        d[1] = (float) (gz * BASE_AMP);
        d[2] = (float) (lap * BASE_AMP);
    }

    double[] domain(double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
    }

    double calibrate() {
        Random random = new Random((long) (double) 0x4641535445524f53L);
        double[] target = new double[CAL_COUNT], sum = new double[CAL_COUNT];
        float[] e = new float[3];
        for (int i = 0; i < CAL_COUNT; i++) {
            double x = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double z = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double[] p = domain(x, z);
            target[i] = mountain.raw(p[0], p[1]);
            FastErosionFilter.erosion((int) Math.round(p[0] / step), (int) Math.round(p[1] / step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            sum[i] = base(p[0], p[1], arm) + .25 * e[0];
        }
        return std(target) / std(sum);
    }

    static double interp(double[] a, double x, double z) {
        int px = (int) Math.floor(x), pz = (int) Math.floor(z);
        int i = px - EX, j = pz - EZ;
        double tx = x - px, tz = z - pz;
        double lo = a[j * WW + i] + (a[j * WW + i + 1] - a[j * WW + i]) * tx;
        double hi = a[(j + 1) * WW + i] + (a[(j + 1) * WW + i + 1] - a[(j + 1) * WW + i]) * tx;
        return lo + (hi - lo) * tz;
    }

    static int EX, EZ, WW, HH;

    void render(Path out) throws Exception {
        double[] eg = new double[w * h];
        java.util.stream.IntStream.range(2, h - 2).parallel().forEach(j -> {
            float[] e = new float[3];
            for (int i = 2; i < w - 2; i++) {
                FastErosionFilter.erosion(ex + i, ez + j, this::fields, FastErosionFilter.ORIGINAL, e);
                eg[j * w + i] = e[0];
            }
        });
        double[] height = new double[N * N], ero = new double[N * N];
        double[] bshell = new double[N * N], mask = new double[N * N];
        double[] aspect = new double[N * N], lapOut = new double[N * N];
        float[] d = new float[3];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i;
            double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
            double[] p = domain(x, z);
            double b = base(p[0], p[1], arm);
            double e = interp(eg, p[0] / step, p[1] / step);
            double amp = mountain.amplitude(p[0], p[1]);
            height[at] = k * (b + .25 * e) * amp;
            ero[at] = k * .25 * e * amp;
            bshell[at] = k * b * amp;
            int px = (int) Math.floor(p[0] / step), pz = (int) Math.floor(p[1] / step);
            fields(px, pz, d);
            float s = (float) StrictMath.hypot(d[0], d[1]) * 262144f * 2f;
            float v = s < .1f ? .5f * s * s / .1f : s - .05f;
            double tv = 1 - Math.max(0, Math.min(1, v));
            mask[at] = 1 - tv * tv;
            aspect[at] = StrictMath.atan2(d[1], d[0]);
            lapOut[at] = d[2] * 262144f / 25f;
        }
        write(out.resolve(arm + "-height.f64be"), height);
        write(out.resolve(arm + "-erosion.f64be"), ero);
        write(out.resolve(arm + "-base-shell.f64be"), bshell);
        write(out.resolve(arm + "-mask.f64be"), mask);
        write(out.resolve(arm + "-aspect.f64be"), aspect);
        write(out.resolve(arm + "-lap.f64be"), lapOut);
        write(out.resolve(arm + "-native-base.f64be"), baseGrid);
        write(out.resolve(arm + "-native-lap.f64be"), lapSmoothed != null ? lapSmoothed : lapGrid);
        write(out.resolve(arm + "-native-mask.f64be"), nativeMask());
    }

    double[] nativeMask() {
        double[] m = new double[w * h];
        for (int i = 0; i < m.length; i++) {
            double s = StrictMath.hypot(gxGrid[i], gzGrid[i]) * BASE_AMP * 262144.0 * 2.0;
            double v = s < .1 ? .5 * s * s / .1 : s - .05;
            m[i] = 1 - Math.pow(1 - Math.max(0, Math.min(1, v)), 2);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionFoldProbe p = new FastErosionFoldProbe();
        EX = p.ex; EZ = p.ez; WW = p.w; HH = p.h;

        // V0 negative control: replicated chain must equal the production double chain bitwise.
        int baseMismatch = 0;
        double maxDelta = 0;
        for (int j = 0; j < p.h; j++) for (int i = 0; i < p.w; i++) {
            double x = (p.ex + i) * p.step, z = (p.ez + j) * p.step;
            double a = p.foldedL2(x, z, 0), b = MorphNoiseEval.eval(p.l2, x, z);
            if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) {
                baseMismatch++;
                maxDelta = Math.max(maxDelta, Math.abs(a - b));
            }
        }
        StringBuilder report = new StringBuilder();
        report.append("V0 negative control: base samples=").append(p.w * p.h)
                .append(" mismatchingBits=").append(baseMismatch)
                .append(" maxAbsDelta=").append(maxDelta).append('\n');
        if (baseMismatch != 0) throw new AssertionError("V0 replication drifted");
        report.append("PASS V0 folded chain reproduces production L2 bitwise => V0 arm == B arm\n");

        // B control: identical pipeline must reproduce the locked B calibration constant bitwise.
        p.arm = "B";
        p.prepare();
        double kB = p.calibrate();
        System.out.println("B calibration control k=" + kB + " locked=" + OLD_K);
        report.append("B calibration control: k=").append(kB).append(" locked=").append(OLD_K).append('\n');
        if (Double.doubleToRawLongBits(kB) != Double.doubleToRawLongBits(OLD_K))
            throw new AssertionError("B calibration path drifted");
        report.append("PASS unchanged pipeline reproduces locked B k bitwise\n");

        // Method A vs B check: Gaussian smoothing commutes with the 5-point Laplacian.
        double maxCommute = 0;
        p.arm = "F3";
        p.prepare();
        double[] smoothH = p.smoothField(p.baseGrid, 3);
        double[] smoothLap = p.lapSmoothed;
        for (int j = 4; j < p.h - 4; j += 7) for (int i = 4; i < p.w - 4; i += 7) {
            double lapA = smoothH[j * p.w + i + 1] + smoothH[j * p.w + i - 1]
                    + smoothH[(j + 1) * p.w + i] + smoothH[(j - 1) * p.w + i] - 4 * smoothH[j * p.w + i];
            maxCommute = Math.max(maxCommute, Math.abs(lapA - smoothLap[j * p.w + i]));
        }
        report.append("method A(smooth h then 5-point) vs B(smooth lap): maxAbsDiff=")
                .append(maxCommute).append(" (linear convolution commutes; same kernel)\n");
        Files.writeString(out.resolve("v0-comparison.txt"), report.toString());
        System.out.print(report);

        for (String a : new String[]{"F1", "F3", "V1", "V2"}) {
            p.arm = a;
            p.armIndex = Arrays.asList(ARMS).indexOf(a);
            p.prepare();
            p.k = p.calibrate();
            p.render(out);
            System.out.println(a + " k=" + p.k + " rendered");
        }
        Files.writeString(out.resolve("parameters.json"), String.format(Locale.ROOT,
                "{\"rFold\":%.17g,\"stepWu\":%.17g,\"mainScaleWu\":%.17g,\"kOld\":%.17g,"
                        + "\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],\"nativeHalo\":4}\n",
                p.rFold, p.step, p.mainScale, OLD_K, p.w, p.h, p.ex, p.ez));
        System.out.println("PASS all arms finite; V1/V2 differ from V0 only by fold");
    }

    static double std(double[] a) {
        double m = 0;
        for (double x : a) m += x;
        m /= a.length;
        double v = 0;
        for (double x : a) v += (x - m) * (x - m);
        return StrictMath.sqrt(v / a.length);
    }

    static void write(Path p, double[] a) throws Exception {
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            for (double x : a) {
                if (!Double.isFinite(x)) throw new AssertionError("nonfinite " + p.getFileName());
                s.writeDouble(x);
            }
        }
    }
}
