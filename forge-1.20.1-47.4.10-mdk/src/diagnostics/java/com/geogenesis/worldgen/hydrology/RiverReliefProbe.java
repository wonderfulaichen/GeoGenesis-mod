package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【河床 vs 两岸地形】审计（2026-10）—— 直击用户判据：
 * <b>"河道完全脱离地形，硬造了一条比原地形高的河道"</b>。
 *
 * <h2>用户定义</h2>
 * <ul>
 *   <li><b>湖泊 = 填水</b>：水面高于盆底正常 ⇒ <b>本探针排除湖列</b>（用 carver 分支判定）；</li>
 *   <li><b>河流 = 在原地形上切出河道</b> ⇒ 河床应<b>低于</b>两岸地形。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <pre>  河列（carver 走河分支）的放置高度 cell.height
 *      vs  8 邻【非水】列的 cell.height 均值
 *  若 河床 > 两岸均值 + 0.5  ⇒ 河道凸起（用户截图形态）</pre>
 *
 * <p>两侧都是【最终放置】高度 ⇒ 同一侵蚀基准，可比。湖列由 carver 的 lakePlan 排除。</p>
 *
 * <pre>{@code gradlew runRiverReliefProbe [-PprobeArgs="seed blockX blockZ radius"]}</pre>
 */
public final class RiverReliefProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -313;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -165;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 48;

        System.out.printf("=== RiverReliefProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        System.out.printf("  skeletonRouting=%s  RIVER_CUT_NONNEGATIVE=%s%n",
                RiverLineNetwork.flowSkeletonRouting, GeoGenesisTerrain.RIVER_CUT_NONNEGATIVE);
        System.out.println("  判据：河床 > 两岸地形均值 + 0.5 ⇒ 河道凸起");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        int riverCols = 0, raised = 0, raised2 = 0;
        double worst = 0, sum = 0;
        int shown = 0;

        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || !(c.riverSurfaceY > c.height)) continue;
                double orig = gen.sample(x / hs, z / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, x, z, orig, hs);
                if (col == null || col.lakePlan()) continue;      // ★ 排除湖列（湖是填水）
                riverCols++;
                // 8 邻非水列（最终放置高度）
                double sumDry = 0; int nDry = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Cell nb = cellAt(gt, x + dx, z + dz);
                        if (nb.riverType != 0 && nb.riverSurfaceY > nb.height) continue;
                        sumDry += nb.height; nDry++;
                    }
                }
                if (nDry < 3) continue;
                double meanDry = sumDry / nDry;
                double rel = c.height - meanDry;
                if (rel > 0.5) {
                    raised++;
                    sum += rel;
                    if (rel > 2.0) raised2++;
                    if (rel > worst) worst = rel;
                    if (shown < 12) {
                        System.out.printf("    (%d,%d) 河床=%.2f 两岸均值=%.2f ⇒ 凸起=%.2f"
                                        + " 水面=%.2f carved=%.2f orig=%.2f%n",
                                x, z, c.height, meanDry, rel, c.riverSurfaceY,
                                col.carvedGroundY(), col.originalGroundY());
                        shown++;
                    }
                }
            }
        }
        System.out.printf("%n河列（走河分支、非湖） = %d%n", riverCols);
        System.out.printf("  ★ 河床高于两岸均值 >0.5 = %d (%.1f%%)%n",
                raised, 100.0 * raised / Math.max(1, riverCols));
        System.out.printf("  ★ 其中 >2 块            = %d (%.1f%%)%n",
                raised2, 100.0 * raised2 / Math.max(1, riverCols));
        System.out.printf("    平均凸起 = %.2f 块，最大 = %.2f 块%n",
                raised == 0 ? 0 : sum / raised, worst);
        System.out.printf("%nstatus=%s riverCols=%d raised=%d(%.1f%%) raised>2=%d worst=%.2f%n",
                raised > 0 ? "DEFECT" : "OK", riverCols, raised,
                100.0 * raised / Math.max(1, riverCols), raised2, worst);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverReliefProbe() { }
}
