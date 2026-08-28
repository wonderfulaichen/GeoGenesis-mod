package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证水文地形门面可按 seed 重建独立 chunk 原始 Cell。 */
public final class HydrologyTerrainFacadeProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        HydrologyTerrainFacade facade = new HydrologyTerrainFacade(generator);
        var cells = facade.sampleChunk(seed, 0, 0);
        long hash = 0xcbf29ce484222325L;
        int invalid = 0;
        for (var cell : cells) {
            if (cell == null || !Double.isFinite(cell.height)) invalid++;
            else {
                hash ^= Double.doubleToLongBits(cell.height);
                hash *= 0x100000001b3L;
            }
        }
        System.out.println("=== HydrologyTerrainFacadeProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("cells=" + cells.length);
        System.out.println("invalid=" + invalid);
        System.out.println("hash=" + Long.toUnsignedString(hash));
        System.out.println("status=" + (invalid == 0 ? "PASS" : "FAIL"));
    }
}
