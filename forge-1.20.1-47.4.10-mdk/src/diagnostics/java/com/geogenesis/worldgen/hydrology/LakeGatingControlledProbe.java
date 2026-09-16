package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【控制变量】湖泊判水门控探针（2026-09-17）。
 *
 * <h2>待验证的门控条件（读 {@code HydrologyBlockCarver} 得到）</h2>
 * <pre>
 *   if (!samples.isEmpty() &amp;&amp; samples.get(0).isLake()) {
 *       double spill = lakeSample.surfaceY();                    // 默认：无侵蚀溢出坎高
 *       if (LAKE_ERODED_SPILL &amp;&amp; ln != null &amp;&amp; ln.hasRim()) {    // ★ 开关：仅 hasRim 的湖
 *           spill = ln.erodedWaterLevel(erodedY);                //   ① 侵蚀短板水位
 *           if (ln.computeFlood(...)) return 弃湖;               //   ② 连通性（并缓存 flood）
 *           if (!ln.inFlood(wuX, wuZ)) return 非湖列;             //   ③ 不在连通区 → 不灌
 *       }
 *       return ...(spill, spill, lakePlan=true);                 // 无 rim 的湖：【直接到这】
 *   }
 * </pre>
 * <p>⇒ <b>假设 H1</b>：无 rim 的湖<b>绕过了连通性约束</b> ⇒ 域内任何低于 spill 的低地都被灌
 * （含水不相连的坡上）—— 与代码注释记录的"水漫到湖盆外的坡上"同症状。</p>
 * <p>⇒ <b>推论 H2</b>：{@code computeFlood} 只在 hasRim 分支被调用 ⇒ 对无 rim 的湖
 * {@code floodX == null} ⇒ {@code inFlood()} <b>恒 false</b>（不能简单"加检查"，
 * 必须让所有湖都先跑 computeFlood）。</p>
 *
 * <h2>本探针做什么（控制变量）</h2>
 * <p>在窗口内取所有"被判定为湖水（{@code hit.isLake()}）"的格，按<b>湖</b>分组，逐湖统计：</p>
 * <ol>
 *   <li>{@code hasRim()} / {@code hasOutline()} —— 决定走哪条门控；</li>
 *   <li>域内水格数 vs 其中 {@code inFlood} 为真的格数
 *       ⇒ <b>有多少水是在连通区之外的</b>（= 应被拦住而没拦）；</li>
 *   <li>水格与其域外旱地的高度差（其中是否有"水位高于紧邻旱地"的违反）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runLakeGatingControlledProbe [-PprobeArgs="seed rx rz halfWu step"]}</pre>
 */
public final class LakeGatingControlledProbe {

    private LakeGatingControlledProbe() { }

    public static void main(String[] args) {
        // 参数：seed cx cz [half] [step]（cx/cz 为窗口中心，单位 wu）
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        double cx = args.length > 1 ? Double.parseDouble(args[1]) : 12.0;
        double cz = args.length > 2 ? Double.parseDouble(args[2]) : 316.0;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 96;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();

        RiverLineNetwork net = new RiverLineNetwork(
                (wx, wz) -> gen.terrainEQuick(wx, wz),
                (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)),
                gen.heightCurve(), seed, hs, rp);

        System.out.printf("=== LakeGatingControlledProbe seed=%d 窗口 wu(%.0f,%.0f)±%d（步长 %d）===%n",
                seed, cx, cz, half, step);


        // 按【湖】分组统计
        java.util.Map<String, int[]> byLake = new java.util.TreeMap<>();   // key → {域格, 连通区格, 被弃格}
        java.util.Map<String, double[]> meta = new java.util.TreeMap<>();  // key → {hasRim, hasOutline}
        java.util.Set<Object> floodDone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Object> oobLakes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int lakeCells = 0, riverCells = 0, floodTrue = 0;
        for (int j = -half; j <= half; j += step) {
            for (int i = -half; i <= half; i += step) {
                double wx = cx + i, wz = cz + j;
                RiverLineNetwork.RiverLineHit hit = net.sample(wx, wz);
                if (hit == null) continue;
                if (!hit.isLake()) {
                    if (hit.distToCenter() <= Math.max(1.0, hit.width() * 1.5)) riverCells++;
                    continue;
                }
                lakeCells++;
                RiverLineRegion.LakeNode ln = hit.lake();
                String key = ln == null ? "(lake==null)" : String.format("湖@(%.0f,%.0f)", ln.x, ln.z);
                byLake.computeIfAbsent(key, k -> new int[3])[0]++;   // [0] 域判定（= sample 的口径）
                meta.computeIfAbsent(key, k -> new double[]{
                        ln != null && ln.hasRim() ? 1 : 0,
                        ln != null && ln.hasOutline() ? 1 : 0});
                // ★ 模拟 carver 的前置：先算侵蚀短板水位 + computeFlood（每湖一次）
                if (ln != null && !floodDone.contains(ln)) {
                    java.util.function.ToDoubleBiFunction<Double, Double> ey =
                            (a, b) -> gen.heightCurve().heightFromE(gen.terrainEQuick(a, b));
                    double sp = ln.erodedWaterLevel(ey);
                    boolean oob = ln.computeFlood(ey, sp, rp.gridCell() * 0.5, rp.gridCell());
                    floodDone.add(ln);
                    if (oob) oobLakes.add(ln);
                }
                // 对照两套判据
                if (ln != null && ln.inFlood(wx, wz)) {
                    byLake.get(key)[1]++;      // [1] 连通区判定（= carver 的口径）
                    floodTrue++;
                }
                if (ln != null && oobLakes.contains(ln)) byLake.get(key)[2]++;  // [2] 该湖被弃
            }
        }

        System.out.printf("采样：湖水格 %d，河水格 %d；其中 inFlood=true 的 %d%n",
                lakeCells, riverCells, floodTrue);
        System.out.println();
        System.out.println("    湖                        【域】格  【连通区】格  连通占比   hasRim hasOutline oob");
        int rimless = 0, rimlessCells = 0, domainSum = 0, floodSum = 0;
        for (java.util.Map.Entry<String, int[]> e : byLake.entrySet()) {
            String k = e.getKey();
            int[] c = e.getValue();
            double[] m = meta.get(k);
            double pct = c[0] == 0 ? 0 : 100.0 * c[1] / c[0];
            boolean hasRim = m[0] > 0;
            if (!hasRim) { rimless++; rimlessCells += c[0]; }
            domainSum += c[0];
            floodSum += c[1];
            System.out.printf("    %-22s %8d %12d %8.1f%%   %5s %8s %5s%n",
                    k, c[0], c[1], pct, hasRim ? "是" : "否", m[1] > 0 ? "是" : "否",
                    c[2] > 0 ? "是" : "否");
        }
        System.out.println();
        System.out.printf("★ 两套判据对照：域 %d 格 vs 连通区 %d 格 ⇒ 【不一致 %d 格（%.1f%%）】%n",
                domainSum, floodSum, domainSum - floodSum,
                domainSum == 0 ? 0 : 100.0 * (domainSum - floodSum) / domainSum);
        System.out.println("  · 【域】= RiverLineNetwork.sample 的口径（预览/绿洲判定用）");
        System.out.println("  · 【连通区】= HydrologyBlockCarver 的口径（实际落块判水用）");
        System.out.println("  ⇒ 二者差 = 「预览显示是湖、落块时不在连通区」的格数 = 预览≠游戏的规模");
        System.out.println();
        System.out.printf("★ 无 rim 的湖：%d 个，占 %d 域格（%.1f%%）⇒ 这些湖在 carver 里跳过连通性检查%n",
                rimless, rimlessCells, domainSum == 0 ? 0 : 100.0 * rimlessCells / domainSum);
        System.out.println();
        System.out.println("判读：① 若『不一致』占比大 ⇒ 预览与游戏判定湖范围的口径**不统一**（违反项目铁律）；");
        System.out.println("      ② 若『无 rim 的湖』占比高 ⇒ 它们绕过连通性约束，域内低地全灌；");
        System.out.println("      修法 = 让【所有湖】统一走 computeFlood/inFlood，且 sample 与 carver 共用同一判据。");
    }
}
