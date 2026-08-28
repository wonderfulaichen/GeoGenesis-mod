package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 定位河流 block 坐标（诊断用）：扫描并输出命中河线的采样点。 */
public final class RiverLineLocateProbe {
    private RiverLineLocateProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int found = 0;
        for (int z = -radius; z <= radius; z += 4) {
            for (int x = -radius; x <= radius; x += 4) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, 1.0);
                if (s != null && s.distToCenter() <= s.width()) {
                    System.out.println("river at block (" + x + "," + z
                            + ") dist=" + String.format("%.1f", s.distToCenter())
                            + " width=" + String.format("%.1f", s.width())
                            + " surface=" + String.format("%.1f", s.surfaceY()));
                    found++;
                }
            }
        }
        System.out.println("found=" + found);
    }
}
