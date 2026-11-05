package com.geogenesis.worldgen.terrain.morph;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.RuneErosionFilter;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;

/**
 * <b>Stage 1 isolated A/B: Rune inserted on the mountain SIGNAL, not after the mix.</b>
 *
 * <p>Production is untouched: this probe only builds extra {@link MorphLandField} instances from the
 * unmodified production traversal and writes arrays/metadata under {@code build/}. Nothing here is
 * wired into {@code GeoGenesisGenerator}, and no existing baseline directory is read or rewritten
 * except for read-only provenance comparisons.</p>
 *
 * <h3>Arms (same window, seed, HS/VS, palette and light for all)</h3>
 * <pre>
 *   control   P1 as accepted: MorphLandField(mountainTexture = true)   ← the frozen reference
 *   carrier   same traversal with the old MorphNoise.Erosion switched off (mountainTexture = false)
 *   post      O2 of the 2026-11-03 round: MorphSingleShaper(carrier) — Rune added AFTER mixing
 *   signal065 diagnostic only: signal arm with the Rune increment scaled by the old 0.65 blend weight
 *   signal    CANDIDATE: Rune applied to the M1 recipe raw (signal layer) inside the unchanged
 *             kernel, so the existing raw → amp/bias → clamp → coverage node transition still runs
 * </pre>
 *
 * <h3>Why {@code signal} is a signal-side candidate and not the old post shaper</h3>
 * <p>{@link MorphKernel#sampleLand} evaluates {@code parameter(stage 0)} per entry and blends the
 * <i>signals</i> by entry weight; the mountain shell ({@code amp}, {@code bias}) is applied after that
 * blend. Wrapping only the M1 {@link MorphRecipe} keeps the whole production traversal (domain warp,
 * badlands resampling of the pre-shape, plateau terrace, soft clamp, coverage, coast) in charge. The
 * Rune increment therefore enters <b>before</b> {@code ×0.7} and is weighted by the real M1 weight,
 * which is exactly what {@code post} cannot do (it adds a delta to an already mixed height).</p>
 *
 * <h3>Units of the Rune contract (identical numbers to the frozen round)</h3>
 * <pre>
 *   A (vertical unit, e)   = MOUNTAIN_AMP · E_SPAN = 1.05        (checks assert |amp·E_SPAN − A| &lt; 1e-7)
 *   reference plane (e)    = GROUND_E + A/2 = 0.545
 *   L (horizontal unit)    = 610 wu (M1 main scale), p = wu / L
 *   n (normalized height)  = (e − reference) / A ; in raw units n = raw − 0.5, so Δraw = Δn
 *   ∇p n                   = (L / A) · ∇wu e ; e_shell(raw) = GROUND_E + raw·A
 *   fadeTarget             = clamp(n / 0.3, −1, 1)
 *   output                 = A · delta(n)  ⇒ raw' = raw + delta
 * </pre>
 * <p>Because {@code amp·E_SPAN = A}, the pure-M1 gradient and fade inputs of this candidate equal the
 * ones the frozen {@code post} arm feeds to Rune — same operator, same seed, same parameters. What
 * differs is the insertion point (and the ±2 wu M1 domain warp, which the kernel applies to the signal
 * coordinates before the wrapper sees them).</p>
 *
 * <h3>Verification carried by this probe (all of it exits non-zero on failure)</h3>
 * <ol>
 *   <li>units + frozen window predicates + query-order/determinism;</li>
 *   <li>control arm reproduces the accepted 1401² P1 array ({@code bea0a47c…}) at fixed samples;</li>
 *   <li>where M1 weight is 0 or coverage alpha is 1 every arm is <b>bit-identical</b> to control;</li>
 *   <li>the candidate's shaping happens inside the shell:
 *       {@code regionHeight == (raw + delta) · MOUNTAIN_AMP} at pure-M1 samples, with the wrapper
 *       called at the kernel's warped coordinates (distance ≤ domain-warp radius);</li>
 *   <li>optional: control / carrier / post reproduce the frozen 2026-11-03 arrays bit-for-bit.</li>
 * </ol>
 */
public final class SignalSideRuneProbe {

    // ============================================================ frozen constants

    private static final long SEED = MorphCatalog.CANONICAL_SEED;

    /** Accepted P1 1401² array (read-only provenance anchor). */
    private static final String BASE_SHA256 =
            "bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965";
    private static final long BASE_SIDE = 1401L;

    static final int ARM_CONTROL = 0, ARM_CARRIER = 1, ARM_POST = 2, ARM_DAMPED = 3, ARM_SIGNAL = 4;
    static final String[] ARMS = {"control", "carrier", "post", "signal065", "signal"};

    /** M1 shell amplitude as the catalog stores it (float 0.7 widened to double). */
    static final double MOUNTAIN_AMP = MorphCatalog.MOUNTAIN_AMP;
    /** M1 main scale (wu) and the Rune horizontal unit. */
    static final double HORIZONTAL_WU = 610.0;
    /** Rune vertical unit in e (one normalized height unit). Equals amp·E_SPAN. */
    static final double VERTICAL_E = 1.05;
    /** Rune reference plane in e. Equals GROUND_E + A/2. */
    static final double REFERENCE_E = 0.545;
    /** Central-difference half step for the signal gradient (wu). */
    static final double DIFF_WU = 2.0;
    /** fadeTarget span in normalized height units. */
    static final double FADE_SPAN = 0.3;
    /** M1 domain-warp radius in wu (MorphKernel: 2 · mountain weight). */
    static final double WARP_RADIUS_WU = 2.0;
    /** Old operator blend weight, used only as the diagnostic amplitude factor of {@code signal065}. */
    static final double OLD_BLEND_WEIGHT = 0.65;

    /** Frozen corrected single-Rune node settings (2026-11-03 round; not re-tuned here). */
    static final RuneErosionFilter.Settings SETTINGS = new RuneErosionFilter.Settings(
            .15, .22, 1.1, 4, .5, 2, .5, .65, 1.5,
            .10, .12, .10, 2, 1.25, 1.25, .7, 1);

    /** Sea level in Y, for sea/land flip counts (same threshold as the approved renderer). */
    private static final double SEA_LEVEL_Y = 63.0;
    /** Blocks per e on the land side (HeightCurve slope), for the "ΔY ≈" note only. */
    private static final double BLOCKS_PER_E_LAND = 236.0;

    // ============================================================ frozen windows

    private static final double MOUNTAIN_CENTER_X = -3242.5, MOUNTAIN_CENTER_Z = -3365.5;
    private static final double TRANSITION_CENTER_X = -3442.5, TRANSITION_CENTER_Z = -2765.5;

    private static final Window MOUNTAIN = new Window("mountain", 401, 1.0, -3442.5, -3565.5,
            "frozen 2026-11-03 window; center (-3242.5,-3365.5) pure M1 weight 1, alpha 0");
    private static final Window TRANSITION = new Window("transition", 401, 1.0, -3642.5, -2965.5,
            "frozen 2026-11-03 window; center (-3442.5,-2765.5) actual M1 mix, alpha 0");
    private static final Window PLATEAU_CONTROL = new Window("plateau-control", 401, 1.0, -3102.5, 2999.5,
            "non-mountain negative control; the exact w17=0 subset must be bit-identical to control");
    private static final Window[] WINDOWS = {MOUNTAIN, TRANSITION, PLATEAU_CONTROL};

    // ============================================================ state

    private final TerrainParams params;
    private final MorphLandField control, carrier, signalField, dampedField;
    private final MorphSingleShaper post;
    private final MountainSignal signalWrapper, dampedWrapper;
    private final MorphOceanAssembler[] oceans = new MorphOceanAssembler[ARMS.length];
    private final HeightCurve curve;
    private final ContinentField continent;
    private Path frozenRound;

    private SignalSideRuneProbe(TerrainParams params) {
        this.params = params;
        MorphCatalog catalog = new MorphCatalog(SEED);
        MorphAssembly assembly = MorphAssembly.of(SEED);
        MorphRecipe[] base = MorphLandRecipe.all(catalog);
        MorphRecipe mountain = base[MorphCatalog.IDX_MOUNTAIN];
        signalWrapper = new MountainSignal(mountain, 1.0);
        dampedWrapper = new MountainSignal(mountain, OLD_BLEND_WEIGHT);
        control = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE, true);
        carrier = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE, false);
        signalField = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE,
                new MorphKernel(withSignal(base, signalWrapper), SEED, false));
        dampedField = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE,
                new MorphKernel(withSignal(base, dampedWrapper), SEED, false));
        post = new MorphSingleShaper(carrier, SETTINGS, SEED, HORIZONTAL_WU, VERTICAL_E, REFERENCE_E);
        curve = new HeightCurve(params, -64, 320);
        continent = new ContinentField(params);
        continent.seed(SEED);
        java.util.function.DoubleBinaryOperator[] land = {
                control::e, carrier::e, post::e, dampedField::e, signalField::e};
        for (int a = 0; a < ARMS.length; a++) {
            oceans[a] = new MorphOceanAssembler(land[a], continent::sample, SEED + 3)
                    .enableCoastalDetail(SEED);
        }
    }

    /** Same 18 recipes, M1 replaced by the signal-side wrapper; everything else untouched. */
    private static MorphRecipe[] withSignal(MorphRecipe[] base, MountainSignal wrapper) {
        MorphRecipe[] recipes = base.clone();
        recipes[MorphCatalog.IDX_MOUNTAIN] = wrapper;
        return recipes;
    }

    private double e(int arm, double x, double z) {
        return oceans[arm].e(x, z);
    }

    private double height(int arm, double x, double z) {
        return curve.heightFromE(oceans[arm].e(x, z));
    }

    private double[] heightsAt(double x, double z) {
        double[] h = new double[ARMS.length];
        for (int a = 0; a < h.length; a++) h[a] = height(a, x, z);
        return h;
    }

    // ============================================================ main

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <frozen config.toml> <new output dir>"
                    + " [accepted P1 1401x1401 f64be] [frozen 2026-11-03 round dir]");
        }
        Path config = Path.of(args[0]), out = Path.of(args[1]);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(config, TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "invalid frozen configuration");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            SignalSideRuneProbe probe = new SignalSideRuneProbe(GeoGenesisConfig.INSTANCE.buildParams());
            probe.invocation = args.clone();
            require(probe.params.horizontalScale() == 2 && probe.params.verticalScale() == 1,
                    "frozen HS/VS");
            probe.checks();
            String verification = probe.verification;
            if (args.length > 2) verification += probe.checkBaseline(Path.of(args[2]));
            if (args.length > 3) probe.frozenRound = Path.of(args[3]);
            if (Files.exists(out)) throw new IllegalArgumentException("output exists: " + out);
            Files.createDirectories(out);
            Files.copy(config, out.resolve("config-input.toml"));
            probe.writeManifest(out);
            java.util.List<String> stats = new java.util.ArrayList<>();
            for (Window window : WINDOWS) {
                verification += probe.render(out.resolve(window.name()), window, stats);
            }
            probe.writeProvenance(out);
            probe.performance(out, MOUNTAIN);
            Files.writeString(out.resolve("stats.json"), "{\"seed\":" + SEED
                    + ",\"hs\":2.0,\"units\":\"heights Y in MC blocks; gradient Y per wu\","
                    + "\"windows\":[\n" + String.join(",\n", stats) + "\n]}\n");
            Files.writeString(out.resolve("verification.txt"), verification);
            System.out.print(verification);
            System.out.println("SIGNAL SIDE RUNE PASS: stage 1 candidate only;"
                    + " no particle erosion, no hydrology, production unchanged");
        }
    }

    private String verification = "";
    /** Raw command-line arguments, recorded verbatim in provenance.txt. */
    private String[] invocation = new String[0];

    // ============================================================ checks

    private void checks() {
        require(Math.abs(MOUNTAIN_AMP * MorphScale.E_SPAN - VERTICAL_E) < 1e-7,
                "vertical unit is not amp*E_SPAN");
        require(Math.abs(MorphScale.GROUND_E + VERTICAL_E * 0.5 - REFERENCE_E) < 1e-12,
                "reference plane is not GROUND_E + A/2");
        require(VERTICAL_E == 1.05 && REFERENCE_E == 0.545, "units drifted from the frozen round");
        var m = carrier.sample(MOUNTAIN_CENTER_X, MOUNTAIN_CENTER_Z);
        require(m.weight()[MorphCatalog.IDX_MOUNTAIN] == 1 && m.alpha() == 0,
                "mountain window center is not pure M1 with alpha 0");
        var t = carrier.sample(TRANSITION_CENTER_X, TRANSITION_CENTER_Z);
        double w = t.weight()[MorphCatalog.IDX_MOUNTAIN];
        require(w > .2 && w < .8 && t.alpha() == 0, "transition window center is not a real M1 mix");
        for (double[] p : new double[][]{{-2901.5, 3252.5}, {1023.5, -11.5}, {1024.5, -11.5},
                {1000000.5, -1000000.5}}) {
            double[] before = heightsAt(p[0], p[1]);
            heightsAt(41.5, -31.5);
            require(Arrays.equals(before, heightsAt(p[0], p[1])), "query order/determinism drift");
            for (double v : before) require(Double.isFinite(v), "nonfinite arm");
        }
        // signal065 must differ from signal by exactly the old blend weight on the applied increment
        double fullRaw = signalWrapper.raw(57.5, 89.5), dampedRaw = dampedWrapper.raw(57.5, 89.5);
        double baseRaw = signalWrapper.probe(57.5, 89.5)[0];
        require(Math.abs((dampedRaw - baseRaw) - OLD_BLEND_WEIGHT * (fullRaw - baseRaw))
                <= 1e-12 * Math.max(1.0, Math.abs(fullRaw)),
                "signal065 is not a pure amplitude factor of signal");
        verification += "CHECKS PASS: units (L=" + HORIZONTAL_WU + "wu, A=" + VERTICAL_E
                + "e, ref=" + REFERENCE_E + "e), frozen window predicates (mountain w17="
                + m.weight()[MorphCatalog.IDX_MOUNTAIN] + " alpha=" + m.alpha() + "; transition w17="
                + w + "), query order/determinism on 4 points, signal065 = 0.65 x signal delta\n";
        System.out.println("CHECKS PASS");
    }

    /** Read-only provenance: the control arm must reproduce the accepted P1 1401² array. */
    private String checkBaseline(Path file) throws Exception {
        long side = BASE_SIDE;
        require(Files.size(file) == side * side * 8, "baseline shape");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        require(sha.equals(BASE_SHA256), "baseline provenance sha " + sha);
        int[][] pts = {{0, 0}, {100, 1100}, {700, 700}, {1400, 1400}, {197, 1313}, {73, 1333}, {420, 33}};
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            for (int[] p : pts) {
                input.seek((p[1] * side + p[0]) * 8);
                require(bits(input.readDouble(), height(ARM_CONTROL, -3642.5 + p[0] * 5,
                        -3365.5 + p[1] * 5)), "P1 baseline sample mismatch at " + Arrays.toString(p));
            }
        }
        System.out.println("P1 BASELINE PASS: " + BASE_SHA256.substring(0, 8) + " and " + pts.length
                + " exact control samples; file read-only");
        return "P1 BASELINE PASS: sha " + BASE_SHA256 + ", " + pts.length
                + " control samples bit-identical, baseline file not rewritten\n";
    }

    // ============================================================ render one window

    private String render(Path out, Window w, java.util.List<String> stats) throws IOException {
        Files.createDirectories(out);
        int n = w.size(), count = n * n;
        double[][] heights = new double[ARMS.length][count];
        double[] weight = new double[count], alpha = new double[count];
        double[] runeRaw = new double[count], runeDelta = new double[count];
        double[] runeSlope = new double[count], runeFade = new double[count];
        int negative = 0, covered = 0, pure = 0;
        double maxShellResidual = 0, maxWarpOffset = 0;
        long start = System.nanoTime();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                double x = w.ox() + i * w.step(), z = w.oz() + j * w.step();
                for (int a = 0; a < ARMS.length; a++) {
                    double y = height(a, x, z);
                    require(Double.isFinite(y), "nonfinite arm " + ARMS[a]);
                    heights[a][k] = y;
                }
                MorphLandField.Sample s = carrier.sample(x, z);
                weight[k] = s.weight()[MorphCatalog.IDX_MOUNTAIN];
                alpha[k] = s.alpha();
                if (weight[k] == 0 || alpha[k] == 1) {
                    for (int a = 1; a < ARMS.length; a++) {
                        require(bits(heights[0][k], heights[a][k]),
                                "negative control drift: " + ARMS[a] + " at (" + x + "," + z + ")");
                    }
                    negative++;
                }
                if (alpha[k] == 1) covered++;
                if (weight[k] == 1 && alpha[k] == 0) {
                    pure++;
                    maxShellResidual = Math.max(maxShellResidual, shellResidual(x, z));
                    maxWarpOffset = Math.max(maxWarpOffset, Math.hypot(
                            signalWrapper.lastX - x, signalWrapper.lastZ - z));
                    runeRaw[k] = signalWrapper.lastValue;
                    runeDelta[k] = signalWrapper.lastDelta;
                    runeSlope[k] = signalWrapper.lastSlope;
                    runeFade[k] = signalWrapper.lastFade;
                }
            }
            if (j % 100 == 0) System.out.println(w.name() + " row=" + j + "/" + n);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        writeArrays(out, heights, weight, alpha);
        String frozen = frozenComparison(out.getFileName().toString(), heights);
        String shell = String.format(Locale.ROOT,
                "SHELL PASS %s: max |regionHeight - (raw+delta)*amp| = %.3e, max |warped - grid| = %.4f wu"
                        + " (domain warp radius %.1f), %d pure-M1 samples\n",
                w.name(), maxShellResidual, maxWarpOffset, WARP_RADIUS_WU, pure);
        require(maxShellResidual <= 1e-9, "signal shaping did not enter the mountain shell before amp");
        require(maxWarpOffset <= WARP_RADIUS_WU + 1e-6,
                "signal wrapper was not driven from the kernel's warped mountain domain");
        System.out.printf(Locale.ROOT, "RENDER %s: %dms pure=%d fullCoverage=%d negative=%d%n",
                w.name(), millis, pure, covered, negative);
        stats.add(windowStats(w, heights, weight, alpha, negative, covered, pure,
                runeRaw, runeDelta, runeSlope, runeFade, millis));
        System.out.print(frozen);
        return shell + frozen;
    }

    /**
     * Structural check: at a pure-M1 sample the kernel's region height must be
     * {@code (raw + delta) * MOUNTAIN_AMP} — i.e. the Rune increment is inside the shell, before amp.
     */
    private double shellResidual(double x, double z) {
        double region = signalField.regionHeight(x, z);
        double expected = (signalWrapper.lastValue + signalWrapper.lastDelta) * MOUNTAIN_AMP;
        return Math.abs(region - expected) / Math.max(1.0, Math.abs(expected));
    }

    private void writeArrays(Path out, double[][] heights, double[] weight, double[] alpha)
            throws IOException {
        String[] names = Arrays.copyOf(ARMS, ARMS.length + 2);
        names[ARMS.length] = "mountain-weight";
        names[ARMS.length + 1] = "coverage-alpha";
        DataOutputStream[] streams = new DataOutputStream[names.length];
        try {
            for (int k = 0; k < names.length; k++) {
                streams[k] = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(out.resolve(names[k] + ".f64be"))));
            }
            int count = weight.length;
            for (int i = 0; i < count; i++) {
                for (int a = 0; a < ARMS.length; a++) streams[a].writeDouble(heights[a][i]);
                streams[ARMS.length].writeDouble(weight[i]);
                streams[ARMS.length + 1].writeDouble(alpha[i]);
            }
        } finally {
            for (DataOutputStream s : streams) if (s != null) s.close();
        }
    }

    /** Optional: control / carrier / post must reproduce the frozen 2026-11-03 arrays bit-for-bit. */
    private String frozenComparison(String windowName, double[][] heights) throws IOException {
        if (frozenRound == null) return "";
        Path dir = frozenRound.resolve(windowName);
        if (!Files.isDirectory(dir)) return "FROZEN " + windowName + ": absent in " + frozenRound + "\n";
        int count = heights[0].length;
        String[] frozenNames = {"control", "carrier", "rune"};
        int[] arms = {ARM_CONTROL, ARM_CARRIER, ARM_POST};
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < frozenNames.length; i++) {
            Path file = dir.resolve(frozenNames[i] + ".f64be");
            require(Files.size(file) == count * 8L, "frozen array shape " + file);
            double[] frozen = readF64(file);
            for (int k = 0; k < count; k++) {
                require(bits(frozen[k], heights[arms[i]][k]),
                        "frozen " + frozenNames[i] + " drift at sample " + k);
            }
            note.append("  ").append(frozenNames[i]).append(" == ").append(ARMS[arms[i]]).append('\n');
        }
        return "FROZEN ROUND PASS " + windowName + ": bit-identical to " + frozenRound.getFileName()
                + "\n" + note;
    }

    private static double[] readF64(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        double[] out = new double[raw.length / 8];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw);
        for (int i = 0; i < out.length; i++) out[i] = buffer.getDouble(i * 8);
        return out;
    }

    // ============================================================ statistics

    private String windowStats(Window w, double[][] heights, double[] weight, double[] alpha,
                               int negative, int covered, int pure, double[] runeRaw,
                               double[] runeDelta, double[] runeSlope, double[] runeFade,
                               long millis) {
        StringBuilder sb = new StringBuilder();
        sb.append("  {\"name\":\"").append(w.name()).append('"')
                .append(",\"size\":").append(w.size())
                .append(",\"stepWu\":").append(num(w.step()))
                .append(",\"originWu\":[").append(num(w.ox())).append(',').append(num(w.oz())).append(']')
                .append(",\"renderMillis\":").append(millis)
                .append(",\"counts\":{\"pureMountain\":").append(pure)
                .append(",\"fullCoverage\":").append(covered)
                .append(",\"negativeControl\":").append(negative)
                .append(",\"samples\":").append(weight.length).append('}')
                .append(",\"curveCeiling\":").append(ceilingStats(heights))
                .append(",\"levels\":{");
        for (int a = 0; a < ARMS.length; a++) {
            if (a > 0) sb.append(',');
            sb.append('"').append(ARMS[a]).append("\":").append(levelStats(heights[a]));
        }
        sb.append("},\"gradient\":{");
        for (int a = 0; a < ARMS.length; a++) {
            if (a > 0) sb.append(',');
            sb.append('"').append(ARMS[a]).append("\":").append(gradientStats(heights[a], w.step()));
        }
        sb.append("},\"vsControl\":{");
        for (int a = 1; a < ARMS.length; a++) {
            if (a > 1) sb.append(',');
            sb.append('"').append(ARMS[a]).append("\":").append(diffStats(heights[0], heights[a], null));
        }
        sb.append("},\"vsCarrier\":{");
        for (int a = ARM_POST; a < ARMS.length; a++) {
            if (a > ARM_POST) sb.append(',');
            sb.append('"').append(ARMS[a]).append("\":").append(diffStats(heights[ARM_CARRIER], heights[a], null));
        }
        sb.append("},\"signalVsPost\":{\"all\":")
                .append(diffStats(heights[ARM_POST], heights[ARM_SIGNAL], null))
                .append(",\"mixedOnly\":")
                .append(diffStats(heights[ARM_POST], heights[ARM_SIGNAL], mixedMask(weight, alpha)))
                .append(",\"pureMountainOnly\":")
                .append(diffStats(heights[ARM_POST], heights[ARM_SIGNAL], pureMask(weight, alpha)))
                .append('}');
        sb.append(",\"runeInputs\":").append(runeInputs(runeRaw, runeDelta, runeSlope, runeFade,
                pureMask(weight, alpha)));
        sb.append('}');
        return sb.toString();
    }

    private static boolean[] mixedMask(double[] weight, double[] alpha) {
        boolean[] mask = new boolean[weight.length];
        for (int i = 0; i < mask.length; i++) mask[i] = weight[i] > 0 && weight[i] < 1 && alpha[i] < 1;
        return mask;
    }

    private static boolean[] pureMask(double[] weight, double[] alpha) {
        boolean[] mask = new boolean[weight.length];
        for (int i = 0; i < mask.length; i++) mask[i] = weight[i] == 1 && alpha[i] == 0;
        return mask;
    }

    /**
     * HeightCurve saturation: the largest Y it can return, plus how many samples of each arm sit
     * exactly there. A nonzero count means that arm is truncated at the curve ceiling (flattened
     * peaks), which no candidate is allowed to hide behind a "peak" claim.
     */
    private String ceilingStats(double[][] heights) {
        double ceiling = curve.heightFromE(1.0);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "{\"ceilingY\":%s,\"seaLevelY\":%s,\"atCeiling\":{",
                num(ceiling), num(curve.seaLevelY())));
        for (int a = 0; a < ARMS.length; a++) {
            int n = 0;
            for (double y : heights[a]) if (y >= ceiling - 1e-9) n++;
            if (a > 0) sb.append(',');
            sb.append('"').append(ARMS[a]).append("\":").append(n);
        }
        return sb.append("}}").toString();
    }

    private static String levelStats(double[] y) {
        double[] q = quantiles(y, new double[]{0.0, 0.01, 0.5, 0.99, 1.0});
        return String.format(Locale.ROOT,
                "{\"minY\":%s,\"p01Y\":%s,\"p50Y\":%s,\"p99Y\":%s,\"maxY\":%s}",
                num(q[0]), num(q[1]), num(q[2]), num(q[3]), num(q[4]));
    }

    /** Central-difference gradient in Y/wu; the renderer uses the same convention. */
    private static String gradientStats(double[] y, double step) {
        int n = (int) Math.round(Math.sqrt(y.length));
        double[] g = new double[(n - 2) * (n - 2)];
        int at = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                double gx = (y[j * n + i + 1] - y[j * n + i - 1]) / (2 * step);
                double gz = (y[(j + 1) * n + i] - y[(j - 1) * n + i]) / (2 * step);
                g[at++] = Math.hypot(gx, gz);
            }
        }
        double[] q = quantiles(g, new double[]{0.5, 0.99, 0.999, 1.0});
        return String.format(Locale.ROOT,
                "{\"p50YperWu\":%s,\"p99YperWu\":%s,\"p999YperWu\":%s,\"maxYperWu\":%s,"
                        + "\"tailRatioP999overP50\":%s}",
                num(q[0]), num(q[1]), num(q[2]), num(q[3]), num(q[0] > 0 ? q[2] / q[0] : 0.0));
    }

    private static String diffStats(double[] base, double[] other, boolean[] mask) {
        double sum = 0, max = 0;
        int n = 0, flips = 0, above20 = 0;
        double[] abs = new double[mask == null ? base.length : count(mask)];
        for (int i = 0; i < base.length; i++) {
            if (mask != null && !mask[i]) continue;
            double d = Math.abs(other[i] - base[i]);
            sum += d;
            max = Math.max(max, d);
            if (d > 20.0) above20++;
            if ((other[i] >= SEA_LEVEL_Y) != (base[i] >= SEA_LEVEL_Y)) flips++;
            abs[n++] = d;
        }
        if (n == 0) return "{\"count\":0}";
        double[] q = quantiles(Arrays.copyOf(abs, n), new double[]{0.5, 0.99, 1.0});
        return String.format(Locale.ROOT,
                "{\"count\":%d,\"meanAbsDeltaY\":%s,\"p50AbsDeltaY\":%s,\"p99AbsDeltaY\":%s,"
                        + "\"maxAbsDeltaY\":%s,\"countAbsGt20Y\":%d,\"seaLandFlips\":%d}",
                n, num(sum / n), num(q[0]), num(q[1]), max, above20, flips);
    }

    private static String runeInputs(double[] raw, double[] delta, double[] slope, double[] fade,
                                     boolean[] pureMask) {
        int pure = count(pureMask);
        if (pure == 0) return "{\"count\":0}";
        double[] usedRaw = new double[pure], usedDelta = new double[pure], usedSlope = new double[pure];
        int saturated = 0, at = 0;
        for (int i = 0; i < raw.length; i++) {
            if (!pureMask[i]) continue;
            usedRaw[at] = raw[i];
            usedDelta[at] = delta[i] * VERTICAL_E;                         // normalized -> e
            usedSlope[at] = slope[i];
            if (Math.abs(fade[i]) >= 0.999) saturated++;
            at++;
        }
        double[] qr = quantiles(usedRaw, new double[]{0.0, 0.5, 1.0});
        double[] qd = quantiles(usedDelta, new double[]{0.01, 0.5, 0.99, 0.0, 1.0});
        double[] qs = quantiles(usedSlope, new double[]{0.5, 0.99});
        return String.format(Locale.ROOT,
                "{\"count\":%d,\"rawSignal\":{\"min\":%s,\"p50\":%s,\"max\":%s},"
                        + "\"deltaE\":{\"p01\":%s,\"p50\":%s,\"p99\":%s,\"min\":%s,\"max\":%s,"
                        + "\"approxBlocksP99\":%s},"
                        + "\"normalizedSlope\":{\"p50\":%s,\"p99\":%s},"
                        + "\"fadeSaturatedFraction\":%s}",
                at, num(qr[0]), num(qr[1]), num(qr[2]),
                num(qd[0]), num(qd[1]), num(qd[2]), num(qd[3]), num(qd[4]),
                num(Math.abs(qd[2]) * BLOCKS_PER_E_LAND),
                num(qs[0]), num(qs[1]), num(saturated / (double) at));
    }

    private static int count(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) if (b) n++;
        return n;
    }

    /** Sorted-copy quantiles; {@code q} entries are fractions in [0,1]. */
    private static double[] quantiles(double[] values, double[] q) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] out = new double[q.length];
        for (int i = 0; i < q.length; i++) {
            double pos = q[i] * (sorted.length - 1);
            int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
            out[i] = lo == hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
        }
        return out;
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    // ============================================================ metadata

    private void writeManifest(Path out) throws IOException {
        StringBuilder windows = new StringBuilder();
        for (Window w : WINDOWS) {
            if (windows.length() > 0) windows.append(",\n");
            windows.append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"size\":%d,\"stepWu\":%s,\"originWu\":[%s,%s],\"selection\":\"%s\"}",
                    w.name(), w.size(), num(w.step()), num(w.ox()), num(w.oz()), w.selection()));
        }
        String settings = String.format(Locale.ROOT,
                "{\"horizontalUnitWu\":%s,\"verticalUnitE\":%s,\"referenceE\":%s,\"fadeSpan\":%s,"
                        + "\"centralDifferenceWu\":%s,\"scale\":%s,\"strength\":%s,\"cellScale\":%s,"
                        + "\"octaves\":%s,\"gain\":%s,\"lacunarity\":%s,\"normalization\":%s,"
                        + "\"gullyWeight\":%s,\"detail\":%s}",
                num(HORIZONTAL_WU), num(VERTICAL_E), num(REFERENCE_E), num(FADE_SPAN), num(DIFF_WU),
                SETTINGS.scale(), SETTINGS.strength(), SETTINGS.cellScale(), SETTINGS.octaves(),
                SETTINGS.gain(), SETTINGS.lacunarity(), SETTINGS.normalization(), SETTINGS.gullyWeight(),
                SETTINGS.detail());
        String definitions = "{\"control\":\"P1, mountainTexture=true\","
                + "\"carrier\":\"same traversal, old MorphNoise.Erosion bypassed\","
                + "\"post\":\"MorphSingleShaper on carrier: Rune added after mixing (2026-11-03 O2)\","
                + "\"signal065\":\"diagnostic: signal arm with Rune increment x 0.65 (old blend weight)\","
                + "\"signal\":\"CANDIDATE: Rune on the M1 recipe raw inside the unchanged kernel\"}";
        Files.writeString(out.resolve("manifest.json"), "{\"seed\":" + SEED + ",\"hs\":2.0,\"arms\":["
                + "\"control\",\"carrier\",\"post\",\"signal065\",\"signal\"],\"settings\":" + settings
                + ",\"armDefinitions\":" + definitions + ",\"units\":\"heights Y in MC blocks\","
                + "\"windows\":[" + windows + "]}\n");
    }

    private void writeProvenance(Path out) throws IOException {
        Files.writeString(out.resolve("provenance.txt"),
                "Stage 1 isolated comparison for plan rune-mountain-integration. Diagnostics only.\n"
                        + "invocation: gradlew.bat --offline -I tools/run-signal-side-rune.gradle"
                        + " runSignalSideRuneProbe -PprobeArgs=\"" + String.join(" ", invocation) + "\"\n"
                        + "jvm: java " + System.getProperty("java.version") + ", file.encoding="
                        + System.getProperty("file.encoding")
                        + "; no geogenesis -D flags (no boundaryPorts / channelMinCells / skeletonSourceCells)\n"
                        + "control=P1 as accepted; carrier=same traversal with MorphKernel mountainTexture=false;\n"
                        + "post=MorphSingleShaper(carrier) = Rune added after mixing (2026-11-03 round, diagnostic);\n"
                        + "signal065=diagnostic amplitude factor 0.65 on the signal-side increment;\n"
                        + "signal=CANDIDATE: Rune applied to the M1 recipe raw (MorphRecipe.raw of entry 17),\n"
                        + "  then the unchanged kernel applies amp/bias/clamp and the real M1 weight;\n"
                        + "  old 0.65 blend weight is NOT reused as an equivalent parameter.\n"
                        + "seed=" + SEED + "; HS=2 (frozen), VS=1; L=" + HORIZONTAL_WU + "wu; A=" + VERTICAL_E
                        + "e; reference=" + REFERENCE_E + "e; central difference +/-" + DIFF_WU + "wu\n"
                        + "A = MOUNTAIN_AMP * E_SPAN (checked < 1e-7); reference = GROUND_E + A/2 (checked < 1e-12)\n"
                        + "n = (e - reference)/A = raw - 0.5 on pure M1; fade = clamp(n/0.3, -1, 1); raw' = raw + delta\n"
                        + "gradient fed to Rune = d(shell e)/d(wu) * (L/A); shell e = GROUND_E + raw*A\n"
                        + "settings=" + SETTINGS + "\n"
                        + "rune hash=integer SplitMix64 world cells; same settings/seed as the frozen round;\n"
                        + "no per-window normalization, no height retuning, no -D geogenesis flags,\n"
                        + "no particle erosion, no hydrology, no production wiring, no baseline rewritten\n"
                        + "windows frozen from build/single-shaper-20261103/manifest.json (size 401, step 1 wu)\n"
                        + "arrays: <window>/<arm>.f64be, mountain-weight.f64be, coverage-alpha.f64be (big-endian f64, row-major z, col x)\n"
                        + "negative control: every arm is bit-identical to control where w17==0 or alpha==1 (enforced)\n");
    }

    // ============================================================ performance

    private void performance(Path out, Window w) throws IOException {
        double[][] points = new double[256][2];
        Random random = new Random(17);
        for (double[] p : points) {
            p[0] = w.ox() + random.nextDouble() * 400;
            p[1] = w.oz() + random.nextDouble() * 400;
        }
        double[][] times = new double[ARMS.length][7];
        double sink = 0;
        for (int round = -3; round < 7; round++) {
            for (int order = 0; order < ARMS.length; order++) {
                int arm = Math.floorMod(round + order, ARMS.length);
                long t = System.nanoTime();
                for (double[] p : points) sink += e(arm, p[0], p[1]);
                if (round >= 0) times[arm][round] = (System.nanoTime() - t) / 1e6;
            }
        }
        StringBuilder csv = new StringBuilder("arm,points,warmupBatches,measuredBatches,p50ms,p95ms\n");
        for (int arm = 0; arm < ARMS.length; arm++) {
            Arrays.sort(times[arm]);
            csv.append(ARMS[arm]).append(",256,3,7,").append(times[arm][3]).append(',')
                    .append(times[arm][6]).append('\n');
        }
        Files.writeString(out.resolve("performance.csv"), csv.toString());
        System.out.print(csv + "sink=" + sink + "; sampling only, not Minecraft throughput\n");
    }

    // ============================================================ nested types

    private record Window(String name, int size, double step, double ox, double oz, String selection) {
        @Override public String toString() {
            return name + "(" + size + "," + step + "," + ox + "," + oz + ")";
        }
    }

    /**
     * Rune applied to the M1 <b>signal layer</b> (the recipe raw), before amp/bias.
     *
     * <p>{@code raw()} returns {@code signal + scale·delta}, so the kernel multiplies the shaped signal
     * by the unchanged M1 amplitude and blends it by the real M1 weight. {@code amp}/{@code bias}/
     * {@code role}/{@code clamped} delegate to the production recipe untouched.</p>
     *
     * <p>Single-threaded probe use: the {@code last*} snapshot records the values the kernel actually
     * used (it calls {@code raw} at domain-warped coordinates), which the shell check reads back.
     * {@code lastDelta} is the <b>unscaled</b> Rune increment; the applied increment is
     * {@code scale · lastDelta}.</p>
     */
    static final class MountainSignal implements MorphRecipe {
        private final MorphRecipe signal;
        private final double scale;
        private double lastX = Double.NaN, lastZ = Double.NaN;
        private double lastValue, lastDelta, lastSlope, lastFade;

        MountainSignal(MorphRecipe signal, double scale) {
            this.signal = signal;
            this.scale = scale;
        }

        @Override public double raw(double x, double z) {
            double value = signal.raw(x, z);
            double gx = gradientX(x, z), gz = gradientZ(x, z);
            double fade = fade(value);
            double delta = RuneErosionFilter.delta(x / HORIZONTAL_WU, z / HORIZONTAL_WU, gx, gz,
                    fade, SETTINGS, SEED);
            lastX = x;
            lastZ = z;
            lastValue = value;
            lastDelta = delta;
            lastSlope = Math.hypot(gx, gz);
            lastFade = fade;
            return value + scale * delta;
        }

        @Override public double amplitude(double x, double z) { return signal.amplitude(x, z); }

        @Override public double bias(double x, double z) { return signal.bias(x, z); }

        @Override public Role role() { return Role.MOUNTAIN; }

        @Override public boolean clamped() { return signal.clamped(); }

        /** {raw, delta (normalized units), |normalized slope|, fadeTarget}; does not touch the snapshot. */
        double[] probe(double x, double z) {
            double value = signal.raw(x, z);
            double gx = gradientX(x, z), gz = gradientZ(x, z);
            double fade = fade(value);
            double delta = RuneErosionFilter.delta(x / HORIZONTAL_WU, z / HORIZONTAL_WU, gx, gz,
                    fade, SETTINGS, SEED);
            return new double[]{value, delta, Math.hypot(gx, gz), fade};
        }

        /** d(shell e)/d(wu) scaled to the normalized plane: (L/A)·∇wu e. */
        private double gradientX(double x, double z) {
            return (shellE(signal.raw(x + DIFF_WU, z)) - shellE(signal.raw(x - DIFF_WU, z)))
                    / (2 * DIFF_WU) * (HORIZONTAL_WU / VERTICAL_E);
        }

        private double gradientZ(double x, double z) {
            return (shellE(signal.raw(x, z + DIFF_WU)) - shellE(signal.raw(x, z - DIFF_WU)))
                    / (2 * DIFF_WU) * (HORIZONTAL_WU / VERTICAL_E);
        }

        /** e of the M1 shell for a raw signal value, with the frozen vertical unit. */
        private static double shellE(double raw) {
            return MorphScale.GROUND_E + raw * VERTICAL_E;
        }

        private static double fade(double raw) {
            double n = (shellE(raw) - REFERENCE_E) / VERTICAL_E;
            double f = n / FADE_SPAN;
            return f < -1 ? -1 : (f > 1 ? 1 : f);
        }
    }

    // ============================================================ utilities

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
