package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.HeightCurve;

/** 验证水文适配层输出完整、有限且与水文剖面一致。 */
public final class HydrologyAdapterProbe {
    private HydrologyAdapterProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        var samples = HydrologyRiverAdapter.adapt(grid,
                new HeightCurve(params, params.minY(), params.maxY()));
        int invalid = 0, missing = 0;
        for (HydrologyRiverSample sample : samples.values()) {
            if (!Double.isFinite(sample.surfaceY()) || !Double.isFinite(sample.bedY())
                    || !Double.isFinite(sample.width()) || !Double.isFinite(sample.depth())
                    || sample.bedY() >= sample.surfaceY()) invalid++;
            if (sample.downstream() >= 0 && !samples.containsKey(sample.downstream())) missing++;
        }
        System.out.println("=== HydrologyAdapterProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples.size());
        System.out.println("invalid=" + invalid);
        System.out.println("missingDownstream=" + missing);
        System.out.println("status=" + (invalid + missing == 0 ? "PASS" : "FAIL"));
    }
}
