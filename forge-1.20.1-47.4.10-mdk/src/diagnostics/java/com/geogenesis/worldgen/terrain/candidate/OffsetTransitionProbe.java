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

/** 三版独立验证；不测性能、不优化，任何契约失败非零退出。 */
public final class OffsetTransitionProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double CX = -140, CZ = 137;
    private static long checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]); Files.createDirectories(out);
        Files.copy(Path.of(args[0]), out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        HeightCurve curve;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load(); require(GeoGenesisConfig.SPEC.isCorrect(cfg), "config correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        for (var pair : new FourPairMorphCandidate.Pair[]{FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS,
                FourPairMorphCandidate.Pair.MOUNTAINS_BADLANDS}) {
            Path dest = out.resolve(pair.name().toLowerCase(Locale.ROOT)); Files.createDirectories(dest);
            StringBuilder summary = new StringBuilder("pair=" + pair + "\n");
            for (long seed : new long[]{SEED, 1, -817263}) verify(seed, pair, curve, summary);
            OffsetTransitionCandidate c = new OffsetTransitionCandidate(SEED, pair, curve, CX);
            maps(dest, c); profiles(dest, c); scan(dest, c);
            summary.append("checks_cumulative=" + checks + " PASS\nNo performance optimization or integration. Visual acceptance remains separate.\n");
            Files.writeString(dest.resolve("metrics.txt"), summary); System.out.print(summary);
        }
        Files.writeString(out.resolve("geometry.json"), "{\"seed\":\"" + SEED + "\",\"center\":[-140,137],"
                + "\"width\":1201,\"height\":601,\"step\":1,\"maxOffset\":160,\"maxReach\":520}\n");
        System.out.println("FINISHED checks=" + checks);
    }

    private static void verify(long seed, FourPairMorphCandidate.Pair pair, HeightCurve curve, StringBuilder summary) {
        OffsetTransitionCandidate c = new OffsetTransitionCandidate(seed, pair, curve, CX);
        AdaptiveWidthCandidate b = new AdaptiveWidthCandidate(seed, pair, curve, CX);
        Random r = new Random(seed); double min = 160, max = -160;
        for (int i = 0; i < 40; i++) {
            double z = r.nextDouble() * 20000 - 10000;
            var g = c.guide(z);
            require(g.equals(c.guide(z)), "guide nondeterminism");
            require(g.width() == b.guide(z).width(), "B1 width changed");
            require(Double.isFinite(g.offset()) && Math.abs(g.offset()) <= 160, "offset bound");
            min = Math.min(min, g.offset()); max = Math.max(max, g.offset());
            double prev = -1;
            for (int x = -550; x <= 550; x += 5) {
                double p = c.position(CX + x, g.width(), g.offset());
                require(p >= prev && p <= 1 && p >= 0, "progress folds"); prev = p;
            }
            require(Math.abs(c.position(CX + g.offset(), g.width(), g.offset()) - .5) < 1e-15, "center mismatch");
            for (double side : new double[]{-1, 1}) {
                double x = CX + side * 521;
                require(c.sample(x, z) == c.at(x, z, 240, 0), "core changed");
                double inside = CX + g.offset() + side * g.width() / 2 - side * .001;
                require(Math.abs(c.at(inside, z, g.width(), g.offset()) - c.at(inside, z, .001, g.offset())) < 2e-6,
                        "same-coordinate endpoint seam");
            }
            double x = CX + r.nextDouble() * 1000 - 500;
            double v = c.at(x, z, g.width(), g.offset());
            require(Double.isFinite(v) && v == c.sample(x, z), "row/sample mismatch");
            c.at(x + 5, z - 2, g.width(), g.offset());
            require(v == c.at(x, z, g.width(), g.offset()), "order dependence");
        }
        double jump = Math.abs(c.position(CX, 240, -80) - c.position(CX, 240, 80));
        require(jump > .6, "hard-offset negative control missed");
        summary.append(String.format("seed=%d offset_min=%.6f offset_max=%.6f negative_control=%.6f%n", seed, min, max, jump));
    }

    private static void maps(Path out, OffsetTransitionCandidate c) throws Exception {
        StringBuilder guide = new StringBuilder("z,width,offset,center_x,left_x,right_x\n");
        try (DataOutputStream a = stream(out.resolve("fixed.f64be"));
             DataOutputStream b = stream(out.resolve("width.f64be"));
             DataOutputStream shifted = stream(out.resolve("offset.f64be"));
             DataOutputStream progress = stream(out.resolve("progress.f64be"))) {
            for (int j = 0; j < 601; j++) {
                double z = CZ + j - 300; var g = c.guide(z);
                guide.append(String.format("%.6f,%.9f,%.9f,%.9f,%.9f,%.9f%n", z, g.width(), g.offset(),
                        CX + g.offset(), CX + g.offset() - g.width() / 2, CX + g.offset() + g.width() / 2));
                for (int i = 0; i < 1201; i++) {
                    double x = CX + i - 600;
                    double ya = c.y(c.at(x, z, 240, 0)), yb = c.y(c.at(x, z, g.width(), 0));
                    double yc = c.y(c.at(x, z, g.width(), g.offset()));
                    require(Double.isFinite(ya) && Double.isFinite(yb) && Double.isFinite(yc), "invalid map");
                    a.writeDouble(ya); b.writeDouble(yb); shifted.writeDouble(yc);
                    progress.writeDouble(HillsPlateauMorphCandidate.ease(c.position(x, g.width(), g.offset())));
                }
            }
        }
        Files.writeString(out.resolve("guides.csv"), guide);
    }

    private static void profiles(Path out, OffsetTransitionCandidate c) throws Exception {
        StringBuilder csv = new StringBuilder("z,x,fixed_y,width_y,offset_y,width,offset\n");
        for (double z : new double[]{CZ - 160, CZ, CZ + 160}) {
            var g = c.guide(z);
            for (int i = 0; i <= 2400; i++) {
                double x = CX - 600 + i * .5;
                csv.append(String.format("%.3f,%.3f,%.9f,%.9f,%.9f,%.9f,%.9f%n", z, x,
                        c.y(c.at(x, z, 240, 0)), c.y(c.at(x, z, g.width(), 0)),
                        c.y(c.at(x, z, g.width(), g.offset())), g.width(), g.offset()));
            }
        }
        Files.writeString(out.resolve("profiles.csv"), csv);
    }

    private static void scan(Path out, OffsetTransitionCandidate c) throws Exception {
        StringBuilder csv = new StringBuilder("epsilon,max_offset_delta,max_height_delta_h01\n");
        for (double eps : new double[]{.1, .01, .001}) {
            double offsetDelta = 0, heightDelta = 0;
            for (int j = 0; j <= 20; j++) {
                double z = CZ - 300 + j * 30; var gm = c.guide(z - eps); var gp = c.guide(z + eps);
                offsetDelta = Math.max(offsetDelta, Math.abs(gp.offset() - gm.offset()));
                for (int i = -510; i <= 510; i += 5) {
                    double x = CX + i;
                    heightDelta = Math.max(heightDelta, Math.abs(c.at(x + eps, z + eps, gp.width(), gp.offset())
                            - c.at(x - eps, z - eps, gm.width(), gm.offset())));
                }
            }
            csv.append(String.format("%.6f,%.12g,%.12g%n", eps, offsetDelta, heightDelta));
        }
        Files.writeString(out.resolve("convergence.csv"), csv);
    }

    private static DataOutputStream stream(Path p) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)));
    }
    private static void require(boolean ok, String msg) { checks++; if (!ok) throw new AssertionError(msg); }
}
