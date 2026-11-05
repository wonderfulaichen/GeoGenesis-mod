package com.geogenesis.worldgen.terrain.morph;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.RuneErosionFilter;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** O0/P1、O1去算子、O2单一骨架的同源局部实验，不接默认生成器。 */
public final class SingleShaperProbe {
    private static final long SEED = 9139912035078620160L;
    private static final String BASE_SHA = "bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965";
    private static final String[] ARMS = {"control", "carrier", "rune", "rounded", "guided"};
    // 固定单位，不按采样窗归一：山系610wu，原M1外壳0.7×E_SPAN=1.05e。
    private static final double HORIZONTAL_WU = 610, VERTICAL_E = 1.05, REFERENCE_E = 0.545;
    private static final RuneErosionFilter.Settings SETTINGS = new RuneErosionFilter.Settings(
            .15, .22, 1.1, 4, .5, 2, .5, .65, 1.5,
            .10, .12, .10, 2, 1.25, 1.25, .7, 1);
    private final TerrainParams params;
    private final MorphLandField original, carrier, roundedCarrier;
    private final MorphSingleShaper shaper, roundedShaper, guidedShaper;
    private final MorphOceanAssembler[] oceans = new MorphOceanAssembler[5];
    private final HeightCurve curve;
    private final ContinentField continent;

    private SingleShaperProbe(TerrainParams params) {
        this.params = params;
        MorphCatalog catalog = new MorphCatalog(SEED);
        MorphAssembly assembly = MorphAssembly.of(SEED);
        original = new MorphLandField(catalog, assembly, 1200, true);
        carrier = new MorphLandField(catalog, assembly, 1200, false);
        shaper = new MorphSingleShaper(carrier, SETTINGS, SEED, HORIZONTAL_WU, VERTICAL_E, REFERENCE_E);
        roundedCarrier = new MorphLandField(catalog, assembly, 1200,
                new MorphKernel(MorphRoundedMountain.recipes(catalog, .1), SEED, false));
        roundedShaper = new MorphSingleShaper(roundedCarrier, SETTINGS, SEED, HORIZONTAL_WU, VERTICAL_E, REFERENCE_E);
        MorphLandField guide = new MorphLandField(catalog, assembly, 1200,
                new MorphKernel(MorphRoundedMountain.guideRecipes(catalog, .1), SEED, false, true));
        MorphNoise.Warped mountainRaw = (MorphNoise.Warped) catalog.entry(17).rawNode();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) ((MorphNoise.Mul) mountainRaw.input()).a();
        double guideUnitE = catalog.entry(17).amplitude(0, 0) * MorphScale.E_SPAN / ridge.max();
        guidedShaper = new MorphSingleShaper(carrier, guide, SETTINGS, SEED, HORIZONTAL_WU, guideUnitE,
                MorphScale.GROUND_E + guideUnitE * .5);
        curve = new HeightCurve(params, -64, 320);
        continent = new ContinentField(params);
        continent.seed(SEED);
        oceans[0] = ocean(original::e);
        oceans[1] = ocean(carrier::e);
        oceans[2] = ocean(shaper::e);
        oceans[3] = ocean(roundedShaper::e);
        oceans[4] = ocean(guidedShaper::e);
    }

    private MorphOceanAssembler ocean(java.util.function.DoubleBinaryOperator land) {
        return new MorphOceanAssembler(land, continent::sample, SEED + 3).enableCoastalDetail(SEED);
    }

    private double height(int arm, double x, double z) { return curve.heightFromE(oceans[arm].e(x, z)); }

    public static void main(String[] args) throws Exception {
        Path config = Path.of(args[0]), out = Path.of(args[1]);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(config, TomlFormat.instance())) {
            cfg.load(); require(GeoGenesisConfig.SPEC.isCorrect(cfg), "invalid frozen configuration");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            SingleShaperProbe probe = new SingleShaperProbe(GeoGenesisConfig.INSTANCE.buildParams());
            require(probe.params.horizontalScale() == 2 && probe.params.verticalScale() == 1, "frozen HS/VS");
            probe.checks();
            checkOldFade();
            if (args.length > 2) probe.checkBaseline(Path.of(args[2]));
            if (Files.exists(out)) throw new IllegalArgumentException("output exists: " + out);
            Files.createDirectories(out);
            Files.copy(config, out.resolve("config-input.toml"));
            Window[] windows = probe.selectWindows();
            probe.manifest(out, windows);
            for (Window window : windows) probe.render(out.resolve(window.name()), window);
            probe.performance(out, windows[0]);
            System.out.println("SINGLE SHAPER PASS: candidate only; no particle/hydrology; production unchanged");
        }
    }

    private void checks() {
        MorphCatalog catalog = new MorphCatalog(SEED);
        MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
        MorphRecipe[] rounded = MorphRoundedMountain.recipes(catalog, .1);
        MorphRecipe[] zeroRadius = MorphRoundedMountain.recipes(catalog, 0);
        for (int id = 0; id < 18; id++) {
            require(bits(recipes[id].raw(57.5, 89.5), zeroRadius[id].raw(57.5, 89.5)), "zero radius drift");
            if (id != 17) require(bits(recipes[id].raw(57.5, 89.5), rounded[id].raw(57.5, 89.5)), "rounded other family drift");
        }
        MorphKernel noTexture = new MorphKernel(recipes, SEED, false);
        double[] onehot = new double[18]; onehot[17] = 1;
        double x = 57.5, z = 89.5;
        int m = (int) ((SEED + 77) ^ ((SEED + 77) >>> 32));
        double angle = MorphNoiseEval.eval(MorphNoise.Perlin.ofScale(m + 3, 10, 1), x, z) * 6.2831855f;
        double direct = recipes[17].raw(x + Math.sin(angle) * 2, z + Math.cos(angle) * 2) * .7f;
        require(bits(direct, noTexture.sampleLand(x, z, onehot)), "pure mountain carrier definition");
        int unchanged = 0;
        for (int id = 0; id < 17; id++) {
            Arrays.fill(onehot, 0); onehot[id] = 1;
            MorphKernel baseline = new MorphKernel(recipes, SEED);
            require(bits(baseline.sampleLand(x, z, onehot), noTexture.sampleLand(x, z, onehot)), "nonmountain entry " + id);
            unchanged++;
        }
        for (double[] p : new double[][]{{-2901.5, 3252.5}, {1023.5, -11.5}, {1024.5, -11.5}, {1000000.5, -1000000.5}}) {
            double before = shaper.e(p[0], p[1]);
            shaper.e(41.5, -31.5);
            require(bits(before, shaper.e(p[0], p[1])), "query order/overlap");
            double roundBefore = roundedShaper.e(p[0], p[1]);
            roundedShaper.e(41.5, -31.5);
            require(bits(roundBefore, roundedShaper.e(p[0], p[1])), "rounded query order");
            require(Double.isFinite(before) && Double.isFinite(roundBefore), "nonfinite candidate");
            var s = carrier.sample(p[0], p[1]);
            if (s.weight()[17] == 0 || s.alpha() == 1) require(bits(before, original.e(p[0], p[1])), "negative control");
        }
        require(carrier.sample(-2901.5, 3252.5).weight()[17] == 0, "old spike point is nonmountain");
        System.out.println("ISOLATION PASS: pure mountain definition, " + unchanged + " nonmountain endpoints, point/order/negative controls");
    }

    private static void checkOldFade() {
        var c = new com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig();
        float below = com.geogenesis.worldgen.erosion.RidgeValleyErosion.evaluatePoint(
                100, 200, Math.nextDown(.75f), 0, 0, 0, c);
        float at = com.geogenesis.worldgen.erosion.RidgeValleyErosion.evaluatePoint(100, 200, .75f, 0, 0, 0, c);
        require(Math.abs(at - below) < 1e-7 && Math.abs(at) < 1e-7, "old fade discontinuity");
        System.out.println("OLD FADE PASS: h=.75 below=" + below + " at=" + at);
    }

    private void checkBaseline(Path file) throws Exception {
        require(Files.size(file) == 1401L * 1401 * 8, "baseline shape");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        require(sha.equals(BASE_SHA), "baseline provenance");
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            for (int[] p : new int[][]{{0, 0}, {100, 1100}, {700, 700}, {1400, 1400}, {197, 1313}, {73, 1333}, {420, 33}}) {
                input.seek((p[1] * 1401L + p[0]) * 8);
                require(bits(input.readDouble(), height(0, -3642.5 + p[0] * 5, -3365.5 + p[1] * 5)), "P1 baseline sample");
            }
        }
        System.out.println("P1 BASELINE PASS: stored BEA0 SHA and 7 exact current-path samples; file not rewritten");
    }

    private Window[] selectWindows() {
        Window mountain = null, transition = null;
        for (int j = 0; j <= 35 && (mountain == null || transition == null); j++) {
            for (int i = 0; i <= 35 && (mountain == null || transition == null); i++) {
                double x = -3642.5 + i * 200, z = -3365.5 + j * 200;
                var s = carrier.sample(x, z);
                if (s.alpha() != 0 || continent.sample(x, z) < .15) continue;
                double w = s.weight()[17];
                if (mountain == null && w == 1) mountain = window("mountain", x, z, "center pure M1, alpha=0");
                if (transition == null && w > .2 && w < .8) transition = window("transition", x, z, "center actual M1 mix, alpha=0");
            }
        }
        require(mountain != null && transition != null, "representative windows absent");
        Window negative = new Window("plateau-control", 401, 1, -3102.5, 2999.5, "old spike area; exact w17=0 subset is negative control");
        System.out.println("WINDOWS " + mountain + " " + transition + " " + negative);
        return new Window[]{mountain, transition, negative};
    }

    private static Window window(String name, double x, double z, String reason) {
        return new Window(name, 401, 1, x - 200, z - 200, reason);
    }

    private void manifest(Path out, Window[] windows) throws Exception {
        StringJoiner json = new StringJoiner(",\n");
        for (Window w : windows) json.add(String.format(Locale.ROOT,
                "{\"name\":\"%s\",\"size\":%d,\"stepWu\":%s,\"originWu\":[%s,%s],\"selection\":\"%s\"}",
                w.name(), w.size(), w.step(), w.ox(), w.oz(), w.reason()));
        String settings = String.format(Locale.ROOT,
                "{\"horizontalUnitWu\":%s,\"verticalUnitE\":%s,\"referenceE\":%s,\"scale\":%s,\"strength\":%s,\"cellScale\":%s,\"octaves\":%s,\"gain\":%s,\"lacunarity\":%s,\"normalization\":%s,\"gullyWeight\":%s,\"detail\":%s}",
                HORIZONTAL_WU, VERTICAL_E, REFERENCE_E, SETTINGS.scale(), SETTINGS.strength(), SETTINGS.cellScale(),
                SETTINGS.octaves(), SETTINGS.gain(), SETTINGS.lacunarity(), SETTINGS.normalization(), SETTINGS.gullyWeight(), SETTINGS.detail());
        Files.writeString(out.resolve("manifest.json"), "{\"seed\":" + SEED + ",\"hs\":2.0,\"arms\":[\"control\",\"carrier\",\"rune\",\"rounded\",\"guided\"],\"settings\":" + settings + ",\"roundedRadius\":0.1,\"windows\":[" + json + "]}\n");
        Files.writeString(out.resolve("provenance.txt"), "O0: default P1; O1: only skip MorphKernel mountain texture; O2: O1 + single Rune before unchanged coverage/coast\n"
                + "seed=" + SEED + "; L=" + HORIZONTAL_WU + "wu; A=" + VERTICAL_E + "e; reference=" + REFERENCE_E + "e; central difference +/-2wu\n"
                + "settings=" + SETTINGS + "; hash=integer SplitMix64 per world cell; no geogenesis -D flags; no particle/hydrology\n"
                + "phase/density fixed globally; output correction scales continuously by M1 weight; no per-window normalization or height retuning\n"
                + "O3/rounded: O2 with M1-only rounded absolute-value radius=0.1; same frequencies, direction warp, amplitudes and Rune settings\n"
                + "guided: O1 final height + Rune(macro first-oct M1 guide without tiny direction warp/terrace/badlands); residual=O1-macro retained; units A=0.7*E_SPAN/ridge.max and reference=GROUND_E+A/2\n");
    }

    private void render(Path out, Window w) throws Exception {
        Files.createDirectories(out);
        String[] names = {"control", "carrier", "rune", "rounded", "guided", "mountain-weight", "coverage-alpha"};
        DataOutputStream[] streams = new DataOutputStream[names.length];
        int negative = 0, covered = 0, mountain = 0;
        long start = System.nanoTime();
        try {
            for (int k = 0; k < names.length; k++) streams[k] = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(names[k] + ".f64be"))));
            for (int j = 0; j < w.size(); j++) for (int i = 0; i < w.size(); i++) {
                double x = w.ox() + i * w.step(), z = w.oz() + j * w.step();
                double[] h = new double[ARMS.length];
                for (int k = 0; k < h.length; k++) h[k] = height(k, x, z);
                var s = carrier.sample(x, z);
                for (int k = 0; k < ARMS.length; k++) { require(Double.isFinite(h[k]), "nonfinite arm"); streams[k].writeDouble(h[k]); }
                streams[ARMS.length].writeDouble(s.weight()[17]); streams[ARMS.length + 1].writeDouble(s.alpha());
                if (s.weight()[17] == 0 || s.alpha() == 1) {
                    for (int k = 1; k < h.length; k++) require(bits(h[0], h[k]), "render negative-control drift");
                    negative++;
                }
                if (s.alpha() == 1) covered++;
                if (s.weight()[17] == 1 && s.alpha() == 0) mountain++;
            }
        } finally { for (var s : streams) if (s != null) s.close(); }
        System.out.printf(Locale.ROOT, "RENDER %s: %.3fms; negative=%d pureMountain=%d fullCoverage=%d%n",
                w.name(), (System.nanoTime() - start) / 1e6, negative, mountain, covered);
    }

    private void performance(Path out, Window w) throws Exception {
        double[][] points = new double[256][2]; Random random = new Random(17);
        for (double[] p : points) { p[0] = w.ox() + random.nextDouble() * 400; p[1] = w.oz() + random.nextDouble() * 400; }
        double[][] times = new double[ARMS.length][7]; double sink = 0;
        java.util.function.DoubleBinaryOperator[] f = {original::e, carrier::e, shaper::e, roundedShaper::e, guidedShaper::e};
        for (int round = -3; round < 7; round++) for (int order = 0; order < ARMS.length; order++) {
            int arm = Math.floorMod(round + order, ARMS.length); long t = System.nanoTime();
            for (double[] p : points) sink += f[arm].applyAsDouble(p[0], p[1]);
            if (round >= 0) times[arm][round] = (System.nanoTime() - t) / 1e6;
        }
        StringBuilder csv = new StringBuilder("arm,points,warmupBatches,measuredBatches,p50ms,p95ms\n");
        for (int arm = 0; arm < ARMS.length; arm++) { Arrays.sort(times[arm]); csv.append(ARMS[arm]).append(",256,3,7,").append(times[arm][3]).append(',').append(times[arm][6]).append('\n'); }
        Files.writeString(out.resolve("performance.csv"), csv.toString());
        System.out.println(csv + "sink=" + sink + "; sampling only, not Minecraft throughput");
    }

    private record Window(String name, int size, double step, double ox, double oz, String reason) { }
    private static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
