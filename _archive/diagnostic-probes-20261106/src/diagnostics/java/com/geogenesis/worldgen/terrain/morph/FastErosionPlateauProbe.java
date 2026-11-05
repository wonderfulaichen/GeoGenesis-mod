package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Stage 2: erosion applied to the plateau source BEFORE Terrace, on a pure plateau window.
 * A' = production (Terrace on raw source). B' = Terrace(k*(raw + .25E)) + surface.
 */
public final class FastErosionPlateauProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 385, CAL = 8192;
    static final float BASE_AMP = 4e-4f;

    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe plateau = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_PLATEAU));
    final MorphNoise.Node terrace, surface, direction;
    final MorphNoise.PerlinRidge ridge0;
    final double mainScale, step, outputStep;
    double cx, cz;
    int ox, oz, ex, ez, w, h;
    double[] baseGrid, gxGrid, gzGrid, lapGrid;
    double k;

    FastErosionPlateauProbe() {
        MorphNoise.Warped w0 = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        ridge0 = (MorphNoise.PerlinRidge) ((MorphNoise.Mul) w0.input()).a();
        mainScale = 1.0 / ridge0.frequency();
        step = mainScale / 512;
        outputStep = step * 2;
        MorphCatalog.Entry e = catalog.entry(MorphCatalog.IDX_PLATEAU);
        terrace = e.terraceNode();
        surface = e.surfaceNode();
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
    }

    void setWindow(double centerX, double centerZ) {
        cx = centerX; cz = centerZ;
        ox = (int) Math.round(centerX / outputStep) - N / 2;
        oz = (int) Math.round(centerZ / outputStep) - N / 2;
        ex = (int) Math.floor(ox * outputStep / step) - 4;
        ez = (int) Math.floor(oz * outputStep / step) - 4;
        w = (int) Math.ceil((ox + N - 1) * outputStep / step) - ex + 1 + 4;
        h = (int) Math.ceil((oz + N - 1) * outputStep / step) - ez + 1 + 4;
    }

    /** Pure-plateau pre-terrace source; mountain weight is 0 so the kernel's domain() is the identity. */
    double rawSource(double x, double z) { return plateau.raw(x, z); }

    double base(double px, double pz) { return rawSource(px * step, pz * step); }

    void prepare() {
        baseGrid = new double[w * h]; gxGrid = new double[w * h];
        gzGrid = new double[w * h]; lapGrid = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++)
            baseGrid[j * w + i] = base(ex + i, ez + j);
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            double c = val(i, j), xp = val(i + 1, j), xm = val(i - 1, j);
            double zp = val(i, j + 1), zm = val(i, j - 1);
            gxGrid[at] = (xp - xm) / 2; gzGrid[at] = (zp - zm) / 2;
            lapGrid[at] = xp + xm + zp + zm - 4 * c;
        }
    }

    double val(int i, int j) {
        if (i >= 0 && j >= 0 && i < w && j < h) return baseGrid[j * w + i];
        return base(ex + i, ez + j);
    }

    void fields(int px, int pz, float[] d) {
        int i = px - ex, j = pz - ez;
        boolean in = i >= 0 && j >= 0 && i < w && j < h;
        double gx, gz, lap;
        if (in) { gx = gxGrid[j * w + i]; gz = gzGrid[j * w + i]; lap = lapGrid[j * w + i]; }
        else {
            gx = (val(i + 1, j) - val(i - 1, j)) / 2;
            gz = (val(i, j + 1) - val(i, j - 1)) / 2;
            lap = val(i + 1, j) + val(i - 1, j) + val(i, j + 1) + val(i, j - 1) - 4 * val(i, j);
        }
        d[0] = (float) (gx * BASE_AMP); d[1] = (float) (gz * BASE_AMP); d[2] = (float) (lap * BASE_AMP);
    }

    double calibrate() {
        Random r = new Random((long) (double) 0x4641535445524f53L);
        double[] t = new double[CAL], s = new double[CAL]; float[] e = new float[3];
        for (int i = 0; i < CAL; i++) {
            double x = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            double z = 16 * mainScale + r.nextInt((int) (64 * mainScale));
            t[i] = rawSource(x, z);
            FastErosionFilter.erosion((int) Math.round(x / step), (int) Math.round(z / step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            s[i] = rawSource(x, z) + .25 * e[0];
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

    /** terrace(source) + surface*plateau, exactly the kernel's plateau branch with weight 1. */
    double finish(double src, double x, double z) {
        double shaped = MorphNoiseEval.eval(terrace, src, 0) + MorphNoiseEval.eval(surface, x, z);
        return shaped * plateau.amplitude(x, z) + plateau.bias(x, z);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        FastErosionPlateauProbe p = new FastErosionPlateauProbe();

        // Window search: same criterion as every earlier window (pure single entry, coverage alpha 0).
        MorphLandField field = new MorphLandField(p.catalog);
        double bestX = Double.NaN, bestZ = Double.NaN;
        int scanned = 0;
        outer:
        for (double x = -8000; x <= 8000; x += 512)
            for (double z = -8000; z <= 8000; z += 512) {
                scanned++;
                MorphLandField.Sample s = field.sample(x, z);
                if (s.weight()[MorphCatalog.IDX_PLATEAU] == 1.0 && s.alpha() == 0.0) {
                    bestX = x; bestZ = z; break outer;
                }
            }
        if (Double.isNaN(bestX)) throw new AssertionError("no pure plateau window found");
        p.setWindow(bestX, bestZ);
        MorphLandField.Sample check = field.sample(bestX, bestZ);
        if (check.weight()[MorphCatalog.IDX_PLATEAU] != 1.0 || check.alpha() != 0.0)
            throw new AssertionError("window is not pure plateau");

        p.prepare();
        p.k = p.calibrate();

        double[] eg = new double[p.w * p.h];
        java.util.stream.IntStream.range(2, p.h - 2).parallel().forEach(j -> {
            float[] e = new float[3];
            for (int i = 2; i < p.w - 2; i++) {
                FastErosionFilter.erosion(p.ex + i, p.ez + j, p::fields, FastErosionFilter.ORIGINAL, e);
                eg[j * p.w + i] = e[0];
            }
        });

        double[] a = new double[N * N], b = new double[N * N], srcA = new double[N * N];
        double[] srcB = new double[N * N], ero = new double[N * N], terrA = new double[N * N];
        MorphKernel kernel = new MorphKernel(MorphLandRecipe.all(p.catalog), SEED, true);
        MorphLandField prod = new MorphLandField(p.catalog);
        double[] wp = kernel.merge(new int[]{MorphCatalog.IDX_PLATEAU}, new double[]{1});
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i;
            double x = (p.ox + i) * p.outputStep, z = (p.oz + j) * p.outputStep;
            a[at] = kernel.sampleLand(x, z, wp);
            if (Double.doubleToRawLongBits(a[at])
                    != Double.doubleToRawLongBits(prod.heightForMerged(x, z, wp)))
                throw new AssertionError("A' does not match the production kernel");
            double raw = p.rawSource(x, z);
            double e = p.interp(eg, x / p.step, z / p.step);
            srcA[at] = raw;
            srcB[at] = p.k * (raw + .25 * e);
            ero[at] = p.k * .25 * e;
            terrA[at] = MorphNoiseEval.eval(p.terrace, raw, 0);
            b[at] = p.finish(srcB[at], x, z);
        }
        // Order control: with erosion removed the pipeline must reduce to A' bitwise.
        int orderBad = 0;
        for (int at = 0; at < N * N; at++) {
            double x = (p.ox + at % N) * p.outputStep, z = (p.oz + at / N) * p.outputStep;
            if (Double.doubleToRawLongBits(p.finish(srcA[at], x, z))
                    != Double.doubleToRawLongBits(a[at])) orderBad++;
        }
        if (orderBad != 0) throw new AssertionError("erosion-off does not reduce to A': " + orderBad);
        write(out.resolve("Ap.f64be"), a);
        write(out.resolve("Bp.f64be"), b);
        write(out.resolve("sourceAp.f64be"), srcA);
        write(out.resolve("sourceBp.f64be"), srcB);
        write(out.resolve("erosion.f64be"), ero);
        write(out.resolve("terraceAp.f64be"), terrA);
        Files.writeString(out.resolve("parameters.json"), String.format(Locale.ROOT,
                "{\"centerWu\":[%.17g,%.17g],\"size\":%d,\"stepWu\":%.17g,\"outputStepWu\":%.17g,"
                        + "\"k\":%.17g,\"scannedCenters\":%d,\"plateauWeight\":%.17g,\"alpha\":%.17g,"
                        + "\"spacing\":%.17g,\"rampHeight\":%.17g,\"ramp\":%.17g,\"cliff\":%.17g,"
                        + "\"blendRange\":%.17g,\"steps\":%d,\"inMax\":%.17g,\"amp\":%.17g}\n",
                p.cx, p.cz, N, p.step, p.outputStep, p.k, scanned,
                check.weight()[MorphCatalog.IDX_PLATEAU], check.alpha(),
                MorphCatalog.PLATEAU_TERRACE_IN_MAX / (MorphCatalog.TERRACE_STEPS - 1),
                MorphCatalog.TERRACE_RAMP_HEIGHT, MorphCatalog.TERRACE_RAMP,
                MorphCatalog.TERRACE_CLIFF, MorphCatalog.TERRACE_BLEND, MorphCatalog.TERRACE_STEPS,
                MorphCatalog.PLATEAU_TERRACE_IN_MAX, p.plateau.amplitude(0, 0)));
        System.out.println("PASS A' equals production kernel; erosion-off reduces to A' bitwise; k=" + p.k
                + " center=(" + p.cx + "," + p.cz + ") scanned=" + scanned);
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
