package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.client.preview.ColorMap;
import com.geogenesis.standalone.FastErosionFilter;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolated replacement of the fancy erosion branch, retaining its location, warp and 65% weight. */
public final class FastErosionInTypeProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final int PER_SCALE = 256, SCALES = 3, N = PER_SCALE * SCALES + 1;
    private final MorphCatalog catalog = new MorphCatalog(SEED);
    private final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphNoise.PerlinRidge ridge = ridgeNode();
    private final double scaleWu = 1.0 / ridge.frequency();
    private final double stepWu = scaleWu / PER_SCALE;
    private final int ox = (int) Math.round(-3242.5 / stepWu) - N / 2;
    private final int oz = (int) Math.round(-3365.5 / stepWu) - N / 2;
    private static final float BASE_AMP = 4e-4f;
    private final double erosionStep;
    private final int ex, ez, nativeWidth, nativeHeight, haloWidth, haloHeight;
    private final double[] rawHalo, erosionHalo;

    private FastErosionInTypeProbe(boolean scaleExperiment) {
        // User-specified hypothesis: 1165/9 samples per main rise. BASE_FREQ in the demo
        // is actually a binary shift (1<<9), NOT nine cycles per frame; do not conflate them.
        erosionStep = scaleExperiment ? scaleWu / (1165.0 / 9.0) : stepWu;
        ex = (int) Math.floor(ox * stepWu / erosionStep);
        ez = (int) Math.floor(oz * stepWu / erosionStep);
        nativeWidth = (int) Math.ceil((ox + N - 1) * stepWu / erosionStep) - ex + 1;
        nativeHeight = (int) Math.ceil((oz + N - 1) * stepWu / erosionStep) - ez + 1;
        haloWidth = nativeWidth + 4; haloHeight = nativeHeight + 4;
        rawHalo = new double[haloWidth * haloHeight];
        erosionHalo = new double[haloWidth * haloHeight];
    }

    private MorphNoise.PerlinRidge ridgeNode() {
        MorphNoise.Node raw = catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        require(raw instanceof MorphNoise.Warped, "M1 raw structure changed");
        MorphNoise.Node body = ((MorphNoise.Warped) raw).input();
        require(body instanceof MorphNoise.Mul, "M1 body structure changed");
        MorphNoise.Node ridgeNode = ((MorphNoise.Mul) body).a();
        require(ridgeNode instanceof MorphNoise.PerlinRidge, "M1 ridge missing");
        return (MorphNoise.PerlinRidge) ridgeNode;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("usage: <NEW output directory> [scale]");
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite: " + out);
        Files.createDirectories(out);
        new FastErosionInTypeProbe(args.length == 2 && args[1].equals("scale")).run(out);
    }

    private void run(Path out) throws Exception {
        require(FastErosionFilter.wrapMode == FastErosionFilter.WrapMode.INTENT, "wrap changed");
        fillHalo();
        double prototype = prototypeMedian(out);
        double[] slopes = new double[N * N], laps = new double[N * N], masks = new double[N * N];
        float[] d = new float[3], e = new float[3];
        for (int j = 0; j < haloHeight; j++) for (int i = 0; i < haloWidth; i++) {
            int px = ex + i - 2, pz = ez + j - 2;
            FastErosionFilter.erosion(px, pz, this::derivatives, FastErosionFilter.ORIGINAL, e);
            erosionHalo[j * haloWidth + i] = e[0];
        }
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int at = j * N + i;
            // Common world points in both arms/scales: centered derivatives of h_in around
            // each point using the selected sample step, no changes to the output grid.
            derivativesAt((ox + i) * stepWu, (oz + j) * stepWu, d);
            slopes[at] = workingSlope(d);
            laps[at] = d[2] * FastErosionFilter.ORIGINAL.maxHeight()
                    / FastErosionFilter.ORIGINAL.laplacianDiv();
            // ORIGINAL ridge/gully rounding both .1: first ease-in -> ease-out mask.
            float s = (float) slopes[at], r = FastErosionFilter.ORIGINAL.ridgeRounding();
            float v = s < r ? .5f * s * s / r : s - .5f * r;
            float t = 1f - Math.max(0f, Math.min(1f, v));
            masks[at] = 1f - t * t;
        }
        write(out, "input-slope", slopes); write(out, "input-lap", laps);
        write(out, "initial-slope-mask", masks);
        double[] nativeSlopes = new double[nativeWidth * nativeHeight];
        double[] nativeLap = new double[nativeSlopes.length];
        for (int j = 0; j < nativeHeight; j++) for (int i = 0; i < nativeWidth; i++) {
            derivatives(ex + i, ez + j, d);
            nativeSlopes[j * nativeWidth + i] = workingSlope(d);
            nativeLap[j * nativeWidth + i] = d[2] * FastErosionFilter.ORIGINAL.maxHeight()
                    / FastErosionFilter.ORIGINAL.laplacianDiv();
        }
        write(out, "native-input-slope", nativeSlopes); write(out, "native-input-lap", nativeLap);
        sampleArms(out);
        writePalette(out);
        writeMetadata(out, prototype, median(slopes));
        System.out.printf("PASS prototypeMedian=%.17g typeMedian=%.17g ratio=%.9f%n",
                prototype, median(slopes), median(slopes) / prototype);
    }

    private void fillHalo() {
        for (int j = 0; j < haloHeight; j++) for (int i = 0; i < haloWidth; i++) {
            rawHalo[j * haloWidth + i] = mountain.raw((ex + i - 2) * erosionStep, (ez + j - 2) * erosionStep);
        }
    }

    private double raw(int px, int pz) {
        int i = px - ex + 2, j = pz - ez + 2;
        if (i >= 0 && i < haloWidth && j >= 0 && j < haloHeight) return rawHalo[j * haloWidth + i];
        return mountain.raw(px * erosionStep, pz * erosionStep);
    }

    /** Prototype sample-unit derivatives: h_in=raw*BASE_AMP, integer sample spacing one. */
    private void derivatives(int px, int pz, float[] dst) {
        double factor = BASE_AMP;
        double c = raw(px, pz), xp = raw(px + 1, pz), xm = raw(px - 1, pz);
        double zp = raw(px, pz + 1), zm = raw(px, pz - 1);
        dst[0] = (float) (factor * (xp - xm) / 2.0);
        dst[1] = (float) (factor * (zp - zm) / 2.0);
        dst[2] = (float) (factor * (xp + xm + zp + zm - 4.0 * c));
    }

    private void derivativesAt(double x, double z, float[] dst) {
        double c = mountain.raw(x, z), xp = mountain.raw(x + erosionStep, z);
        double xm = mountain.raw(x - erosionStep, z), zp = mountain.raw(x, z + erosionStep);
        double zm = mountain.raw(x, z - erosionStep);
        dst[0] = (float) (BASE_AMP * (xp - xm) / 2);
        dst[1] = (float) (BASE_AMP * (zp - zm) / 2);
        dst[2] = (float) (BASE_AMP * (xp + xm + zp + zm - 4 * c));
    }

    /** Exact float arithmetic used in FastErosionFilter, including BASE_AMP in the demo callback. */
    private static double workingSlope(float[] d) {
        return (float) StrictMath.hypot(d[0], d[1]) * FastErosionFilter.ORIGINAL.maxHeight()
                * FastErosionFilter.ORIGINAL.steepnessScale();
    }

    private static double prototypeMedian(Path out) throws Exception {
        Class<?> demo = Class.forName("com.geogenesis.standalone.FastErosionOriginalDemo");
        Method base = demo.getDeclaredMethod("baseDerivatives", int.class, int.class, float[].class);
        Method gridX = demo.getDeclaredMethod("gridX", float.class);
        Method gridZ = demo.getDeclaredMethod("gridZ", float.class);
        base.setAccessible(true); gridX.setAccessible(true); gridZ.setAccessible(true);
        // Frozen approved demo frame, not a terrain input. No demo height enters either arm.
        int width = 1165, height = 654;
        double[] slopes = new double[width * height];
        float[] d = new float[3];
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int px = (int) gridX.invoke(null, col + .5f);
            int pz = (int) gridZ.invoke(null, (height - 1 - row) + .5f);
            base.invoke(null, px, pz, d);
            slopes[row * width + col] = workingSlope(d);
        }
        write(out, "prototype-input-slope", slopes);
        return median(slopes);
    }

    private void sampleArms(Path out) throws Exception {
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        MorphKernel original = new MorphKernel(recipes, SEED, true);
        MorphKernel replacement = new MorphKernel(recipes, SEED, true);
        MorphKernel carrier = new MorphKernel(recipes, SEED, false);
        // Diagnostic-only instance injection: only erosion node changes. Kernel code/warp/blend stay real.
        java.lang.reflect.Field field = MorphKernel.class.getDeclaredField("erosion");
        field.setAccessible(true);
        MorphNoise.Node old = (MorphNoise.Node) field.get(replacement);
        require(old instanceof MorphNoise.Erosion, "production erosion node changed");
        MorphNoise.Node fast = new MorphNoise.DualNode(
                (x, z) -> (float) replacementValue(x, z), this::replacementValue);
        field.set(replacement, fast);
        require(field.get(replacement) == fast, "replacement failed");
        java.lang.reflect.Field directionField = MorphKernel.class.getDeclaredField("direction");
        directionField.setAccessible(true);
        MorphNoise.Node direction = (MorphNoise.Node) directionField.get(replacement);
        MorphLandField production = new MorphLandField(catalog);
        double[] weights = original.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
        double[] a = new double[N * N], b = new double[N * N], raw = new double[N * N];
        double[] erosion = new double[N * N], baseSignal = new double[N * N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int at = j * N + i, px = ox + i, pz = oz + j;
                double x = px * stepWu, z = pz * stepWu;
                a[at] = original.sampleLand(x, z, weights);
                require(bits(a[at], production.heightForMerged(x, z, weights)), "A != production");
                raw[at] = carrier.sampleLand(x, z, weights);
                b[at] = replacement.sampleLand(x, z, weights);
                double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
                double wx = x + Math.sin(angle) * 2, wz = z + Math.cos(angle) * 2;
                double source = mountain.raw(wx, wz), branch = replacementValue(wx, wz);
                double expected = (branch + (source - branch) * (1 - .65))
                        * mountain.amplitude(wx, wz) + mountain.bias(wx, wz);
                require(bits(b[at], expected), "B insertion/domain/blend mismatch");
                baseSignal[at] = mountain.raw(x, z);
                erosion[at] = erosionAt(x / erosionStep, z / erosionStep);
                require(Double.isFinite(b[at]), "nonfinite B");
                require(Math.abs(b[at] - raw[at]) <= .65 * .25 * mountain.amplitude(x, z) + 1e-12,
                        "replacement escaped original blend amplitude");
            }
            if (j % 128 == 0) System.out.println("row " + j + "/" + N);
        }
        verifyOrder(this::derivatives);
        write(out, "A", a); write(out, "B", b); write(out, "raw-shell", raw);
        write(out, "raw-signal", baseSignal); write(out, "erosion-unit", erosion);
        Files.writeString(out.resolve("verification-java.txt"), "PASS every A sample bit-identical to "
                + "MorphLandField.heightForMerged(onehot M1): " + N * N + "\n"
                + "PASS production MorphKernel only erosion node instance replaced via diagnostics reflection\n"
                + "PASS same 2wu domain warp, .65 blend, shell; |B-carrier|<=.65*.25*.7 every sample\n"
                + "PASS filter repeat/reverse-order 257 samples, raw halo direct stencil; all B finite\n"
                + "ORIGINAL unchanged; no parameter retuning and no normalization coefficient\n");
    }

    private double replacementValue(double x, double z) {
        return mountain.raw(x, z) + FastErosionFilter.ORIGINAL.amplitude() * erosionAt(x / erosionStep, z / erosionStep);
    }

    /** Integer-only filter: bilinear extension of E, never quantize domain-warped coordinates. */
    private double erosionAt(double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double tx = px - ix, tz = pz - iz;
        double a = erosionAt(ix, iz), b = erosionAt(ix + 1, iz);
        double c = erosionAt(ix, iz + 1), d = erosionAt(ix + 1, iz + 1);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private double erosionAt(int px, int pz) {
        int i = px - ex + 2, j = pz - ez + 2;
        require(i >= 0 && i < haloWidth && j >= 0 && j < haloHeight, "erosion halo exhausted");
        return erosionHalo[j * haloWidth + i];
    }

    private void verifyOrder(FastErosionFilter.Fields fields) {
        float[] e = new float[3];
        for (int t = 256; t >= 0; t--) {
            int at = (int) ((long) t * (nativeWidth * nativeHeight - 1) / 256);
            int px = ex + at % nativeWidth, pz = ez + at / nativeWidth;
            FastErosionFilter.erosion(px, pz, fields, FastErosionFilter.ORIGINAL, e);
            require(bits(e[0], erosionAt(px, pz)), "filter query order changed");
            for (int[] off : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                require(bits(raw(px + off[0], pz + off[1]), mountain.raw(
                        (px + off[0]) * erosionStep, (pz + off[1]) * erosionStep)), "halo changed raw");
            }
        }
    }

    /** Read actual GeoPalette stops, use the production ColorMap Lab implementation; no copied palette. */
    private static void writePalette(Path out) throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/geogenesis/client/preview/GeoPalette.java"));
        int start = source.indexOf("private static final float[][] S_ELEVATION_E = {");
        String block = source.substring(start, source.indexOf("};", start));
        Matcher rows = Pattern.compile("\\{\\s*([-+.0-9]+)f,\\s*([-+.0-9]+)f,\\s*([-+.0-9]+)f,\\s*([-+.0-9]+)f\\s*}").matcher(block);
        float[][] stops = new float[9][4];
        int at = 0;
        while (rows.find()) {
            for (int c = 0; c < 4; c++) stops[at][c] = Float.parseFloat(rows.group(c + 1));
            at++;
        }
        require(at == 9, "GeoPalette format changed");
        double lo = -.35, hi = 1.2;
        for (int i = 0; i < at; i++) {
            double e = i == 0 ? lo : i == at - 1 ? hi : stops[i][0];
            stops[i][0] = (float) Math.max(0, Math.min(1, (e - lo) / (hi - lo)));
        }
        ColorMap palette = new ColorMap("elevation", stops);
        StringBuilder csv = new StringBuilder("e,r,g,b\n");
        for (int i = 0; i <= 4096; i++) {
            float p = (float) i / 4096;
            int rgb = palette.getRGB(p);
            csv.append(lo + (hi - lo) * i / 4096).append(',').append(rgb >> 16 & 255)
                    .append(',').append(rgb >> 8 & 255).append(',').append(rgb & 255).append('\n');
        }
        Files.writeString(out.resolve("project-palette.csv"), csv);
    }

    private void writeMetadata(Path out, double prototype, double typeMedian) throws Exception {
        String json = String.format(Locale.ROOT,
                "{\"seed\":%d,\"size\":%d,\"ridgeScaleWu\":%.17g,\"stepWu\":%.17g,"
                + "\"spanWu\":%.17g,\"originSample\":[%d,%d],\"originWu\":[%.17g,%.17g],"
                + "\"prototypeMedian\":%.17g,\"typeMedian\":%.17g,\"medianRatio\":%.17g,"
                + "\"baseAmp\":%.17g,\"amplitudeRaw\":0.25,\"blendWeight\":0.65,"
                + "\"amplitudeShell\":%.17g,\"shellAmp\":%.17g,\"groundE\":%.17g,\"eSpan\":%.17g,"
                + "\"filterParams\":\"%s\",\"wrap\":\"%s\"}\n",
                SEED, N, scaleWu, stepWu, (N - 1) * stepWu, ox, oz, ox * stepWu, oz * stepWu,
                prototype, typeMedian, typeMedian / prototype, (double) BASE_AMP,
                .65 * .25 * mountain.amplitude(0, 0), mountain.amplitude(0, 0),
                MorphScale.GROUND_E, MorphScale.E_SPAN, FastErosionFilter.ORIGINAL, FastErosionFilter.wrapMode);
        json = json.strip();
        json = json.substring(0, json.length() - 1) + String.format(Locale.ROOT,
                ",\"erosionStepWu\":%.17g,\"erosionGridSize\":[%d,%d],\"erosionOriginSample\":[%d,%d]}",
                erosionStep, nativeWidth, nativeHeight, ex, ez);
        Files.writeString(out.resolve("sampling.json"), json + "\n");
        Files.writeString(out.resolve("scale-derivation.txt"),
                "ONLY treatment: erosion sample step. Same 769^2 output grid/window and A.\n"
                + "User-specified hypothesis: 1165/9=129.4444444 samples per main rise.\n"
                + "erosionStep=L/(1165/9)=L*9/1165; prior step=L/256; ratio=2304/1165.\n"
                + "Source caveat: actual demo BASE_FREQ=9 is used in 1<<freqShift, i.e. 512 sample\n"
                + "simplex frequency scale, NOT nine cycles across 1165. User hypothesis executed,\n"
                + "but NOT called an exact source-derived wavelength match.\n"
                + "Absolute sample lattice origin remains world zero. Phase at fixed world locations\n"
                + "changes as a consequence of scaling; no independently chosen phase shift.\n"
                + "Native grid covers fixed window via floor/ceil bounds plus two-sample halo.\n"
                + "Output maps/profiles stay 769^2 so A can match previous array bit-for-bit.\n"
                + "input-slope/lap/mask = finite-difference stencils at identical world output points\n"
                + "with selected step; actual native lattice diagnostics are separately exported.\n");
        Files.writeString(out.resolve("method.txt"),
                "DIAGNOSTICS ONLY. No src/main changes or production integration.\n"
                + "Production MorphKernel B instance: reflectively replace ONLY erosion Node with DualNode.\n"
                + "Same kernel code, same direction domain, same blend(eval(fast,p),source,1-.65*mountain).\n"
                + "Fast branch=raw(p)+.25*E(p); raw from actual catalog Erosion.input.\n"
                + "ORIGINAL unchanged: maxHeight=262144, steepnessScale=2, laplacianDiv=25.\n"
                + "h_in=raw*4e-4f; centered gx/gz and 5-point lap all multiplied by same BASE_AMP.\n"
                + "Derivative unit is PER INTEGER SAMPLE as original filter contract, NOT per wu.\n"
                + "Selected erosionStepWu in sampling.json; per-sample derivative scales with that step.\n"
                + "Only sample spacing changes in scale experiment; see scale-derivation.txt.\n"
                + "Gabor requires integer positions. At actual noninteger 2wu warped domain, E is bilinearly\n"
                + "extended from four absolute sample-grid E values; raw is evaluated EXACTLY at warped p.\n"
                + "No coordinate rounding/snapping at domain(p). Bilinear E interpolation can smooth details,\n"
                + "is an adapter assumption, not a modified filter parameter; no continuous port is claimed.\n"
                + "OUTPUT grid L=1/ridge.frequency (~610wu); span=3L; output step=L/256; 769^2.\n"
                + "Origin snapped near previous center (-3242.5,-3365.5), same window as prior run.\n"
                + "raw-shell is real carrier kernel with erosion OFF but direction warp ON, common to A/B.\n"
                + "A includes old .65 erosion branch; B uses same .65 branch weight, no old erosion.\n"
                + "Input slope/lap/mask arrays describe identical output world points before domain warp.\n"
                + "Native-input arrays separately record actual integer filter grid (shape in sampling.json).\n"
                + "Initial slope mask=original easeOutQuad(easeInQuadLinear(S,rounding=.1));\n"
                + "saturation S>=1.05 => mask=1; subsequent octave masks are NOT measured by this probe.\n"
                + "No hydrology, coverage, HeightCurve or world postprocess. No per-window normalization.\n");
    }

    private static void write(Path out, String name, double[] a) throws Exception {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(out.resolve(name + ".f64be"))))) {
            for (double v : a) { require(Double.isFinite(v), "nonfinite " + name); stream.writeDouble(v); }
        }
    }

    private static double median(double[] a) {
        double[] sorted = a.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[n / 2];
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
