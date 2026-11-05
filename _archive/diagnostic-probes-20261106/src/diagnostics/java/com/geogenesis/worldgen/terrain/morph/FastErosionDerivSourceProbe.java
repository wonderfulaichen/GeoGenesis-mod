package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * J: height stays the full 2-octave raw; ONLY the derivative source changes.
 * J1 derivatives from the first ridge octave x first scaler octave (>=610wu content).
 * J2 derivatives from the full base low-passed with sigma = 260/2 = 130wu, radius 3 sigma.
 */
public final class FastErosionDerivSourceProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, CAL = 8192;
    static final float BASE_AMP = 4e-4f;
    static final double LOCKED_K = .8205431216912392;
    static final double CUTOFF_WU = 260.0;

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node scalerFull, scalerLow, direction, wx, wz, l2full;
    final MorphNoise.PerlinRidge ridgeFull, ridgeLow;
    final double mainScale, step, outputStep, warpPow, sigmaWu, sigmaSamples;
    final int radius, halo;
    final int ox, oz, ex, ez, w, h;

    String arm;                    // J1 or J2
    double[] derivGrid;            // derivative source over the padded window
    int dw, dh, dx0, dz0;
    double[] gxGrid, gzGrid, lapGrid;  // derivatives on the native window
    double k;

    FastErosionDerivSourceProbe() {
        MorphNoise.Warped warped = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warped.input();
        ridgeFull = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.AlphaMix prodScaler = (MorphNoise.AlphaMix) body.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) prodScaler.input();
        wx = warped.wx(); wz = warped.wz(); warpPow = warped.pow();
        mainScale = 1.0 / ridgeFull.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        ridgeLow = new MorphNoise.PerlinRidge(ridgeFull.seed(), ridgeFull.frequency(), 1,
                ridgeFull.lacunarity(), ridgeFull.gain());
        scalerFull = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), prodScaler.alpha());
        scalerLow = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 1,
                p.lacunarity(), p.gain()), prodScaler.alpha());
        l2full = MorphNoise.warp(new MorphNoise.Mul(ridgeFull, scalerFull), wx, wz, (float) warpPow);
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);

        sigmaWu = CUTOFF_WU / 2.0;                 // sigma = cutoff/2
        sigmaSamples = sigmaWu / step;
        radius = (int) Math.ceil(3 * sigmaSamples); // 3 sigma support
        halo = radius + 2;                          // +1 for the central differences after smoothing

        ox = (int) Math.round(-3242.5 / outputStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step) - halo;
        ez = (int) Math.floor(oz * outputStep / step) - halo;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + halo;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + halo;
    }

    /** Full 2-octave base (the HEIGHT source, unchanged). */
    double heightBase(double x, double z) { return MorphNoiseEval.eval(l2full, x, z); }

    /** Derivative source for J1: first ridge octave x first scaler octave, same warp. */
    double lowBase(double x, double z) {
        double dx = MorphNoiseEval.eval(wx, x, z), dz = MorphNoiseEval.eval(wz, x, z);
        double px = x + (dx - .5) * warpPow, pz = z + (dz - .5) * warpPow;
        return MorphNoiseEval.eval(ridgeLow, px, pz) * MorphNoiseEval.eval(scalerLow, px, pz);
    }

    double derivValue(double x, double z) {
        if (arm.equals("J1")) return lowBase(x, z);
        // J2: smoothed full base, read from the precomputed grid
        int i = (int) Math.floor(x / step) - dx0, j = (int) Math.floor(z / step) - dz0;
        if (i < 0 || j < 0 || i >= dw || j >= dh) return heightBase(x, z);
        return derivGrid[j * dw + i];
    }

    void buildDerivGrid() {
        dx0 = ex; dz0 = ez; dw = w; dh = h;
        derivGrid = new double[dw * dh];
        if (arm.equals("J1")) {
            for (int j = 0; j < dh; j++) for (int i = 0; i < dw; i++)
                derivGrid[j * dw + i] = lowBase((dx0 + i) * step, (dz0 + j) * step);
            return;
        }
        // J2: separable Gaussian over the procedural full base, sigma computed from the cutoff.
        double[] src = new double[dw * dh];
        java.util.stream.IntStream.range(0, dh).parallel().forEach(j -> {
            for (int i = 0; i < dw; i++) src[j * dw + i] = heightBase((dx0 + i) * step, (dz0 + j) * step);
        });
        double[] kern = new double[2 * radius + 1];
        double sum = 0;
        for (int k = -radius; k <= radius; k++) {
            double wgt = StrictMath.exp(-(k * k) / (2 * sigmaSamples * sigmaSamples));
            kern[k + radius] = wgt; sum += wgt;
        }
        for (int k = 0; k < kern.length; k++) kern[k] /= sum;
        double[] tmp = new double[dw * dh];
        java.util.stream.IntStream.range(0, dh).parallel().forEach(j -> {
            for (int i = 0; i < dw; i++) {
                double acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int ii = Math.min(dw - 1, Math.max(0, i + k));
                    acc += kern[k + radius] * src[j * dw + ii];
                }
                tmp[j * dw + i] = acc;
            }
        });
        java.util.stream.IntStream.range(0, dw).parallel().forEach(i -> {
            for (int j = 0; j < dh; j++) {
                double acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int jj = Math.min(dh - 1, Math.max(0, j + k));
                    acc += kern[k + radius] * tmp[jj * dw + i];
                }
                derivGrid[j * dw + i] = acc;
            }
        });
        // Edge clamp is used ONLY for the smoothing halo bookkeeping; the arm window sits >=radius
        // inside the padded grid, so the displayed samples only ever see true procedural values.
    }

    void buildDerivatives() {
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
        if (calibLow) return lowBase((dx0 + i) * step, (dz0 + j) * step);
        if (i >= 0 && j >= 0 && i < dw && j < dh) return derivGrid[j * dw + i];
        return arm.equals("J1") ? lowBase((dx0 + i) * step, (dz0 + j) * step) : heightBase((dx0 + i) * step, (dz0 + j) * step);
    }

    /**
     * True only while calibrating. The sigma=130wu kernel cannot be evaluated at the distant
     * calibration domain (radius 329 samples over a domain 40x the window), so calibration uses the
     * first-octave source for both arms; the report quantifies how close J2's field is to it.
     */
    boolean calibLow;

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

    double calibrate() {
        Random r = new Random((long) (double) 0x4641535445524f53L);
        double[] t = new double[CAL], s = new double[CAL]; float[] e = new float[3];
        for (int i = 0; i < CAL; i++) {
            double x = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            double z = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            double[] q = domain(x, z);
            t[i] = mountain.raw(q[0], q[1]);
            FastErosionFilter.erosion((int) Math.round(q[0] / step), (int) Math.round(q[1] / step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            s[i] = heightBase(q[0], q[1]) + .25 * e[0];   // HEIGHT base is unchanged
        }
        return std(t) / std(s);
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
            double[] q = domain(x, z);
            double b = heightBase(q[0], q[1]);          // height: full 2 octaves, unchanged
            double e = interp(eg, q[0] / step, q[1] / step);
            double amp = mountain.amplitude(q[0], q[1]);
            height[at] = k * (b + .25 * e) * amp;
            ero[at] = k * .25 * e * amp;
            int px = (int) Math.floor(q[0] / step), pz = (int) Math.floor(q[1] / step);
            fields(px, pz, d);
            double s = StrictMath.hypot(d[0], d[1]) * 262144.0 * 2.0;
            double v = s < .1 ? .5 * s * s / .1 : s - .05;
            double tv = 1 - Math.max(0, Math.min(1, v));
            mask[at] = 1 - tv * tv;
            aspect[at] = StrictMath.atan2(d[1], d[0]);
        }
        write(out.resolve(arm + "-height.f64be"), height);
        write(out.resolve(arm + "-erosion.f64be"), ero);
        write(out.resolve(arm + "-mask.f64be"), mask);
        write(out.resolve(arm + "-aspect.f64be"), aspect);
        // derivative-source diagnostics (what the filter actually consumed)
        double[] dsrc = new double[N * N];
        for (int at = 0; at < N * N; at++) {
            double x = (ox + at % N) * outputStep, z = (oz + at / N) * outputStep;
            double[] q = domain(x, z);
            dsrc[at] = arm.equals("J1") ? lowBase(q[0], q[1]) : derivValue(q[0], q[1]);
        }
        write(out.resolve(arm + "-derivsource.f64be"), dsrc);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionDerivSourceProbe p = new FastErosionDerivSourceProbe();
        // Control: J1's derivative source at 2 octaves would be the height base; check the low field
        // really is the first-octave-only version by comparing against a 1-octave node evaluation.
        double maxd = 0;
        for (int i = 0; i < 500; i++) {
            double x = -3000 + i * 7.3, z = -2500 + i * 5.1;
            maxd = Math.max(maxd, Math.abs(p.lowBase(x, z)
                    - MorphNoiseEval.eval(MorphNoise.warp(new MorphNoise.Mul(p.ridgeLow, p.scalerLow),
                    p.wx, p.wz, (float) p.warpPow), x, z)));
        }
        if (maxd != 0) throw new AssertionError("J1 low source is not the 1-octave node: " + maxd);
        System.out.println("PASS J1 derivative source equals the 1-octave node bitwise on a sample grid");
        System.out.printf(Locale.ROOT,
                "J2 low-pass: sigmaWu=%.6f sigmaSamples=%.6f radius=%d halo=%d grid=%dx%d%n",
                p.sigmaWu, p.sigmaSamples, p.radius, p.halo, p.w, p.h);
        StringBuilder meta = new StringBuilder();
        for (String a : new String[]{"J1", "J2"}) {
            p.arm = a;
            p.buildDerivGrid();
            p.buildDerivatives();
            p.calibLow = true;                 // see dv(): same-rule calibration, disclosed approximation
            p.k = p.calibrate();
            p.calibLow = false;
            p.render(out);
            meta.append(String.format(Locale.ROOT, "%s k=%.17g%n", a, p.k));
            System.out.println(a + " k=" + p.k + " rendered");
        }
        Files.writeString(out.resolve("parameters.json"), String.format(Locale.ROOT,
                "{\"cutoffWu\":%.17g,\"sigmaWu\":%.17g,\"sigmaSamples\":%.17g,\"radius\":%d,\"halo\":%d,"
                        + "\"nativeSize\":[%d,%d],\"nativeOrigin\":[%d,%d],\"stepWu\":%.17g,\"lockedK\":%.17g}\n",
                CUTOFF_WU, p.sigmaWu, p.sigmaSamples, p.radius, p.halo, p.w, p.h, p.ex, p.ez, p.step, LOCKED_K));
        Files.writeString(out.resolve("k-report.txt"), meta.toString());
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
