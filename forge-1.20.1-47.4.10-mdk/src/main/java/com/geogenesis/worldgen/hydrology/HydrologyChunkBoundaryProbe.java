package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 扫描并验证相邻 chunk 的水文雕刻计划和灌水边界。 */
public final class HydrologyChunkBoundaryProbe {
    private HydrologyChunkBoundaryProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int riverChunks = 0, waterChunks = 0, boundaryJumps = 0, carveJumps = 0;
        double maxGroundJump = 0.0, maxBaseJump = 0.0, maxCarveDeltaJump = 0.0, maxWaterJump = 0.0;
        for (int cz = -8; cz < 8; cz++) {
            for (int cx = -8; cx < 8; cx++) {
                double[] ground = ground(terrain, cx, cz, horizontalScale);
                var current = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, ground);
                if (current.isEmpty()) continue;
                riverChunks++;
                if (current.stream().anyMatch(HydrologyBlockCarvedColumn::fillWater)) waterChunks++;
                if (cx < 7) {
                    double[] eastGround = ground(terrain, cx + 1, cz, horizontalScale);
                    var east = HydrologyBlockCarver.carveChunk(engine, cx + 1, cz, horizontalScale, eastGround);
                    for (int lz = 0; lz < 16; lz++) {
                        HydrologyBlockCarvedColumn a = at(current, cx * 16 + 15, cz * 16 + lz);
                        HydrologyBlockCarvedColumn b = at(east, (cx + 1) * 16, cz * 16 + lz);
                        if (a == null || b == null) continue;
                        maxGroundJump = Math.max(maxGroundJump, Math.abs(a.carvedGroundY() - b.carvedGroundY()));
                        maxBaseJump = Math.max(maxBaseJump, Math.abs(a.originalGroundY() - b.originalGroundY()));
                        maxCarveDeltaJump = Math.max(maxCarveDeltaJump,
                                Math.abs(a.erosion() - b.erosion()));
                        maxWaterJump = Math.max(maxWaterJump, Math.abs(a.waterSurfaceY() - b.waterSurfaceY()));
                        if (Math.abs(a.erosion() - b.erosion()) > 0.25) carveJumps++;
                        if (Math.abs(a.waterSurfaceY() - b.waterSurfaceY()) > 0.25) boundaryJumps++;
                    }
                }
            }
        }
        System.out.println("=== HydrologyChunkBoundaryProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + horizontalScale);
        System.out.println("riverChunks=" + riverChunks);
        System.out.println("waterChunks=" + waterChunks);
        System.out.println("boundaryJumps=" + boundaryJumps);
        System.out.println("carveJumps=" + carveJumps);
        System.out.println("maxBaseJump=" + maxBaseJump);
        System.out.println("maxCarveDeltaJump=" + maxCarveDeltaJump);
        System.out.println("maxGroundJump=" + maxGroundJump);
        System.out.println("maxWaterJump=" + maxWaterJump);
        System.out.println("status=" + (boundaryJumps == 0 && carveJumps == 0 ? "PASS" : "REVIEW"));
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[z * 16 + x] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }

    private static HydrologyBlockCarvedColumn at(java.util.List<HydrologyBlockCarvedColumn> columns,
                                                  int x, int z) {
        for (HydrologyBlockCarvedColumn column : columns) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }
}
