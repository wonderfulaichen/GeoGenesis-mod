package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证 GeoGenesisTerrain 水文实验入口的种子切换、确定性和隔离性。 */
public final class HydrologyTerrainEntryProbe {
    private HydrologyTerrainEntryProbe() { }

    public static void main(String[] args) {
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        long first = hash(terrain, generator, 12345L);
        long repeat = hash(terrain, generator, 12345L);
        long second = hash(terrain, generator, 777L);
        long restored = hash(terrain, generator, 12345L);
        boolean deterministic = first == repeat && first == restored;
        boolean seedSensitive = first != second;
        boolean hydrologyDefault = HydrologyExperimentSwitch.hydrologyEnabled();
        System.out.println("=== HydrologyTerrainEntryProbe ===");
        System.out.println("first=" + Long.toUnsignedString(first));
        System.out.println("repeat=" + Long.toUnsignedString(repeat));
        System.out.println("second=" + Long.toUnsignedString(second));
        System.out.println("restored=" + Long.toUnsignedString(restored));
        System.out.println("deterministic=" + deterministic);
        System.out.println("seedSensitive=" + seedSensitive);
        System.out.println("hydrologyDefault=" + hydrologyDefault);
        System.out.println("status=" + (deterministic && seedSensitive && hydrologyDefault ? "PASS" : "FAIL"));
    }

    private static long hash(GeoGenesisTerrain terrain, CellGenerator generator, long seed) {
        terrain.seed(seed);
        HydrologyChunkResult result = terrain.calculateHydrologyChunk(0, 0);
        return result.hash();
    }
}
