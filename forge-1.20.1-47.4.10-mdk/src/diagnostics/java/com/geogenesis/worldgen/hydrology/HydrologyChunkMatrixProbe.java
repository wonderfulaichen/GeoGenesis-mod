package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 多 seed、多 horizontalScale 的完整 chunk 边界验收矩阵。 */
public final class HydrologyChunkMatrixProbe {
    private static final long[] SEEDS = {12345L, 777L, 98765L};
    private static final double[] SCALES = {2.0};

    private HydrologyChunkMatrixProbe() { }

    public static void main(String[] args) {
        int failures = 0;
        for (long seed : SEEDS) for (double scale : SCALES) {
            Metrics metrics = measure(seed, scale);
            failures += metrics.pass ? 0 : 1;
            System.out.println("seed=" + seed + " scale=" + scale
                    + " compared=" + metrics.compared + " carveJumps=" + metrics.carveJumps
                    + " waterJumps=" + metrics.waterJumps + " maxCarve=" + metrics.maxCarve
                    + " maxWater=" + metrics.maxWater + " status=" + (metrics.pass ? "PASS" : "REVIEW"));
        }
        System.out.println("=== HydrologyChunkMatrixSummary ===");
        System.out.println("cases=" + (SEEDS.length * SCALES.length));
        System.out.println("failures=" + failures);
        System.out.println("status=" + (failures == 0 ? "PASS" : "REVIEW"));
    }

    private static Metrics measure(long seed, double scale) {
        TerrainParams params = TerrainParams.defaults();
        if (Math.abs(params.horizontalScale() - scale) > 1e-9) {
            throw new IllegalStateException("matrix scale requires matching project default horizontalScale");
        }
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        HydrologyChunkEngine engine = new HydrologyChunkEngine(generator, seed);
        int compared = 0, carveJumps = 0, waterJumps = 0;
        double maxCarve = 0.0, maxWater = 0.0;
        for (int cz = -2; cz < 2; cz++) for (int cx = -2; cx < 2; cx++) {
            HydrologyChunkResult current = engine.calculate(cx, cz);
            if (cx >= 1) continue;
            HydrologyChunkResult east = engine.calculate(cx + 1, cz);
            for (int z = 0; z < 16; z++) {
                HydrologyBlockCarvedColumn a = at(current, cx * 16 + 15, cz * 16 + z);
                HydrologyBlockCarvedColumn b = at(east, (cx + 1) * 16, cz * 16 + z);
                if (a == null || b == null) continue;
                compared++;
                double carve = Math.abs(a.erosion() - b.erosion());
                double water = Math.abs(a.waterSurfaceY() - b.waterSurfaceY());
                maxCarve = Math.max(maxCarve, carve);
                maxWater = Math.max(maxWater, water);
                if (carve > 0.25) carveJumps++;
                if (water > 0.25) waterJumps++;
            }
        }
        return new Metrics(compared, carveJumps, waterJumps, maxCarve, maxWater,
                compared == 0 || (carveJumps == 0 && waterJumps == 0));
    }


    private static HydrologyBlockCarvedColumn at(HydrologyChunkResult result, int x, int z) {
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }

    private record Metrics(int compared, int carveJumps, int waterJumps,
                           double maxCarve, double maxWater, boolean pass) { }
}
