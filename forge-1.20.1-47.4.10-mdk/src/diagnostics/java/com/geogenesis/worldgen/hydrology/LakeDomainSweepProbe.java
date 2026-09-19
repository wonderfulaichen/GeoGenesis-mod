package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「湖域收窄代价」扫描探针（2026-09-19）—— <b>架构第二刀（湖域过大）的判决实验</b>。
 *
 * <h2>要回答的问题</h2>
 * <p>当前湖域 = 洼地轮廓外扩 {@code gridCell*2 = 48wu}。它带来两种代价，且<b>方向相反</b>：</p>
 * <ul>
 *   <li><b>域太宽</b> ⇒ 湖分支（{@code carved = original}）接管了本应由【河】塑形的谷壁带
 *       ⇒ 陆地河谷不被雕（§1.18 实测 1497 列）。</li>
 *   <li><b>域太窄</b> ⇒ 低于 {@code spill} 的格拿不到湖命中 ⇒ 落回河分支 ⇒ 不灌
 *       ⇒ <b>域边界重新变成一条硬边</b>（正是 2026-09-19 修掉的那条直线）。</li>
 * </ul>
 *
 * <h2>方法（零生产改动）</h2>
 * <p>用 {@link RiverLineNetwork#domainToleranceOverride} 扫描容差 T：</p>
 * <ul>
 *   <li>基准集 = T 取生产默认（{@code -1}）时的【湖域内、地形低于湖面的格】（= 水体列）。</li>
 *   <li>每个 T ⇒ 重算水体列集 ⇒ {@code 失水 = 基准 − 本 T}（被截断的水体 ⇒ 硬边代价）。</li>
 *   <li>同时统计 {@code 抑制河谷}（河道外 + 高于湖面 + 有河命中 + 拿到湖命中）。</li>
 * </ul>
 * <p>跑完复位为 {@code -1}。</p>
 *
 * <pre>{@code gradlew runLakeDomainSweepProbe [-PprobeArgs="seed bx bz half step"]}</pre>
 */
public final class LakeDomainSweepProbe {

    private LakeDomainSweepProbe() { }

    /** 湖域外扩容差候选（wu）。 */
    private static final double[] TOLS = {0.0, 6.0, 12.0, 24.0, 48.0, 96.0};
    /**
     * 生产默认容差（wu）—— 仅用于表格里标"← 生产"。
     * ★ 2026-09-19 已由 2×gridCell(48) 收窄为 0.5×gridCell(12)（gridCell=24）
     *   ⇒ 改容差时【必须同步本常量】。
     */
    private static final double PROD_TOL = 12.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2;
                TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        System.out.printf("=== LakeDomainSweepProbe seed=%d 块(%d,%d)±%d 步长=%d ===%n",
                seed, bx0, bz0, half, step);
        System.out.printf("    生产域容差 = %.0f wu（gridCell 24 × 2）%n%n", PROD_TOL);

        // ---------- 基准集：生产默认容差下的【水体列】 ----------
        RiverLineNetwork.domainToleranceOverride = -1.0;
        Set<Long> baseWet = new HashSet<>();
        long[] dummy = new long[1];
        long cols = pass(engine, gen, hs, bx0, bz0, half, step, baseWet, dummy);
        System.out.printf("[基准] 生产默认域下 水体列 = %d（采样 %d 列）%n%n", baseWet.size(), cols);

        System.out.printf("%-8s %-12s %-12s %-12s %-12s%n",
                "容差wu", "水体列", "★失水", "★多出", "抑制河谷");
        System.out.println("--------------------------------------------------------------------------");

        for (double t : TOLS) {
            RiverLineNetwork.domainToleranceOverride = t;
            Set<Long> wet = new HashSet<>();
            long[] sup = new long[1];
            pass(engine, gen, hs, bx0, bz0, half, step, wet, sup);

            long lost = 0;
            for (long k : baseWet) if (!wet.contains(k)) lost++;
            long gained = 0;
            for (long k : wet) if (!baseWet.contains(k)) gained++;

            String mark = Math.abs(t - PROD_TOL) < 1e-9 ? "  ← 生产" : "";
            System.out.printf("%-8.0f %-12d %-12d %-12d %-12d%s%n",
                    t, wet.size(), lost, gained, sup[0], mark);
        }

        RiverLineNetwork.domainToleranceOverride = -1.0;
        System.out.println();
        System.out.println("判读：");
        System.out.println("  · 失水 = 0 且 抑制河谷 大降 ⇒ 收窄【安全且有效】⇒ 采用该容差；");
        System.out.println("  · 失水 快速增长 ⇒ 收窄会重现硬边（域边界成水边）⇒ 不能只靠收窄域；");
        System.out.println("  · 若失水与抑制河谷【同步恶化】（此消彼长）⇒ 收窄域是错的解法，");
        System.out.println("    应改走方案 A（湖分支内也做谷壁塑形，与域宽窄解耦）。");
    }

    /**
     * 一趟采样：填 {@code wetOut}（湖域内、地形低于湖面的列），
     * 并在 {@code supOut[0]} 累计"抑制河谷"列数。返回采样列数。
     */
    private static long pass(HydrologyExperimentEngine engine, CellGenerator gen, double hs,
                             int bx0, int bz0, int half, int step,
                             Set<Long> wetOut, long[] supOut) {
        long cols = 0;
        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                cols++;
                List<HydrologyBlockSample> samples = engine.sampleBlockAll(bx, bz, hs);
                if (samples == null || samples.isEmpty()) continue;

                double lakeSurf = Double.NaN;
                double riverDist = Double.POSITIVE_INFINITY, riverWidth = 1.0;
                boolean hasR = false, hasL = false;
                for (HydrologyBlockSample s : samples) {
                    if (s.isLake()) {
                        // 同一 region 的湖命中都源自同一个 bestLn ⇒ 水面一致，取首个即可
                        if (!hasL) lakeSurf = s.surfaceY();
                        hasL = true;
                    } else {
                        hasR = true;
                        if (s.distToCenter() < riverDist) {
                            riverDist = s.distToCenter();
                            riverWidth = Math.max(s.width(), 1.0);
                        }
                    }
                }
                if (!hasL) continue;

                double h = gen.sample(bx / hs, bz / hs).height;
                if (h < lakeSurf - 0.5) {
                    wetOut.add(((long) bx << 32) ^ (bz & 0xffffffffL));      // 应是水
                } else if (hasR && riverDist > riverWidth) {
                    supOut[0]++;                                             // 高于湖面 + 河道外 ⇒ 本应塑形
                }
            }
        }
        return cols;
    }
}
