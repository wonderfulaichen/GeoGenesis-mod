package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.river.RiverNetwork;
import com.geogenesis.worldgen.river.RiverSample;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 同一坐标网格并排输出水文模型与旧 RTF 模型，便于定位路线差异。 */
public final class HydrologySideBySideProbe {
    private HydrologySideBySideProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine hydrology = new HydrologyExperimentEngine(generator, seed);
        RiverNetwork legacy = terrain.riverNetwork();
        int both = 0, hydrologyOnly = 0, legacyOnly = 0, rows = 0;
        for (int z = -radius; z <= radius; z += step) {
            StringBuilder row = new StringBuilder();
            for (int x = -radius; x <= radius; x += step) {
                HydrologyRiverSample modern = hydrology.sample(x, z);
                RiverSample old = legacy.sampleRiver(x, z);
                boolean a = modern != null;
                boolean b = old.inChannel();
                if (a && b) { row.append('X'); both++; }
                else if (a) { row.append('H'); hydrologyOnly++; }
                else if (b) { row.append('R'); legacyOnly++; }
                else row.append('.');
            }
            System.out.println(row);
            rows++;
        }
        System.out.println("=== HydrologySideBySideProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("radius=" + radius + " step=" + step + " rows=" + rows);
        System.out.println("both=" + both);
        System.out.println("hydrologyOnly=" + hydrologyOnly);
        System.out.println("legacyOnly=" + legacyOnly);
        System.out.println("legend=.=none,H=hydrology,R=RTF,X=both");
        System.out.println("status=COMPARISON_ONLY");
    }
}
