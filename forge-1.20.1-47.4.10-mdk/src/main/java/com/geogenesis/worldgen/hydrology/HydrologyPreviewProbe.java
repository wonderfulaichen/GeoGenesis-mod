package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.river.RiverNetwork;
import com.geogenesis.worldgen.river.RiverSample;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 独立双模式预览输出：ASCII 显示所选河流数据源，不触碰游戏生成路径。 */
public final class HydrologyPreviewProbe {
    private HydrologyPreviewProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        HydrologyExperimentMode mode = args.length > 3
                ? parseMode(args[3]) : HydrologyExperimentMode.HYDROLOGY_EXPERIMENT;
        HydrologyExperimentSwitch.setMode(mode);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);
        RiverNetwork legacy = terrain.riverNetwork();
        int rows = 0, riverCells = 0;
        for (int z = -radius; z <= radius; z += step) {
            StringBuilder row = new StringBuilder();
            for (int x = -radius; x <= radius; x += step) {
                boolean hydrology = mode == HydrologyExperimentMode.HYDROLOGY_EXPERIMENT;
                HydrologyRiverSample modern = engine.sample(x, z);
                RiverSample old = legacy.sampleRiver(x, z);
                char symbol = hydrology ? symbol(modern) : symbol(old);
                if (hydrology ? modern != null : old.inChannel()) riverCells++;
                row.append(symbol);
            }
            System.out.println(row);
            rows++;
        }
        System.out.println("=== HydrologyPreviewProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("radius=" + radius + " step=" + step + " mode=" + mode);
        System.out.println("rows=" + rows);
        System.out.println("riverCells=" + riverCells);
        System.out.println("cachedRegions=" + engine.cachedRegions());
        System.out.println("legend=~=none, :=small, +=medium, #=large, O=lake-outlet, >ocean-outlet");
        System.out.println("status=PREVIEW_ONLY");
    }

    private static HydrologyExperimentMode parseMode(String value) {
        try {
            return HydrologyExperimentMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return HydrologyExperimentMode.HYDROLOGY_EXPERIMENT;
        }
    }

    private static char symbol(RiverSample sample) {
        if (!sample.inChannel()) return '~';
        if (sample.type() == com.geogenesis.worldgen.river.RiverSegmentType.LAKE) return 'O';
        if (sample.width() >= 12.0) return '#';
        if (sample.width() >= 7.0) return '+';
        return ':';
    }

    private static char symbol(HydrologyRiverSample sample) {
        if (sample == null) return '~';
        if (sample.outletType() == RiverOutlet.Type.LAKE) return 'O';
        if (sample.outletType() == RiverOutlet.Type.OCEAN) return '>';
        if (sample.width() >= 12.0) return '#';
        if (sample.width() >= 7.0) return '+';
        return ':';
    }
}
