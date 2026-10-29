package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 海岸带宽度与海底分层占比探针。
 *
 * <h3>要回答的两个问题</h3>
 * <ol>
 *   <li><b>海岸过渡带有多宽（按方块）？</b>—— {@code coast→inland} 的 {@code edge} 跨度
 *       换成块数 = {@code span / |∇edge|}。参考的 0.054 在它的 {@code continentEdge} 下
 *       只有几十格；我们的 {@code c} 是 4000wu 波长的平滑 FBM，同样的 0.054 可能是几百格。</li>
 *   <li><b>深海/浅海/海岸各占海底多少？</b>—— 真实海洋里大陆架约 8%、大陆坡 5%、
 *       深海平原 85%；若"海岸带"吃掉一半以上海底面积，说明分层阈值错位。</li>
 * </ol>
 *
 * <p>用法：{@code <toml> <outDir> <samples> <spanBlocks>}</p>
 */
public final class CoastBandProbe {

    private static final long SEED = 9139912035078620160L;
    /** 参考 Presets.java:24 的四个控制点。 */
    private static final double CP_DEEP = 0.1, CP_SHALLOW = 0.25, CP_COAST = 0.448, CP_INLAND = 0.502;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 120000;
        int span = args.length > 3 ? Integer.parseInt(args[3]) : 40000;
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);

        int counts[] = new int[4];   // deep / shallow / coastBand / land
        double[] gradSum = new double[4];
        int[] gradCnt = new int[4];
        double[] edgeHist = new double[20];   // 0.00..1.00，每格 0.05

        double step = 4.0;   // 有限差分步长（块）
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = (span / 2.0) * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double c = continent.sample(x, z);
            double e = (c + 1.0) * 0.5;

            int bin = Math.min(3, e < CP_DEEP ? 0 : (e < CP_SHALLOW ? 1 : (e < CP_INLAND ? 2 : 3)));
            counts[bin]++;

            // 前向差分求 |∇edge|（块⁻¹）
            double cx = continent.sample(x + step, z);
            double cz = continent.sample(x, z + step);
            double gx = ((cx + 1) * 0.5 - e) / step;
            double gz = ((cz + 1) * 0.5 - e) / step;
            double g = Math.hypot(gx, gz);
            if (g > 1e-9) {
                gradSum[bin] += g;
                gradCnt[bin]++;
            }
            edgeHist[Math.max(0, Math.min(19, (int) (e * 20)))]++;
        }

        int total = n;
        System.out.printf(Locale.ROOT, "样本 %d，跨度 %d 块%n", n, span);
        System.out.println("--- 分层占比（全图）---");
        String[] names = {"深海 (edge<0.1)", "浅海 (0.1-0.25)", "海岸带 (0.25-0.502)", "陆地 (edge>0.502)"};
        for (int b = 0; b < 4; b++) {
            System.out.printf(Locale.ROOT, "  %-24s %5.1f%%   |∇edge| 中位≈%.2e /块%n",
                    names[b], 100.0 * counts[b] / total,
                    gradCnt[b] > 0 ? gradSum[b] / gradCnt[b] : 0);
        }

        double ocean = counts[0] + counts[1] + counts[2];
        System.out.println("--- 海底内部分层（占海底）---");
        if (ocean > 0) {
            System.out.printf(Locale.ROOT, "  深海 %.1f%%  浅海 %.1f%%  海岸带 %.1f%%%n",
                    100.0 * counts[0] / ocean, 100.0 * counts[1] / ocean, 100.0 * counts[2] / ocean);
            System.out.println("  （真实海洋参考：大陆架 ~8%、大陆坡 ~5%、深海平原 ~85%）");
        }

        System.out.println("--- 各带的实际宽度（块）= 该带 edge 跨度 / 平均 |∇edge| ---");
        double[] spans = {CP_DEEP - 0.0, CP_SHALLOW - CP_DEEP, CP_COAST - CP_SHALLOW, 1.0 - CP_INLAND};
        for (int b = 0; b < 4; b++) {
            double g = gradCnt[b] > 0 ? gradSum[b] / gradCnt[b] : 0;
            double width = g > 1e-12 ? spans[b] / g : Double.POSITIVE_INFINITY;
            System.out.printf(Locale.ROOT, "  %-24s edge 跨度 %.4f ÷ %.2e = %8.0f 块%n",
                    names[b], spans[b], g, width);
        }

        System.out.println("--- 海岸过渡带（coast→inland, span=0.054）的实际宽度 ---");
        double gCoast = gradCnt[2] > 0 ? gradSum[2] / gradCnt[2] : 0;
        double wCoast = gCoast > 1e-12 ? (CP_INLAND - CP_COAST) / gCoast : Double.POSITIVE_INFINITY;
        System.out.printf(Locale.ROOT, "  ≈ %.0f 块（这就是「半海半陆滩涂」的宽度）%n", wCoast);

        System.out.println("--- 反推：要让海岸过渡带只有 N 块，span 该取多少 ---");
        for (int target : new int[]{8, 16, 32, 64}) {
            System.out.printf(Locale.ROOT, "  目标 %3d 块 ⇒ span = %.4f%n", target, target * gCoast);
        }

        System.out.println("--- edge 直方图（每 0.05）---");
        for (int i = 0; i < 20; i++) {
            double lo = i * 0.05;
            int bars = (int) Math.round(60.0 * edgeHist[i] / total * 20);
            System.out.printf(Locale.ROOT, "  %.2f-%.2f %5.2f%% %s%n",
                    lo, lo + 0.05, 100.0 * edgeHist[i] / total, "#".repeat(Math.max(0, bars)));
        }
    }
}