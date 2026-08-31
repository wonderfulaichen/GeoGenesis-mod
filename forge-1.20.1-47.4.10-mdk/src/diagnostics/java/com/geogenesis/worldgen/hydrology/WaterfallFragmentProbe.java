package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 瀑布碎裂度探针（2026-08-31）。
 *
 * <p>用户担忧：某坡度（临界 minAngle=12°）维持较长时，噪声使逐段角度在阈值上下抖动 →
 * run 频繁断开重开 → 沿长坡串出一串中小瀑布，视觉"很碎"。本探针量测真实数据判定：</p>
 * <ul>
 *   <li><b>台阶落差分布</b>：水面纵剖面每次下降（surf[i]&lt;surf[i-1] 且处于跌水段）算一阶，
 *       统计各阶落差。碎 = 大量 &lt;2 / &lt;4 格的小阶。</li>
 *   <li><b>台阶间距</b>：相邻跌水阶的节点间距；碎 = 大量 &lt;minSpacing 的密集阶。</li>
 *   <li><b>run 数 / 河</b>：跌水 run（连续 fallDrop&gt;0 段）数量分布。</li>
 * </ul>
 */
public final class WaterfallFragmentProbe {

    private WaterfallFragmentProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        List<Integer> stepsPerRiver = new ArrayList<>();
        List<Integer> runsPerRiver = new ArrayList<>();
        List<Double> stepDrops = new ArrayList<>();
        List<Double> runAngles = new ArrayList<>();   // 每 run 的整体坡角（落差/跨度）
        List<Integer> runLens = new ArrayList<>();     // 每 run 节点数
        int tiny2 = 0, small4 = 0;

        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion r = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline p : r.rivers) {
                    int n = p.nodes.length;
                    int steps = 0, runs = 0, runLen = 0, curRunLen = 0;
                    double runDrop = 0.0, runHoriz = 0.0;
                    boolean inRun = false;
                    for (int i = 1; i < n; i++) {
                        double dx = p.nodes[i].x() - p.nodes[i - 1].x();
                        double dz = p.nodes[i].z() - p.nodes[i - 1].z();
                        double segBlocks = Math.hypot(dx, dz) * 2.0;
                        boolean fall = p.fallDrop[i] > 0.0;
                        double drop = p.surfaceY[i - 1] - p.surfaceY[i];   // 水面下降量
                        if (fall) {
                            if (!inRun) { inRun = true; curRunLen = 1; runDrop = 0; runHoriz = 0; }
                            else curRunLen++;
                            runDrop += Math.max(0, drop);
                            runHoriz += segBlocks;
                            if (drop > 0.25) {   // 一阶跌水
                                steps++;
                                stepDrops.add(drop);
                                if (drop < 2.0) tiny2++;
                                else if (drop < 4.0) small4++;
                            }
                        } else if (inRun) {
                            // run 结束
                            runs++; runLen += curRunLen;
                            if (runHoriz > 1e-6) {
                                runAngles.add(Math.atan2(runDrop, runHoriz) * 180.0 / Math.PI);
                                runLens.add(curRunLen);
                            }
                            inRun = false;
                        }
                    }
                    if (inRun) {
                        runs++; runLen += curRunLen;
                        if (runHoriz > 1e-6) {
                            runAngles.add(Math.atan2(runDrop, runHoriz) * 180.0 / Math.PI);
                            runLens.add(curRunLen);
                        }
                    }
                    if (steps > 0) stepsPerRiver.add(steps);
                    if (runs > 0) runsPerRiver.add(runs);
                }
            }
        }

        System.out.println("=== WaterfallFragmentProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("rivers with steps=" + stepsPerRiver.size()
                + "  total steps=" + stepDrops.size());
        System.out.println("step drops: <2格=" + tiny2 + "  2~4格=" + small4
                + "  >=4格=" + (stepDrops.size() - tiny2 - small4)
                + "  (碎=大量 <2/2~4 小阶)");
        System.out.println("max steps in one river=" + max(stepsPerRiver)
                + "  avg=" + avg(stepsPerRiver));
        System.out.println("max runs in one river=" + max(runsPerRiver)
                + "  avg=" + avg(runsPerRiver));
        System.out.println("run 整体坡角分布(°): min=" + fmt(min(runAngles))
                + " p25=" + fmt(pct(runAngles, 25)) + " median=" + fmt(pct(runAngles, 50))
                + " p75=" + fmt(pct(runAngles, 75)) + " max=" + fmt(maxD(runAngles)));
        long nearThreshold = runAngles.stream().filter(a -> a < 20.0).count();
        System.out.println("run 坡角 <20° 的占比=" + nearThreshold + "/" + runAngles.size()
                + "  (临界缓坡 run 越多 → 越可能把长缓坡误判成一串碎瀑布)");
        System.out.println("run 长度(节点): median=" + fmt(pct(runLens, 50))
                + " max=" + max(runLens));
    }

    private static int max(List<Integer> l) { int m = 0; for (int x : l) m = Math.max(m, x); return m; }
    private static double maxD(List<Double> l) { double m = 0; for (double x : l) m = Math.max(m, x); return m; }
    private static double min(List<Double> l) { double m = Double.MAX_VALUE; for (double x : l) m = Math.min(m, x); return l.isEmpty() ? 0 : m; }
    private static double avg(List<Integer> l) { if (l.isEmpty()) return 0; double s = 0; for (int x : l) s += x; return s / l.size(); }
    private static double pct(List<? extends Number> l, double p) {
        if (l.isEmpty()) return 0;
        List<Double> a = new ArrayList<>();
        for (Number x : l) a.add(x.doubleValue());
        a.sort(Double::compare);
        int idx = (int) Math.min(a.size() - 1, Math.round(p / 100.0 * (a.size() - 1)));
        return a.get(idx);
    }
    private static String fmt(double x) { return String.format("%.1f", x); }
}
