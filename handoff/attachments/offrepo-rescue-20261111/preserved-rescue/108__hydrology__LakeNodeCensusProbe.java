package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 湖节点普查探针（2026-09-19）—— <b>T2.4（湖节点合并）的先量后改</b>。
 *
 * <h2>背景</h2>
 * <p>{@code RiverLineNetwork.extractLakes} 为<b>每个洼地</b>建一个 {@code LakeNode}；
 * 每个节点后续要跑一次 {@code computeFlood}（BFS + 建 O(1) 索引）。
 * <b>实测：湖域 14338 列中仅 7664 列有水（46.5% 空转）</b>
 * ⇒ 怀疑存在大量"极小/相邻/同水位"的湖节点 ⇒ 可合并以省成本。</p>
 *
 * <h2>要回答</h2>
 * <ol>
 *   <li>一个 region 有多少湖节点？洼地格数分布如何（有多少是 1~2 格的"碎湖"）？</li>
 *   <li>相邻且水位接近的节点有多少 ⇒ 合并潜力；</li>
 *   <li>按水位分桶，桶内能合并多少。</li>
 * </ol>
 *
 * <pre>{@code gradlew runLakeNodeCensusProbe [-PprobeArgs="seed rx rz regionCount"]}</pre>
 */
public final class LakeNodeCensusProbe {

    private LakeNodeCensusProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int regionCount = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();

        System.out.printf("=== LakeNodeCensusProbe seed=%d region=(%d,%d) 起 %d×%d ===%n",
                seed, rx0, rz0, regionCount, regionCount);

        List<RiverLineRegion.LakeNode> all = new ArrayList<>();
        for (int dz = 0; dz < regionCount; dz++) {
            for (int dx = 0; dx < regionCount; dx++) {
                RiverLineRegion r = net.region(rx0 + dx, rz0 + dz);
                if (r == null) continue;
                all.addAll(r.lakes);
            }
        }
        System.out.printf("%n[1] 湖节点总数 = %d（%d 个 region）%n",
                all.size(), regionCount * regionCount);
        if (all.isEmpty()) {
            System.out.println("    ⚠ 无湖节点 —— 换区域或加大 regionCount");
            return;
        }

        // [2] 洼地格数分布
        int[] buckets = new int[8];   // 0:1格 1:2格 2:3-4 3:5-8 4:9-16 5:17-32 6:33-64 7:>64
        long totalCells = 0;
        for (RiverLineRegion.LakeNode ln : all) {
            int n = (ln.cellX == null) ? 0 : ln.cellX.length;
            totalCells += n;
            int b;
            if (n <= 1) b = 0;
            else if (n == 2) b = 1;
            else if (n <= 4) b = 2;
            else if (n <= 8) b = 3;
            else if (n <= 16) b = 4;
            else if (n <= 32) b = 5;
            else if (n <= 64) b = 6;
            else b = 7;
            buckets[b]++;
        }
        String[] names = {"1格", "2格", "3-4格", "5-8格", "9-16格", "17-32格", "33-64格", ">64格"};
        System.out.printf("%n[2] 洼地格数分布（总格数 %d）%n", totalCells);
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] == 0) continue;
            System.out.printf("    %-8s 节点 %4d（%5.1f%%）%n",
                    names[i], buckets[i], 100.0 * buckets[i] / all.size());
        }
        long tiny = buckets[0] + buckets[1];
        System.out.printf("    ★ 碎湖（≤2 格）= %d（%.1f%%）%n",
                tiny, 100.0 * tiny / all.size());

        // [3] 水位分布 —— 同一水位（±0.5 块）视为可合并候选
        List<Double> levels = new ArrayList<>();
        for (RiverLineRegion.LakeNode ln : all) levels.add(ln.height);
        java.util.Collections.sort(levels);
        System.out.printf("%n[3] 湖面水位（spill）分布%n");
        System.out.printf("    最小=%.3f  p10=%.3f  p50=%.3f  p90=%.3f  最大=%.3f%n",
                levels.get(0), pctSorted(levels, 0.10), pctSorted(levels, 0.50),
                pctSorted(levels, 0.90), levels.get(levels.size() - 1));

        // [4] 合并潜力：按水位 ±0.5 分桶
        int merged = 0;
        int i = 0;
        double tol = 0.5;
        while (i < levels.size()) {
            int j = i;
            while (j + 1 < levels.size()
                    && levels.get(j + 1) - levels.get(i) <= tol) j++;
            int group = j - i + 1;
            if (group > 1) merged += group - 1;
            i = j + 1;
        }
        System.out.printf("%n[4] 合并潜力（水位差 ≤ %.1f 块视为可合并）%n", tol);
        System.out.printf("    可合并掉的节点 = %d（%.1f%%）%n",
                merged, 100.0 * merged / all.size());
        System.out.printf("    合并后节点数   = %d%n", all.size() - merged);

        System.out.println();
        System.out.println("判读：");
        System.out.println("  · 若【碎湖占比】高 ⇒ 大量的计算花在极小湖上 ⇒ 应先过滤碎湖；");
        System.out.println("  · 若【合并潜力】高 ⇒ 相邻同水位节点多 ⇒ 合并可显著省 BFS 次数；");
        System.out.println("  · 两者都低 ⇒ T2 省成本的思路不成立，应转向其他（如 C3）。");
    }

    private static double pctSorted(List<Double> sorted, double q) {
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
