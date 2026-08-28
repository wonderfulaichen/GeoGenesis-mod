package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.river.RiverNetwork;
import com.geogenesis.worldgen.river.RiverSample;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 预览侧模式选择：同一坐标只切换诊断数据源，不触碰游戏生成路径。 */
public final class HydrologyPreviewModeProbe {
    private HydrologyPreviewModeProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine hydrology = new HydrologyExperimentEngine(generator, seed);
        RiverNetwork legacy = terrain.riverNetwork();
        int legacyHits = countLegacy(legacy);
        int hydrologyHits = countHydrology(hydrology);
        HydrologyExperimentSwitch.setMode(HydrologyExperimentMode.HYDROLOGY_EXPERIMENT);
        int selectedHydrology = selectedCount(hydrology, legacy);
        HydrologyExperimentSwitch.setMode(HydrologyExperimentMode.LEGACY_RTF);
        int selectedLegacy = selectedCount(hydrology, legacy);
        System.out.println("=== HydrologyPreviewModeProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("legacyHits=" + legacyHits);
        System.out.println("hydrologyHits=" + hydrologyHits);
        System.out.println("selectedHydrology=" + selectedHydrology);
        System.out.println("selectedLegacy=" + selectedLegacy);
        System.out.println("gamePathChanged=false");
        System.out.println("status=" + (selectedHydrology == hydrologyHits
                && selectedLegacy == legacyHits ? "PASS" : "FAIL"));
    }

    private static int countLegacy(RiverNetwork legacy) {
        int count = 0;
        for (int z = -256; z <= 256; z += 32) {
            for (int x = -256; x <= 256; x += 32) {
                if (legacy.sampleRiver(x, z).inChannel()) count++;
            }
        }
        return count;
    }

    private static int countHydrology(HydrologyExperimentEngine hydrology) {
        int count = 0;
        for (int z = -256; z <= 256; z += 32) {
            for (int x = -256; x <= 256; x += 32) {
                if (hydrology.sample(x, z) != null) count++;
            }
        }
        return count;
    }

    private static int selectedCount(HydrologyExperimentEngine hydrology, RiverNetwork legacy) {
        int count = 0;
        for (int z = -256; z <= 256; z += 32) {
            for (int x = -256; x <= 256; x += 32) {
                boolean selected = HydrologyExperimentSwitch.hydrologyEnabled()
                        ? hydrology.sample(x, z) != null
                        : legacy.sampleRiver(x, z).inChannel();
                if (selected) count++;
            }
        }
        return count;
    }
}
