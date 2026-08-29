package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 比较相邻完整 chunk 结果。
 *
 * <p>★ 判据（2026-08-27 修正）：玩家可见接缝 = 雕刻后地面绝对差 − 基础地形自身差。
 * 水面差只在两列都灌水时才有意义（一侧有水一侧无水是河道边缘，属正常形态）。</p>
 */
public final class HydrologyChunkResultBoundaryProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        HydrologyChunkEngine engine = new HydrologyChunkEngine(generator, seed);
        int compared = 0, carveJumps = 0, waterJumps = 0;
        double maxBase = 0.0, maxCarve = 0.0, maxWater = 0.0;
        for (int cz = -4; cz < 4; cz++) for (int cx = -4; cx < 4; cx++) {
            HydrologyChunkResult current = engine.calculate(cx, cz);
            if (cx >= 3) continue;
            HydrologyChunkResult east = engine.calculate(cx + 1, cz);
            for (int lz = 0; lz < 16; lz++) {
                HydrologyBlockCarvedColumn a = at(current, cx * 16 + 15, cz * 16 + lz);
                HydrologyBlockCarvedColumn b = at(east, (cx + 1) * 16, cz * 16 + lz);
                if (a == null || b == null) continue;
                compared++;
                double base = Math.abs(a.originalGroundY() - b.originalGroundY());
                double carvedDiff = Math.abs(a.carvedGroundY() - b.carvedGroundY());
                maxBase = Math.max(maxBase, base);
                maxCarve = Math.max(maxCarve, carvedDiff);
                // 可见接缝 = 雕刻后地面差 − 基础地形自身差
                if (carvedDiff - base > 0.25) carveJumps++;
                // ★ 水面差仅在两侧都灌水时检查（同一条河内不允许台阶）
                if (a.fillWater() && b.fillWater()) {
                    double water = Math.abs(a.waterSurfaceY() - b.waterSurfaceY());
                    maxWater = Math.max(maxWater, water);
                    if (water > 0.25) waterJumps++;
                }
            }
        }
        System.out.println("=== HydrologyChunkResultBoundaryProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("compared=" + compared);
        System.out.println("carveJumps=" + carveJumps);
        System.out.println("waterJumps=" + waterJumps);
        System.out.println("maxBaseJump=" + maxBase);
        System.out.println("maxCarvedJump=" + maxCarve);
        System.out.println("maxWaterJump(bothFilled)=" + maxWater);
        System.out.println("status=" + (carveJumps == 0 && waterJumps == 0 ? "PASS" : "REVIEW"));
    }

    private static HydrologyBlockCarvedColumn at(HydrologyChunkResult result, int x, int z) {
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }
}
