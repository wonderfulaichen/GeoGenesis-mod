package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证 16×16 chunk 水文雕刻计划的只下挖与灌水条件。 */
public final class HydrologyChunkPlanProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        double horizontalScale = args.length > 3 ? Double.parseDouble(args[3]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        double[] ground = new double[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                ground[lz * 16 + lx] = terrain.sample(
                        (chunkX * 16 + lx) / horizontalScale,
                        (chunkZ * 16 + lz) / horizontalScale).height;
            }
        }
        var columns = HydrologyBlockCarver.carveChunk(engine, chunkX, chunkZ, horizontalScale, ground);
        int invalid = 0, uplift = 0, water = 0;
        double maxErosion = 0.0;
        for (HydrologyBlockCarvedColumn column : columns) {
            if (!Double.isFinite(column.carvedGroundY())
                    || column.carvedGroundY() > column.originalGroundY()
                    || column.carvedGroundY() < column.waterSurfaceY() - column.erosion() - 100.0) invalid++;
            if (column.carvedGroundY() > column.originalGroundY() + 1e-9) uplift++;
            if (column.fillWater()) water++;
            maxErosion = Math.max(maxErosion, column.erosion());
        }
        System.out.println("=== HydrologyChunkPlanProbe ===");
        System.out.println("seed=" + seed + " chunk=" + chunkX + "," + chunkZ
                + " horizontalScale=" + horizontalScale);
        System.out.println("riverColumns=" + columns.size());
        System.out.println("waterColumns=" + water);
        System.out.println("invalid=" + invalid);
        System.out.println("uplift=" + uplift);
        System.out.println("maxErosion=" + maxErosion);
        System.out.println("status=" + (invalid + uplift == 0 ? "PASS" : "FAIL"));
    }
}
