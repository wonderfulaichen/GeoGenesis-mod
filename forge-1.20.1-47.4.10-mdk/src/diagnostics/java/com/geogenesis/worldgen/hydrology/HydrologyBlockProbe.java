package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证 MC block 坐标映射在 HS=2 下的 chunk 内与边界连续性。 */
public final class HydrologyBlockProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int samples = 0, missing = 0, jumps = 0;
        double maxSurfaceJump = 0.0, maxBedJump = 0.0;
        for (int chunkZ = -4; chunkZ <= 4; chunkZ++) {
            for (int chunkX = -4; chunkX <= 4; chunkX++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        double bx = chunkX * 16.0 + lx;
                        double bz = chunkZ * 16.0 + lz;
                        HydrologyBlockSample sample = engine.sampleBlock(bx, bz, hs);
                        HydrologyBlockSample east = engine.sampleBlock(bx + 0.01, bz, hs);
                        HydrologyBlockSample south = engine.sampleBlock(bx, bz + 0.01, hs);
                        if (sample == null) { missing++; continue; }
                        samples++;
                        checkPair(sample, east, Limits.INSTANCE);
                        checkPair(sample, south, Limits.INSTANCE);
                    }
                }
            }
        }
        maxSurfaceJump = Limits.INSTANCE.maxSurface;
        maxBedJump = Limits.INSTANCE.maxBed;
        jumps = Limits.INSTANCE.jumps;
        System.out.println("=== HydrologyBlockProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + hs);
        System.out.println("samples=" + samples);
        System.out.println("missing=" + missing);
        System.out.println("jumps=" + jumps);
        System.out.println("maxSurfaceJump=" + maxSurfaceJump);
        System.out.println("maxBedJump=" + maxBedJump);
        System.out.println("status=" + (jumps == 0 ? "PASS" : "REVIEW"));
    }

    private static void checkPair(HydrologyBlockSample a, HydrologyBlockSample b, Limits limits) {
        if (b == null) return;
        double surface = Math.abs(a.surfaceY() - b.surfaceY());
        double bed = Math.abs(a.bedY() - b.bedY());
        limits.maxSurface = Math.max(limits.maxSurface, surface);
        limits.maxBed = Math.max(limits.maxBed, bed);
        if (surface > 0.25 || bed > 0.5) limits.jumps++;
    }

    private static final class Limits {
        static final Limits INSTANCE = new Limits();
        double maxSurface;
        double maxBed;
        int jumps;
    }
}
