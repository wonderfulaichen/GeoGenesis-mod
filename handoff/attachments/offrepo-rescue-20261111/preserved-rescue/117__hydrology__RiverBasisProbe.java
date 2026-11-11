package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【河流跟哪种地形走】判定（2026-10）—— 用户怀疑："河用的地形是未被侵蚀的地形"。
 *
 * <h2>三种地形基准</h2>
 * <ol>
 *   <li><b>无侵蚀</b> {@code gen.sample()}         —— 河网建网用的是这个；</li>
 *   <li><b>含侵蚀</b> {@code gen.sampleWu()}        —— 玩家真正看到的地面；</li>
 *   <li><b>最终放置</b> {@code cell.height}         —— 侵蚀 + 河道雕刻之后。</li>
 * </ol>
 *
 * <h2>判据</h2>
 * <p>河流应在【含侵蚀地面】的谷底。若河跟着【无侵蚀地面】走，而侵蚀把这里削低了
 * 几十块 ⇒ 河就"浮"在真实地面之上（用户看到的"高架"）。</p>
 *
 * <pre>  对每个河列：比较 河床(放置) 与 无侵蚀地形 / 含侵蚀地形 的局部最低值
 *      若 河床 ≈ 无侵蚀谷底（而明显高于 含侵蚀谷底）⇒ 河跟错了地形</pre>
 *
 * <pre>{@code gradlew runRiverBasisProbe [-PprobeArgs="seed bx bz radius"]}</pre>
 */
public final class RiverBasisProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 200;

        String sp = System.getProperty("geogenesis.skeletonRouting");
        if (sp != null) RiverLineNetwork.flowSkeletonRouting = Boolean.parseBoolean(sp);
        System.out.printf("=== RiverBasisProbe seed=%d centre=(%d,%d) r=%d skeleton=%s ===%n",
                seed, bx, bz, radius, RiverLineNetwork.flowSkeletonRouting);
        System.out.println("  判据：河床更接近【无侵蚀谷底】还是【含侵蚀谷底】");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();

        int river = 0;
        int followsRaw = 0, followsEroded = 0, between = 0;
        double sumRawGap = 0, sumErodedGap = 0, sumErosionDrop = 0;
        int shown = 0;

        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || c.isLake) continue;
                river++;
                double wx = x / hs, wz = z / hs;
                // ±4 格窗口内两种基准的局部最低
                double rawMin = Double.MAX_VALUE, eroMin = Double.MAX_VALUE;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        rawMin = Math.min(rawMin, gen.sample(wx + dx, wz + dz).height);
                        eroMin = Math.min(eroMin, gen.sampleWu(wx + dx, wz + dz).height);
                    }
                }
                double rawGap = c.height - rawMin;      // 河床高出"无侵蚀谷底"多少
                double eroGap = c.height - eroMin;      // 河床高出"含侵蚀谷底"多少
                sumRawGap += rawGap;
                sumErodedGap += eroGap;
                sumErosionDrop += (rawMin - eroMin);
                if (rawGap < eroGap) followsRaw++;
                else if (eroGap < rawGap) followsEroded++;
                else between++;

                if (shown < 8 && (rawGap - eroGap) > 3.0) {
                    System.out.printf("    (%d,%d) 河床=%.2f 无侵蚀谷底=%.2f(gap%+.2f)"
                                    + " 含侵蚀谷底=%.2f(gap%+.2f) 侵蚀削了=%.2f%n",
                            x, z, c.height, rawMin, rawGap, eroMin, eroGap, rawMin - eroMin);
                    shown++;
                }
            }
        }
        System.out.printf("%n河列 = %d%n", river);
        System.out.printf("  · 更接近【无侵蚀】地形 = %d (%.1f%%)%n",
                followsRaw, 100.0 * followsRaw / Math.max(1, river));
        System.out.printf("  · 更接近【含侵蚀】地形 = %d (%.1f%%)   <== 期望这个高%n",
                followsEroded, 100.0 * followsEroded / Math.max(1, river));
        System.out.printf("    平均高出无侵蚀谷底 %.2f 块 / 高出含侵蚀谷底 %.2f 块%n",
                sumRawGap / Math.max(1, river), sumErodedGap / Math.max(1, river));
        System.out.printf("  · 该处侵蚀平均削了 %.2f 块%n", sumErosionDrop / Math.max(1, river));
        System.out.printf("%nstatus=followsRaw=%d(%.1f%%) followsEroded=%d(%.1f%%)%n",
                followsRaw, 100.0 * followsRaw / Math.max(1, river),
                followsEroded, 100.0 * followsEroded / Math.max(1, river));
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverBasisProbe() { }
}