package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.tectonic.TectonicTrend;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>构造趋势 A/B 对照图</b>（阶段五验收）——同窗、同底、只切趋势开关。
 *
 * <p>产出 {@code build/ocean-ab/trend-{on,off,diff}.png}：</p>
 * <ul>
 *   <li>{@code trend-off}：趋势关闭（= schema134 的陆地形态）</li>
 *   <li>{@code trend-on}：趋势开启（会聚带整体抬升 + 票槽偏山地/高原）</li>
 *   <li>{@code diff}：差异热图（红=变高，蓝=变低，暗=无变化）</li>
 * </ul>
 *
 * <p>为什么单独写探针：{@code MorphTerrainSampler} 的趋势开关是 {@code static final}
 * 系统属性，同一 JVM 内无法构造两个不同开关的实例。本探针直接建两个
 * {@link MorphLandField}（一个 {@code trendFn=null}、一个注入 {@link TectonicTrend}），
 * 共享同一 catalog/assembly ⇒ <b>差异 100% 来自趋势</b>。</p>
 */
public final class TrendABProbe {

    private static final String OUT_DIR = "build/ocean-ab";
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 700;
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 3000.0;
        double cx = args.length > 2 ? Double.parseDouble(args[2]) : 18000.0;
        double cz = args.length > 3 ? Double.parseDouble(args[3]) : -27000.0;

        TerrainParams p = TerrainParams.defaults();
        MorphCatalog catalog = new MorphCatalog(SEED);
        MorphAssembly assembly = MorphAssembly.of(SEED);
        MorphKernel kernel = new MorphKernel(MorphLandRecipe.all(catalog), SEED, true);

        ContinentField continent = new ContinentField(p);
        continent.seed(SEED);
        TectonicField tectonic = new TectonicField(SEED);
        TectonicTrend trend = new TectonicTrend(tectonic);

        // 关趋势：trendFn = null
        MorphLandField off = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE,
                kernel, null);
        // 开趋势：注入回调（crust01 由 continent 现算，与 MorphTerrainSampler 同式）
        MorphLandField on = new MorphLandField(catalog, assembly, MorphAssembly.REGION_SCALE,
                kernel, (x, z, ignored) -> trend.trend(x, z, (continent.sample(x, z) + 1.0) * 0.5));

        double[][] hOff = new double[n][n];
        double[][] hOn = new double[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                hOff[j][i] = off.e(wx, wz);
                hOn[j][i] = on.e(wx, wz);
            }
        }

        render(hOff, n, radius, "trend-off.png", "trend OFF (schema134 land shape)");
        render(hOn, n, radius, "trend-on.png", "trend ON (tectonic uplift)");
        renderDiff(hOn, hOff, n, "trend-diff.png", "diff trend-on minus off");

        // 统计：多少格被抬升、抬多少
        int up = 0, down = 0, same = 0;
        double maxUp = 0, maxDown = 0, sumUp = 0, sumDown = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double d = hOn[j][i] - hOff[j][i];
                double blocks = d * 236.0;   // e → 格（陆侧）
                if (blocks > 0.5) {
                    up++;
                    sumUp += blocks;
                    maxUp = Math.max(maxUp, blocks);
                } else if (blocks < -0.5) {
                    down++;
                    sumDown += -blocks;
                    maxDown = Math.max(maxDown, -blocks);
                } else {
                    same++;
                }
            }
        }
        // 诊断：找出「e 由正变负」的格子（湖泊来源），打印其 covered/trend 分解
        int turnedSea = 0;
        int shown = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (hOff[j][i] > 0 && hOn[j][i] <= 0) {
                    turnedSea++;
                    if (shown < 5) {
                        double wx = cx - radius + 2 * radius * i / (n - 1);
                        double wz = cz - radius + 2 * radius * j / (n - 1);
                        MorphLandField.Sample so = off.sample(wx, wz);
                        MorphLandField.Sample sn = on.sample(wx, wz);
                        System.out.printf(Locale.ROOT,
                                "[变海] (%.0f,%.0f) e %.4f→%.4f  covered %.4f→%.4f  trend %+.4f"
                                        + "  dom %d→%d%n",
                                wx, wz, so.e(), sn.e(), so.coveredHeight(), sn.coveredHeight(),
                                sn.trend(), so.dominantEntry(), sn.dominantEntry());
                        shown++;
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "[变海] 共 %d 格由陆地变海（%.2f%%）%n",
                turnedSea, 100.0 * turnedSea / (n * n));

        System.out.printf(Locale.ROOT,
                "=== 趋势 A/B  窗口(%.0f,%.0f) R=%.0f  %d² ===%n"
                + "[抬升] %d 格（%.1f%%）均值 +%.1f 格 最大 +%.1f 格%n"
                + "[压低] %d 格（%.1f%%）均值 -%.1f 格 最大 -%.1f 格%n"
                + "[无变化] %d 格（%.1f%%）%n",
                cx, cz, radius, n,
                up, 100.0 * up / (n * n), sumUp / Math.max(1, up), maxUp,
                down, 100.0 * down / (n * n), sumDown / Math.max(1, down), maxDown,
                same, 100.0 * same / (n * n));
    }

    private static void render(double[][] h, int n, double radius, String name, String label)
            throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double cellWu = 2 * radius / (n - 1);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double e = h[j][i];
                int r, g, b;
                if (e <= 0) {
                    double t = Math.min(1.0, -e / 0.45);
                    r = (int) (40 + 45 * (1 - t));
                    g = (int) (78 + 52 * (1 - t));
                    b = (int) (110 + 60 * (1 - t));
                } else {
                    double t = Math.min(1.0, e / 0.30);
                    r = (int) (58 + 150 * t);
                    g = (int) (112 + 70 * t);
                    b = (int) (52 + 90 * t);
                }
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 4), i1 = Math.min(n - 1, i + 4);
                int j0 = Math.max(0, j - 4), j1 = Math.min(n - 1, j + 4);
                double gx = (h[j][i1] - h[j][i0]) * 236.0 / ((i1 - i0) * cellWu);
                double gy = (h[j1][i] - h[j0][i]) * 236.0 / ((j1 - j0) * cellWu);
                double nx = -gx, ny = -gy, nz = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.5 - ny * 0.5 + nz * 0.7071) / len;
                double sh = 0.55 + 0.7 * Math.max(0, Math.min(1, dd));
                int c = img.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b2 = Math.min(255, (int) ((c & 0xFF) * sh));
                img.setRGB(i, j, (r << 16) | (g << 8) | b2);
            }
        }
        drawLabel(img, label);
        Path dir = Path.of(OUT_DIR);
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("output escapes dir: " + file);
        }
        ImageIO.write(img, "png", file.toFile());
        System.out.println("image -> " + file.toAbsolutePath());
    }

    private static void renderDiff(double[][] a, double[][] b, int n, String name, String label)
            throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double d = (a[j][i] - b[j][i]) * 236.0;   // 格
                int r, g, bl;
                if (Math.abs(d) < 2) {
                    r = 40; g = 40; bl = 48;
                } else if (d > 0) {
                    int t = (int) Math.min(255, 70 + Math.abs(d) * 4);
                    r = t; g = (int) (t * 0.35); bl = (int) (t * 0.2);
                } else {
                    int t = (int) Math.min(255, 70 + Math.abs(d) * 4);
                    r = (int) (t * 0.2); g = (int) (t * 0.4); bl = t;
                }
                img.setRGB(i, j, (r << 16) | (g << 8) | bl);
            }
        }
        drawLabel(img, label);
        Path dir = Path.of(OUT_DIR);
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("output escapes dir: " + file);
        }
        ImageIO.write(img, "png", file.toFile());
        System.out.println("image -> " + file.toAbsolutePath());
    }

    private static void drawLabel(BufferedImage img, String label) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
        g.drawString(label, 10, 20);
        g.dispose();
    }

    private TrendABProbe() { }
}
