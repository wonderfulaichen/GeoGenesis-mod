package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.Map;

/**
 * 墙体网格分解探针（2026-09-09）。用户站墙顶 (-167,155,-366)，seed 9139912035078620160。
 * 在该点周围做网格 dump：每格 final / lightOrig / carved / cut / surfY / fill，
 * 直接定位墙体单元及其成因（雕刻下切 vs 侵蚀堆积）。
 */
public final class WallDecomposeProbe {

    private WallDecomposeProbe() { }

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);

        // 网格：x -174..-158，z -374..-358（覆盖用户站位与瀑布）
        Map<Long, HydrologyBlockCarvedColumn> colMap = new HashMap<>();
        for (int cx = Math.floorDiv(-174, 16); cx <= Math.floorDiv(-158, 16); cx++) {
            for (int cz = Math.floorDiv(-374, 16); cz <= Math.floorDiv(-358, 16); cz++) {
                HydrologyChunkResult result = terrain.calculateHydrologyChunk(cx, cz);
                for (HydrologyBlockCarvedColumn c : result.carvedColumns()) {
                    colMap.put((((long) c.blockX()) << 32) | (c.blockZ() & 0xffffffffL), c);
                }
            }
        }

        System.out.println("=== WallDecomposeProbe grid x[-174..-158] z[-374..-358] ===");
        System.out.println("format carve-col: final[light,pipe]/carved ; no-carve: (light)");
        System.out.println("-> natural cliff iff light steps; carve-wall iff pipe~same but carved steps; erosion-wall iff carved~same but final steps");
        for (int bz = -374; bz <= -358; bz++) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("z=%d ", bz));
            for (int bx = -174; bx <= -158; bx++) {
                Cell fin = terrain.sampleCell(bx, bz);
                Cell light = terrain.sampleCellLight(bx, bz);
                double lh = light != null ? light.height : -999;
                HydrologyBlockCarvedColumn col =
                        colMap.get((((long) bx) << 32) | (bz & 0xffffffffL));
                if (col != null) {
                    sb.append(String.format("%3.0f[%3.0f,%3.0f]/%3.0f%s ",
                            fin.height, lh, col.originalGroundY(), col.carvedGroundY(),
                            col.fillWater() ? "W" : " "));
                } else {
                    sb.append(String.format("(%3.0f) ", lh));
                }
            }
            System.out.println(sb);
        }
    }
}
