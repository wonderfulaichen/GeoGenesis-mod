package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 微自适应选线探针（2026-09-08，用户："让河流局部路线与河道生成匹配侵蚀后的地形"）。
 *
 * <p>同 seed 构建【关】（delta=null，基线）与【开】（delta=erosionDeltaE，微自适应）
 * 两个 RiverLineNetwork，对同区域取河线节点，统计：</p>
 * <ol>
 * <li>河数/节点数：确认拓扑基本稳定（没有整条河被挤没/暴增）；</li>
 * <li>节点位置偏移（wu）分布：微自适应预期是"局部"微调，大偏移占比应低；</li>
 * <li>引导方向 sanity：偏移量与节点处 |delta| 的相关（侵蚀强的地方改动应更大）。</li>
 * </ol>
 *
 * <p>用法：gradlew runAdaptiveRoutingProbe [-PprobeArgs="seed"]</p>
 */
public final class AdaptiveRoutingProbe {
    private AdaptiveRoutingProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;

        // 关：走 HydrologyExperimentEngine（探针无 Forge config → 微自适应自动关闭 = 基线）
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator g1 = new CellGenerator(tp, tp.minY(), tp.maxY());
        g1.seed(seed);
        HydrologyExperimentEngine off = new HydrologyExperimentEngine(g1, seed);

        // 开：直接构造（绕过 config），delta = erosionDeltaE（e 单位 tile 增量）
        CellGenerator g2 = new CellGenerator(tp, tp.minY(), tp.maxY());
        g2.seed(seed);
        RiverLineNetwork on = new RiverLineNetwork(
                g2::terrainEQuick,
                (wx, wz) -> g2.sample(wx, wz).height,
                g2::erosionDeltaE, 1.0,
                g2.heightCurve(), seed, g2.params().horizontalScale(),
                RiverLineParams.defaults());

        List<double[]> nodesOff = new ArrayList<>();
        List<double[]> nodesOn = new ArrayList<>();
        int riversOff = 0, riversOn = 0;
        for (int rx = 0; rx < 2; rx++) {
            for (int rz = 0; rz < 2; rz++) {
                RiverLineRegion ro = off.network().region(rx, rz);
                RiverLineRegion rn = on.region(rx, rz);
                riversOff += ro.rivers.size();
                riversOn += rn.rivers.size();
                collect(ro, nodesOff);
                collect(rn, nodesOn);
            }
        }

        System.out.println("=== 微自适应选线探针 seed=" + seed + " ===");
        System.out.println("关: 河=" + riversOff + " 节点=" + nodesOff.size()
                + "  开: 河=" + riversOn + " 节点=" + nodesOn.size());

        if (!nodesOff.isEmpty() && !nodesOn.isEmpty()) {
            double sum = 0, sumSq = 0, max = 0;
            int moved = 0;
            double corrSum = 0, corrA = 0, corrB = 0;
            int n = nodesOn.size();
            for (double[] p : nodesOn) {
                double best = Double.MAX_VALUE;
                for (double[] q : nodesOff) {
                    double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                    if (d < best) best = d;
                }
                sum += best; sumSq += best * best;
                if (best > max) max = best;
                if (best > 0.5) moved++;
                // 相关性：偏移 vs |delta|（侵蚀强的地方改动应更大）
                double ad = Math.abs(g2.erosionDeltaE(p[0], p[1]));
                corrSum += best * ad; corrA += best * best; corrB += ad * ad;
            }
            double meanD = sum / n;
            double meanA = Math.sqrt(corrA / n), meanB = Math.sqrt(corrB / n);
            double corr = (corrSum / n) / (meanA * meanB + 1e-9);
            System.out.printf("偏移(wu): 均值=%.2f RMS=%.2f 最大=%.2f | >0.5wu 占比=%.1f%% | 偏移·|δ| 相关=%.3f%n",
                    meanD, Math.sqrt(sumSq / n), max, 100.0 * moved / n, corr);
        }
        System.out.println("status=OK");
    }

    private static void collect(RiverLineRegion r, List<double[]> out) {
        for (RiverLineRegion.RiverPolyline river : r.rivers) {
            for (var node : river.nodes) out.add(new double[]{node.x(), node.z()});
        }
    }
}
