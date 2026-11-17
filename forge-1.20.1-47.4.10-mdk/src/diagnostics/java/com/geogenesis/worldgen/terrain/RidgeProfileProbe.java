package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import java.util.Locale;

/**
 * <b>洋中脊剖面诊断（2026-11-17）</b>——回答「这位置属于地质学正常情况吗」。
 *
 * <p>沿一条东西向剖面采样，打印每个断面的控制量，用于判定「深→浅边界」的归属：</p>
 * <ul>
 *   <li>{@code continent}（大陆性 c）：MorphOceanAssembler 的 edge=(c+1)/2 决定
 *       深海/浅海/岸段三段剖面——若深浅边界与它对齐 ⇒ 是「大陆距离坐标」的产物。</li>
 *   <li>{@code baseE}（CrustField 裸 e）：年龄律热沉降——若边界与它对齐 ⇒ 是
 *       「离脊距离」的正常地质过渡。</li>
 *   <li>{@code ridge}/{@code seamount}：特征分量（门控是否在翼上被截断）。</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runRidgeProfileProbe}（默认 (1530,1230) 东西向 ±500wu）。</p>
 */
public final class RidgeProfileProbe {

    private static final long SEED = 9139912035078620160L;
    private static final double CX = 1530;
    private static final double CZ = 1230;
    private static final double HALF = 500;
    private static final int N = 21;

    public static void main(String[] args) throws Exception {
        double cx = args.length > 0 ? Double.parseDouble(args[0]) : CX;
        double cz = args.length > 1 ? Double.parseDouble(args[1]) : CZ;
        // （args.length==0 时下面会覆盖为自动选点）
        double half = args.length > 2 ? Double.parseDouble(args[2]) : HALF;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        // ★ 自动选点（2026-11-17）：找一段「两侧都是深海」的洋中脊——
        //   固定坐标 (1750,900) 恰好落在「深海盆→大陆架」的斜坡上，
        //   剖面是单调上升的斜坡 + 裂谷尖峰，脊梁被大陆坡淹没
        //   ⇒ 用它判读会误判「没有山脊」。自动选点要求：
        //   中心 ridge>0.05、左右 600wu 都 < −0.20（深海）、且中心比两侧 ≥0.04e。
        if (args.length == 0) {
            double bestScore = -1, bx = 0, bz = 0;
            for (double z = -8000; z <= 8000; z += 200) {
                for (double x = -8000; x <= 8000; x += 200) {
                    var c = smp.sampleColumn(x, z);
                    if (c.oceanFeat == null || c.oceanFeat.ridge < 0.05) {
                        continue;
                    }
                    double l = smp.sampleColumn(x - 600, z).e;
                    double r = smp.sampleColumn(x + 600, z).e;
                    if (l < -0.15 && r < -0.15 && c.e > l + 0.03 && c.e > r + 0.03) {
                        double score = c.e - Math.max(l, r) + c.oceanFeat.ridge;
                        if (score > bestScore) {
                            bestScore = score;
                            bx = x;
                            bz = z;
                        }
                    }
                }
            }
            cx = bx;
            cz = bz;
            System.out.printf(Locale.ROOT,
                    "[自动选点] 两侧深海的真脊段 (%.0f,%.0f) score=%.3f（无候选则为 0,0）%n",
                    cx, cz, bestScore);
        }

        System.out.printf(Locale.ROOT,
                "=== 东西剖面 z=%.0f  x∈[%.0f,%.0f] ===%n", cz, cx - half, cx + half);
        System.out.println("     x      continent   edge   baseE   seamnt   ridge      e   height(格)  type");
        for (int i = 0; i < N; i++) {
            double x = cx - half + 2 * half * i / (N - 1);
            var c = smp.sampleColumn(x, cz);
            double edge = (c.continent + 1.0) * 0.5;
            double baseE = c.oceanFeat == null ? Double.NaN : c.oceanFeat.baseE;
            double seamount = c.oceanFeat == null ? 0 : c.oceanFeat.seamount;
            double ridge = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
            System.out.printf(Locale.ROOT,
                    "%8.0f  %10.3f  %6.3f  %6.3f  %7.3f  %7.3f  %6.3f  %8.1f  %s%n",
                    x, c.continent, edge, baseE, seamount, ridge, c.e, c.height, c.terrainType);
        }
        System.out.println("判读：深浅边界与 edge 对齐 ⇒ 大陆距离坐标产物（不自然）；");
        System.out.println("      与 baseE 对齐 ⇒ 离脊年龄热沉降（正常地质）。");

        // ---- ★ 剖面曲线图（2026-11-17 用户："你到底有没有做剖面图分析？"）----
        //   表格只能读数字，看不出「脊形」。画三条曲线：e / baseE / ridge 分量。
        int W = 1000, H = 400, M = 60;
        int N2 = 401;
        double halfW = 500;
        double[] xs = new double[N2];
        double[] es = new double[N2];
        double[] bs = new double[N2];
        double[] rs = new double[N2];
        double minE = 1, maxE = -1;
        for (int i = 0; i < N2; i++) {
            double x = cx - halfW + 2 * halfW * i / (N2 - 1);
            var c = smp.sampleColumn(x, cz);
            xs[i] = x;
            es[i] = c.e;
            bs[i] = c.oceanFeat == null ? c.e : c.oceanFeat.baseE;
            rs[i] = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
            LAST_PEAK = Math.max(LAST_PEAK, rs[i]);
            minE = Math.min(minE, Math.min(es[i], bs[i]));
            maxE = Math.max(maxE, Math.max(es[i], bs[i]));
        }
        // y 映射：e ∈ [minE-0.02, maxE+0.02]
        double lo = minE - 0.02, hi = maxE + 0.02;
        BufferedImage prof = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = prof.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(new java.awt.Color(230, 230, 230));
        for (int k = 0; k <= 10; k++) {
            int yy = M + (H - 2 * M) * k / 10;
            g.drawLine(M, yy, W - M, yy);
        }
        g.setColor(new java.awt.Color(200, 200, 200));
        g.drawLine(M, H / 2, W - M, H / 2);   // 参考中线
        // 曲线绘制器
        java.util.function.BiConsumer<double[], java.awt.Color> draw = (arr, col) -> {
            g.setColor(col);
            int px = -1, py = -1;
            for (int i = 0; i < N2; i++) {
                int xx = M + (W - 2 * M) * i / (N2 - 1);
                int yy = (int) (H - M - (arr[i] - lo) / Math.max(1e-9, hi - lo) * (H - 2 * M));
                if (px >= 0) {
                    g.drawLine(px, py, xx, yy);
                }
                px = xx;
                py = yy;
            }
        };
        draw.accept(bs, new java.awt.Color(170, 170, 170));   // baseE（年龄律）
        draw.accept(es, java.awt.Color.BLACK);                // e（最终）
        // ridge 分量单独画（放大到 e 轴）
        double[] rScaled = new double[N2];
        for (int i = 0; i < N2; i++) {
            rScaled[i] = lo + (rs[i] / Math.max(1e-9, peakRidge())) * (hi - lo) * 0.5;
        }
        draw.accept(rScaled, new java.awt.Color(30, 90, 200));
        g.setColor(java.awt.Color.BLACK);
        g.drawString(String.format(Locale.ROOT,
                "RIDGE PROFILE E-W  z=%.0f  e:[%.3f,%.3f]  black=e  gray=baseE(age)  blue=ridge(amp %.3f)",
                cz, minE, maxE, peakRidge()), M, 30);
        g.drawString("horizontal: x (wu)  vertical: e (0 = sea level, 127 blocks/e)", M, 50);
        g.dispose();
        java.nio.file.Path dir = java.nio.file.Path.of("build/ridge-profile");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(prof, "png", dir.resolve("ridge-profile.png").toFile());
        System.out.println("image -> " + dir.resolve("ridge-profile.png").toAbsolutePath());

        // ---- 2D 网格：看「深→浅边界」的走向（e 与 baseE 并排）----
        System.out.println();
        System.out.println("=== 9x9 网格（step 50wu）  e 矩阵（. 空行） ===");
        double gx = 200, gz = 200;
        for (int j = -4; j <= 4; j++) {
            StringBuilder se = new StringBuilder();
            StringBuilder sb = new StringBuilder();
            for (int i = -4; i <= 4; i++) {
                var c = smp.sampleColumn(cx + i * 50, cz + j * 50);
                double baseE = c.oceanFeat == null ? 0 : c.oceanFeat.baseE;
                se.append(String.format(Locale.ROOT, "%6.3f", c.e));
                sb.append(String.format(Locale.ROOT, "%6.3f", baseE));
            }
            System.out.println(String.format(Locale.ROOT, "z=%+6.0f  e:[%s]", cz + j * 50, se));
            System.out.println(String.format(Locale.ROOT, "         b:[%s]", sb));
        }
        System.out.println("（e 与 baseE 的形状一致 ⇒ 边界来自构造年龄律；某一行/列突变 ⇒ 坐标系伪影）");
    }

    /** 峰位辅助：从样本里找 ridge 分量峰值（供图标题）。 */
    private static double peakRidge() {
        return LAST_PEAK;
    }

    private static double LAST_PEAK = 0.1;

    private RidgeProfileProbe() { }
}