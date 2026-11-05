package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Stage 1: ONLY the ridge octave count changes (2 -> 1). Scaler modulation, 150wu warp, 1-|n| fold,
 * hard clamp, +/-1 central gradient, five-point Laplacian, step, 4e-4, ORIGINAL, base+0.25E, x0.7
 * and the k rule are all unchanged.
 */
public final class FastErosionOctaveProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, CAL = 8192;
    static final float BASE_AMP = 4e-4f;
    static final double LOCKED_K = .8205431216912392;

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node scaler, direction, wx, wz;
    final MorphNoise.PerlinRidge prodRidge;
    MorphNoise.PerlinRidge activeRidge;
    final double mainScale, step, outputStep, warpPow;
    final int ox, oz, ex, ez, w, h;

    int ridgeOctaves;
    double[] baseGrid, gxGrid, gzGrid, lapGrid;
    double k;

    FastErosionOctaveProbe() {
        MorphNoise.Warped warped = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        prodRidge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix prodScaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) prodScaler.input();
        wx = warped.wx(); wz = warped.wz(); warpPow = warped.pow();
        mainScale = 1.0 / prodRidge.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        scaler = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), prodScaler.alpha());
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step) - 4;
        ez = (int) Math.floor(oz * outputStep / step) - 4;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + 4;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + 4;
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
    }

    /** Ridge chain with a parameterised octave count; the node supplies max()/spectral() as in production. */
    double ridgeValue(double x, double z) {
        MorphNoise.PerlinRidge r = activeRidge;
        int oct = r.octaves(), seed = r.seed();
        double freq = r.frequency(), lac = r.lacunarity(), gain = r.gain(), max = r.max();
        float[] spectral = r.spectral();
        x *= freq;
        z *= freq;
        double amp = 2, value = 0, weight = 1;
        for (int i = 0; i < oct; i++) {
            double s = 1 - Math.abs(MorphNoiseEval.sample(seed + i, x, z));
            s *= s;
            s *= weight;
            weight = MorphNoiseEval.clamp(s * amp, 0, 1);   // hard clamp unchanged
            value += s * spectral[i];
            x *= lac;
            z *= lac;
            amp *= gain;
        }
        return MorphNoiseEval.map(value, 0, max);
    }

    double base(double x, double z) {
        double dx = MorphNoiseEval.eval(wx, x, z), dz = MorphNoiseEval.eval(wz, x, z);
        double px = x + (dx - .5) * warpPow, pz = z + (dz - .5) * warpPow;
        return ridgeValue(px, pz) * MorphNoiseEval.eval(scaler, px, pz);
    }

    void prepare() {
        baseGrid = new double[w * h];
        gxGrid = new double[w * h];
        gzGrid = new double[w * h];
        lapGrid = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++)
            baseGrid[j * w + i] = base((ex + i) * step, (ez + j) * step);
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            double c = val(i, j), xp = val(i + 1, j), xm = val(i - 1, j);
            double zp = val(i, j + 1), zm = val(i, j - 1);
            gxGrid[at] = (xp - xm) / 2;
            gzGrid[at] = (zp - zm) / 2;
            lapGrid[at] = xp + xm + zp + zm - 4 * c;
        }
    }

    double val(int i, int j) {
        if (i >= 0 && j >= 0 && i < w && j < h) return baseGrid[j * w + i];
        return base((ex + i) * step, (ez + j) * step);
    }

    void fields(int x, int z, float[] d) {
        int i = x - ex, j = z - ez;
        boolean inside = i >= 0 && j >= 0 && i < w && j < h;
        double gx, gz, lap;
        if (inside) {
            gx = gxGrid[j * w + i]; gz = gzGrid[j * w + i]; lap = lapGrid[j * w + i];
        } else {
            gx = (val(i + 1, j) - val(i - 1, j)) / 2;
            gz = (val(i, j + 1) - val(i, j - 1)) / 2;
            lap = val(i + 1, j) + val(i - 1, j) + val(i, j + 1) + val(i, j - 1) - 4 * val(i, j);
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
        double[] target = new double[CAL], sum = new double[CAL];
        float[] e = new float[3];
        for (int i = 0; i < CAL; i++) {
            double x = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double z = 16 * mainScale + random.nextInt((int) (64 * mainScale));
            double[] p = domain(x, z);
            target[i] = mountain.raw(p[0], p[1]);
            FastErosionFilter.erosion((int) Math.round(p[0] / step), (int) Math.round(p[1] / step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            sum[i] = base(p[0], p[1]) + .25 * e[0];
        }
        return std(target) / std(sum);
    }

    double interp(double[] a, double x, double z) {
        int px = (int) Math.floor(x), pz = (int) Math.floor(z);
        int i = px - ex, j = pz - ez;
        double tx = x - px, tz = z - pz;
        double lo = a[j * w + i] + (a[j * w + i + 1] - a[j * w + i]) * tx;
        double hi = a[(j + 1) * w + i] + (a[(j + 1) * w + i + 1] - a[(j + 1) * w + i]) * tx;
        return lo + (hi - lo) * tz;
    }

    void render(Path out) throws Exception {
        double[] eg = new double[w * h];
        java.util.stream.IntStream.range(2, h - 2).parallel().forEach(j -> {
            float[] e = new float[3];
            for (int i = 2; i < w - 2; i++) {
                FastErosionFilter.erosion(ex + i, ez + j, this::fields, FastErosionFilter.ORIGINAL, e);
                eg[j * w + i] = e[0];
            }
        });
        double[] height = new double[N * N], ero = new double[N * N], mask = new double[N * N];
        double[] aspect = new double[N * N];
        float[] d = new float[3];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i;
            double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
            double[] p = domain(x, z);
            double b = base(p[0], p[1]);
            double e = interp(eg, p[0] / step, p[1] / step);
            double amp = mountain.amplitude(p[0], p[1]);
            height[at] = k * (b + .25 * e) * amp;
            ero[at] = k * .25 * e * amp;
            int px = (int) Math.floor(p[0] / step), pz = (int) Math.floor(p[1] / step);
            fields(px, pz, d);
            double s = StrictMath.hypot(d[0], d[1]) * 262144.0 * 2.0;
            double v = s < .1 ? .5 * s * s / .1 : s - .05;
            double tv = 1 - Math.max(0, Math.min(1, v));
            mask[at] = 1 - tv * tv;
            aspect[at] = StrictMath.atan2(d[1], d[0]);
        }
        write(out.resolve("R1-height.f64be"), height);
        write(out.resolve("R1-erosion.f64be"), ero);
        write(out.resolve("R1-mask.f64be"), mask);
        write(out.resolve("R1-aspect.f64be"), aspect);
        write(out.resolve("R1-native-base.f64be"), baseGrid);
        write(out.resolve("R1-native-lap.f64be"), lapGrid);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionOctaveProbe p = new FastErosionOctaveProbe();

        // Negative control: with two octaves the replication must equal production L2 on a strided grid.
        p.activeRidge = new MorphNoise.PerlinRidge(p.prodRidge.seed(), p.prodRidge.frequency(), 2,
                p.prodRidge.lacunarity(), p.prodRidge.gain());
        MorphNoise.Node control = MorphNoise.warp(new MorphNoise.Mul(p.activeRidge, p.scaler),
                p.wx, p.wz, (float) p.warpPow);
        int bad = 0;
        for (int j = 0; j < p.h; j += 3) for (int i = 0; i < p.w; i += 3) {
            double x = (p.ex + i) * p.step, z = (p.ez + j) * p.step;
            if (Double.doubleToRawLongBits(p.base(x, z))
                    != Double.doubleToRawLongBits(MorphNoiseEval.eval(control, x, z))) bad++;
        }
        if (bad != 0) throw new AssertionError("two-octave replication drifted: " + bad);
        System.out.println("PASS two-octave replication matches production L2 on the strided grid");

        p.activeRidge = new MorphNoise.PerlinRidge(p.prodRidge.seed(), p.prodRidge.frequency(), 1,
                p.prodRidge.lacunarity(), p.prodRidge.gain());
        p.prepare();
        p.k = p.calibrate();
        p.render(out);
        Files.writeString(out.resolve("parameters.json"), String.format(Locale.ROOT,
                "{\"ridgeOctaves\":1,\"scalerOctaves\":2,\"warpPow\":%.17g,\"k\":%.17g,\"lockedK\":%.17g,"
                        + "\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],\"stepWu\":%.17g}\n",
                p.warpPow, p.k, LOCKED_K, p.w, p.h, p.ex, p.ez, p.step));
        System.out.println("R1 k=" + p.k + " rendered");
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
