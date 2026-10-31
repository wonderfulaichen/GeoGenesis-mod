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

/** 固定240与B1对照；失败抛断言非零退出。 */
public final class AdaptiveWidthProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double CX = -140, CZ = 137;
    private static long checks;
    private static volatile double sink;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        Files.copy(Path.of(args[0]), out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        HeightCurve curve;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "config correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        for (var pair : new FourPairMorphCandidate.Pair[]{FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS,
                FourPairMorphCandidate.Pair.MOUNTAINS_BADLANDS}) {
            Path dest = out.resolve(pair.name().toLowerCase(Locale.ROOT));
            Files.createDirectories(dest);
            StringBuilder summary = new StringBuilder("pair=" + pair + "\n");
            for (long seed : new long[]{SEED, 1, -817263}) verify(seed, pair, curve, summary);
            AdaptiveWidthCandidate b = new AdaptiveWidthCandidate(SEED, pair, curve, CX);
            maps(dest, b);
            profiles(dest, b);
            scan(dest, b);
            benchmark(b, summary);
            summary.append("checks_cumulative=" + checks + " PASS\n");
            summary.append("Sampled numerical checks only; visual acceptance is separate.\n");
            Files.writeString(dest.resolve("metrics.txt"), summary);
            System.out.print(summary);
        }
        Files.writeString(out.resolve("geometry.json"), "{\"seed\":\"" + SEED + "\",\"center\":[-140,137],"
                + "\"width\":1201,\"height\":601,\"step\":1,\"offset\":0,\"minWidth\":240,\"maxWidth\":720}\n");
        System.out.println("BOTH PAIRS FINISHED checks=" + checks);
    }

    private static void verify(long seed, FourPairMorphCandidate.Pair pair, HeightCurve c, StringBuilder summary) {
        AdaptiveWidthCandidate b = new AdaptiveWidthCandidate(seed, pair, c, CX);
        Random r = new Random(seed);
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (int i = 0; i < 180; i++) {
            double z = r.nextDouble() * 20000 - 10000;
            var guide = b.guide(z);
            double w = guide.width();
            min = Math.min(min, w); max = Math.max(max, w);
            require(w >= 240 && w <= 720 && Double.isFinite(w), "width bound");
            require(guide.equals(b.guide(z)), "guide nondeterminism");
            require(b.position(CX, w) == 0.5, "center shifted");
            double prior = -1;
            for (int j = -400; j <= 400; j += 8) {
                double p = b.position(CX + j, w);
                require(p >= prior && p >= 0 && p <= 1, "nonmonotonic coordinate");
                prior = p;
            }
            for (double side : new double[]{-1, 1}) {
                double x = CX + side * 361;
                require(b.sample(x, z) == b.atWidth(x, z, 240), "pure core mismatch");
                double at = CX + side * w / 2;
                double inside = at - side * .001;
                // 同一坐标对比纯族，排除原族自身空间坡度。
                double endpoint = b.atWidth(inside, z, 0.001);
                double error = Math.abs(b.atWidth(inside, z, w) - endpoint);
                require(error < 2e-6, "adaptive seam " + error);
            }
            double x = CX + r.nextDouble() * 720 - 360;
            double v = b.sample(x, z);
            require(Double.isFinite(v) && v == b.sample(x, z), "sample nondeterminism");
            require(v == b.atWidth(x, z, w), "row reuse changed sample");
            b.sample(x + 7, z + 5);
            require(v == b.sample(x, z), "order dependence");
        }
        // 注入硬宽度切换，验证缩小步长判据可检测阶跃。
        double jump = Math.abs(b.position(CX + 80, 240) - b.position(CX + 80, 720));
        require(jump > .2, "negative control missed width step");
        summary.append(String.format("seed=%d width_min=%.6f width_max=%.6f negative_control_jump=%.6f%n", seed, min, max, jump));
    }

    private static void maps(Path out, AdaptiveWidthCandidate b) throws Exception {
        StringBuilder widths = new StringBuilder("z,width,height_gap,roughness_gap,left_x,right_x\n");
        try (DataOutputStream a = stream(out.resolve("fixed.f64be"));
             DataOutputStream adaptive = stream(out.resolve("adaptive.f64be"));
             DataOutputStream progress = stream(out.resolve("progress.f64be"))) {
            for (int j = 0; j < 601; j++) {
                double z = CZ + j - 300;
                var g = b.guide(z);
                widths.append(String.format("%.6f,%.9f,%.9f,%.9f,%.9f,%.9f%n", z, g.width(), g.heightGap(),
                        g.roughnessGap(), CX - g.width() / 2, CX + g.width() / 2));
                // 同一行width仅依赖z，局部复用；不写入跨调用缓存。
                for (int i = 0; i < 1201; i++) {
                    double x = CX + i - 600;
                    double ya = b.y(b.atWidth(x, z, 240)), yb = b.y(b.atWidth(x, z, g.width()));
                    require(Double.isFinite(ya) && Double.isFinite(yb), "map finite");
                    a.writeDouble(ya); adaptive.writeDouble(yb);
                    progress.writeDouble(HillsPlateauMorphCandidate.ease(b.position(x, g.width())));
                }
            }
        }
        Files.writeString(out.resolve("widths.csv"), widths);
    }

    private static void profiles(Path out, AdaptiveWidthCandidate b) throws Exception {
        StringBuilder csv = new StringBuilder("z,x,fixed_y,adaptive_y,width\n");
        for (double z : new double[]{CZ - 160, CZ, CZ + 160}) {
            double w = b.guide(z).width();
            for (int i = 0; i <= 2400; i++) {
                double x = CX - 600 + i * .5;
                csv.append(String.format("%.3f,%.3f,%.9f,%.9f,%.9f%n", z, x,
                        b.y(b.atWidth(x, z, 240)), b.y(b.atWidth(x, z, w)), w));
            }
        }
        Files.writeString(out.resolve("profiles.csv"), csv);
    }

    private static void scan(Path out, AdaptiveWidthCandidate b) throws Exception {
        StringBuilder csv = new StringBuilder("epsilon,max_width_delta,max_interior_h01_delta\n");
        for (double eps : new double[]{.1, .01, .001}) {
            double dw = 0, dh = 0;
            for (int j = 0; j <= 20; j++) {
                double z = CZ - 300 + j * 30;
                double wm = b.guide(z - eps).width(), wp = b.guide(z + eps).width();
                dw = Math.max(dw, Math.abs(wp - wm));
                for (int i = -350; i <= 350; i += 5) {
                    double x = CX + i;
                    dh = Math.max(dh, Math.abs(b.atWidth(x + eps, z + eps, wp) - b.atWidth(x - eps, z - eps, wm)));
                }
            }
            csv.append(String.format("%.6f,%.12g,%.12g%n", eps, dw, dh));
        }
        Files.writeString(out.resolve("convergence.csv"), csv);
    }

    private static void benchmark(AdaptiveWidthCandidate b, StringBuilder summary) {
        Random r = new Random(1234);
        double[] x = new double[800], z = new double[800];
        for (int i = 0; i < x.length; i++) { x[i] = CX + r.nextDouble() * 700 - 350; z[i] = CZ + r.nextDouble() * 600 - 300; }
        for (int i = 0; i < 100; i++) { sink += b.atWidth(x[i], z[i], 240); sink += b.sample(x[i], z[i]); }
        double[] a = new double[5], adaptive = new double[5];
        long calls = b.endpointCalls();
        for (int trial = 0; trial < 5; trial++) {
            for (int mode = 0; mode < 2; mode++) {
                boolean isB = (mode + trial) % 2 == 1;
                long start = System.nanoTime(); double sum = 0;
                for (int i = 0; i < x.length; i++) sum += isB ? b.sample(x[i], z[i]) : b.atWidth(x[i], z[i], 240);
                sink = sum;
                (isB ? adaptive : a)[trial] = (System.nanoTime() - start) / 1e6;
            }
        }
        java.util.Arrays.sort(a); java.util.Arrays.sort(adaptive);
        summary.append(String.format("benchmark_points=800 rounds=5 median_A_ms=%.6f median_B_uncached_ms=%.6f ratio=%.6f guide_endpoint_calls=%d%n",
                a[2], adaptive[2], adaptive[2] / a[2], b.endpointCalls() - calls));
    }

    private static DataOutputStream stream(Path p) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)));
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
