package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>构造特征局部特写</b>——放大到单座火山/海山可见的尺度。
 *
 * <p>区域格局图（R≥4000wu）为 11+ wu/px，一座半径 140~300wu 的火山只有
 * 十几像素，看不出锥形与火口。<b>本探针默认 R=600wu（约 1.7 wu/px）</b>，
 * 单座火山占 100~350 像素，锥体/火口/熔岩台地清晰可辨。</p>
 *
 * <p>自动定位：扫描窗口内的特征强度图，挑<b>最强单点</b>作为中心
 * （避免人工猜坐标）。可用 {@code -PprobeArgs} 显式指定中心。</p>
 *
 * <p>配色：与 {@link WorldOverviewProbe} 同一套 8 级色带 + NW 光照，
 * 保证跨图可比（同一 e 值 = 同一颜色）。</p>
 */
public final class FeatureCloseupProbe {

    private static final String OUT_DIR = "build/ocean-ab";
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 700;
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 600.0;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        // ---- 自动定位最强特征中心 ----
        double cx;
        double cz;
        String kind;
        if (args.length >= 4) {
            cx = Double.parseDouble(args[2]);
            cz = Double.parseDouble(args[3]);
            kind = "explicit";
        } else {
            double[] best = findStrongest(smp);
            cx = best[0];
            cz = best[1];
            kind = best[2] == 0 ? "volcano" : best[2] == 1 ? "seamount" : "ridge";
        }
        System.out.printf(Locale.ROOT,
                "=== 特征特写  %d²  R=%.0fwu（%.1f wu/px，%.0f×%.0f 块视野） centre=(%.0f,%.0f) 自动定位=%s ===%n",
                n, radius, 2 * radius / (n - 1), 4 * radius, 4 * radius, cx, cz, kind);

        double[][] e = new double[n][n];
        double half = radius;
        for (int j = 0; j < n; j++) {
            double wz = cz - half + 2 * half * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - half + 2 * half * i / (n - 1);
                e[j][i] = smp.sampleE(wx, wz);
            }
        }

        // ---- 窗口内特征统计 + 掩码（用于叠加标注）----
        int vol = 0, seamt = 0, ridge = 0, field = 0;
        double maxE = -9, minE = 9;
        // 特征掩码：0=无 1=火山 2=熔岩台地 3=海山 4=洋中脊
        int[][] mask = new int[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - half + 2 * half * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - half + 2 * half * i / (n - 1);
                var col = smp.sampleColumn(wx, wz);
                var landS = smp.landField().sample(wx, wz);
                if (landS.volcanoGate() > 0.10) {
                    vol++;
                    mask[j][i] = landS.volcanoGate() > 0.45 ? 1 : 5;
                }
                if (col.oceanFeat != null && col.oceanFeat.seamount > 0.02) {
                    seamt++;
                    mask[j][i] = 3;
                } else if (col.oceanFeat != null && col.oceanFeat.ridge > 0.05) {
                    ridge++;
                    mask[j][i] = 4;
                }
                maxE = Math.max(maxE, e[j][i]);
                minE = Math.min(minE, e[j][i]);
            }
        }
        System.out.printf(Locale.ROOT,
                "[窗内] 火山 %d 格  熔岩台地 %d  海山 %d  洋中脊 %d   e∈[%.3f, %.3f]（%.0f~%.0f 格）%n",
                vol, field, seamt, ridge, minE, maxE,
                minE * 127 + 63, maxE * 236 + 63);

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                img.setRGB(i, j, colorOf(e[j][i]));
            }
        }
        shade(img, e, n);
        overlayMask(img, mask, n);
        drawLabel(img, String.format(Locale.ROOT,
                "CLOSEUP %.0fwu (%.0f,%.0f)  red=volcano cyan=seamount yellow=ridge",
                2 * radius, cx, cz));

        Path dir = Path.of(OUT_DIR);
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve("feature-closeup.png").normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("output escapes dir: " + file);
        }
        ImageIO.write(img, "png", file.toFile());
        System.out.println("image -> " + file.toAbsolutePath());
    }

    /**
     * 扫描较宽范围，返回特征强度最大的点。
     *
     * @return {@code [x, z, kindCode]}（0=火山 1=海山 2=洋中脊）
     */
    private static double[] findStrongest(MorphTerrainSampler smp) {
        final int m = 120;
        final double span = 12000.0;   // ±6000wu 搜索范围
        double bestScore = -1;
        double[] best = {0, 0, 0};
        for (int j = 0; j < m; j++) {
            double wz = -span / 2 + span * j / (m - 1);
            for (int i = 0; i < m; i++) {
                double wx = -span / 2 + span * i / (m - 1);
                var landS = smp.landField().sample(wx, wz);
                var col = smp.sampleColumn(wx, wz);
                double score = 0;
                int kind = 0;
                // ★ 阶段六修正：火山强度改读类型层（VolcanoRecipe → land.sample）
                if (landS.volcanoGate() > score) {
                    score = landS.volcanoGate();
                    kind = 0;
                }
                if (col.oceanFeat != null && col.oceanFeat.seamount > score) {
                    score = col.oceanFeat.seamount;
                    kind = 1;
                }
                if (col.oceanFeat != null && col.oceanFeat.ridge > score) {
                    score = col.oceanFeat.ridge;
                    kind = 2;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new double[]{wx, wz, kind};
                }
            }
        }
        System.out.printf(Locale.ROOT, "[定位] 最强特征强度 %.3f（kind=%d）%n", bestScore, (int) best[2]);
        return best;
    }

    /** e → RGB（8 级色带，与 WorldOverviewProbe 完全一致以保证跨图可比）。 */
    private static int colorOf(double e) {
        if (e < -0.30) {
            return 0x0E2B4A;
        }
        if (e < -0.12) {
            return 0x1B4E79;
        }
        if (e < -0.02) {
            return 0x4A8FC0;
        }
        if (e < 0.0) {
            return 0x87C4DC;
        }
        if (e < 0.08) {
            return 0x4C8B3F;
        }
        if (e < 0.25) {
            return 0x7FA84A;
        }
        if (e < 0.50) {
            return 0xBCA76A;
        }
        return 0xE8E4DC;
    }

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

    /**
     * 特征掩码叠加：只在<b>特征覆盖区的边缘</b>画彩色轮廓（不填充），
     * 这样既能看出特征位置，又不遮挡地形明暗。
     *
     * <p>颜色：火山=红、火山锥体(强)=亮红、熔岩台地=橙、海山=青、洋中脊=黄。</p>
     */
    private static void overlayMask(BufferedImage img, int[][] mask, int n) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int m = mask[j][i];
                if (m == 0) {
                    continue;
                }
                // 只在边界（四邻有不同值）画线
                boolean edge = (i == 0 || j == 0 || i == n - 1 || j == n - 1);
                if (!edge) {
                    int m0 = mask[j][i - 1], m1 = mask[j][i + 1];
                    int m2 = mask[j - 1][i], m3 = mask[j + 1][i];
                    edge = (m0 != m) || (m1 != m) || (m2 != m) || (m3 != m);
                }
                if (!edge) {
                    continue;
                }
                int col = switch (m) {
                    case 1 -> 0xFF2020;   // 火山锥体（强）
                    case 5 -> 0xA03030;   // 火山边缘（弱）
                    case 2 -> 0xFF9020;   // 熔岩台地
                    case 3 -> 0x20E0E0;   // 海山
                    case 4 -> 0xE0E020;   // 洋中脊
                    default -> 0xFFFFFF;
                };
                img.setRGB(i, j, col);
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

    private FeatureCloseupProbe() { }
}
