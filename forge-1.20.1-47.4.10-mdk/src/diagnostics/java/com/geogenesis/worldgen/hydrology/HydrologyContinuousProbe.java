package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证连续水文采样在网格边界附近没有大幅跳变（河线距离场版：直接测 block 采样）。 */
public final class HydrologyContinuousProbe {
    private HydrologyContinuousProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int samples = 0, missing = 0, jumps = 0;
        double maxSurfaceJump = 0.0, maxBedJump = 0.0;
        for (int z = -512; z <= 512; z += 8) {
            for (int x = -512; x < 512; x += 8) {
                HydrologyBlockSample a = engine.sampleBlock(x + 3, z + 3, 1.0);
                HydrologyBlockSample b = engine.sampleBlock(x + 5, z + 5, 1.0);
                if (a == null || b == null) { missing++; continue; }
                samples++;
                double surface = Math.abs(a.surfaceY() - b.surfaceY());
                double bed = Math.abs(a.bedY() - b.bedY());
                maxSurfaceJump = Math.max(maxSurfaceJump, surface);
                maxBedJump = Math.max(maxBedJump, bed);
                if (surface > 0.25 || bed > 0.5) jumps++;
            }
        }
        System.out.println("=== HydrologyContinuousProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples);
        System.out.println("missing=" + missing);
        System.out.println("jumps=" + jumps);
        System.out.println("maxSurfaceJump=" + maxSurfaceJump);
        System.out.println("maxBedJump=" + maxBedJump);
        System.out.println("status=" + (jumps == 0 ? "PASS" : "REVIEW"));
    }
}
