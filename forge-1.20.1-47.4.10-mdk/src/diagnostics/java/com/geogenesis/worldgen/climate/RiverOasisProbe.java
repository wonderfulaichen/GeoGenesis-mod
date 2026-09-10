package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河流绿洲数据通路自检（2026-09-10）。
 *
 * <p>验证两件事：
 * <ol>
 *   <li><b>一致性</b>：新增的只读查询 {@link RiverLineNetwork#distanceToWater} 与既有
 *       {@code sample(wx,wz).distToCenter()} 是否同一量（新查询不能自造一套距离定义，
 *       否则预览与游戏会看到两条不同的"河"）。</li>
 *   <li><b>性能</b>：群系快速路径会逐格调用它，必须远快于侵蚀 tile（~800ms）；
 *       实测单次微秒数，确认不会把"建世界 7.5 分钟 → 秒级"的优化吃回去。</li>
 * </ol>
 */
public final class RiverOasisProbe {

    /** 与 BiomeClassifier.OASIS_REACH 一致的走廊半宽（wu）。 */
    private static final double OASIS_REACH = 24.0;
    private static final int N = 2000;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        RiverLineNetwork net = new RiverLineNetwork(
            gen::terrainEQuick,
            (wx, wz) -> gen.sample(wx, wz).height,
            null, 0.0,
            gen.heightCurve(), seed, p.horizontalScale(),
            RiverLineParams.defaults());

        double[] xs = new double[N], zs = new double[N];
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < N; i++) {
            xs[i] = (rnd.nextDouble() - 0.5) * 4000.0;
            zs[i] = (rnd.nextDouble() - 0.5) * 4000.0;
        }

        System.out.println("=== 河流绿洲数据通路自检 ===");
        System.out.println("采样点 " + N + " 个，走廊半宽 " + OASIS_REACH + " wu");

        // ---- 第 1 遍：一致性（顺带把 region 建出来）----
        // 不变式：新查询取「所有河段的真实最近距离」，既有 sample() 只服务于雕刻，
        // 会因 valleyReach 截断 / 跌水端帽豁免漏掉一些段 → 恒有 d ≤ ref。
        // 反之（d > ref）说明新查询漏扫了河段，才是真错。
        double maxAbsDiff = 0.0;
        double maxSigned = Double.NEGATIVE_INFINITY;   // max(d - ref)，应 ≤ 0
        double maxGap = 0.0;                           // max(ref - d)：既有过滤造成的间距
        int violations = 0;
        int finiteOnlyNew = 0;   // 新查询有值、但既有 sample() 无命中
        int compared = 0;
        for (int i = 0; i < N; i++) {
            double d = net.distanceToWater(xs[i], zs[i]);
            RiverLineNetwork.RiverLineHit hit = net.sample(xs[i], zs[i]);
            double ref = hit == null ? Double.POSITIVE_INFINITY : hit.distToCenter();
            boolean dn = Double.isFinite(d), rn = Double.isFinite(ref);
            if (dn && rn) {
                double diff = d - ref;
                maxAbsDiff = Math.max(maxAbsDiff, Math.abs(diff));
                maxSigned = Math.max(maxSigned, diff);
                maxGap = Math.max(maxGap, -diff);
                if (diff > 1e-6) violations++;
                compared++;
            } else if (dn && !rn) {
                finiteOnlyNew++;
            }
        }
        System.out.println();
        System.out.println("--- 一致性（distanceToWater vs sample().distToCenter）---");
        System.out.printf("  可比样本 %d，不变式违反(d > ref) %d 次 %s%n",
            compared, violations, violations == 0 ? "→ OK" : "→ NG（新查询漏扫河段）");
        System.out.printf("  max(d - ref) = %+.6f wu（应 ≤ 0：新查询取真实最近）%n", maxSigned);
        System.out.printf("  max(ref - d) = %.3f wu，最大绝对差 %.3f wu%n", maxGap, maxAbsDiff);
        System.out.println("  仅新查询有值（既有 sample() 因雕刻截断/豁免无命中）：" + finiteOnlyNew
            + " —— 预期行为，绿洲需要真实距离");

        // ---- 第 2 遍：性能（region 已缓存，等价于稳态热路径）----
        long t0 = System.nanoTime();
        int within = 0;
        for (int i = 0; i < N; i++) {
            if (net.distanceToWater(xs[i], zs[i]) < OASIS_REACH) within++;
        }
        long elapsedNs = System.nanoTime() - t0;
        double usPerQuery = elapsedNs / 1000.0 / N;

        System.out.println();
        System.out.println("--- 性能（region 已缓存的稳态）---");
        System.out.printf("  单次查询 %.2f µs；%d 次共 %.1f ms%n",
            usPerQuery, N, elapsedNs / 1_000_000.0);
        System.out.printf("  侵蚀 tile 约 800 ms → 本查询约为其 %.5f%%%n",
            (elapsedNs / 1_000_000.0) / N / 800.0 * 100.0);
        System.out.println();
        System.out.println("--- 绿洲走廊覆盖 ---");
        System.out.printf("  走廊内（<%.0f wu）采样点 %d / %d = %.2f%%%n",
            OASIS_REACH, within, N, within * 100.0 / N);
        System.out.println("  （干旱群区才走这条规则，实际命中率 = 该比例 × 干旱占比 × 噪声门控通过率）");

        // ---- 坡度分布：标定「陡坡裸岩」阈值 ----
        // 必须走 sampleWu（= sample + applyTileDelta）：gradient 只在完整管线里算，
        // 因为它取自侵蚀 tile 的高度网格。范围限制在 144 wu（≈3×3 个 tile）以免生成过多 tile。
        System.out.println();
        System.out.println("--- 坡度分布（陡坡裸岩阈值标定，384 wu 内）---");
        int gn = 300;
        double[] gs = new double[gn];
        java.util.Random gr = new java.util.Random(seed + 1);
        for (int i = 0; i < gn; i++) {
            gs[i] = gen.sampleWu(gr.nextDouble() * 384.0, gr.nextDouble() * 384.0).gradient;
        }
        java.util.Arrays.sort(gs);
        StringBuilder sb = new StringBuilder();
        for (double q : new double[]{0.5, 0.75, 0.9, 0.95, 0.99}) {
            sb.append(String.format("p%.0f=%.3f  ", q * 100, gs[(int) (q * (gn - 1))]));
        }
        System.out.println("  " + sb);
        System.out.printf("  mean=%.3f  max=%.3f%n",
            java.util.Arrays.stream(gs).average().orElse(0.0), gs[gn - 1]);
        System.out.println("  各阈值覆盖率：");
        for (double th : new double[]{0.30, 0.45, 0.60, 0.70, 0.85}) {
            System.out.printf("    > %.2f → %.2f%%%n", th,
                java.util.Arrays.stream(gs).filter(g -> g > th).count() * 100.0 / gn);
        }
        System.out.println("  （参考：0.70 ≈ 35°，1.00 = 45°；RTF 岩层从 ~0.3 起、0.65+ 为重岩）");
    }
}
