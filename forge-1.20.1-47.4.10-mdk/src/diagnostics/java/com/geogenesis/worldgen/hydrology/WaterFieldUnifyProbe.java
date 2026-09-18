package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「统一水位接线」可行性探针（2026-09-19）—— <b>T1.3（W 作湖水位）的先量后改</b>。
 *
 * <h2>要回答</h2>
 * <p>把湖水位从 {@code LakeNode.spill}（每湖一个、region 内求）换成
 * {@code W = 低通地形 + offset}（逐格闭式、跨区一致），<b>水位会变多少</b>？</p>
 *
 * <h2>方法（零生产改动）</h2>
 * <ol>
 *   <li>用生产 {@code carveColumnAt} 取湖列，读其 {@code waterSurfaceY}（= 现行 spill 链路）；</li>
 *   <li>用 {@code WaterField.waterYAt} 算同一格的 W；</li>
 *   <li>统计差值分布 ⇒ 判断"能否直接换"。</li>
 * </ol>
 *
 * <h2>判读口径</h2>
 * <ul>
 *   <li>若 |差| 中位数小（&lt;1 块）⇒ 两者接近，可平滑切换；</li>
 *   <li>若差大且【系统性偏移】⇒ 需先标定 offset（不是随机误差，是可修的）；</li>
 *   <li>若差大且【散布】⇒ 两者语义不同（W 是区域平均、spill 是盆地出口）⇒ 不能直接换。</li>
 * </ul>
 *
 * <pre>{@code gradlew runWaterFieldUnifyProbe [-PprobeArgs="seed bx bz half step smoothWu offset"]}</pre>
 */
public final class WaterFieldUnifyProbe {

    private WaterFieldUnifyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        double smoothWu = args.length > 5 ? Double.parseDouble(args[5])
                : WaterField.DEFAULT_SMOOTH_WU;
        double offset = args.length > 6 ? Double.parseDouble(args[6])
                : WaterField.DEFAULT_OFFSET;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        HeightCurve curve = gen.heightCurve();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        WaterField wf = new WaterField(gen::terrainEQuick, curve, smoothWu, offset);

        System.out.printf("=== WaterFieldUnifyProbe seed=%d 块(%d,%d)±%d 步长=%d "
                        + "smoothWu=%.0f offset=%.2f ===%n",
                seed, bx0, bz0, half, step, smoothWu, offset);
        System.out.printf("seaLevelY=%.1f%n", curve.seaLevelY());

        java.util.ArrayList<Double> diffs = new java.util.ArrayList<>();   // W − spill
        java.util.ArrayList<Double> absDiffs = new java.util.ArrayList<>();
        long lakeCols = 0, riverCols = 0, noneCols = 0;
        long wAbove = 0, wBelow = 0;
        double maxAbove = 0, maxBelow = 0;
        int shown = 0;

        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                double original = gen.sample(bx / hs, bz / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, bx, bz, original, hs);
                if (col == null) { noneCols++; continue; }
                if (col.lakeNode() == null) { riverCols++; continue; }
                lakeCols++;

                double spill = col.waterSurfaceY();
                if (Double.isNaN(spill) || spill <= 0) continue;
                double w = wf.waterYAt(bx / hs, bz / hs);
                double d = w - spill;
                diffs.add(d);
                absDiffs.add(Math.abs(d));
                if (d > 0) {
                    wAbove++;
                    if (d > maxAbove) maxAbove = d;
                } else {
                    wBelow++;
                    if (-d > maxBelow) maxBelow = -d;
                }
                if (shown < 8 && Math.abs(d) > 3.0) {
                    Cell cell = gt.getChunkCells(bx >> 4, bz >> 4)
                            [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                    System.out.printf("    块(%d,%d) spill=%.3f W=%.3f 差=%+.3f "
                                    + "(h=%.2f inFlood=? riverType=%d)%n",
                            bx, bz, spill, w, d, cell.height, cell.riverType);
                    shown++;
                }
            }
        }

        System.out.printf("%n[1] 列数：湖 %d / 河 %d / 无计划 %d%n",
                lakeCols, riverCols, noneCols);
        if (diffs.isEmpty()) {
            System.out.println("    ⚠ 无有效湖列 —— 换区域或加大 half");
            return;
        }

        java.util.Collections.sort(diffs);
        java.util.Collections.sort(absDiffs);
        double[] da = diffs.stream().mapToDouble(Double::doubleValue).toArray();
        double[] aa = absDiffs.stream().mapToDouble(Double::doubleValue).toArray();

        System.out.printf("%n[2] W − spill 差值（n=%d）%n", da.length);
        System.out.printf("    p10=%+.3f p50=%+.3f p90=%+.3f  最小=%+.3f 最大=%+.3f%n",
                pct(da, 0.10), pct(da, 0.50), pct(da, 0.90), da[0], da[da.length - 1]);
        System.out.printf("    均值=%+.3f  标准差=%.3f%n", mean(da), std(da));

        System.out.printf("%n[3] |差| 分布%n");
        System.out.printf("    p50=%.3f p90=%.3f max=%.3f%n",
                pct(aa, 0.50), pct(aa, 0.90), aa[aa.length - 1]);
        System.out.printf("    |差|<1 块：%d（%.1f%%）%n",
                countBelow(aa, 1.0), 100.0 * countBelow(aa, 1.0) / aa.length);
        System.out.printf("    |差|<3 块：%d（%.1f%%）%n",
                countBelow(aa, 3.0), 100.0 * countBelow(aa, 3.0) / aa.length);
        System.out.printf("    |差|≥10 块：%d（%.1f%%）%n",
                aa.length - countBelow(aa, 10.0),
                100.0 * (aa.length - countBelow(aa, 10.0)) / aa.length);

        System.out.printf("%n[4] 方向%n");
        System.out.printf("    W 高于 spill：%d（%.1f%%）最大 +%.3f 块%n",
                wAbove, 100.0 * wAbove / da.length, maxAbove);
        System.out.printf("    W 低于 spill：%d（%.1f%%）最大 −%.3f 块%n",
                wBelow, 100.0 * wBelow / da.length, maxBelow);

        // ---------- [5] ★★★★ W 洼地掩膜 vs 实际湖水的重合度（T2 可行性） ----------
        //   T2（湖域收窄）要用 W 的 depthBelowSmooth() > 0 作【廉价预筛】：
        //   只在该掩膜为真的地方建湖节点/跑连通性，避开"湖域大而实际无水"的空转。
        //   本段量：掩膜 与 实际湖水 的重合度（命中率 / 漏检率 / 误检率）。
        long maskTrue = 0, maskFalse = 0;
        long wetAndMask = 0, wetNotMask = 0, dryAndMask = 0, dryNotMask = 0;
        long riverAndMask = 0;
        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                double original = gen.sample(bx / hs, bz / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, bx, bz, original, hs);
                if (col == null) continue;
                if (col.lakeNode() == null) {
                    if (wf.depthBelowSmooth(bx / hs, bz / hs) > 0) riverAndMask++;
                    continue;
                }
                Cell cell = gt.getChunkCells(bx >> 4, bz >> 4)
                        [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                boolean mask = wf.depthBelowSmooth(bx / hs, bz / hs) > 0;
                boolean wet = cell.riverType != 0;
                if (mask) maskTrue++; else maskFalse++;
                if (wet && mask) wetAndMask++;
                else if (wet) wetNotMask++;
                else if (mask) dryAndMask++;
                else dryNotMask++;
            }
        }
        long lakeCols2 = wetAndMask + wetNotMask + dryAndMask + dryNotMask;
        System.out.printf("%n[5] ★★ W 洼地掩膜（depthBelowSmooth>0）vs 实际湖水  湖列=%d%n", lakeCols2);
        System.out.printf("    掩膜为真 = %d（%.1f%%）  掩膜为假 = %d%n",
                maskTrue, 100.0 * maskTrue / Math.max(1, lakeCols2), maskFalse);
        System.out.printf("    湖列中：有水且掩膜 %d / 有水但掩膜假 %d / 无水且掩膜 %d / 无水掩膜假 %d%n",
                wetAndMask, wetNotMask, dryAndMask, dryNotMask);
        long wetTotal = wetAndMask + wetNotMask;
        long dryTotal = dryAndMask + dryNotMask;
        System.out.printf("    ★ 命中率（有水且掩膜 / 有水）= %d/%d = %.1f%%%n",
                wetAndMask, wetTotal, 100.0 * wetAndMask / Math.max(1, wetTotal));
        System.out.printf("    ★ 误检率（无水且掩膜 / 掩膜真）= %d/%d = %.1f%%%n",
                dryAndMask, maskTrue, 100.0 * dryAndMask / Math.max(1, maskTrue));
        System.out.printf("    ★ 若用掩膜预筛：湖列 %d → %d（省 %.1f%%）%n",
                lakeCols2, maskTrue, 100.0 * (1.0 - (double) maskTrue / Math.max(1, lakeCols2)));
        System.out.printf("    （河列中掩膜为真 = %d，占河列 %.1f%%）%n",
                riverAndMask, 100.0 * riverAndMask / Math.max(1, riverCols));
        System.out.println("    判读：命中率高（>90%）+ 误检低 ⇒ 可用掩膜预筛（T2 可行）；");
        System.out.println("          命中率低 ⇒ 会漏掉真湖 ⇒ 不可用（须另找收窄判据）。");

        System.out.println();
        System.out.println("判读：");
        System.out.println("  · |差| p50 小（<1 块）⇒ W 与 spill 接近，可直接接线（T1.3）；");
        System.out.println("  · 若【系统性同向偏移】（均值大但标准差小）⇒ 调 offset 即可对齐；");
        System.out.println("  · 若【散布大】（标准差大）⇒ W（区域平均）与 spill（盆地出口）语义不同，");
        System.out.println("    须按 §8.2 结论：W 只给水面高度，湖位置仍由河网/连通性决定。");
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s / Math.max(1, v.length);
    }

    private static double std(double[] v) {
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / Math.max(1, v.length));
    }

    private static int countBelow(double[] sortedAsc, double thr) {
        int c = 0;
        for (double x : sortedAsc) {
            if (x < thr) c++;
            else break;
        }
        return c;
    }
}
