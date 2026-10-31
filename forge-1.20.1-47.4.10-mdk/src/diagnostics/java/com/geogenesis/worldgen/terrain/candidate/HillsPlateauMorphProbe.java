package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Random;

/** 只运行单对原型；验证失败抛 AssertionError，非零退出。 */
public final class HillsPlateauMorphProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double CX = -140, CZ = 137;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path config = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        Files.copy(config, out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        HeightCurve curve;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(
                out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "TOML requires correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
            curve = new HeightCurve(params, -64, 320);
        }
        for (long seed : new long[]{SEED, 1L, -817263L}) verifyEndpoints(seed);
        HillsPlateauMorphCandidate morph = new HillsPlateauMorphCandidate(SEED);
        double nonLerp = verifyIntermediate(morph);
        String terraceSummary = verifyTerrace(out, curve);
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("max_h01_difference_from_finished_lerp=%.9f%n", nonLerp));
        summary.append(terraceSummary);
        for (int stage = 0; stage <= 4; stage++) {
            double t = stage / 4.0;
            writeMap(out.resolve("stage-" + stage + ".f64be"), 601, 601, 2.0,
                    (x, z) -> worldY(curve, morph.sample(x, z, t)));
        }
        writeMap(out.resolve("spatial.f64be"), 1201, 601, 1.0,
                (x, z) -> worldY(curve, morph.spatial(x, z, CX)));
        writeMap(out.resolve("finished-lerp.f64be"), 1201, 601, 1.0,
                (x, z) -> worldY(curve, finishedLerp(morph, x, z)));
        writeProfiles(out, curve, morph);
        summary.append(String.format("checks=%d PASS%n", checks));
        Files.writeString(out.resolve("metrics.txt"), summary);
        Files.writeString(out.resolve("geometry.json"), String.format(
                "{\"seed\":\"%d\",\"center\":[%.1f,%.1f],\"stages\":5,"
                + "\"stageSize\":601,\"stageStep\":2,\"width\":1201,\"height\":601,"
                + "\"step\":1,\"halfWidth\":120,\"groundE\":0.02,\"eSpan\":1.50}%n", SEED, CX, CZ));
        System.out.print(summary);
        System.out.println("Terrace switch checks PASS; visual acceptance of the whole transition remains separate.");
        System.out.println("out=" + out.toAbsolutePath());
    }

    private static void verifyEndpoints(long seed) {
        HillsCandidate hills = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        hills.seed(seed + 11);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        plateau.seed(seed + 44);
        HillsPlateauMorphCandidate m = new HillsPlateauMorphCandidate(seed);
        int p = (int) ((seed + 44) ^ ((seed + 44) >>> 32));
        RefNoise.Node detail = new RefNoise.Mul(
                RefNoise.Perlin.ofScale(p + 6, 20f, 3), new RefNoise.Constant(0.05f));
        RefNoise.Node surface = RefNoise.warpPerlin(detail, p + 7, 40f, 2, 20f);
        Random r = new Random(seed);
        for (int i = 0; i < 12000; i++) {
            double x = r.nextDouble() * 200000 - 100000;
            double z = r.nextDouble() * 200000 - 100000;
            require(m.sample(x, z, 0) == hills.height01(x, z), "hills endpoint mismatch");
            require(Math.abs(m.sample(x, z, 1) - referencePlateau(plateau, surface, x, z)) < 1e-7,
                    "reference-range plateau endpoint mismatch");
            require(m.spatial(CX - 121, z, CX) == hills.height01(CX - 121, z), "left core");
            require(Math.abs(m.spatial(CX + 121, z, CX)
                    - referencePlateau(plateau, surface, CX + 121, z)) < 1e-7, "right core");
        }
    }

    private static double verifyIntermediate(HillsPlateauMorphCandidate m) {
        Random r = new Random(SEED);
        double maxDiff = 0;
        for (int i = 0; i < 10000; i++) {
            double x = r.nextDouble() * 1200 + CX - 600;
            double z = r.nextDouble() * 1200 + CZ - 600;
            double p = r.nextDouble(), t = HillsPlateauMorphCandidate.ease(p);
            double v = m.sample(x, z, p);
            require(Double.isFinite(v) && v >= 0 && v <= 0.7, "invalid intermediate");
            require(v == m.sample(x, z, p), "nondeterministic");
            double lerp = m.sample(x, z, 0) * (1 - t) + m.sample(x, z, 1) * t;
            maxDiff = Math.max(maxDiff, Math.abs(v - lerp));
        }
        require(maxDiff > 0.001, "prototype reduced to finished-height lerp");
        return maxDiff;
    }

    private static double referencePlateau(PlateauCandidate plateau, RefNoise.Node surface,
                                           double x, double z) {
        return (referenceTerrace((float) plateau.debugBase(x, z))
                + surface.compute((float) x, (float) z)) * 0.475f;
    }

    // 按参考 Step[] 构建与 next.value 计算，独立于共享 RefNoise.Terrace。
    private static float referenceTerrace(float input) {
        float v = Math.max(0f, Math.min(0.999999f, input));
        float spacing = (0.75f * 0.6f + 0.3f + 0.15f) / 3f;
        float[] values = {0f, spacing, 2f * spacing, 3f * spacing};
        int i = (int) Math.floor(v * 4f);
        float value = values[i];
        if (i == 3) return value;
        float bound = (spacing - spacing * 0.4f) / 2f;
        float lower = value - bound, upper = value + bound;
        if (v < lower) return value;
        if (v > upper) return values[i + 1];
        float alpha = (v - lower) / (upper - lower);
        float ramp = 1f - 0.9f * 0.5f, cliff = 1f - 0.15f * 0.5f;
        if (alpha > ramp) value += (values[i + 1] - value)
                * ((alpha - ramp) / (1f - ramp)) * 0.35f;
        if (alpha > cliff) value += ((alpha - cliff) / (1f - cliff)) * (values[i + 1] - value);
        return value;
    }

    private static String verifyTerrace(Path out, HeightCurve curve) throws Exception {
        HillsPlateauMorphCandidate m = new HillsPlateauMorphCandidate(SEED);
        RefNoise.Node old = new RefNoise.Terrace((v, unused) -> v,
                0.9f, 0.15f, 0.35f, 0.4f, 4, 0f, 1.05f, false);
        StringBuilder summary = new StringBuilder("terrace_range=reference_declared_0_to_0.9\n");
        for (float v : new float[]{0.25f, 0.5f, 0.75f}) {
            float left = m.terraceValue(Math.nextDown(v)), at = m.terraceValue(v);
            float right = m.terraceValue(Math.nextUp(v));
            require(Math.max(Math.abs(at - left), Math.abs(right - at)) < 1e-6f,
                    "Terrace switch jump at " + v);
            summary.append(String.format("switch_%.2f_left=%.9f at=%.9f right=%.9f%n", v, left, at, right));
        }
        double oldJump = old.compute(Math.nextUp(0.75f), 0) - old.compute(Math.nextDown(0.75f), 0);
        require(oldJump > 0.29, "negative control failed to detect the old jump");
        summary.append(String.format("negative_control_old_jump_0.75=%.9f%n", oldJump));
        for (int i = 0; i <= 20000; i++) {
            float v = i / 20000f;
            require(Math.abs(m.terraceValue(v) - referenceTerrace(v)) < 2e-7f, "reference mismatch");
        }
        StringBuilder csv = new StringBuilder("input,old_terrace,corrected_terrace,old_y,corrected_y\n");
        for (int i = 0; i <= 4000; i++) {
            float v = 0.65f + i * 0.00005f;
            float a = old.compute(v, 0), b = m.terraceValue(v);
            csv.append(String.format("%.9f,%.9f,%.9f,%.9f,%.9f%n", v, a, b,
                    worldY(curve, a * 0.475f), worldY(curve, b * 0.475f)));
        }
        Files.writeString(out.resolve("terrace-transfer.csv"), csv);
        return summary.toString();
    }

    private static void writeProfiles(Path out, HeightCurve c, HillsPlateauMorphCandidate m)
            throws Exception {
        StringBuilder csv = new StringBuilder("z,x,position,morph_y,lerp_y,hills_y,plateau_y\n");
        for (double z : new double[]{CZ - 160, CZ, CZ + 160}) {
            for (int i = 0; i <= 2400; i++) {
                double x = CX - 600 + i * 0.5;
                double p = Math.max(0, Math.min(1, (x - CX + 120) / 240));
                csv.append(String.format(Locale.ROOT, "%.3f,%.3f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                        z, x, p, worldY(c, m.spatial(x, z, CX)),
                        worldY(c, finishedLerp(m, x, z)), worldY(c, m.sample(x, z, 0)),
                        worldY(c, m.sample(x, z, 1))));
            }
        }
        Files.writeString(out.resolve("profiles.csv"), csv);
        StringBuilder seams = new StringBuilder("boundary,epsilon,max_error_from_endpoint_h01\n");
        for (double side : new double[]{-1, 1}) {
            for (double eps : new double[]{1, 0.1, 0.01, 0.001}) {
                double max = 0, x = CX + side * 120 - side * eps;
                for (int z = -300; z <= 300; z += 3) {
                    max = Math.max(max, Math.abs(m.spatial(x, CZ + z, CX)
                            - m.sample(x, CZ + z, side < 0 ? 0 : 1)));
                }
                seams.append(String.format(Locale.ROOT, "%.0f,%.6f,%.12g%n", side, eps, max));
            }
        }
        Files.writeString(out.resolve("seams.csv"), seams);
    }

    private static double finishedLerp(HillsPlateauMorphCandidate m, double x, double z) {
        double t = HillsPlateauMorphCandidate.ease((x - CX + 120) / 240);
        return m.sample(x, z, 0) * (1 - t) + m.sample(x, z, 1) * t;
    }

    private interface Field { double at(double x, double z); }

    private static void writeMap(Path file, int w, int h, double step, Field f) throws Exception {
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    double y = f.at(CX + (x - w / 2) * step, CZ + (z - h / 2) * step);
                    require(Double.isFinite(y), "invalid map");
                    s.writeDouble(y);
                }
            }
        }
    }

    private static double worldY(HeightCurve c, double h) {
        return c.heightFromE(Math.max(0.02 + h * 1.5, 0));
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
