package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * H: on the unchanged V0 chain, only the 150wu domain warp power changes (0 and 75wu).
 * Everything else - 2 ridge octaves, 2 scaler octaves, 1-|n| fold, +/-1 central gradient,
 * five-point Laplacian, step, 4e-4, ORIGINAL, base+0.25E, x0.7 - is untouched.
 */
public final class FastErosionWarpProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, CAL = 8192;
    static final float BASE_AMP = 4e-4f;
    static final double LOCKED_K = .8205431216912392;
    static final double P_H = 0.0, P_H2 = 75.0;

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node l2, scaler, direction;
    final MorphNoise.PerlinRidge ridge;
    final MorphNoise.Warped warped;
    final double mainScale, step, outputStep, nativePow;
    final int ox, oz, ex, ez, w, h;

    double warpPow;
    String arm;
    double k;
    double[] baseGrid, gxGrid, gzGrid, lapGrid;

    FastErosionWarpProbe() {
        warped = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        MorphNoise.PerlinRidge prodRidge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix prodScaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) prodScaler.input();
        mainScale = 1.0 / prodRidge.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        nativePow = warped.pow();
        ridge = new MorphNoise.PerlinRidge(prodRidge.seed(), prodRidge.frequency(), 2,
                prodRidge.lacunarity(), prodRidge.gain());
        scaler = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), prodScaler.alpha());
        l2 = MorphNoise.warp(new MorphNoise.Mul(ridge, scaler), warped.wx(), warped.wz(), warped.pow());
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step) - 4;
        ez = (int) Math.floor(oz * outputStep / step) - 4;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + 4;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + 4;
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
    }

    double ridgeValue(double x, double z) {
        int oct = ridge.octaves(), seed = ridge.seed();
        double freq = ridge.frequency(), lac = ridge.lacunarity(), gain = ridge.gain(), max = ridge.max();
        float[] spectral = ridge.spectral();
        x *= freq;
        z *= freq;
        double amp = 2, value = 0, weight = 1;
        for (int i = 0; i < oct; i++) {
            double s = 1 - Math.abs(MorphNoiseEval.sample(seed + i, x, z));
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

    /** Only the warp power is substituted here. */
    double base(double x, double z) {
        double dx = MorphNoiseEval.eval(warped.wx(), x, z);
        double dz = MorphNoiseEval.eval(warped.wz(), x, z);
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

    double lapProc(int i, int j) {
        return val(i + 1, j) + val(i - 1, j) + val(i, j + 1) + val(i, j - 1) - 4 * val(i, j);
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
            lap = lapProc(i, j);
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
        double[] aspect = new double[N * N], lapOut = new double[N * N];
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
            lapOut[at] = d[2] * 262144f / 25f;
        }
        write(out.resolve(arm + "-height.f64be"), height);
        write(out.resolve(arm + "-erosion.f64be"), ero);
        write(out.resolve(arm + "-mask.f64be"), mask);
        write(out.resolve(arm + "-aspect.f64be"), aspect);
        write(out.resolve(arm + "-lap.f64be"), lapOut);
        write(out.resolve(arm + "-native-base.f64be"), baseGrid);
        write(out.resolve(arm + "-native-lap.f64be"), lapGrid);
    }

    /** Jacobian of the ORIGINAL warp map, i.e. the same field production composes. */
    double[] jacobian() {
        double[] det = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            double x = (ex + i) * step, z = (ez + j) * step;
            double hh = step;
            double oxp = (MorphNoiseEval.eval(warped.wx(), x + hh, z) - .5) * nativePow;
            double oxm = (MorphNoiseEval.eval(warped.wx(), x - hh, z) - .5) * nativePow;
            double oxzp = (MorphNoiseEval.eval(warped.wx(), x, z + hh) - .5) * nativePow;
            double oxzm = (MorphNoiseEval.eval(warped.wx(), x, z - hh) - .5) * nativePow;
            double oyp = (MorphNoiseEval.eval(warped.wz(), x + hh, z) - .5) * nativePow;
            double oym = (MorphNoiseEval.eval(warped.wz(), x - hh, z) - .5) * nativePow;
            double oyzp = (MorphNoiseEval.eval(warped.wz(), x, z + hh) - .5) * nativePow;
            double oyzm = (MorphNoiseEval.eval(warped.wz(), x, z - hh) - .5) * nativePow;
            double jxx = 1 + (oxp - oxm) / (2 * hh), jxz = (oxzp - oxzm) / (2 * hh);
            double jyx = (oyp - oym) / (2 * hh), jyz = 1 + (oyzp - oyzm) / (2 * hh);
            det[j * w + i] = jxx * jyz - jxz * jyx;
        }
        return det;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionWarpProbe p = new FastErosionWarpProbe();

        // Control: with the untouched power the replication must equal production L2 bitwise.
        p.warpPow = p.nativePow;
        int bad = 0;
        for (int j = 0; j < p.h; j += 3) for (int i = 0; i < p.w; i += 3) {
            double x = (p.ex + i) * p.step, z = (p.ez + j) * p.step;
            if (Double.doubleToRawLongBits(p.base(x, z))
                    != Double.doubleToRawLongBits(MorphNoiseEval.eval(p.l2, x, z))) bad++;
        }
        if (bad != 0) throw new AssertionError("V0 replication drifted: " + bad);
        System.out.println("PASS unchanged-power replication matches production L2 on the strided grid");

        double[] det = p.jacobian();
        write(out.resolve("warp-detJ.f64be"), det);

        StringBuilder meta = new StringBuilder();
        int neg = 0, near = 0;
        double min = Double.MAX_VALUE;
        for (double d : det) {
            if (d < 0) neg++;
            if (Math.abs(d) < .25) near++;
            min = Math.min(min, d);
        }
        meta.append(String.format(Locale.ROOT,
                "{\"nativePow\":%.17g,\"stepWu\":%.17g,\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],"
                        + "\"detJNegFraction\":%.9g,\"detJAbsBelowQuarterFraction\":%.9g,\"detJMin\":%.9g}\n",
                p.nativePow, p.step, p.w, p.h, p.ex, p.ez,
                neg / (double) det.length, near / (double) det.length, min));
        Files.writeString(out.resolve("warp-jacobian.json"), meta.toString());
        System.out.print(meta);

        for (double pow : new double[]{P_H, P_H2}) {
            p.warpPow = pow;
            p.arm = pow == 0 ? "H" : "H2";
            p.prepare();
            p.k = p.calibrate();
            p.render(out);
            System.out.println(p.arm + " pow=" + pow + " k=" + p.k + " rendered");
        }
        Files.writeString(out.resolve("parameters.json"), String.format(Locale.ROOT,
                "{\"nativePow\":%.17g,\"powH\":%.17g,\"powH2\":%.17g,\"lockedK\":%.17g,"
                        + "\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],\"stepWu\":%.17g}\n",
                p.nativePow, P_H, P_H2, LOCKED_K, p.w, p.h, p.ex, p.ez, p.step));
        System.out.println("PASS H/H2 finite");
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
