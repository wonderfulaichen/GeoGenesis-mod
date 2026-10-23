package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 残留 DEAD_END 的【物理成因】分类，而不是只数原因字符串。
 *
 * <p>对每个最终保留的 DEAD_END 终点，直接查同一窗口的 bed 场：
 * ① 8 邻是否真的无严格更低（真局部极小）；② 若有更低邻格，为什么没走
 * （自交守卫 / 自贴近 / 跨界 / 其它）。这能区分"地形就是坑"与"守卫误杀"。</p>
 *
 * <p>只读，不改路由。</p>
 */
public final class DeadEndCauseProbe {

    public static void main(String[] args) {
        System.setProperty("geogenesis.lifecycleDiag", "true");
        System.setProperty("geogenesis.drainageAudit", "true");
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);

        int bx = rx * 512 + 256, bz = rz * 512 + 256;
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        var p = provider.pipeForProbe(bx, bz);
        double[] bed = p.bed();
        if (bed == null) throw new AssertionError("bed missing");

        var audit = provider.drainageForProbe(rx, rz);
        if (audit == null) throw new AssertionError("audit missing");

        int dead = 0, truePit = 0, hasLower = 0, offWindow = 0;
        Map<String, Integer> kinds = new HashMap<>();
        List<String> rows = new ArrayList<>();
        for (var s : audit.segments()) {
            if (s.end() != DeterministicSkeleton.End.DEAD_END) continue;
            dead++;
            int i = (int) Math.round((s.mouth().x() - ox) / step);
            int j = (int) Math.round((s.mouth().z() - oz) / step);
            if (i < 0 || j < 0 || i >= n || j >= n) {
                offWindow++;
                continue;
            }
            int k = i * n + j;
            int lowerCount = 0;
            double lowest = Double.POSITIVE_INFINITY;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                double h = bed[a * n + b];
                if (h < bed[k] - 1e-9) {
                    lowerCount++;
                    lowest = Math.min(lowest, h);
                }
            }
            String kind;
            if (lowerCount == 0) {
                kind = "TRUE_LOCAL_PIT";
                truePit++;
            } else {
                kind = "HAS_LOWER_NEIGHBOUR";
                hasLower++;
            }
            kinds.merge(kind, 1, Integer::sum);
            if (rows.size() < 20) {
                rows.add(String.format("  (%d,%d) bed=%.3f lowerNeighbours=%d lowest=%.3f kind=%s",
                        (int) s.mouth().x(), (int) s.mouth().z(), bed[k], lowerCount,
                        Double.isFinite(lowest) ? lowest : Double.NaN, kind));
            }
        }
        System.out.println("owner=" + rx + "," + rz + " retainedDEAD_END=" + dead);
        System.out.println("  TRUE_LOCAL_PIT=" + truePit
                + "  HAS_LOWER_NEIGHBOUR=" + hasLower
                + "  outsideWindow=" + offWindow);
        System.out.println("  kinds=" + kinds);
        rows.forEach(System.out::println);
        System.out.println("VERDICT: "
                + (hasLower > 0
                ? "存在「有更低地形却没走」的终点 ⇒ 守卫/跨区问题，不是地形必然"
                : "全部为真实局部极小 ⇒ 属洼地/盆问题，需盆接口而非放宽守卫"));
    }
}
