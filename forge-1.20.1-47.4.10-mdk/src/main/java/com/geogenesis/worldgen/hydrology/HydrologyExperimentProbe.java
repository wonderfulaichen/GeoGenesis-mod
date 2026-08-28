package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证实验接入门面跨区域采样和确定性缓存，不连接游戏生成路径。 */
public final class HydrologyExperimentProbe {
    private HydrologyExperimentProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int samples = 0, riverSamples = 0, missing = 0;
        long hash = 0xcbf29ce484222325L;
        for (int z = -2048; z <= 2048; z += 64) {
            for (int x = -2048; x <= 2048; x += 64) {
                HydrologyRiverSample sample = engine.sample(x, z);
                samples++;
                if (sample == null) missing++;
                else {
                    riverSamples++;
                    hash ^= Double.doubleToLongBits(sample.surfaceY());
                    hash *= 0x100000001b3L;
                    hash ^= Double.doubleToLongBits(sample.bedY());
                    hash *= 0x100000001b3L;
                }
            }
        }
        System.out.println("=== HydrologyExperimentProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples);
        System.out.println("riverSamples=" + riverSamples);
        System.out.println("noRiverSamples=" + missing);
        System.out.println("cachedRegions=" + engine.cachedRegions());
        System.out.println("hash=" + Long.toUnsignedString(hash));
        System.out.println("status=" + (samples > 0 && engine.cachedRegions() > 0 ? "PASS" : "REVIEW"));
    }
}
