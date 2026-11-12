package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 构造物理量层（S1）验收探针（2026-11-12）。
 *
 * <h3>验什么</h3>
 * <p>{@link LithoPhysical} 只产出物理量（厚度/密度/活动度/均衡量），<b>不改高度</b>。
 * 本探针出四张分布图并统计连续性，确认它可作为高度基准的来源：</p>
 * <ol>
 *   <li><b>连续性</b>：{@code |∇|} 的 P99.9/P50 长尾比（平滑场 ~3~8；含折痕者极大）</li>
 *   <li><b>物理合理性</b>：陆壳应厚而轻、洋壳薄而重；洋壳厚度随离脊距离增长</li>
 *   <li><b>量纲</b>：输出为归一化无量纲量，不是格数</li>
 * </ol>
 *
 * <h3>为何用连续性做判据</h3>
 * <p>项目铁律：折痕在地形上表现为笔直线段/同心环。而 {@code |∇|} 图上的亮线
 * 也可能是"平滑脊的两翼"（极值在 d=σ 处）——所以判据必须看<b>长尾比</b>，
 * 而不是"图上有没有线"。平滑场 P99.9/P50 通常在 3~8。</p>
 *
 * <p>用法：{@code LithoPhysicalProbe <toml> <outDir> [size] [cx] [cz] [r]}</p>
 */
public final class LithoPhysicalProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: LithoPhysicalProbe <toml> <outDir> [size] [cx] [cz] [r]");
            return;
        }
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 401;
        double cx = args.length > 3 ? Double.parseDouble(args[3]) : 1590;
        double cz = args.length > 4 ? Double.parseDouble(args[4]) : 2250;
        double radiusBlocks = args.length > 5 ? Double.parseDouble(args[5]) : 900;

        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config invalid");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
            double hs = p.horizontalScale();

            // 生产构造场 + 大陆场（shellS 由 c 派生，与生产同源）
            TectonicField tect = new TectonicField(SEED);
            ContinentField continent = new ContinentField(p);
            continent.seed(SEED);

            System.out.printf(Locale.ROOT,
                    "=== S1 构造物理量 seed=%d size=%d centre=(%.0f,%.0f) r=%.0f hs=%.1f ===%n",
                    SEED, size, cx, cz, radiusBlocks, hs);

            double[][] thick = new double[size][size];
            double[][] dens = new double[size][size];
            double[][] buoy = new double[size][size];
            double[][] shell = new double[size][size];
            double[][] distA = new double[size][size];
            double[][] cField = new double[size][size];
            boolean[][] isLand = new boolean[size][size];

            double tMin = 1e9, tMax = -1e9, bMin = 1e9, bMax = -1e9;
            double landThickSum = 0, oceanThickSum = 0;
            int landN = 0, oceanN = 0;

            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double bx = cx - radiusBlocks + (double) i / (size - 1) * 2 * radiusBlocks;
                    double bz = cz - radiusBlocks + (double) j / (size - 1) * 2 * radiusBlocks;
                    double wx = bx / hs, wz = bz / hs;

                    double c = continent.sample(wx, wz);
                    cField[j][i] = c;
                    double s = TectonicField.shellFromC(c);
                    TectonicField.Sample ts = tect.sample(wx, wz);
                    LithoPhysical.Sample ph = LithoPhysical.of(ts, s);

                    thick[j][i] = ph.thickness();
                    dens[j][i] = ph.density();
                    buoy[j][i] = ph.buoyancy();
                    shell[j][i] = s;
                    distA[j][i] = ts.dist();

                    // 用 c 场判陆海（与"海岸线由高度涌现"同源：c 是海陆场）
                    boolean land = c > 0;
                    isLand[j][i] = land;
                    if (land) { landThickSum += ph.thickness(); landN++; }
                    else { oceanThickSum += ph.thickness(); oceanN++; }

                    tMin = Math.min(tMin, ph.thickness()); tMax = Math.max(tMax, ph.thickness());
                    bMin = Math.min(bMin, ph.buoyancy()); bMax = Math.max(bMax, ph.buoyancy());
                }
                if (j % 100 == 0) System.out.println("row " + j);
            }

            System.out.println();
            System.out.println("=== 物理合理性与量纲 ===");
            System.out.printf(Locale.ROOT, "  thickness 范围 [%.3f, %.3f]（归一化，非格数）%n", tMin, tMax);
            System.out.printf(Locale.ROOT, "  buoyancy  范围 [%.3f, %.3f]%n", bMin, bMax);
            System.out.printf(Locale.ROOT, "  陆壳(c>0) 平均厚度 = %.3f（n=%d）%n",
                    landThickSum / Math.max(1, landN), landN);
            System.out.printf(Locale.ROOT, "  洋壳(c<=0) 平均厚度 = %.3f（n=%d）%n",
                    oceanThickSum / Math.max(1, oceanN), oceanN);
            System.out.printf(Locale.ROOT, "  ⇒ 陆壳应比洋壳厚：%s%n",
                    (landThickSum / Math.max(1, landN)) > (oceanThickSum / Math.max(1, oceanN))
                            ? "是 ✓" : "否 ✗ 有问题");

            // 洋壳厚度应随离脊距离增长
            System.out.println();
            System.out.println("=== 洋壳厚度 vs 离脊距离（应单调递增）===");
            double[] edges = {0, 150, 300, 450, 650, 900, 1200, 1e9};
            double[] sum = new double[edges.length];
            int[] cnt = new int[edges.length];
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    if (isLand[j][i]) continue;
                    double d = distA[j][i];
                    for (int b = 0; b < edges.length - 1; b++) {
                        if (d >= edges[b] && d < edges[b + 1]) {
                            sum[b] += thick[j][i]; cnt[b]++; break;
                        }
                    }
                }
            }
            double prev = -1; boolean mono = true;
            for (int b = 0; b < edges.length - 1; b++) {
                if (cnt[b] == 0) continue;
                double m = sum[b] / cnt[b];
                String label = edges[b + 1] > 1e8
                        ? String.format(Locale.ROOT, ">=%.0f", edges[b])
                        : String.format(Locale.ROOT, "%.0f-%.0f", edges[b], edges[b + 1]);
                System.out.printf(Locale.ROOT, "  %-14s n=%7d  平均厚度=%.4f%n", label, cnt[b], m);
                if (prev >= 0 && m < prev - 1e-6) mono = false;
                prev = m;
            }
            System.out.printf(Locale.ROOT, "  ⇒ 单调递增：%s%n", mono ? "是 ✓" : "否（需检查）");

            // 连续性：|∇| 梯度统计
            System.out.println();
            System.out.println("=== 连续性判据：|∇| 梯度（对照 c 场自证判据有效性）===");
            System.out.println("  ⚠ 不用 P99.9/P50：本场在 |c|>0.15 处被钳到端点 ⇒ P50=0 ⇒ 比值无意义。");
            System.out.println("  判据改为：|∇| 的最大值是否与 c 场同量级（smoothstep 正常放大约 4×）。");
            reportGradient("c(自证)  ", cField, size);
            reportGradient("thickness", thick, size);
            reportGradient("density  ", dens, size);
            reportGradient("buoyancy ", buoy, size);

            // 出图
            writeImg(thick, tMin, tMax, new File(outDir.toFile(), "litho-thickness.png"));
            writeImg(dens, 0.5, 0.95, new File(outDir.toFile(), "litho-density.png"));
            writeImg(buoy, bMin, bMax, new File(outDir.toFile(), "litho-buoyancy.png"));
            writeImg(shell, 0, 1, new File(outDir.toFile(), "litho-shellS.png"));

            // 并排（厚度 / 密度 / 均衡量 / shellS）
            BufferedImage strip = new BufferedImage(size * 4 + 12, size, BufferedImage.TYPE_INT_RGB);
            var imgs = new BufferedImage[]{
                    gray(thick, tMin, tMax), gray(dens, 0.5, 0.95),
                    gray(buoy, bMin, bMax), gray(shell, 0, 1)};
            for (int k = 0; k < imgs.length; k++) {
                int x0 = k * (size + 4);
                for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
                    strip.setRGB(i + x0, j, imgs[k].getRGB(i, j));
                }
            }
            ImageIO.write(strip, "png", new File(outDir.toFile(), "litho-all-strip.png"));
            System.out.println("images -> " + outDir.toAbsolutePath());
        }
    }

    /**
     * |∇| 梯度统计。
     *
     * <p>⚠ <b>不用 P99.9/P50</b>：本场在 {@code |c|>SHELL_BLEND} 处被 {@code smoothstep}
     * 钳到端点 ⇒ 超过一半的点梯度严格为 0 ⇒ P50=0 ⇒ 比值爆炸成假信号
     * （实测报过 1144，属判据失效，不是折痕）。</p>
     *
     * <p>改用：{@code max|∇|} 与 {@code c 场}同量级比较。{@code shellS} 的 smoothstep
     * 在 {@code ±0.15} 窗口内会把梯度放大约 {@code 1/(2·0.15)·1.5 ≈ 5×}，
     * 故物理量的 {@code max|∇|} 应为 {@code c} 的 1~10 倍；若达数十倍才是折痕。</p>
     */
    private static void reportGradient(String name, double[][] f, int n) {
        double[] g = new double[(n - 2) * (n - 2)];
        int k = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                double gx = (f[j][i + 1] - f[j][i - 1]) * 0.5;
                double gz = (f[j + 1][i] - f[j - 1][i]) * 0.5;
                g[k++] = Math.hypot(gx, gz);
            }
        }
        Arrays.sort(g, 0, k);
        double p90 = g[(int) (k * 0.90)];
        double p99 = g[(int) (k * 0.99)];
        double max = g[k - 1];
        System.out.printf(Locale.ROOT, "  %s  P90=%.3e  P99=%.3e  max=%.3e%n", name, p90, p99, max);
    }

    private static void writeImg(double[][] d, double lo, double hi, File f) throws Exception {
        ImageIO.write(gray(d, lo, hi), "png", f);
    }

    private static BufferedImage gray(double[][] d, double lo, double hi) {
        int n = d.length;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double range = Math.max(1e-12, hi - lo);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double t = (d[j][i] - lo) / range;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                int v = (int) Math.round(255 * t);
                img.setRGB(i, j, v << 16 | v << 8 | v);
            }
        }
        return img;
    }
}
