package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【河流是否走在谷底】判定（2026-10）—— 用户判据："河流路线不对"（图上河贴山脊）。
 *
 * <h2>判据</h2>
 * <p>对每个【河列】（riverType!=0 且 !isLake），比较其地表高度与 8 邻的地表高度：</p>
 * <pre>  河列高度 vs 邻域【最高】地面   ⇒ 河低于两侧 = 在谷底 ✔
 *                            vs 邻域【最低】地面   ⇒ 河高于两侧 = 在脊上 ✘</pre>
 * <p>输出"在谷底"与"在脊上"的列数与占比 ⇒ 客观量化"河贴脊"。</p>
 *
 * <pre>{@code gradlew runRiverProfileProbe [-PprobeArgs="seed blockX blockZ radius"]}</pre>
 */
public final class RiverProfileProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 200;

        System.out.printf("=== RiverProfileProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        // ★ 2026-10：必须在【构造任何 engine 之前】设开关 —— GeoGenesisTerrain 构造时
        //   会建一个 engine，那一刻会读配置/系统属性并缓存流路线值。
        //   （探针里 engine 构造顺序不定 ⇒ 之前测到的是旧路径）
        String sp = System.getProperty("geogenesis.skeletonRouting");
        if (sp != null) {
            RiverLineNetwork.flowSkeletonRouting = Boolean.parseBoolean(sp);
            System.out.printf("  [强制] skeletonRouting=%s%n", RiverLineNetwork.flowSkeletonRouting);
        }
        System.out.printf("  skeletonRouting=%s  CELL_BLOCKS=%d%n",
                RiverLineNetwork.flowSkeletonRouting, RiverLineNetwork.SKELETON_CELL_BLOCKS);
        System.out.println("  判据：河列高度 < 邻域最高地面 => 在谷底；> 邻域最低地面 => 在脊上");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int river = 0, valley = 0, ridge = 0, mid = 0;
        double ridgeOver = 0;
        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || c.isLake) continue;
                river++;
                double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
                boolean any = false;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Cell n = cellAt(gt, x + dx, z + dz);
                        hMin = Math.min(hMin, n.height);
                        hMax = Math.max(hMax, n.height);
                        any = true;
                    }
                }
                if (!any) continue;
                if (c.height < hMax) valley++;
                else if (c.height > hMin) { ridge++; ridgeOver += c.height - hMin; }
                else mid++;
            }
        }
        System.out.printf("%n河列 = %d%n", river);
        System.out.printf("  · 在【谷底】(低于全部邻域) = %d (%.1f%%)%n",
                valley, 100.0 * valley / Math.max(1, river));
        System.out.printf("  · 在【山脊】(高于全部邻域) = %d (%.1f%%)   <== 河贴脊量%n",
                ridge, 100.0 * ridge / Math.max(1, river));
        System.out.printf("    平均高出邻域最低地面 %.2f 块%n",
                ridge == 0 ? 0 : ridgeOver / ridge);
        System.out.printf("  · 居中                     = %d (%.1f%%)%n",
                mid, 100.0 * mid / Math.max(1, river));
        System.out.printf("%nstatus=river=%d valley=%.1f%% ridge=%.1f%%%n",
                river, 100.0 * valley / Math.max(1, river), 100.0 * ridge / Math.max(1, river));
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverProfileProbe() { }
}