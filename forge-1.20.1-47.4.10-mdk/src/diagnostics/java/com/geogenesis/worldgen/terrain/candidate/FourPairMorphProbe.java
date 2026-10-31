package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Random;

/** 独立受控实验：默认原四组，remaining 为剩余五组；断言失败非零退出。 */
public final class FourPairMorphProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double CX = -140, CZ = 137;
    private static long checks;
    private interface Field { double at(double x, double z); }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        Files.copy(Path.of(args[0]), out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        HeightCurve curve;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "config correction required");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        FourPairMorphCandidate.Pair[] pairs = args.length > 2 && "remaining".equals(args[2])
                ? new FourPairMorphCandidate.Pair[]{FourPairMorphCandidate.Pair.PLAIN_PLATEAU,
                    FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS, FourPairMorphCandidate.Pair.PLAIN_BADLANDS,
                    FourPairMorphCandidate.Pair.HILLS_BADLANDS, FourPairMorphCandidate.Pair.MOUNTAINS_BADLANDS}
                : new FourPairMorphCandidate.Pair[]{FourPairMorphCandidate.Pair.PLAIN_HILLS,
                    FourPairMorphCandidate.Pair.HILLS_MOUNTAINS, FourPairMorphCandidate.Pair.PLATEAU_MOUNTAINS,
                    FourPairMorphCandidate.Pair.PLATEAU_BADLANDS};
        for (FourPairMorphCandidate.Pair pair : pairs) {
            StringBuilder summary = new StringBuilder("pair=" + pair + "\n");
            for (long seed : new long[]{SEED, 1, -817263}) verify(pair, seed, summary);
            Path dest = out.resolve(pair.name().toLowerCase(Locale.ROOT));
            Files.createDirectories(dest);
            FourPairMorphCandidate m = new FourPairMorphCandidate(SEED, pair);
            for (int i = 0; i <= 4; i++) {
                double p = i / 4.0;
                map(dest.resolve("stage-" + i + ".f64be"), 601, 601, 2,
                        (x, z) -> y(curve, m.sample(x, z, p)));
            }
            map(dest.resolve("spatial.f64be"), 1201, 601, 1, (x, z) -> y(curve, m.spatial(x, z, CX)));
            map(dest.resolve("finished-lerp.f64be"), 1201, 601, 1, (x, z) -> y(curve, lerp(m, x, z)));
            profiles(dest, curve, m);
            seams(dest, m);
            summary.append("checks_cumulative=" + checks + " PASS\n");
            summary.append("Numerical endpoint/finite/determinism PASS; visual acceptance and internal cliff attribution remain separate.\n");
            Files.writeString(dest.resolve("metrics.txt"), summary);
            System.out.print(summary);
        }
        Files.writeString(out.resolve("geometry.json"), "{\"seed\":\"" + SEED + "\",\"center\":[-140,137],"
                + "\"stageSize\":601,\"stageStep\":2,\"width\":1201,\"height\":601,\"halfWidth\":120}\n");
        System.out.println("FINISHED pairs=" + pairs.length + "; checks=" + checks);
    }

    private static void verify(FourPairMorphCandidate.Pair pair, long seed, StringBuilder summary) {
        FourPairMorphCandidate m = new FourPairMorphCandidate(seed, pair);
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        plains.seed(seed);
        HillsCandidate hills = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        hills.seed(seed + 11);
        MountainsCandidate mountain = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        mountain.seed(seed + 77);
        BadlandsCandidate bad = new BadlandsCandidate();
        bad.seed(seed + 55);
        HillsPlateauMorphCandidate plateau = new HillsPlateauMorphCandidate(seed);
        Field a = switch (pair) {
            case PLAIN_HILLS, PLAIN_PLATEAU, PLAIN_MOUNTAINS, PLAIN_BADLANDS -> plains::height01;
            case HILLS_MOUNTAINS, HILLS_BADLANDS -> hills::height01;
            case MOUNTAINS_BADLANDS -> mountain::height01;
            default -> (x, z) -> plateau.sample(x, z, 1);
        };
        Field b = switch (pair) {
            case PLAIN_HILLS -> hills::height01;
            case PLAIN_PLATEAU -> (x, z) -> plateau.sample(x, z, 1);
            case PLATEAU_BADLANDS, PLAIN_BADLANDS, HILLS_BADLANDS, MOUNTAINS_BADLANDS -> bad::height01;
            default -> mountain::height01;
        };
        Random r = new Random(seed);
        double maxEndpoint = 0, maxNonLerp = 0;
        for (int i = 0; i < 3000; i++) {
            double x = r.nextDouble() * 200000 - 100000, z = r.nextDouble() * 200000 - 100000;
            double e = Math.max(Math.abs(m.sample(x, z, 0) - a.at(x, z)), Math.abs(m.sample(x, z, 1) - b.at(x, z)));
            maxEndpoint = Math.max(maxEndpoint, e);
            require(e <= 2e-7, "endpoint " + pair + " seed=" + seed + " error=" + e);
            require(Math.abs(m.spatial(CX - 121, z, CX) - a.at(CX - 121, z)) <= 2e-7, "left core");
            require(Math.abs(m.spatial(CX + 121, z, CX) - b.at(CX + 121, z)) <= 2e-7, "right core");
            double p = r.nextDouble(), t = HillsPlateauMorphCandidate.ease(p);
            double value = m.sample(x, z, p);
            require(Double.isFinite(value) && value >= -0.03 && value < 1.5, "invalid sample");
            require(value == m.sample(x, z, p), "nondeterministic");
            maxNonLerp = Math.max(maxNonLerp, Math.abs(value - ((1 - t) * a.at(x, z) + t * b.at(x, z))));
        }
        require(maxNonLerp > 0.001, "finished height lerp equivalent");
        summary.append(String.format("seed=%d max_endpoint_h01=%.12g max_difference_vs_finished_lerp=%.9f%n",
                seed, maxEndpoint, maxNonLerp));
    }

    private static void seams(Path out, FourPairMorphCandidate m) throws Exception {
        StringBuilder csv = new StringBuilder("boundary,epsilon,max_error_from_endpoint_h01\n");
        for (double side : new double[]{-1, 1}) {
            for (double eps : new double[]{1, 0.1, 0.01, 0.001}) {
                double x = CX + side * 120 - side * eps, max = 0;
                for (int z = -300; z <= 300; z += 3) {
                    max = Math.max(max, Math.abs(m.spatial(x, CZ + z, CX) - m.sample(x, CZ + z, side < 0 ? 0 : 1)));
                }
                if (eps == 0.001) require(max < 2e-6, "seam limit error=" + max);
                csv.append(String.format("%.0f,%.6f,%.12g%n", side, eps, max));
            }
        }
        Files.writeString(out.resolve("seams.csv"), csv);
        StringBuilder scan = new StringBuilder("epsilon,max_interior_neighbor_delta_h01\n");
        for (double eps : new double[]{0.1, 0.01, 0.001}) {
            double max = 0;
            for (int j = 0; j <= 20; j++) {
                for (int i = 1; i < 240; i++) {
                    double x = CX - 120 + i, z = CZ - 300 + j * 30;
                    max = Math.max(max, Math.abs(m.spatial(x + eps, z, CX) - m.spatial(x - eps, z, CX)));
                }
            }
            scan.append(String.format("%.6f,%.12g%n", eps, max));
        }
        Files.writeString(out.resolve("interior-scan.csv"), scan);
    }

    private static void profiles(Path out, HeightCurve c, FourPairMorphCandidate m) throws Exception {
        StringBuilder csv = new StringBuilder("z,x,position,morph_y,lerp_y,left_y,right_y\n");
        for (double z : new double[]{CZ - 160, CZ, CZ + 160}) {
            for (int i = 0; i <= 2400; i++) {
                double x = CX - 600 + i * 0.5;
                csv.append(String.format("%.3f,%.3f,%.9f,%.9f,%.9f,%.9f,%.9f%n", z, x,
                        Math.max(0, Math.min(1, (x - CX + 120) / 240)), y(c, m.spatial(x, z, CX)),
                        y(c, lerp(m, x, z)), y(c, m.sample(x, z, 0)), y(c, m.sample(x, z, 1))));
            }
        }
        Files.writeString(out.resolve("profiles.csv"), csv);
    }

    private static double lerp(FourPairMorphCandidate m, double x, double z) {
        double t = HillsPlateauMorphCandidate.ease((x - CX + 120) / 240);
        return (1 - t) * m.sample(x, z, 0) + t * m.sample(x, z, 1);
    }

    private static double y(HeightCurve c, double value) { return c.heightFromE(Math.max(0.02 + 1.5 * value, 0)); }

    private static void map(Path file, int w, int h, double step, Field f) throws Exception {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    double v = f.at(CX + (x - w / 2) * step, CZ + (z - h / 2) * step);
                    require(Double.isFinite(v), "invalid map");
                    stream.writeDouble(v);
                }
            }
        }
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
