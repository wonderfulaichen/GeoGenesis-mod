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
    /** 山麓裙摆：宽低缓的底座（比主锥宽 2.2 倍、高 12%）。 */
    private static final double SKIRT_SCALE = 2.2;
    private static final double SKIRT_AMP = 0.12 * AMP;

    private static final long SEED = 9139912035078620160L;
    private static final int N = 700;
    private static final double CELL = RADIUS * 3.6 / N;   // 窗口覆盖 3.6×半径

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

                // 主锥：**凹形剖面**（stratovolcano 特征——坡顶陡、坡脚缓）。
                //   exp(−d/0.30)：d=0.1 → 0.72（顶部掉得快）、d=0.6 → 0.13（坡脚平缓）。
                //   （旧 CONE 用 exp(−d²/…) 是高斯=缓顶陡底，那是盾状火山，不是层状火山。）
                double cone = d < 2.2
                        ? AMP * Math.exp(-d / 0.30)
                        : 0.0;
                // 裙摆：宽低缓底座（shield 式），让火山从地形里"长出来"而不是"扣上去"
                double ds = d / SKIRT_SCALE;
                double skirt = ds < 1.6
                        ? SKIRT_AMP * Math.exp(-(ds * ds) / (2 * 0.42 * 0.42))
                        : 0.0;
                // ★ 放射状冲沟（barrancos）：层状火山翼部的标志性地貌。
                //   按<b>方位角</b>取噪声，沿半径方向的"沟槽/山脊"条纹；
                //   只在翼部（0.15 < d < 1.2）生效，中心（火口区）与远处不生效。
                double ang = Math.atan2(pz, px);
                double gullyN = fbm2(Math.cos(ang) * 2.6 + 7.3,
                        Math.sin(ang) * 2.6 - 4.1, 0x61726D9L);
                double gully = 0.10 * AMP
                        * Math.max(0.0, Math.min(1.0, (d - 0.15) / 0.25))
                        * (1.0 - Math.max(0.0, Math.min(1.0, (d - 1.0) / 0.3)))
                        * gullyN;
                // 火口：中心下凹（VolcanicShape.crater 同式）
                double crater = VolcanicShape.crater(d, CRATER_FRAC, CRATER_DEPTH);

                h[j][i] = Math.max(0.0, cone + skirt + gully - crater);
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
                double t = h[j][i] / maxH;
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

        // ---- 剖面折线（东西向过中心，可看出火口下凹）----
        int profN = n;
        BufferedImage prof = new BufferedImage(profN, 240, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = prof.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, profN, 240);
        g.setColor(java.awt.Color.LIGHT_GRAY);
        g.drawLine(0, 200, profN, 200);
        g.setColor(java.awt.Color.BLACK);
        double peakH = AMP;
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
                + "  域扭曲 %.0fwu  裙摆 %.1f×半径/%.2fe%n"
                + "  输出 build/volcano-form/{volcano-form,volcano-profile}.png%n"
                + "  ⇒ 请目检：像不像一座火山？火口是否可见？是否\"从地形里长出来\"？%n",
                RADIUS, RADIUS * 2, AMP, AMP * 236, CRATER_DEPTH, CRATER_DEPTH * 236,
                WARP_AMP, SKIRT_SCALE, SKIRT_AMP);
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

    private static void shade(BufferedImage img, double[][] h, int n) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                int j0 = Math.max(0, j - 2), j1 = Math.min(n - 1, j + 2);
                double gx = (h[j][i1] - h[j][i0]) / Math.max(1, i1 - i0);
                double gy = (h[j1][i] - h[j0][i]) / Math.max(1, j1 - j0);
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
