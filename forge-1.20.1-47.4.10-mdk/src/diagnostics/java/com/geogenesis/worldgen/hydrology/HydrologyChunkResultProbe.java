package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证完整水文 chunk 结果契约。 */
public final class HydrologyChunkResultProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        HydrologyChunkEngine engine = new HydrologyChunkEngine(generator, seed);
        HydrologyChunkResult result = engine.calculate(chunkX, chunkZ);
        int invalid = 0, uplift = 0;
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            if (!Double.isFinite(column.carvedGroundY())
                    || column.carvedGroundY() > column.originalGroundY()) invalid++;
            if (column.carvedGroundY() > column.originalGroundY() + 1e-9) uplift++;
        }
        System.out.println("=== HydrologyChunkResultProbe ===");
        System.out.println("seed=" + seed + " chunk=" + chunkX + "," + chunkZ);
        System.out.println("originalCells=" + result.originalCells().length);
        System.out.println("carvedColumns=" + result.carvedColumns().size());
        System.out.println("waterColumns=" + result.waterColumns());
        System.out.println("maxErosion=" + result.maxErosion());
        System.out.println("invalid=" + invalid);
        System.out.println("uplift=" + uplift);
        System.out.println("hash=" + Long.toUnsignedString(result.hash()));
        System.out.println("status=" + (invalid + uplift == 0 ? "PASS" : "FAIL"));
    }
}
