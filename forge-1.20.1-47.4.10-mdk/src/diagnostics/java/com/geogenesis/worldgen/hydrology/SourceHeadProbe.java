package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 河流源头位置探针（2026-08-31）：用户报告"河流源头大部分生成在山顶"。
 *
 * <p>对每条河的首节点（源头），统计其周围半径 R 内地形高程比它【更高】的比例：
 * 山顶/山脊 → 几乎无更高点（fracHigher≈0）；山坳/谷头/坡面汇流处 → 大部分更高。
 * 另报源头高程在 region 内的百分位（源头若集中在 top 百分位即为"往山顶堆"）。</p>
 */
public final class SourceHeadProbe {

    private SourceHeadProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        double R = args.length > 2 ? Double.parseDouble(args[2]) : 60.0;   // 判定半径(wu)
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // region 内全部地形高程（求百分位用）
        List<Double> allH = new ArrayList<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                double size = RiverLineParams.defaults().regionSize();
                double x0 = rx * size, z0 = rz * size;
                for (int j = 0; j <= 24; j++) {
                    for (int i = 0; i <= 24; i++) {
                        allH.add(terrain.sample(x0 + size * i / 24.0, z0 + size * j / 24.0).height);
                    }
                }
            }
        }
        allH.sort(Double::compare);
        double seaLevel = terrain.heightCurve().seaLevelY();

        System.out.println("=== SourceHeadProbe ===");
        System.out.printf("seed=%d 判定半径=%.0fwu 海平面=%.1f%n", seed, R, seaLevel);

        int n = 0, crest = 0, ridge = 0, slope = 0, valley = 0, submerged = 0;
        List<String> worst = new ArrayList<>();
        double sumHigher = 0, sumPct = 0;
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    if (river.nodes.length == 0) continue;
                    double wx = river.nodes[0].x(), wz = river.nodes[0].z();
                    double h0 = terrain.sample(wx, wz).height;
                    // 周围 R 半径内采样，统计更高点比例
                    int tot = 0, higher = 0;
                    for (int dj = -4; dj <= 4; dj++) {
                        for (int di = -4; di <= 4; di++) {
                            if (di == 0 && dj == 0) continue;
                            double px = wx + di * R / 4.0, pz = wz + dj * R / 4.0;
                            if (Math.hypot(di * R / 4.0, dj * R / 4.0) > R) continue;
                            double hh = terrain.sample(px, pz).height;
                            tot++;
                            if (hh > h0 + 0.5) higher++;
                        }
                    }
                    if (tot == 0) continue;
                    double fracHigher = (double) higher / tot;
                    double pct = percentile(allH, h0);
                    n++;
                    sumHigher += fracHigher;
                    sumPct += pct;
                    if (h0 < seaLevel) submerged++;
                    if (fracHigher <= 0.05) { crest++; }
                    else if (fracHigher <= 0.25) { ridge++; }
                    else if (fracHigher <= 0.60) { slope++; }
                    else { valley++; }
                    if (worst.size() < 12 || (fracHigher <= 0.05 && isWorseThan(worst))) {
                        worst.add(String.format("    源头(%d,%d) wu(%.0f,%.0f) 高程=%.1f "
                                        + "更高点=%.0f%% 高程百分位=%.0f 块坐标=(%d,%d)",
                                rx, rz, wx, wz, h0, fracHigher * 100, pct,
                                (int) Math.floor(wx * hs), (int) Math.floor(wz * hs)));
                    }
                }
            }
        }
        if (n == 0) { System.out.println("未找到河流"); return; }
        System.out.printf("河流总数=%d  源头平均更高点比例=%.0f%%  平均高程百分位=%.0f%%%n",
                n, sumHigher / n * 100, sumPct / n);
        System.out.println("按源头地形位置分类（周围 R 内更高点比例）：");
        System.out.printf("  山顶/峰顶  (<=5%%)   = %d  (%.0f%%)  ← 用户抱怨的形态%n",
                crest, crest * 100.0 / n);
        System.out.printf("  山脊/鞍部  (5~25%%)  = %d  (%.0f%%)%n", ridge, ridge * 100.0 / n);
        System.out.printf("  坡面       (25~60%%) = %d  (%.0f%%)%n", slope, slope * 100.0 / n);
        System.out.printf("  谷头/洼地  (>60%%)   = %d  (%.0f%%)  ← 自然河源应有的形态%n",
                valley, valley * 100.0 / n);
        System.out.printf("  源头在海平面以下     = %d%n", submerged);
        System.out.println("最典型（更高点最少）的源头样例：");
        for (String line : worst) System.out.println(line);
        System.out.println(crest * 2 > n ? "status=FAIL（过半源头在山顶）" : "status=PASS");
    }

    private static double percentile(List<Double> sorted, double v) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid) < v) lo = mid + 1; else hi = mid;
        }
        return lo * 100.0 / sorted.size();
    }

    private static boolean isWorseThan(List<String> worst) {
        return worst.size() < 12;
    }
}
