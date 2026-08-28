package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 扫描多个 chunk 统计真实灌水列，验证水文河流有水。 */
public final class HydrologyWaterFillProbe {
    private HydrologyWaterFillProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int chunks = 0, riverChunks = 0, waterColumns = 0, invalid = 0, uplift = 0;
        double maxErosion = 0.0;
        for (int cz = -6; cz < 6; cz++) for (int cx = -6; cx < 6; cx++) {
            chunks++;
            double[] ground = ground(terrain, cx, cz, horizontalScale);
            var columns = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, ground);
            if (columns.isEmpty()) continue;
            riverChunks++;
            for (HydrologyBlockCarvedColumn column : columns) {
                if (column.fillWater()) waterColumns++;
                if (!Double.isFinite(column.carvedGroundY())
                        || column.carvedGroundY() > column.originalGroundY()) invalid++;
                if (column.carvedGroundY() > column.originalGroundY() + 1e-9) uplift++;
                maxErosion = Math.max(maxErosion, column.erosion());
            }
        }
        System.out.println("=== HydrologyWaterFillProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + horizontalScale);
        System.out.println("chunks=" + chunks + " riverChunks=" + riverChunks);
        System.out.println("waterColumns=" + waterColumns);
        System.out.println("invalid=" + invalid + " uplift=" + uplift);
        System.out.println("maxErosion=" + maxErosion);
        System.out.println("status=" + (waterColumns > 0 && invalid == 0 && uplift == 0
                ? "PASS" : "FAIL"));
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[z * 16 + x] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }
}
