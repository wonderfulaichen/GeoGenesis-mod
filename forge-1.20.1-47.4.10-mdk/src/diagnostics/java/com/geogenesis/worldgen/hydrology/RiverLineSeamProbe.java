package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨 region 接缝缺口量化探针：验证"河网按 640wu 瓦片生成、边界截断"是否在大
 * 陆上产生可见断河（缺口）。
 *
 * <p>判定（按"河"而非按"节点"，避免沿缝并行小河被重复计数）：</p>
 * <ol>
 *   <li>一条河若其<b>下游端（tail）</b>在 {@code APPROACH_BAND} 内逼近某接缝 → 视为"逼近接缝"；</li>
 *   <li>若该侧 tail 在对侧 region 找到<b>源点（head）</b>在 {@code CROSS_TOL} 内 → 视为跨缝衔接（连续）；</li>
 *   <li>否则为缺口：按 tail 处地形 e 判定陆地/海洋（e>oceanE → 陆地，可见缺口；否则海岸，正常终止）；
 *       缺口长度 = tail 到对侧最近河节点的有限 2D 距离（对侧无河也不会溢出 Infinity）。</li>
 * </ol>
 *
 * <p>输出：接缝总数、陆地缺口数、海洋缺口数、缺口长度分布、含陆地缺口的接缝清单、
 * 以及所有接缝长度中落在陆地上的占比。</p>
 *
 * <p>用法：{@code gradlew runRiverLineSeamProbe -PprobeArgs="12345 2"}</p>
 */
public final class RiverLineSeamProbe {
    private RiverLineSeamProbe() { }

    /** 下游端距接缝多近算"逼近接缝"（wu）。覆盖到 margin 外（河可追过缝）。 */
    private static final double APPROACH_BAND = 300.0;
    /** 对侧源点（head）距接缝多近算候选衔接（head 距缝 ≥ borderDist=96）。 */
    private static final double CONT_HEAD_BAND = 300.0;
    /** 跨缝 tail→head 2D 距离 ≤ 此值视为连续（远大于 meander 偏移，不会误杀真连续）。 */
    private static final double CROSS_TOL = 300.0;
    /** 缺口长度上限（对侧完全无河时的有限兜底）。 */
    private static final double HARD_BREAK = 2000.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(tp, tp.minY(), tp.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineNetwork net = engine.network();
        RiverLineParams P = RiverLineParams.defaults();
        double R = P.regionSize();
        double oceanE = P.oceanE();

        System.out.println("=== RiverLine cross-region seam gap diagnostics ===");
        System.out.println("seed=" + seed + " regionRadius=" + n + " regionSize=" + R
                + "wu approachBand=" + APPROACH_BAND + " crossTol=" + CROSS_TOL
                + " oceanE=" + oceanE);

        RiverLineRegion[][] grid = new RiverLineRegion[2 * n + 1][2 * n + 1];
        for (int rz = -n; rz <= n; rz++)
            for (int rx = -n; rx <= n; rx++)
                grid[rx + n][rz + n] = net.region(rx, rz);

        int seamCount = 0, gapTotal = 0, gapLand = 0, gapOcean = 0;
        int hardBreak = 0;   // 对侧完全无河衔接的硬断
        List<Double> landGapLen = new ArrayList<>();
        List<String> landSeams = new ArrayList<>();
        double seamLenLand = 0.0, seamLenTotal = 0.0;
        double span = (2 * n + 1) * R;

        // 水平接缝 (rx,rz)-(rx+1,rz)，接缝 x = (rx+1)*R
        for (int rz = -n; rz <= n; rz++) {
            for (int rx = -n; rx < n; rx++) {
                RiverLineRegion A = grid[rx + n][rz + n];
                RiverLineRegion B = grid[rx + 1 + n][rz + n];
                double S = (rx + 1) * R;
                int[] r = analyzeSeam(A, B, true, S, terrain, oceanE);
                seamCount++;
                gapTotal += r[0]; gapLand += r[1]; gapOcean += r[2]; hardBreak += r[3];
                landGapLen.addAll(lastLandGaps);
                if (r[1] > 0) landSeams.add(String.format(
                        "  H seam (%d,%d)|(%d,%d) x=%.0f landGaps=%d",
                        rx, rz, rx + 1, rz, S, r[1]));
                seamLenLand += seamLandFraction(terrain, oceanE, true, S, -n * R, (n + 1) * R) * span;
                seamLenTotal += span;
            }
        }
        // 垂直接缝 (rx,rz)-(rx,rz+1)，接缝 z = (rz+1)*R
        for (int rx = -n; rx <= n; rx++) {
            for (int rz = -n; rz < n; rz++) {
                RiverLineRegion A = grid[rx + n][rz + n];
                RiverLineRegion B = grid[rx + n][rz + 1 + n];
                double S = (rz + 1) * R;
                int[] r = analyzeSeam(A, B, false, S, terrain, oceanE);
                seamCount++;
                gapTotal += r[0]; gapLand += r[1]; gapOcean += r[2]; hardBreak += r[3];
                landGapLen.addAll(lastLandGaps);
                if (r[1] > 0) landSeams.add(String.format(
                        "  V seam (%d,%d)|(%d,%d) z=%.0f landGaps=%d",
                        rx, rz, rx, rz + 1, S, r[1]));
                seamLenLand += seamLandFraction(terrain, oceanE, false, S, -n * R, (n + 1) * R) * span;
                seamLenTotal += span;
            }
        }

        System.out.println();
        System.out.println("-- seam gap summary --");
        System.out.println("seams analyzed = " + seamCount);
        System.out.println("total gaps = " + gapTotal + "  onLand(visible) = " + gapLand
                + "  onOcean(coast, ok) = " + gapOcean);
        System.out.println("  of which hard breaks (no continuation in neighbor) = " + hardBreak);
        System.out.println("seam length on land = " + pct(seamLenLand / seamLenTotal)
                + "  (of all seam length, how much lies on continent)");
        if (!landGapLen.isEmpty()) {
            Stats st = new Stats(landGapLen);
            System.out.println("land gap length (wu): " + st.render());
        } else {
            System.out.println("land gap length: (none)");
        }

        System.out.println();
        System.out.println("-- seams with land gaps (" + landSeams.size() + "/" + seamCount + ") --");
        for (String s : landSeams) System.out.println(s);

        System.out.println();
        System.out.println("(判读：onLand 远大于 0 且缺口长度 ≫ 0 → 大陆上确有断河，");
        System.out.println(" 值得做『跨 region 连续河』；onLand≈0 → 缺口全在海岸，无需处理)");
    }

    /** 上一次 analyzeSeam 收集的陆地缺口长度（wu，有限）。 */
    private static List<Double> lastLandGaps = new ArrayList<>();

    /** 分析一条接缝：返回 [gapTotal, gapLand, gapOcean, hardBreaks]。 */
    private static int[] analyzeSeam(RiverLineRegion A, RiverLineRegion B,
                                     boolean horizontal, double S,
                                     CellGenerator terrain, double oceanE) {
        lastLandGaps.clear();
        // 各侧：逼近接缝的下游端(tail)、可作衔接的源点(head)、全部节点（供有限距离）
        List<double[]> aTail = approachingTails(A, horizontal, S);
        List<double[]> bTail = approachingTails(B, horizontal, S);
        List<double[]> aHead = nearHeads(A, horizontal, S);
        List<double[]> bHead = nearHeads(B, horizontal, S);
        List<double[]> aAll = allNodes(A);
        List<double[]> bAll = allNodes(B);

        int gapTotal = 0, gapLand = 0, gapOcean = 0, hard = 0;
        for (double[] t : aTail) {
            if (!matched(t, bHead)) {
                gapTotal++;
                boolean land = terrain.terrainEQuick(t[0], t[1]) > oceanE;
                double d = bAll.isEmpty() ? HARD_BREAK : minDistTo(t, bAll);
                if (bAll.isEmpty()) hard++;
                if (land) { gapLand++; lastLandGaps.add(d); }
                else gapOcean++;
            }
        }
        for (double[] t : bTail) {
            if (!matched(t, aHead)) {
                gapTotal++;
                boolean land = terrain.terrainEQuick(t[0], t[1]) > oceanE;
                double d = aAll.isEmpty() ? HARD_BREAK : minDistTo(t, aAll);
                if (aAll.isEmpty()) hard++;
                if (land) { gapLand++; lastLandGaps.add(d); }
                else gapOcean++;
            }
        }
        return new int[]{gapTotal, gapLand, gapOcean, hard};
    }

    /** 下游端（tail）在 APPROACH_BAND 内逼近接缝 S 的河，返回其世界坐标。 */
    private static List<double[]> approachingTails(RiverLineRegion r, boolean horizontal, double S) {
        List<double[]> out = new ArrayList<>();
        for (RiverLineRegion.RiverPolyline pl : r.rivers) {
            if (pl.nodes.length == 0) continue;
            MidpointDisplacement.Node tail = pl.nodes[pl.nodes.length - 1];
            double c = horizontal ? tail.x() : tail.z();
            if (Math.abs(c - S) <= APPROACH_BAND) out.add(new double[]{tail.x(), tail.z()});
        }
        return out;
    }

    /** 源点（head）在 CONT_HEAD_BAND 内临近接缝 S 的河，返回其世界坐标。 */
    private static List<double[]> nearHeads(RiverLineRegion r, boolean horizontal, double S) {
        List<double[]> out = new ArrayList<>();
        for (RiverLineRegion.RiverPolyline pl : r.rivers) {
            if (pl.nodes.length == 0) continue;
            MidpointDisplacement.Node head = pl.nodes[0];
            double c = horizontal ? head.x() : head.z();
            if (Math.abs(c - S) <= CONT_HEAD_BAND) out.add(new double[]{head.x(), head.z()});
        }
        return out;
    }

    private static List<double[]> allNodes(RiverLineRegion r) {
        List<double[]> out = new ArrayList<>();
        for (RiverLineRegion.RiverPolyline pl : r.rivers)
            for (MidpointDisplacement.Node nd : pl.nodes)
                out.add(new double[]{nd.x(), nd.z()});
        return out;
    }

    /** t 是否与候选 head 列表中有任一在 CROSS_TOL 内（2D）。 */
    private static boolean matched(double[] t, List<double[]> heads) {
        for (double[] h : heads) {
            if (Math.hypot(t[0] - h[0], t[1] - h[1]) <= CROSS_TOL) return true;
        }
        return false;
    }

    private static double minDistTo(double[] p, List<double[]> others) {
        double best = Double.MAX_VALUE;
        for (double[] q : others) {
            double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
            if (d < best) best = d;
        }
        return best == Double.MAX_VALUE ? HARD_BREAK : best;
    }

    /** 沿接缝采样地形，返回接缝落在陆地上的长度占比。 */
    private static double seamLandFraction(CellGenerator terrain, double oceanE,
                                            boolean horizontal, double S, double lo, double hi) {
        double step = 32.0;
        int land = 0, total = 0;
        for (double t = lo; t <= hi; t += step) {
            double e = horizontal ? terrain.terrainEQuick(S, t) : terrain.terrainEQuick(t, S);
            total++;
            if (e > oceanE) land++;
        }
        return total == 0 ? 0.0 : (double) land / total;
    }

    private static String pct(double r) { return String.format("%6.2f%%", r * 100.0); }

    private static String fmt(double d) { return String.format("%.3f", d); }

    /** 简单描述统计（min/mean/median/max/标准差）。 */
    private static final class Stats {
        final double min, max, mean, median, sd;
        final int n;
        Stats(List<Double> v) {
            if (v.isEmpty()) { min = max = mean = median = sd = Double.NaN; n = 0; return; }
            List<Double> s = new ArrayList<>(v);
            s.sort(Double::compareTo);
            n = s.size();
            min = s.get(0);
            max = s.get(n - 1);
            double sum = 0.0;
            for (double d : s) sum += d;
            mean = sum / n;
            median = s.get(n / 2);
            double var = 0.0;
            for (double d : s) var += (d - mean) * (d - mean);
            sd = Math.sqrt(var / n);
        }
        String render() {
            if (n == 0) return "(empty)";
            return "min=" + fmt(min) + "  mean=" + fmt(mean) + "  median=" + fmt(median)
                    + "  max=" + fmt(max) + "  sd=" + fmt(sd) + "  n=" + n;
        }
    }
}
