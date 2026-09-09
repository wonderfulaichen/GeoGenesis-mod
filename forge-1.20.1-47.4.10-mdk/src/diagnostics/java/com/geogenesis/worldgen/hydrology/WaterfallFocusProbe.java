package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 聚焦单条瀑布河的诊断（2026-09-09）。用户截图坐标 (-169,-383)，seed 9139912035078620160。
 * 找最近河 → dump 全节点表（surfaceY/fallDrop/terrainY）→ 在含跌水的节点附近沿河两侧 dump 岸坡剖面。
 */
public final class WaterfallFocusProbe {

    private WaterfallFocusProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -169;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -383;
        double hs = 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);

        double wuX = bx / hs, wuZ = bz / hs;
        int rx = (int) Math.floorDiv((int) Math.floor(wuX), 640);
        int rz = (int) Math.floorDiv((int) Math.floor(wuZ), 640);
        System.out.println("=== WaterfallFocusProbe focus=(" + bx + "," + bz + ") wu=("
                + wuX + "," + wuZ + ") region=" + rx + "," + rz + " ===");

        // 找最近河折线
        RiverLineRegion.RiverPolyline best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (int drz = -1; drz <= 1; drz++) for (int drx = -1; drx <= 1; drx++) {
            RiverLineRegion reg = engine.network().region(rx + drx, rz + drz);
            for (RiverLineRegion.RiverPolyline pl : reg.rivers) {
                for (int i = 0; i < pl.nodes.length; i++) {
                    double d2 = (pl.nodes[i].x() - wuX) * (pl.nodes[i].x() - wuX)
                            + (pl.nodes[i].z() - wuZ) * (pl.nodes[i].z() - wuZ);
                    if (d2 < bestD2) { bestD2 = d2; best = pl; }
                }
            }
        }
        if (best == null) { System.out.println("no river near focus"); return; }
        System.out.println("nearest river: " + best.nodes.length + " nodes, minDist2=" + bestD2);

        // dump 节点表（block 坐标）
        for (int i = 0; i < best.nodes.length; i++) {
            boolean hasFall = best.fallDrop[i] > 0.0;
            if (hasFall || i % 3 == 0) {
                System.out.printf("  node[%d] blk=(%d,%d) surf=%.2f fall=%.2f%s%n",
                        i, (int) (best.nodes[i].x() * hs), (int) (best.nodes[i].z() * hs),
                        best.surfaceY[i], best.fallDrop[i], hasFall ? " <-- FALL" : "");
            }
        }

        // 对每个 fall 节点，dump 沿河岸坡剖面（该节点投影 + 上游 -10 ~ +12）
        for (int fi = 1; fi < best.nodes.length; fi++) {
            if (best.fallDrop[fi] <= 0.0) continue;
            int i0 = fi - 1, i1 = fi;
            double[] a = {best.nodes[i0].x(), best.nodes[i0].z()};
            double[] b = {best.nodes[i1].x(), best.nodes[i1].z()};
            double ux = b[0] - a[0], uz = b[1] - a[1];
            double len = Math.hypot(ux, uz);
            if (len < 1e-6) continue;
            ux /= len; uz /= len;
            double px = -uz, pz = ux;
            System.out.printf("FALL node[%d]->[%d] lip=%.1f pool=%.1f drop=%.1f blk=(%d,%d)-(%d,%d)%n",
                    i0, i1, best.surfaceY[i0], best.surfaceY[i1], best.fallDrop[i1],
                    (int) (a[0] * hs), (int) (a[1] * hs), (int) (b[0] * hs), (int) (b[1] * hs));
            // 沿河剖面：中点在落差线（投影 u 在 b），从上游 16 block 到下游 16 block
            for (double offBlock : new double[]{1.0, 3.0, 5.0, 8.0}) {
                for (int side = -1; side <= 1; side += 2) {
                    double off = offBlock / hs;
                    System.out.printf("  off=%.0f side=%s:", offBlock, side > 0 ? "R" : "L");
                    for (int s = -16; s <= 16; s++) {
                        double wx = b[0] + ux * (s / hs) + px * off * side;
                        double wz = b[1] + uz * (s / hs) + pz * off * side;
                        int qx = (int) Math.floor(wx * hs), qz = (int) Math.floor(wz * hs);
                        Cell c = terrain.sampleCell(qx, qz);
                        if (s % 4 == 0) System.out.printf(" %d:%.0f", s, c.height);
                    }
                    System.out.println();
                }
            }
        }
    }
}
