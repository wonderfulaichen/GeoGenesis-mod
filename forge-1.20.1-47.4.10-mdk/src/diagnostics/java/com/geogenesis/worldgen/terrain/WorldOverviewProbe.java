package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>世界全览图</b>——一张图同时看到海洋与大陆的分布格局。
 *
 * <h3>为什么需要它</h3>
 * <p>此前所有出图都是局部窗口（R=1500~3000wu），只能看到"几座岛"。
 * 要判断「世界长什么样」，需要覆盖多个大陆块与大洋的尺度：
 * 核间距 2100wu、occ 波长 5000wu ⇒ <b>窗口半径需 ≥15000wu</b>（3 个 occ 波长）。</p>
 *
 * <h3>走生产路径（铁律：渲染图 = 游戏实机）</h3>
 * <p>直接调 {@link MorphTerrainSampler#sampleE}（与 chunk 采样同一入口），
 * 不经任何探针专用的近似。</p>
 *
 * <h3>配色（8 级，按 e 分档）</h3>
 * <pre>
 *   e &lt; −0.30  深海     深蓝
 *   −0.30..−0.12 洋盆    中蓝
 *   −0.12..−0.02 陆架    浅蓝
 *   −0.02..0    海岸浅滩  青
 *   0..0.08     平原     绿
 *   0.08..0.25  丘陵     黄绿
 *   0.25..0.50  山地     土黄
 *   &gt;0.50      高山     灰白
 * </pre>
 *
 * <p>用法：{@code gradlew runWorldOverviewProbe [-PprobeArgs="&lt;size&gt; &lt;radiusWu&gt; &lt;cx&gt; &lt;cz&gt;"]}<br>
 * 默认 size=900, radius=18000, 中心 (0,0)（= 36000wu ≈ 72000 块的视野）。</p>
 */
public final class WorldOverviewProbe {

    private static final String OUT_DIR = "build/ocean-ab";
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 900;
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 18000.0;
        double cx = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;
        double cz = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;

        MorphTerrainSampler sampler = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        long t0 = System.nanoTime();
        double[][] e = new double[n][n];
        double half = radius;
        for (int j = 0; j < n; j++) {
            double wz = cz - half + 2 * half * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - half + 2 * half * i / (n - 1);
                e[j][i] = sampler.sampleE(wx, wz);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        double wuPerPx = 2 * radius / (n - 1);
        System.out.printf(Locale.ROOT,
                "=== 世界全览  %d²  视野 %.0f×%.0f wu（%.0f×%.0f 块）  分辨率 %.1f wu/px  %d ms ===%n",
                n, 2 * radius, 2 * radius, 4 * radius, 4 * radius, wuPerPx, ms);

        // 统计
        int land = 0, sea = 0, shelf = 0, deep = 0;
        double maxE = -9, minE = 9;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double v = e[j][i];
                maxE = Math.max(maxE, v);
                minE = Math.min(minE, v);
                if (v > 0) {
                    land++;
                } else if (v > -0.12) {
                    shelf++;
                } else if (v > -0.30) {
                    sea++;
                } else {
                    deep++;
                }
            }
        }
        int total = n * n;
        System.out.printf(Locale.ROOT,
                "[构成] 陆地 %.1f%%  陆架 %.1f%%  洋盆 %.1f%%  深海 %.1f%%    e 范围 [%.3f, %.3f]%n",
                100.0 * land / total, 100.0 * shelf / total,
                100.0 * sea / total, 100.0 * deep / total, minE, maxE);

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                img.setRGB(i, j, colorOf(e[j][i]));
            }
        }
        shade(img, e, n);

        // 经纬网格（每 5000wu 一条，辅助判断尺度）
        int stepPx = (int) Math.round(5000.0 / wuPerPx);
        if (stepPx > 20) {
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(255, 255, 255, 40));
            for (int k = stepPx; k < n; k += stepPx) {
                g.drawLine(k, 0, k, n);
                g.drawLine(0, k, n, k);
            }
            g.dispose();
        }

        drawLabel(img, String.format(Locale.ROOT,
                "WORLD OVERVIEW  %.0fx%.0f wu  (5000wu grid)  land %.0f%%",
                2 * radius, 2 * radius, 100.0 * land / total));

        Path dir = Path.of(OUT_DIR);
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve("world-overview.png").normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("output escapes dir: " + file);
        }
        ImageIO.write(img, "png", file.toFile());
        System.out.println("image -> " + file.toAbsolutePath());
    }

    /** e → RGB（8 级色带）。 */
    private static int colorOf(double e) {
        if (e < -0.30) {
            return 0x0E2B4A;            // 深海
        }
        if (e < -0.12) {
            return 0x1B4E79;            // 洋盆
        }
        if (e < -0.02) {
            return 0x4A8FC0;            // 陆架
        }
        if (e < 0.0) {
            return 0x87C4DC;            // 海岸浅滩
        }
        if (e < 0.08) {
            return 0x4C8B3F;            // 平原
        }
        if (e < 0.25) {
            return 0x7FA84A;            // 丘陵
        }
        if (e < 0.50) {
            return 0xBCA76A;            // 山地
        }
        return 0xE8E4DC;                // 高山
    }

    /** NW 光照（只照陆地，海拔差用 e）。 */
    private static void shade(BufferedImage img, double[][] e, int n) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (e[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                int j0 = Math.max(0, j - 2), j1 = Math.min(n - 1, j + 2);
                double gx = (e[j][i1] - e[j][i0]) * 236.0 / Math.max(1, i1 - i0);
                double gy = (e[j1][i] - e[j0][i]) * 236.0 / Math.max(1, j1 - j0);
                double nx = -gx, ny = -gy, nz = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.5 - ny * 0.5 + nz * 0.7071) / len;
                double sh = 0.55 + 0.75 * Math.max(0, Math.min(1, dd));
                int c = img.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b = Math.min(255, (int) ((c & 0xFF) * sh));
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
    }

    private static void drawLabel(BufferedImage img, String label) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 15));
        g.drawString(label, 10, 24);
        g.dispose();
    }

    private WorldOverviewProbe() { }
}
