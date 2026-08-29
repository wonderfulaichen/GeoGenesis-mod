package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 打印 chunk 边界两侧列的完整数值，定位雕刻/水面跳变来源。 */
public final class HydrologyBoundaryDiagProbe {
    private HydrologyBoundaryDiagProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        HydrologyChunkEngine engine = new HydrologyChunkEngine(generator, seed);
        int printed = 0;
        for (int cz = -4; cz < 4 && printed < 8; cz++) {
            for (int cx = -4; cx < 3 && printed < 8; cx++) {
                HydrologyChunkResult current = engine.calculate(cx, cz);
                HydrologyChunkResult east = engine.calculate(cx + 1, cz);
                for (int lz = 0; lz < 16 && printed < 8; lz++) {
                    HydrologyBlockCarvedColumn a = at(current, cx * 16 + 15, cz * 16 + lz);
                    HydrologyBlockCarvedColumn b = at(east, (cx + 1) * 16, cz * 16 + lz);
                    if (a == null || b == null) continue;
                    // ★ 只看两侧都灌水的边界（同一条河内的水面台阶才是真问题）
                    if (!a.fillWater() || !b.fillWater()) continue;
                    double waterDiff = Math.abs(a.waterSurfaceY() - b.waterSurfaceY());
                    double carvedDiff = Math.abs(a.carvedGroundY() - b.carvedGroundY());
                    if (waterDiff <= 0.25) continue;
                    System.out.printf("boundary(%d|%d,z=%d) A: orig=%.2f carved=%.2f surf=%.2f | B: orig=%.2f carved=%.2f surf=%.2f | carvedDiff=%.2f waterDiff=%.2f%n",
                            a.blockX(), b.blockX(), a.blockZ(),
                            a.originalGroundY(), a.carvedGroundY(), a.waterSurfaceY(),
                            b.originalGroundY(), b.carvedGroundY(), b.waterSurfaceY(),
                            carvedDiff, waterDiff);
                    printed++;
                }
            }
        }
    }

    private static HydrologyBlockCarvedColumn at(HydrologyChunkResult result, int x, int z) {
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }
}
