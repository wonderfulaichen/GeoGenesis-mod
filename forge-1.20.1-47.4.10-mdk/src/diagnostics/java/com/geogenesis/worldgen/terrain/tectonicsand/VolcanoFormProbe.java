package com.geogenesis.worldgen.terrain.tectonicsand;

import com.geogenesis.worldgen.terrain.VolcanicShape;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.io.File;
import java.util.Locale;

/**
 * <b>火山形态沙箱</b>——独立评估火山形状，不接生产（用户 2026-11-17 裁定：
 * 「先把火山地形类型形态做好了，没做好不建议接入类型库」）。
 *
 * <h3>渲染什么</h3>
 * <p>用 {@link VolcanicShape}（CONE/GUYOT/CALDERA + 火口 + 各向异性拉伸 + 域扭曲）
 * 渲染一座火山的<b>形态图</b>与<b>剖面线</b>：</p>
 * <ul>
 *   <li>{@code volcano-form.png}：单座火山的山体明暗图（含火口、山麓裙摆）</li>
 *   <li>{@code volcano-profile.png}：过中心的东西剖面折线（可看出火口下凹）</li>
 * </ul>
 *
 * <h3>参数（独立可调，做好后才定接入值）</h3>
 * <ul>
 *   <li>半径 {@code radius}（wu）</li>
 *   <li>高度 {@code amp}（e）</li>
 *   <li>火口深度/半径比例</li>
 *   <li>域扭曲幅度（让轮廓不规则，打破完美圆）</li>
 * </ul>
 *
 * <h3>判定方式</h3>
 * <p><b>由用户目检</b>——「像不像一座火山」。探针只负责把形状画清楚。</p>
 */
public final class VolcanoFormProbe {

    private static final String OUT_DIR = "build/volcano-form";

    // ---- 形态参数（沙箱调参用；做好后才冻结接入值）----
    /** 火山半径（wu）。 */
    private static final double RADIUS = 120.0;
    /** 山体高度（e）。 */
    private static final double AMP = 0.24;
    /** 火口深度（e，占 amp 的比例由调用定）。 */
    private static final double CRATER_DEPTH = 0.060;
    /** 火口半径占火山半径的比例。 */
    private static final double CRATER_FRAC = 0.30;
    /** 域扭曲幅度（wu）——打破完美圆。 */
    private static final double WARP_AMP = 30.0;
    /**
     * ★ 堆积基底（apron）——2026-11-17 用户反馈「像从地面挤出来，真实火山是堆积起来的」。
     *
     * <p>真实层状火山：底部直径 ≈ 锥高 × 10~20，山麓是 1~3° 的火山碎屑堆积扇，
     * 30° 的主锥坐落在它上面。旧高斯裙摆只有 0.12·amp（7 格）高 ⇒ 锥体像
     * 「薄饼上的痘痘」。新的堆积基底：半径 3.4R（816 块）、高 0.24·amp（13.6 格），
     * 肩线 {@code (1−d/R)^1.5}——近锥脚稍陡、远脚趋平（碎屑扇自然收尾）。</p>
     */
    private static final double APRON_R = 3.4;
    private static final double APRON_AMP = 0.24 * AMP;
    /** 基底轮廓不规则度（半径按方位角噪声 ±18%——不是圆规画的圆）。 */
    private static final double APRON_ROUGH = 0.18;

    private static final long SEED = 9139912035078620160L;
    private static final int N = 700;
    // 窗口覆盖 8×半径（±4R=480wu）：堆积基底半径 3.4R 要完整入画
    private static final double CELL = RADIUS * 8.0 / N;

    public static void main(String[] args) throws Exception {
        int n = N;
        double[][] h = new double[n][n];
        double center = n / 2.0;

        // 域扭曲偏移表（每点一次，供山体/裙摆/火口共用——同一扭曲保证一致性）
        double[][] warpX = new double[n][n];
        double[][] warpZ = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double wx = (i - center) * CELL;
                double wz = (j - center) * CELL;
                warpX[j][i] = WARP_AMP * fbm2(wx / 220.0, wz / 220.0, 0x1A2B3C);
                warpZ[j][i] = WARP_AMP * fbm2(wx / 220.0, wz / 220.0, 0x3D4E5F);
            }
        }

        // ---- 合成：裙摆 + 主锥 + 冲沟 + 火口（都作用于扭曲后的局部坐标）----
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double wx = (i - center) * CELL;
                double wz = (j - center) * CELL;
                double px = wx + warpX[j][i];
                double pz = wz + warpZ[j][i];
                double d = Math.sqrt(px * px + pz * pz) / RADIUS;
                double ang = Math.atan2(pz, px);

                // 主锥：**凹形剖面**（stratovolcano 特征——坡顶陡、坡脚缓）。
                //   exp(−d/0.30)：d=0.1 → 0.72（顶部掉得快）、d=0.6 → 0.13（坡脚平缓）。
                //   （旧 CONE 用 exp(−d²/…) 是高斯=缓顶陡底，那是盾状火山，不是层状火山。）
                double cone = d < 2.2
                        ? AMP * Math.exp(-d / 0.30)
                        : 0.0;
                // ★ 堆积基底（apron）：宽缓碎屑扇托住主锥（见 APRON_R 注释）。
                //   半径按方位角噪声起伏 ±18%：基底轮廓不规则（不是圆规画的圆）。
                double apronR = APRON_R * (1.0 + APRON_ROUGH
                        * fbm2(Math.cos(ang) * 1.7 + 3.1, Math.sin(ang) * 1.7 - 2.4, 0x5A4B3C2L));
                double apron = d < apronR
                        ? APRON_AMP * Math.pow(1.0 - d / apronR, 1.5)
                        : 0.0;
                // ★ 放射状冲沟（barrancos）：层状火山翼部的标志性地貌。
                //   按<b>方位角</b>取噪声，沿半径方向的"沟槽/山脊"条纹；
                //   从翼部一直延续到堆积扇（0.15 < d < 2.6，远端渐隐）——
                //   真实冲沟在坡脚延续为旱谷，是「堆积感」的一半。
                double gullyN = fbm2(Math.cos(ang) * 2.6 + 7.3,
                        Math.sin(ang) * 2.6 - 4.1, 0x61726D9L);
                double gully = 0.13 * AMP
                        * Math.max(0.0, Math.min(1.0, (d - 0.15) / 0.25))
                        * (1.0 - Math.max(0.0, Math.min(1.0, (d - 1.2) / 1.4)))
                        * gullyN;
                // 火口：中心下凹（VolcanicShape.crater 同式）
                double crater = VolcanicShape.crater(d, CRATER_FRAC, CRATER_DEPTH);

                h[j][i] = Math.max(0.0, cone + apron + gully - crater);
            }
        }

        // ---- 渲染形态图（灰度 = 高度；火山体独立于海/陆配色）----
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double maxH = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                maxH = Math.max(maxH, h[j][i]);
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // sqrt 拉伸：火山 90% 面积是低缓基底（h/max < 0.25），
                // 线性映射会挤在色带最暗端——提亮低处才看得见堆积扇纹理。
                double t = Math.pow(h[j][i] / maxH, 0.45);
                // 火山配色：低=深绿（草地）→ 中=岩石棕 → 高=浅灰（火山灰/雪）
                int r = (int) (40 + 175 * t);
                int g = (int) (90 + 110 * t);
                int b = (int) (50 + 130 * t);
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        shade(img, h, n);
        drawLabel(img, String.format(Locale.ROOT,
                "VOLCANO FORM  R=%.0fwu amp=%.2fe crater=%.2fe warp=%.0fwu  (%.0fx%.0f wu)",
                RADIUS, AMP, CRATER_DEPTH, WARP_AMP, n * CELL, n * CELL));
        write(img, "volcano-form.png");

        // ---- 近景（2.6R=312wu 半径）：锥体/火口/冲沟细节 ----
        int cn = n;
        double cellC = RADIUS * 2.6 / cn;
        BufferedImage close = new BufferedImage(cn, cn, BufferedImage.TYPE_INT_RGB);
        double[][] hc = new double[cn][cn];
        for (int j = 0; j < cn; j++) {
            for (int i = 0; i < cn; i++) {
                double wx = (i - cn / 2.0) * cellC;
                double wz = (j - cn / 2.0) * cellC;
                hc[j][i] = sampleAt(wx, wz);
            }
        }
        for (int j = 0; j < cn; j++) {
            for (int i = 0; i < cn; i++) {
                double t = Math.pow(hc[j][i] / maxH, 0.45);
                int r = (int) (40 + 175 * t);
                int g = (int) (90 + 110 * t);
                int b = (int) (50 + 130 * t);
                close.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        shade2(close, hc, cn, cellC);
        drawLabel(close, String.format(Locale.ROOT,
                "VOLCANO CLOSEUP 2.6R (%.0fwu)  R=%.0f amp=%.2fe apron=%.1fR",
                2.6 * RADIUS * 2, RADIUS, AMP, APRON_R));
        write(close, "volcano-closeup.png");

        // ---- 剖面折线（东西向过中心，可看出火口下凹）----
        int profN = n;
        BufferedImage prof = new BufferedImage(profN, 240, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = prof.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, profN, 240);
        g.setColor(java.awt.Color.LIGHT_GRAY);
        g.drawLine(0, 200, profN, 200);
        g.setColor(java.awt.Color.BLACK);
        double peakH = AMP + APRON_AMP;
        int prevY = 0;
        for (int i = 0; i < profN; i++) {
            double v = h[n / 2][i] / Math.max(1e-9, peakH + CRATER_DEPTH);
            int y = 210 - (int) (v * 180);
            if (i > 0) {
                g.drawLine(i - 1, prevY, i, y);
            }
            prevY = y;
        }
        g.setColor(java.awt.Color.RED);
        g.drawString("profile E-W (through crater)", 10, 20);
        g.dispose();
        write(prof, "volcano-profile.png");

        System.out.printf(Locale.ROOT,
                "=== 火山形态沙箱 ===%n"
                + "  半径 %.0fwu（%.0f 块）  高度 %.2fe（%.0f 格）  火口深 %.2fe（%.0f 格）%n"
                + "  域扭曲 %.0fwu  堆积基底 %.1fR/%.3fe（%.0f 块宽/%.1f 格高）%n"
                + "  输出 build/volcano-form/{volcano-form,volcano-profile}.png%n"
                + "  ⇒ 请目检：像不像一座火山？火口是否可见？是否\"从地形里长出来\"？%n",
                RADIUS, RADIUS * 2, AMP, AMP * 236, CRATER_DEPTH, CRATER_DEPTH * 236,
                WARP_AMP, APRON_R, APRON_AMP, APRON_R * RADIUS * 2, APRON_AMP * 236);
    }

    /** 两倍频值噪声（沙箱自用，确定性）。 */
    private static double fbm2(double x, double z, long salt) {
        double n1 = vnoise(x, z, salt);
        double n2 = vnoise(x * 2.3 + 11.7, z * 2.3 + 5.9, salt ^ 0x7919L);
        return (n1 + 0.5 * n2) / 1.5;
    }

    private static double vnoise(double x, double z, long salt) {
        long seed = SEED;
        double fx = Math.floor(x), fz = Math.floor(z);
        long ix = (long) fx, iz = (long) fz;
        double tx = x - fx, tz = z - fz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double n00 = unit(hash(seed, ix, iz, salt)) * 2 - 1;
        double n10 = unit(hash(seed, ix + 1, iz, salt)) * 2 - 1;
        double n01 = unit(hash(seed, ix, iz + 1, salt)) * 2 - 1;
        double n11 = unit(hash(seed, ix + 1, iz + 1, salt)) * 2 - 1;
        return (n00 * (1 - tx) + n10 * tx) * (1 - tz) + (n01 * (1 - tx) + n11 * tx) * tz;
    }

    private static long hash(long seed, long x, long z, long salt) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31);
        h *= 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 27);
        h *= 0x165667B19E3779F9L;
        h ^= salt;
        h ^= h >>> 29;
        h *= 0x2545F4914F6CDD1DL;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 单点形态（与主合成循环同式），供近景图复用。 */
    private static double sampleAt(double wx, double wz) {
        double px = wx + WARP_AMP * fbm2(wx / 220.0, wz / 220.0, 0x1A2B3C);
        double pz = wz + WARP_AMP * fbm2(wx / 220.0, wz / 220.0, 0x3D4E5F);
        double d = Math.sqrt(px * px + pz * pz) / RADIUS;
        double ang = Math.atan2(pz, px);
        double cone = d < 2.2 ? AMP * Math.exp(-d / 0.30) : 0.0;
        double apronR = APRON_R * (1.0 + APRON_ROUGH
                * fbm2(Math.cos(ang) * 1.7 + 3.1, Math.sin(ang) * 1.7 - 2.4, 0x5A4B3C2L));
        double apron = d < apronR ? APRON_AMP * Math.pow(1.0 - d / apronR, 1.5) : 0.0;
        double gullyN = fbm2(Math.cos(ang) * 2.6 + 7.3,
                Math.sin(ang) * 2.6 - 4.1, 0x61726D9L);
        double gully = 0.13 * AMP
                * Math.max(0.0, Math.min(1.0, (d - 0.15) / 0.25))
                * (1.0 - Math.max(0.0, Math.min(1.0, (d - 1.2) / 1.4)))
                * gullyN;
        double crater = VolcanicShape.crater(d, CRATER_FRAC, CRATER_DEPTH);
        return Math.max(0.0, cone + apron + gully - crater);
    }

    /** shade 的 cell 参数版（近景用）。 */
    private static void shade2(BufferedImage img, double[][] h, int n, double cell) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                int j0 = Math.max(0, j - 2), j1 = Math.min(n - 1, j + 2);
                double gx = (h[j][i1] - h[j][i0]) * 236.0 / Math.max(1, i1 - i0) / cell;
                double gy = (h[j1][i] - h[j0][i]) * 236.0 / Math.max(1, j1 - j0) / cell;
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

    private static void shade(BufferedImage img, double[][] h, int n) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                int j0 = Math.max(0, j - 2), j1 = Math.min(n - 1, j + 2);
                // ★ 梯度换算成真实坡度（格/块）：e→格 ×236、像素→wu ×CELL。
                //   漏这一步 ⇒ 所有法线≈垂直 ⇒ 光照处处相同 ⇒ "图上什么都看不出"
                //   （2026-11-17 实测：旧图整片均匀深绿，只有基底轮廓可见）。
                double gx = (h[j][i1] - h[j][i0]) * 236.0
                        / Math.max(1, i1 - i0) / CELL;
                double gy = (h[j1][i] - h[j0][i]) * 236.0
                        / Math.max(1, j1 - j0) / CELL;
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

    private static void write(BufferedImage img, String name) throws Exception {
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
        g.drawString(label, 10, 22);
        g.dispose();
    }

    private VolcanoFormProbe() { }
}
