package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** Validates that rivers entering the ocean anchor their water surface at sea level. */
public final class RiverLineMouthProbe {
    private RiverLineMouthProbe() {
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(tp, tp.minY(), tp.maxY());
        terrain.seed(seed);
        RiverLineNetwork network = new RiverLineNetwork(terrain::terrainEQuick,
                (x, z) -> terrain.sample(x, z).height, terrain.heightCurve(), seed);
        double seaLevel = terrain.heightCurve().seaLevelY();
        // 量测两种海平面定义：名义海平面(e=0) vs 海岸 spline 处换算值
        System.out.println("seaLevelY(e=0)=" + seaLevel
                + "  terrain.seaLevel()=" + terrain.seaLevel()
                + "  delta=" + (terrain.seaLevel() - seaLevel));

        int rivers = 0, mouths = 0, locked = 0, belowSea = 0;
        double maxDeviation = 0.0;
        for (int rz = -radius; rz <= radius; rz++) {
            for (int rx = -radius; rx <= radius; rx++) {
                RiverLineRegion region = network.region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    rivers++;
                    if (river.nodes.length < 2) continue;
                    var last = river.nodes[river.nodes.length - 1];
                    double ground = terrain.sample(last.x(), last.z()).height;
                    if (ground >= seaLevel - 0.5) continue;
                    mouths++;
                    if (mouths <= 3) {
                        int lastIdx = river.surfaceY.length - 1;
                        int upIdx = Math.max(0, lastIdx - 30);
                        StringBuilder sb = new StringBuilder(String.format(
                                "mouth%d upstream w=%.2f d=%.2f | tail: ",
                                mouths, river.width[upIdx], river.depth[upIdx]));
                        for (int i = Math.max(0, lastIdx - 2); i <= lastIdx; i++) {
                            double nodeGround = terrain.sample(
                                    river.nodes[i].x(), river.nodes[i].z()).height;
                            sb.append(String.format("[n%d g=%.2f s=%.2f w=%.2f d=%.2f] ",
                                    i, nodeGround, river.surfaceY[i],
                                    river.width[i], river.depth[i]));
                        }
                        System.out.println(sb);
                    }
                    double deviation = Math.abs(river.surfaceY[river.surfaceY.length - 1] - seaLevel);
                    maxDeviation = Math.max(maxDeviation, deviation);
                    if (deviation <= 0.5) locked++;
                    if (river.surfaceY[river.surfaceY.length - 1] < seaLevel - 0.5) belowSea++;
                }
            }
        }
        System.out.println("=== RiverLineMouthProbe ===");
        System.out.println("seed=" + seed + " seaLevel=" + seaLevel);
        System.out.println("rivers=" + rivers + " oceanMouths=" + mouths
                + " lockedToSeaLevel=" + locked + " stillBelowSea=" + belowSea);
        System.out.println("maxDeviationFromSeaLevel=" + maxDeviation);
        System.out.println("status=" + (mouths > 0 && belowSea == 0 ? "PASS" : "FAIL"));
    }
}
