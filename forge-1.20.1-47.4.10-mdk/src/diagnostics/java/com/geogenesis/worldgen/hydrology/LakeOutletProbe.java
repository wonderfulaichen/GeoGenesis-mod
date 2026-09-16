package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「湖的真出口」测量探针（2026-09-17）—— <b>只读；先证明能修，再谈改产出</b>。
 *
 * <h2>背景（已由控制变量定位）</h2>
 * <p>水位来源链实测为 {@code spill 167.074 → erodedWaterLevel 166.627 → 落块 166.627}，
 * 而<b>紧邻旱地只有 140.551（差 26 块）</b>。{@code computeFlood} 的短板迭代
 * 给出同值 ⇒ 它<b>找不到真出口</b>。已怀疑两处限制：搜索窗仅 +72wu、BFS 只有 24wu 粗格。</p>
 *
 * <h2>本探针回答三问</h2>
 * <ol>
 *   <li><b>真出口有多低、多远</b>：以湖心为中心、按距离环（&lt;48 / 48~96 / 96~192 / 192~384 wu）
 *       统计"低于当前水位"的格的最低高度与方位；</li>
 *   <li><b>{@code filledAt}（priority-flood 溢流高程）是否就是答案</b>：
 *       打印湖盆洼地格的 {@code fillEAt / filledAt / basinDepthAt}；</li>
 *   <li><b>由粗到细（adaptive resampling）能否找到它</b>：从湖心出发做多轮"粗扫→加密"，
 *       对比它与"单轮粗扫"、"全域细扫"的结果 —— 验证用户建议的采样策略。</li>
 * </ol>
 *
 * <pre>{@code gradlew runLakeOutletProbe [-PprobeArgs="seed wuX wuZ"]}</pre>
 */
public final class LakeOutletProbe {

    private LakeOutletProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        double qx = args.length > 1 ? Double.parseDouble(args[1]) : 12.0;
        double qz = args.length > 2 ? Double.parseDouble(args[2]) : 316.0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();
        double seaLevel = gen.heightCurve().seaLevelY();

        // ★ 统一用【廉价地形口径】：terrainEQuick → heightFromE（与 groundYAt 同口径，
        //   且不触发侵蚀 tile 生成）。生产用 sampleWu，此处先看【结构】。
        com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement.ElevationSampler eQuick =
                (wx, wz) -> gen.terrainEQuick(wx, wz);
        java.util.function.BiFunction<Double, Double, Double> ground =
                (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz));

        RiverLineNetwork net = new RiverLineNetwork(eQuick,
                (wx, wz) -> ground.apply(wx, wz), gen.heightCurve(), seed, hs, rp);
        RiverLineNetwork.RiverLineHit hit = net.sample(qx, qz);
        System.out.printf("=== LakeOutletProbe seed=%d 查询 wu(%.0f,%.0f) ===%n", seed, qx, qz);
        if (hit == null || !hit.isLake()) {
            System.out.println("该点未命中湖（isLake=false）——请换一个湖内的点。");
            return;
        }
        RiverLineRegion.LakeNode ln = hit.lake();
        System.out.printf("湖：中心 wu(%.1f,%.1f)  湖面=%.3f（无侵蚀）  域格 %d  radius=%.1f%n",
                ln.x, ln.z, ln.height,
                ln.cellX == null ? 0 : ln.cellX.length, ln.radius);
        double level = ln.height;

        // ===== ② priority-flood 的 filledAt（大窗 + 细格）=====
        double R = 384;
        FlowField f = new FlowField(ln.x - R, ln.z - R, ln.x + R, ln.z + R, 6.0,
                eQuick);
        f.computeFill((wx, wz) -> ground.apply(wx, wz), seaLevel);
        System.out.println();
        System.out.printf("[2] filledAt（priority-flood 溢流高程，6wu 细格、±%.0f wu 大窗）%n", R);
        double minFillE = Double.MAX_VALUE, maxFilled = -Double.MAX_VALUE, minFilled = Double.MAX_VALUE;
        int nCells = 0;
        if (ln.cellX != null) {
            for (int i = 0; i < ln.cellX.length; i++) {
                int idx = f.indexOf(ln.cellX[i], ln.cellZ[i]);
                double fe = f.fillEAt(idx), fl = f.filledAt(idx);
                if (Double.isNaN(fe)) continue;
                nCells++;
                minFillE = Math.min(minFillE, fe);
                minFilled = Math.min(minFilled, fl);
                maxFilled = Math.max(maxFilled, fl);
            }
        }
        System.out.printf("    湖盆洼地格 %d 个：地面 %.3f~%.3f，filledAt %.3f~%.3f%n",
                nCells, minFillE, maxFillEof(f, ln), minFilled, maxFilled);
        System.out.printf("    ★ filledAt 最小值 = %.3f  ⇒ 若它 ≈ 真出口，则水位应取此值%n", minFilled);

        // ===== ① 真出口：按距离环统计"低于湖面"的最低格 =====
        System.out.println();
        System.out.println("[1] 按距离环统计「低于湖面」的最低格（细格 2wu）");
        double[] ringLo = {0, 48, 96, 192, 384};
        double[] bestH = new double[ringLo.length];
        double[] bestX = new double[ringLo.length], bestZ = new double[ringLo.length];
        java.util.Arrays.fill(bestH, Double.MAX_VALUE);
        for (double z = ln.z - R; z <= ln.z + R; z += 2) {
            for (double x = ln.x - R; x <= ln.x + R; x += 2) {
                double h = ground.apply(x, z);
                if (h >= level) continue;                    // 只看"低于湖面"的（潜在的溢出口）
                double d = Math.hypot(x - ln.x, z - ln.z);
                for (int r = ringLo.length - 1; r >= 0; r--) {
                    if (d >= ringLo[r]) {
                        if (h < bestH[r]) { bestH[r] = h; bestX[r] = x; bestZ[r] = z; }
                        break;
                    }
                }
            }
        }
        for (int r = 0; r < ringLo.length; r++) {
            if (bestH[r] == Double.MAX_VALUE) continue;
            System.out.printf("    距湖心 ≥%3.0f wu：最低 %.3f @(%.0f,%.0f)  ⇒ 比湖面低 %.3f 块%n",
                    ringLo[r], bestH[r], bestX[r], bestZ[r], level - bestH[r]);
        }

        // ===== ③ 由粗到细（用户建议：变化大处加密）=====
        System.out.println();
        System.out.println("[3] 由粗到细搜索（coarse-to-fine / adaptive）vs 单轮粗扫");
        long t0 = System.nanoTime();
        double[] single = coarseToFine(ground, ln.x, ln.z, R, 24.0, 1);
        long t1 = System.nanoTime();
        double[] adapt = coarseToFine(ground, ln.x, ln.z, R, 24.0, 5);
        long t2 = System.nanoTime();
        System.out.printf("    单轮粗扫(24wu)：最低 %.3f @(%.0f,%.0f)   耗时 %d ms%n",
                single[2], single[0], single[1], (t1 - t0) / 1_000_000);
        System.out.printf("    由粗到细(5 轮)：最低 %.3f @(%.0f,%.0f)   耗时 %d ms%n",
                adapt[2], adapt[0], adapt[1], (t2 - t1) / 1_000_000);
        System.out.printf("    ⇒ 细化把最低点从 %.3f 降到 %.3f（再降 %.3f 块）%n",
                single[2], adapt[2], single[2] - adapt[2]);

        System.out.println();
        System.out.println("判读：");
        System.out.println("  · 若 filledAt 最小值 ≈ 距离环最低 ⇒ **priority-flood 已经给出正确答案**，");
        System.out.println("    修法 = 湖水位改用 filledAt（而非局部 rim），无需自研搜索；");
        System.out.println("  · 若『由粗到细』显著优于单轮粗扫 ⇒ 采纳自适应采样（用户建议）。");
        System.out.println("  两者都指向同一结论：短板必须**在大范围、细分辨率**上求。");
    }

    /** 湖盆洼地格的地面最大值（诊断用）。 */
    private static double maxFillEof(FlowField f, RiverLineRegion.LakeNode ln) {
        double m = -Double.MAX_VALUE;
        if (ln.cellX == null) return Double.NaN;
        for (int i = 0; i < ln.cellX.length; i++) {
            double v = f.fillEAt(f.indexOf(ln.cellX[i], ln.cellZ[i]));
            if (!Double.isNaN(v)) m = Math.max(m, v);
        }
        return m;
    }

    /**
     * 由粗到细搜索最低点（每一轮把窗口收窄到上一轮步长的范围、并把步长减到 1/4）。
     *
     * <p>这正是"自适应重采样"的思路：<b>不在全域用细格</b>（代价 O((2R/step)²)），
     * 而是先用粗格定位候选区域，再只在候选附近加密 ⇒ 同等精度下显著省采样。</p>
     *
     * @param rounds 轮数（1 = 单轮粗扫，作为对照基线）
     * @return {x, z, height}
     */
    private static double[] coarseToFine(java.util.function.BiFunction<Double, Double, Double> g,
                                        double cx, double cz, double r0, double step0, int rounds) {
        double bx = cx, bz = cz, bh = g.apply(bx, bz);
        double r = r0, s = step0;
        for (int k = 0; k < rounds; k++) {
            for (double z = bz - r; z <= bz + r; z += s) {
                for (double x = bx - r; x <= bx + r; x += s) {
                    double h = g.apply(x, z);
                    if (h < bh) { bh = h; bx = x; bz = z; }
                }
            }
            r = s;                              // 下一轮只在"上一轮步长"范围内找
            s = Math.max(1.0, s / 4.0);         // 步长减到 1/4（逐级加密）
        }
        return new double[]{bx, bz, bh};
    }
}
