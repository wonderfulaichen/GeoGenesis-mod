package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** 完整沙箱独立入口；不改变旧Gallery，不接生产生成器。 */
public final class FullMorphGallery {
    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;
    private static final String[] ARMS = {"baseline-coastal", "baseline-islands", "plateau-only-coastal",
            "plateau-only-islands", "morph-coastal", "morph-islands"};
    private final RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
    private final CombineCandidate original = catalog.original();
    private final FullMorphLand morph = new FullMorphLand(catalog);
    private final HeightCurve curve;
    private final ContinentField continent;
    private final OceanAssemblyCandidate baseline, plateauOnly, experiment;
    private final ShallowSeaIslandCandidate islands = new ShallowSeaIslandCandidate(SEED + 900);

    private FullMorphGallery(Path input, Path out) throws Exception {
        Files.createDirectories(out);
        Files.copy(input, out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "frozen config requires correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            var params = GeoGenesisConfig.INSTANCE.buildParams();
            curve = new HeightCurve(params, -64, 320);
            continent = new ContinentField(params);
            continent.seed(SEED);
        }
        baseline = new OceanAssemblyCandidate(original, continent, SEED + 3).enableCoastalDetail(SEED);
        plateauOnly = new OceanAssemblyCandidate(this::plateauOnlyE, continent::sample, SEED + 3).enableCoastalDetail(SEED);
        experiment = new OceanAssemblyCandidate(morph::e, continent::sample, SEED + 3).enableCoastalDetail(SEED);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        FullMorphGallery g = new FullMorphGallery(Path.of(args[0]), out);
        g.interfaceCheck();
        g.render(out);
        g.baselineCheck(out);
        Files.writeString(out.resolve("geometry.json"), "{\"seed\":\"" + SEED + "\",\"size\":1401,\"step\":5,"
                + "\"center\":[-140,137],\"sampleOrigin\":[-3642.5,-3365.5],\"jvmProperties\":[\"file.encoding=UTF-8\"]}\n");
        System.out.println("FULL MORPH GALLERY PASS");
    }

    /** 保留旧border，仅将区域选中台地换成已认可修正值域；覆盖层仍是旧台地。 */
    private double plateauOnlyE(double x, double z) {
        float oldEdge = original.debugEdge(x, z);
        double oldRegion = original.debugRegionHeight(x, z);
        var ref = readPartitionSample(x, z);
        if (catalog.slotEntry(RegionPartition.selectIndex(ref.regionId(), catalog.slotCount())) == 15) {
            double selected = catalog.entry(15).reference(x, z);
            double border = original.debugBorderAt(x, z);
            oldRegion = oldEdge >= 1 ? selected : border + (selected - border) * oldEdge;
        }
        return Math.max(CombineCandidate.GROUND_E + overlay(oldRegion, x, z) * CombineCandidate.E_SPAN, 0);
    }

    private RegionPartition.Sample readPartitionSample(double x, double z) {
        RegionPartition p = (RegionPartition) RecipeMorphCatalog.field(original, "partition");
        return p.sample((float) x, (float) z);
    }

    private double overlay(double region, double x, double z) {
        float select = original.debugMountainMask(x, z);
        float low = ((Number) staticField("MTN_BLEND_LOW")).floatValue();
        float high = ((Number) staticField("MTN_BLEND_HIGH")).floatValue();
        if (select < low) return region;
        double coverage = catalog.entry(15).oldReference(x, z);
        if (select > high) return coverage;
        float alpha = (select - low) / (high - low);
        return region + (coverage - region) * alpha;
    }

    private static Object staticField(String key) {
        try {
            var f = CombineCandidate.class.getDeclaredField(key);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private void interfaceCheck() {
        OceanAssemblyCandidate delegated = new OceanAssemblyCandidate(original::e, continent::sample, SEED + 3)
                .enableCoastalDetail(SEED);
        var random = new java.util.Random(77);
        for (int i = 0; i < 2000; i++) {
            double x = random.nextDouble() * 12000 - 6000, z = random.nextDouble() * 12000 - 6000;
            require(baseline.e(x, z) == delegated.e(x, z), "old/new ocean constructor mismatch");
            require(overlay(original.debugRegionHeight(x, z), x, z) == original.height01(x, z), "old coverage mismatch");
        }
    }

    private void render(Path out) throws Exception {
        DataOutputStream[] streams = new DataOutputStream[ARMS.length];
        try (DataOutputStream recipe = stream(out.resolve("recipe.u8"));
             DataOutputStream participants = stream(out.resolve("participants.u8"));
             DataOutputStream coverage = stream(out.resolve("coverage.f64be"))) {
            for (int i = 0; i < ARMS.length; i++) streams[i] = stream(out.resolve(ARMS[i] + ".f64be"));
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP, z = CZ + (j - SIZE / 2.0) * STEP;
                    double[] bases = {baseline.e(x, z), plateauOnly.e(x, z), experiment.e(x, z)};
                    for (int k = 0; k < bases.length; k++) {
                        double baseE = bases[k];
                        double withIslands = baseE + islands.delta((a, b) -> baseE, continent::sample, x, z);
                        double y = curve.heightFromE(baseE), iy = curve.heightFromE(withIslands);
                        require(Double.isFinite(y) && Double.isFinite(iy), "nonfinite full map");
                        streams[k * 2].writeDouble(y);
                        streams[k * 2 + 1].writeDouble(iy);
                    }
                    double[] w = morph.weights(x, z);
                    int best = 0, active = 0;
                    for (int k = 0; k < w.length; k++) {
                        if (w[k] > w[best]) best = k;
                        if (w[k] > 0) active++;
                    }
                    recipe.writeByte(best);
                    participants.writeByte(active);
                    coverage.writeDouble(original.debugMountainMask(x, z));
                }
                if (j % 50 == 0) System.out.println("full row=" + j);
            }
        } finally {
            for (DataOutputStream s : streams) if (s != null) s.close();
        }
    }

    private static void baselineCheck(Path out) throws Exception {
        require(sha(out.resolve("baseline-coastal.f64be")).equals("5a8bb721ba191c7949c583b90d7b76baaf5c6ac9e08c4619021afd2f60261d28"), "coastal baseline drift");
        require(sha(out.resolve("baseline-islands.f64be")).equals("94ba83f7359526df1267572aac181ad3922e1c41f7bca31d6b6c5ccb9be271a1"), "islands baseline drift");
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static DataOutputStream stream(Path file) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
    }
    private static void require(boolean ok, String error) { if (!ok) throw new AssertionError(error); }
}
