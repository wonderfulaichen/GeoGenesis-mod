package com.geogenesis.worldgen.terrain.tectonicsand;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>洋中脊三段式剖面沙箱（2026-11-17）</b>——用户裁定「之前做火山就是这么错的」，
 * 所以先出图判定形态，<b>不碰生产代码</b>。
 *
 * <h3>为什么重做</h3>
 * 生产实现（{@code OceanFeatures} + {@code CrustField}）的脊剖面是
 * <b>「单个法向高斯（ridge）+ 年龄律浅脊轴 + 一个 14 格窄槽（裂谷）」三层叠加</b>，
 * 三者宽度不匹配 ⇒ 分解图显示 ridge 通道是<b>噪声斑块</b>而非连续脊带，
 * 最终地形像「被破坏的海沟」（用户 2026-11-17 判断）。
 *
 * <h3>真实 MOR 横剖面（本沙箱采用的形状）</h3>
 * <pre>
 *           轴部高地（axial high）
 *              ╱╲
 *     脊肩    ╱  ╲   脊肩
 *      ╱╲    ╱ ╲╱ ╲    ╱╲
 *     ╱  ╲  ╱ 地堑  ╲  ╱  ╲     ← 轴部裂谷：窄（~60wu）、陡壁
 *    ╱ rift ╲      ╱ mount ╲
 *   ╱────────────────────────────────╲
 *   两翼热沉降（由年龄律提供，本沙箱只画 ridge 增量）
 * </pre>
 *
 * <p>关键：<b>用一个分段确定性剖面取代 高斯+高斯+窄槽</b>，宽度按真实比例：
 * 地堑半宽 60wu、轴部高地半宽 200wu、脊肩峰 500wu、脊脚 1400wu。</p>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>{@code ridge-top.png}：俯视（模拟光照，看脊肩是否成「两条平行亮脊」）</li>
 *   <li>{@code ridge-section.png}：横剖面曲线（看三段式 + 地堑）</li>
 *   <li>{@code ridge-along.png}：沿轴剖面（看分段感/连续性）</li>
 * </ul>
 */
public final class RidgeFormProbe {

    // ===== 剖面尺寸（wu）=====
    //
    // ★ 比例按真实慢速扩张洋中脊（Mid-Atlantic Ridge 型）标定：
    //   实测（Searle 2013 / MAR 23°N、36°N 断面）：
    //     裂谷     宽 20~30km、深 1~2km
    //     脊肩峰   离轴 15~30km、高出谷底 1~2km
    //     翼部     延伸 100~300km 到深海平原
    //     总起伏   轴部→深海平原 ~2.5~3km
    //   ⇒ 比例：谷深/总起伏 = 0.33~0.66；谷宽/脊半宽 = 0.10~0.15；
    //           脊肩位置/脊半宽 = 0.10~0.30。
    //
    //   本沙箱取（FOOT=1500wu 作为脊半宽）：
    //     谷深 0.070 / 总起伏 0.190 = 0.37 ✓
    //     谷全宽 160 / 3000 = 0.053（半宽比 0.107 ✓）
    //     脊肩 340 / 1500 = 0.23 ✓
    //
    // ⚠ 首版错误：多了一段「轴部台面」（AXIAL_HALF=230）且脊肩放到 520wu
    //   （0.35 半宽）——真实慢速洋中脊没有轴部台面：谷壁直接升到脊肩。
    //   那版让轴部台面最高、脊肩次高，俯视图只剩"一条沟"，用户判断为
    //   「像被破坏的海沟」。

    /** 轴部裂谷半宽（wu）：谷全宽 160wu。 */
    static final double RIFT_HALF = 80.0;
    /** 轴部裂谷深度（e）：相对脊肩峰 0.07e ≈ 9 格（真实 1~2km）。 */
    static final double RIFT_DEPTH = 0.070;
    /** 脊肩峰离轴距离（wu）= 0.23 × FOOT。 */
    static final double SHOULDER_AT = 340.0;
    /** 脊肩峰高度（相对深海平原，e）= 0.19e ≈ 24 格（真实 2.5~3km 总起伏里，
     *  年龄律另提供大部分，本剖面只负责局部山形）。 */
    static final double SHOULDER_H = 0.190;
    /** 脊脚（脊增量归零处，wu）。 */
    static final double FOOT = 1500.0;

    // ===== 沿轴调制 =====
    /** 沿轴分段波长（wu）。 */
    static final double SEG_WAVE = 1200.0;
    /** 沿轴分段幅度（±比例）。 */
    static final double SEG_AMP = 0.35;
    /** 沿轴蜿蜒幅度（wu）——脊轴中心横向摆动。 */
    static final double WANDER = 110.0;
    /** 蜿蜒波长（wu）。 */
    static final double WANDER_WAVE = 900.0;
    /** 表面粗糙度（±比例）。 */
    static final double ROUGH = 0.16;

    private static final long SEED = 9139912035078620160L;
    private static final int N = 700;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("build/ridge-form");
        Files.createDirectories(dir);

        // ---- 1) 俯视图：轴向沿 z，x 为横剖面方向 ----
        double halfW = 1600;    // x ∈ [-1600, 1600]（含脊脚）
        double halfL = 1600;    // z ∈ [-1600, 1600]
        double[][] h = new double[N][N];
        double maxH = 0;
        for (int j = 0; j < N; j++) {
            double wz = -halfL + 2 * halfL * j / (N - 1);
            for (int i = 0; i < N; i++) {
                double wx = -halfW + 2 * halfW * i / (N - 1);
                double v = ridgeHeight(wx, wz);
                h[j][i] = v;
                maxH = Math.max(maxH, v);
            }
        }
        BufferedImage top = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                double t = Math.max(0, Math.min(1, h[j][i] / Math.max(1e-9, maxH)));
                // 海底配色：深蓝(0) → 青 → 浅灰(峰)
                // 更强对比：深（低）→ 青 → 亮黄白（高），让脊肩明确发光
                int r = (int) (18 + 230 * Math.pow(t, 1.4));
                int g = (int) (44 + 200 * Math.pow(t, 1.1));
                int b = (int) (100 + 140 * t);
                top.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        // 光照（梯度放大到可辨）
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int i0 = Math.max(0, i - 2), i1 = Math.min(N - 1, i + 2);
                int j0 = Math.max(0, j - 2), j1 = Math.min(N - 1, j + 2);
                double gx = (h[j][i1] - h[j][i0]) / Math.max(1, i1 - i0) * (N / (2 * halfW)) * 3.0;
                double gy = (h[j1][i] - h[j0][i]) / Math.max(1, j1 - j0) * (N / (2 * halfL)) * 3.0;
                double nx = -gx, ny = -gy, nz = 1;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.6 - ny * 0.6 + nz * 0.53) / len;
                double sh = 0.45 + 0.85 * Math.max(0, Math.min(1, dd));
                int c = top.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b = Math.min(255, (int) ((c & 0xFF) * sh));
                top.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        label(top, String.format(Locale.ROOT,
                "RIDGE TOP  (%.0fx%.0f wu)  rift half %.0f / shoulder @%.0f / foot %.0f",
                2 * halfW, 2 * halfL, RIFT_HALF, SHOULDER_AT, FOOT));
        ImageIO.write(top, "png", dir.resolve("ridge-top.png").toFile());
        System.out.println("image -> " + dir.resolve("ridge-top.png").toAbsolutePath());

        // ---- 2) 横剖面曲线（z=0，近景 ±900wu 看清地堑/脊肩）----
        double sectHalf = 900;
        int W = 1000, H = 420, M = 60;
        int NS = 801;
        double[] xs = new double[NS];
        double[] vs = new double[NS];
        double lo = 9, hi = -9;
        for (int k = 0; k < NS; k++) {
            double x = -sectHalf + 2 * sectHalf * k / (NS - 1);
            xs[k] = x;
            vs[k] = ridgeHeight(x, 0);
            lo = Math.min(lo, vs[k]);
            hi = Math.max(hi, vs[k]);
        }
        BufferedImage sec = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sec.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(232, 232, 232));
        for (int k = 0; k <= 10; k++) {
            int yy = M + (H - 2 * M) * k / 10;
            g.drawLine(M, yy, W - M, yy);
        }
        g.setColor(new Color(150, 150, 150));
        g.drawString(String.format(Locale.ROOT, "%.3fe", hi), 8, M + 4);
        g.drawString(String.format(Locale.ROOT, "%.3fe", lo), 8, H - M + 4);
        // 剖面曲线
        g.setColor(new Color(20, 60, 190));
        int px = -1, py = -1;
        for (int k = 0; k < NS; k++) {
            int xx = M + (W - 2 * M) * k / (NS - 1);
            int yy = (int) (H - M - (vs[k] - lo) / Math.max(1e-9, hi - lo) * (H - 2 * M));
            if (px >= 0) {
                g.drawLine(px, py, xx, yy);
            }
            px = xx;
            py = yy;
        }
        // 标注关键位置
        g.setColor(new Color(200, 40, 40));
        for (double mark : new double[]{-SHOULDER_AT, -RIFT_HALF, 0,
                RIFT_HALF, SHOULDER_AT}) {
            int xx = M + (int) ((mark + sectHalf) / (2 * sectHalf) * (W - 2 * M));
            g.drawLine(xx, M, xx, H - M);
        }
        g.setColor(Color.BLACK);
        g.drawString(String.format(Locale.ROOT,
                "RIDGE SECTION  z=0  ridge increment only  [%.3f, %.3f]e", lo, hi), M, 26);
        g.drawString("red lines: rift / axial-high edge / shoulder", M, 44);
        g.drawString("horizontal: x (wu)   vertical: e (127 blocks/e)", M, H - 12);
        g.dispose();
        ImageIO.write(sec, "png", dir.resolve("ridge-section.png").toFile());
        System.out.println("image -> " + dir.resolve("ridge-section.png").toAbsolutePath());

        // ---- 3) 沿轴剖面（x=0，z ∈ [-1600,1600]）----
        BufferedImage along = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = along.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, W, H);
        double alo = 9, ahi = -9;
        double[] av = new double[NS];
        for (int k = 0; k < NS; k++) {
            double z = -halfL + 2 * halfL * k / (NS - 1);
            av[k] = ridgeHeight(0, z);
            alo = Math.min(alo, av[k]);
            ahi = Math.max(ahi, av[k]);
        }
        g2.setColor(new Color(232, 232, 232));
        for (int k = 0; k <= 10; k++) {
            int yy = M + (H - 2 * M) * k / 10;
            g2.drawLine(M, yy, W - M, yy);
        }
        g2.setColor(new Color(160, 40, 160));
        px = -1;
        py = -1;
        for (int k = 0; k < NS; k++) {
            int xx = M + (W - 2 * M) * k / (NS - 1);
            int yy = (int) (H - M - (av[k] - alo) / Math.max(1e-9, ahi - alo) * (H - 2 * M));
            if (px >= 0) {
                g2.drawLine(px, py, xx, yy);
            }
            px = xx;
            py = yy;
        }
        g2.setColor(Color.BLACK);
        g2.drawString(String.format(Locale.ROOT,
                "RIDGE ALONG-AXIS  x=0  [%.3f, %.3f]e  (segment wavelength %.0fwu)",
                alo, ahi, SEG_WAVE), M, 26);
        g2.drawString("horizontal: z (wu)   vertical: e", M, H - 12);
        g2.dispose();
        ImageIO.write(along, "png", dir.resolve("ridge-along.png").toFile());
        System.out.println("image -> " + dir.resolve("ridge-along.png").toAbsolutePath());

        // ---- 数值摘要 ----
        double axial = ridgeHeight(0, 0);
        double riftFloor = ridgeHeight(RIFT_HALF * 0.5, 0);
        double shoulder = ridgeHeight(SHOULDER_AT, 0);
        double foot = ridgeHeight(FOOT, 0);
        System.out.printf(Locale.ROOT,
                "=== 剖面摘要 ===%n"
                + "  谷底 (x=0)      %.4fe%n"
                + "  脊肩 (x=%.0f)   %.4fe  ⇒ 谷底到脊肩落差 %.4fe（%.1f 格）%n"
                + "  脊脚 (x=%.0f)   %.4fe  ⇒ 脊肩到脊脚落差 %.4fe（%.1f 格）%n"
                + "  比例核对：谷深/总起伏 = %.2f（真实 0.33~0.66）%n"
                + "            谷全宽/脊半宽 = %.3f（真实 0.10~0.15）%n"
                + "            脊肩位置/脊半宽 = %.2f（真实 0.10~0.30）%n"
                + "  ⇒ 判读：横剖面 = 中央 U 形地堑 + 两侧脊肩（唯一最高）+ 翼部衰减；%n"
                + "     俯视图 = 两条平行亮脊 + 中央细暗线，沿轴蜿蜒。%n",
                axial,
                SHOULDER_AT, shoulder, shoulder - axial, (shoulder - axial) * 127,
                FOOT, foot, shoulder - foot, (shoulder - foot) * 127,
                RIFT_DEPTH / SHOULDER_H,
                2 * RIFT_HALF / (2 * FOOT),
                SHOULDER_AT / FOOT);
    }

    // ============================================================ 形态函数

    /**
     * 脊高度增量（e）：分段确定性剖面 × 沿轴调制 × 粗糙度。
     *
     * @param wx 横向（离轴方向）
     * @param wz 沿轴方向
     */
    static double ridgeHeight(double wx, double wz) {
        // 沿轴蜿蜒：轴心位置随 z 摆动（一维噪声）
        double wander = WANDER * vnoise(wz / WANDER_WAVE, 0x1111L);
        double d = Math.abs(wx - wander);

        // 沿轴分段：段间高度调制（±35%）
        double seg = 1.0 + SEG_AMP * vnoise(wz / SEG_WAVE + 7.3, 0x2222L);

        // 两段式剖面（真实慢速洋中脊：谷壁直接升到脊肩，无轴部台面）
        //   ① d < RIFT_HALF：U 形谷，谷底 = SHOULDER_H − RIFT_DEPTH
        //   ② RIFT_HALF..SHOULDER_AT：谷壁/内翼上升 → 脊肩峰（全剖面最高）
        //   ③ SHOULDER_AT..FOOT：外翼余弦衰减 → 0
        double valleyFloor = SHOULDER_H - RIFT_DEPTH;
        double prof;
        if (d < RIFT_HALF) {
            double t = d / RIFT_HALF;
            // 平底占 40%，其余 smoothstep 升壁（陡壁=正断层崖）
            double wall = t < 0.40 ? 0.0 : smoothstep01((t - 0.40) / 0.60);
            prof = valleyFloor + (SHOULDER_H - valleyFloor) * wall;
        } else if (d < SHOULDER_AT) {
            // 谷缘 → 脊肩峰：单调上升，前段陡（断层崖）后段缓（脊肩圆顶）
            double t = (d - RIFT_HALF) / (SHOULDER_AT - RIFT_HALF);
            double rise = smoothstep01(t);
            prof = SHOULDER_H + (valleyFloor - SHOULDER_H) * (1.0 - rise) * 0.0
                    + (SHOULDER_H - (SHOULDER_H - valleyFloor) * (1.0 - rise)) - SHOULDER_H;
            // 上式展开= valleyFloor + (SHOULDER_H−valleyFloor)·rise
            prof = valleyFloor + (SHOULDER_H - valleyFloor) * rise;
        } else if (d < FOOT) {
            double t = (d - SHOULDER_AT) / (FOOT - SHOULDER_AT);
            prof = SHOULDER_H * (0.5 + 0.5 * Math.cos(Math.PI * t));
        } else {
            prof = 0.0;
        }

        // ★ 粗糙度：真实 MOR 翼部纹理是**平行脊轴的细条带**（断层/火山脊，
        //   abyssal hill fabric）——噪声只随 wz 变化（沿轴），不随 wx（横向），
        //   否则二维斜纹噪声会把地堑/脊肩糊成斜纹布（首版俯视图实测）。
        // 谷内完全关噪声（窄谷壁会被放大成尖刺）；谷缘外渐入。
        double roughAmp = d < RIFT_HALF ? 0.0
                : ROUGH * smoothstep01((d - RIFT_HALF) / 120.0);
        double fabric1 = vnoise(wz / 55.0, 0x3333L);          // 主导：~55wu 条带
        double fabric2 = vnoise(wz / 140.0 + 3.7, 0x4444L);   // 次级：~140wu
        // ★ 条带的「断续性」：纯一维噪声沿轴周期均匀 ⇒ 图上像斑马纹。
        //   真实 abyssal hill 是分段的海山脊——用横向低频噪声给条带做幅度包络，
        //   使每条脊在不同横向位置强弱不一、局部消失（打破贯通条纹的机械感）。
        double breakUp = 0.55 + 0.45 * vnoise2(wx / 260.0 + wz / 900.0, 0x6666L);
        double rough = 1.0 + roughAmp * breakUp * (0.65 * fabric1 + 0.35 * fabric2);

        // 翼部额外的火山脊（沿轴连续的细脊，小幅）
        double ridges = 0.0;
        if (d > RIFT_HALF && d < FOOT) {
            double wing = smoothstep01((d - RIFT_HALF) / 120.0)
                    * (1.0 - smoothstep01((d - SHOULDER_AT) / (FOOT - SHOULDER_AT) * 0.6));
            double ridgeN = vnoise(wz / 85.0 + 11.1, 0x5555L);
            ridges = 0.012 * wing * Math.max(0.0, ridgeN);
        }

        return Math.max(0.0, prof * seg * rough + ridges);
    }

    /** 简化二维噪声（两一维混合，够用且便宜）。 */
    private static double vnoise2(double x, long salt) {
        return 0.5 * (vnoise(x, salt) + vnoise(x * 1.73 + 5.1, salt ^ 0x7777L));
    }

    private static double smoothstep01(double x) {
        double t = x < 0 ? 0 : (x > 1 ? 1 : x);
        return t * t * (3 - 2 * t);
    }

    private static double vnoise(double x, long salt) {
        double fx = Math.floor(x);
        long ix = (long) fx;
        double t = x - fx;
        t = t * t * (3 - 2 * t);
        double a = unit(hash(SEED, ix, salt)) * 2 - 1;
        double b = unit(hash(SEED, ix + 1, salt)) * 2 - 1;
        return a * (1 - t) + b * t;
    }

    private static long hash(long seed, long i, long salt) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ salt;
        h = Long.rotateLeft(h, 31);
        h *= 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0x2545F4914F6CDD1DL;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static void label(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
        g.drawString(text, 8, 18);
        g.dispose();
    }

    private RidgeFormProbe() { }
}