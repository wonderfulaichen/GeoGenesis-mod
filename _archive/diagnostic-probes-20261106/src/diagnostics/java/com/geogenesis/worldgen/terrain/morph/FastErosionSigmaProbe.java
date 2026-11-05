package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * K: only the low-pass sigma of the DERIVATIVE source changes (30, 50, 80 wu).
 * Height stays the full 2-octave raw. Calibration uses a separate local patch with true halo.
 */
public final class FastErosionSigmaProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, PATCH = 385;
    static final float BASE_AMP = 4e-4f;
    static final double J1_K_DISTANT = .8878256414409725;   // known from the J round, same source
    static final double LOCKED_K = .8205431216912392;
    static final double[] SIGMAS = {30.0, 50.0, 80.0};
    static final String[] ARMS = {"S30", "S50", "S80"};

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node scalerFull, scalerLow, direction, wx, wz, l2full;
    final MorphNoise.PerlinRidge ridgeFull, ridgeLow;
    final double mainScale, step, outputStep, warpPow;
    final int ox, oz;
    double sigmaWu, sigmaSamples;
    int radius, halo, ex, ez, w, h;
    double[] derivGrid;
    double[] gxGrid, gzGrid, lapGrid;
    double k;

    FastErosionSigmaProbe() {
        MorphNoise.Warped warped = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        ridgeFull = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix ps = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) ps.input();
        wx = warped.wx(); wz = warped.wz(); warpPow = warped.pow();
        mainScale = 1.0 / ridgeFull.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        ridgeLow = new MorphNoise.PerlinRidge(ridgeFull.seed(), ridgeFull.frequency(), 1,
                ridgeFull.lacunarity(), ridgeFull.gain());
        scalerFull = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), ps.alpha());
        scalerLow = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 1,
                p.lacunarity(), p.gain()), ps.alpha());
        l2full = MorphNoise.warp(new MorphNoise.Mul(ridgeFull, scalerFull), wx, wz, (float) warpPow);
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
    }

    void configure(double sigma) {
        sigmaWu = sigma;
        sigmaSamples = sigmaWu / step;
        radius = (int) Math.ceil(3 * sigmaSamples);
        halo = radius + 2;
        ex = (int) Math.floor(ox * outputStep / step) - halo;
        ez = (int) Math.floor(oz * outputStep / step) - halo;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + halo;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + halo;
    }

    double heightBase(double x, double z) { return MorphNoiseEval.eval(l2full, x, z); }

    double lowBase(double x, double z) {
        double dx = MorphNoiseEval.eval(wx, x, z), dz = MorphNoiseEval.eval(wz, x, z);
        double px = x + (dx - .5) * warpPow, pz = z + (dz - .5) * warpPow;
        return MorphNoiseEval.eval(ridgeLow, px, pz) * MorphNoiseEval.eval(scalerLow, px, pz);
    }

    /** Separable Gaussian low-pass of the full base over an arbitrary padded window. */
    double[] smoothed(int x0, int z0, int ww, int hh) {
        double[] src = new double[ww * hh];
        java.util.stream.IntStream.range(0, hh).parallel().forEach(j -> {
            for (int i = 0; i < ww; i++) src[j * ww + i] = heightBase((x0 + i) * step, (z0 + j) * step);
        });
        double[] kern = new double[2 * radius + 1];
        double sum = 0;
        for (int k = -radius; k <= radius; k++) {
            double wt = StrictMath.exp(-(k * k) / (2 * sigmaSamples * sigmaSamples));
            kern[k + radius] = wt; sum += wt;
        }
        for (int k = 0; k < kern.length; k++) kern[k] /= sum;
        double[] tmp = new double[ww * hh];
        java.util.stream.IntStream.range(0, hh).parallel().forEach(j -> {
            for (int i = 0; i < ww; i++) {
                double acc = 0;
                for (int k = -radius; k <= radius; k++)
                    acc += kern[k + radius] * src[j * ww + Math.min(ww - 1, Math.max(0, i + k))];
                tmp[j * ww + i] = acc;
            }
        });
        double[] out = new double[ww * hh];
        java.util.stream.IntStream.range(0, ww).parallel().forEach(i -> {
            for (int j = 0; j < hh; j++) {
                double acc = 0;
                for (int k = -radius; k <= radius; k++)
                    acc += kern[k + radius] * tmp[Math.min(hh - 1, Math.max(0, j + k)) * ww + i];
                out[j * ww + i] = acc;
            }
        });
        return out;
    }

    void prepare() {
        derivGrid = smoothed(ex, ez, w, h);
        gxGrid = new double[w * h]; gzGrid = new double[w * h]; lapGrid = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            double c = dv(i, j), xp = dv(i + 1, j), xm = dv(i - 1, j);
            double zp = dv(i, j + 1), zm = dv(i, j - 1);
            gxGrid[j * w + i] = (xp - xm) / 2;
            gzGrid[j * w + i] = (zp - zm) / 2;
            lapGrid[j * w + i] = xp + xm + zp + zm - 4 * c;
        }
    }

    double dv(int i, int j) {
        if (i >= 0 && j >= 0 && i < w && j < h) return derivGrid[j * w + i];
        return heightBase((ex + i) * step, (ez + j) * step);
    }

    void fields(int px, int pz, float[] d) {
        int i = px - ex, j = pz - ez;
        double gx, gz, lap;
        if (i >= 0 && j >= 0 && i < w && j < h) {
            gx = gxGrid[j * w + i]; gz = gzGrid[j * w + i]; lap = lapGrid[j * w + i];
        } else {
            double c = dv(i, j), xp = dv(i + 1, j), xm = dv(i - 1, j), zp = dv(i, j + 1), zm = dv(i, j - 1);
            gx = (xp - xm) / 2; gz = (zp - zm) / 2; lap = xp + xm + zp + zm - 4 * c;
        }
        d[0] = (float) (gx * BASE_AMP); d[1] = (float) (gz * BASE_AMP); d[2] = (float) (lap * BASE_AMP);
    }

    double[] domain(double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
    }

    double interp(double[] a, double x, double z, int gx0, int gz0, int gw, int gh) {
        int px = (int) Math.floor(x), pz = (int) Math.floor(z);
        int i = px - gx0, j = pz - gz0;
        double tx = x - px, tz = z - pz;
        double lo = a[j * gw + i] + (a[j * gw + i + 1] - a[j * gw + i]) * tx;
        double hi = a[(j + 1) * gw + i] + (a[(j + 1) * gw + i + 1] - a[(j + 1) * gw + i]) * tx;
        return lo + (hi - lo) * tz;
    }

    /**
     * k from a SEPARATE local patch with its own true halo, away from the display window.
     * The same formula as every earlier round; only the sampling domain differs, and the J1 bridge
     * below measures what that substitution costs.
     */
    double calibratePatch(int offset) {
        int gx0 = ex + w + 64 + offset, gz0 = ez;
        int gw = PATCH + 2 * halo, gh = PATCH + 2 * halo;
        double[] g = smoothed(gx0 - halo, gz0 - halo, gw, gh);
        double[] savedGrid = derivGrid; int sw = w, sh = h, sx = ex, sz = ez;
        derivGrid = g; w = gw; h = gh; ex = gx0 - halo; ez = gz0 - halo;
        int cx = gx0 + PATCH / 2, cz = gz0 + PATCH / 2;
        double[] tgt = new double[PATCH * PATCH], sm = new double[PATCH * PATCH];
        float[] e = new float[3];
        for (int j = 0; j < PATCH; j++) for (int i = 0; i < PATCH; i++) {
            double x = (cx + i) * step, z = (cz + j) * step;
            tgt[j * PATCH + i] = mountain.raw(x, z);
            FastErosionFilter.erosion(cx + i, cz + j, this::fields, FastErosionFilter.ORIGINAL, e);
            sm[j * PATCH + i] = heightBase(x, z) + .25 * e[0];
        }
        derivGrid = savedGrid; w = sw; h = sh; ex = sx; ez = sz;
        return std(tgt) / std(sm);
    }

    void render(Path out, String arm) throws Exception {
        double[] eg = new double[w * h];
        final int i0 = Math.max(2, (int) Math.floor(ox * outputStep / step) - ex - 3);
        final int i1 = Math.min(w - 2, (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 3);
        final int j0 = Math.max(2, (int) Math.floor(oz * outputStep / step) - ez - 3);
        final int j1 = Math.min(h - 2, (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 3);
        java.util.stream.IntStream.range(j0, j1).parallel().forEach(j -> {
            float[] e = new float[3];
            for (int i = i0; i < i1; i++) {
                FastErosionFilter.erosion(ex + i, ez + j, this::fields, FastErosionFilter.ORIGINAL, e);
                eg[j * w + i] = e[0];
            }
        });
        double[] height = new double[N * N], ero = new double[N * N], mask = new double[N * N];
        float[] d = new float[3];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i;
            double x = (ox + i) * outputStep, z = (oz + j) * outputStep;
            double[] q = domain(x, z);
            double b = heightBase(q[0], q[1]);
            double e = interp(eg, q[0] / step, q[1] / step, ex, ez, w, h);
            double amp = mountain.amplitude(q[0], q[1]);
            height[at] = k * (b + .25 * e) * amp;
            ero[at] = k * .25 * e * amp;
            fields((int) Math.floor(q[0] / step), (int) Math.floor(q[1] / step), d);
            double s = StrictMath.hypot(d[0], d[1]) * 262144.0 * 2.0;
            double v = s < .1 ? .5 * s * s / .1 : s - .05;
            double tv = 1 - Math.max(0, Math.min(1, v));
            mask[at] = 1 - tv * tv;
        }
        write(out.resolve(arm + "-height.f64be"), height);
        write(out.resolve(arm + "-erosion.f64be"), ero);
        write(out.resolve(arm + "-mask.f64be"), mask);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionSigmaProbe p = new FastErosionSigmaProbe();
        StringBuilder meta = new StringBuilder();
        for (int i = 0; i < ARMS.length; i++) {
            p.configure(SIGMAS[i]);
            p.prepare();
            p.k = p.calibratePatch(0);
            double kPatchB = p.calibratePatch(1024);   // stationarity check: independent local region
            p.render(out, ARMS[i]);
            double a610 = StrictMath.exp(-2 * Math.PI * Math.PI * p.sigmaWu * p.sigmaWu / (610.0 * 610.0));
            double a260 = StrictMath.exp(-2 * Math.PI * Math.PI * p.sigmaWu * p.sigmaWu / (260.0 * 260.0));
            String line = String.format(Locale.ROOT,
                    "%s sigmaWu=%.6f sigmaSamples=%.6f radius=%d halo=%d alpha610=%.9f alpha260=%.9f "
                            + "kPatch=%.17g kPatchB=%.17g patchToPatchRelDiff=%.6f",
                    ARMS[i], p.sigmaWu, p.sigmaSamples, p.radius, p.halo, a610, a260, p.k,
                    kPatchB, Math.abs(kPatchB - p.k) / p.k);
            meta.append(line).append('\n');
            System.out.println(line);
        }
        Files.writeString(out.resolve("parameters.txt"), meta.toString());
    }

    static double std(double[] a) {
        double m = 0; for (double x : a) m += x; m /= a.length;
        double v = 0; for (double x : a) v += (x - m) * (x - m);
        return StrictMath.sqrt(v / a.length);
    }

    static void write(Path p, double[] a) throws Exception {
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            for (double x : a) { if (!Double.isFinite(x)) throw new AssertionError("nonfinite"); s.writeDouble(x); }
        }
    }
}
