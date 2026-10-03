package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 深挖审计（2026-10）—— 找"被挖掉几十块的列"，并判定是【河分支】还是【湖分支】干的。
 *
 * <p>用户定义：河=原地形上切出河道（下挖幅度应有限）；湖=填水（不挖）。
 * 若某列被挖掉几十块 ⇒ 既不是正常河道、也不是湖 ⇒ 机制出错。</p>
 *
 * <pre>{@code gradlew runDeepCarveProbe [-PprobeArgs="seed blockX blockZ radius [minDrop]"]}</pre>
 */
public final class DeepCarveProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -383;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -68;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 96;
        double minDrop = args.length > 4 ? Double.parseDouble(args[4]) : 8.0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        System.out.printf("=== DeepCarveProbe seed=%d centre=(%d,%d) r=%d minDrop=%.1f ===%n",
                seed, bx, bz, radius, minDrop);
        System.out.printf("  skeletonRouting(生效) = %s%n", RiverLineNetwork.flowSkeletonRouting);

        int deep = 0, deepRiver = 0, deepLake = 0, plantRaised = 0;
        double worst = 0;
        int shown = 0;
        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                double orig = gen.sample(x / hs, z / hs).height;
                double drop = orig - c.height;
                if (drop < minDrop) continue;
                deep++;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, x, z, orig, hs);
                boolean lake = col != null && col.lakePlan();
                if (lake) deepLake++; else deepRiver++;
                if (drop > worst) worst = drop;
                if (shown < 14) {
                    System.out.printf("    (%d,%d) 未侵蚀=%.2f 放置=%.2f ⇒ 挖深=%.2f"
                                    + "  水面=%.2f rt=%d isLake=%b  分支=%s carved=%.2f origC=%.2f%n",
                            x, z, orig, c.height, drop, c.riverSurfaceY, c.riverType, c.isLake,
                            lake ? "湖(不挖)" : "河(挖)",
                            col == null ? Double.NaN : col.carvedGroundY(),
                            col == null ? Double.NaN : col.originalGroundY());
                    shown++;
                }
            }
        }
        System.out.printf("%n挖深 >%.1f 块的列 = %d%n", minDrop, deep);
        System.out.printf("  · 走【湖】分支（本该不挖）  = %d  ← 湖分支不该挖地%n", deepLake);
        System.out.printf("  · 走【河】分支             = %d%n", deepRiver);
        System.out.printf("  最大挖深 = %.2f 块%n", worst);
        System.out.printf("%nstatus=%s deep=%d lake=%d river=%d worst=%.2f%n",
                deepLake > 0 ? "DEFECT(湖分支挖地)" : (deep > 0 ? "INFO" : "OK"),
                deep, deepLake, deepRiver, worst);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private DeepCarveProbe() { }
}
