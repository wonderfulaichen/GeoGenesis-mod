package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 打印单列雕刻计划细节，定位灌水判定失败的具体数值。 */
public final class HydrologyColumnDiagProbe {
    private HydrologyColumnDiagProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int chunkX = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int chunkZ = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        double[] ground = new double[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            ground[lz * 16 + lx] = terrain.sample((chunkX * 16 + lx) / horizontalScale,
                    (chunkZ * 16 + lz) / horizontalScale).height;
        }
        var columns = HydrologyBlockCarver.carveChunk(engine, chunkX, chunkZ,
                horizontalScale, ground);
        System.out.println("=== HydrologyColumnDiagProbe chunk=" + chunkX + "," + chunkZ + " ===");
        for (HydrologyBlockCarvedColumn column : columns) {
            System.out.printf("block=(%d,%d) orig=%.3f carved=%.3f surface=%.3f erosion=%.3f fill=%s%n",
                    column.blockX(), column.blockZ(), column.originalGroundY(),
                    column.carvedGroundY(), column.waterSurfaceY(),
                    column.erosion(), column.fillWater());
        }
    }
}
