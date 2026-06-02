package com.geogenesis.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;

import java.util.HashMap;
import java.util.Map;

/**
 * 气候→群系映射器
 *
 * 基于温度、湿度、海拔、大陆性选择原版群系
 * 6温度带 × 3湿度带 × 3海拔带 = 54种映射 + 海洋/海岸/河流覆盖
 */
public class ClimateBiomeMapper {

    private static final Map<String, ResourceKey<Biome>> climateBiomeMap = new HashMap<>();
    private static ResourceKey<Biome> defaultBiome = Biomes.PLAINS;

    static {
        registerCold();
        registerTemperate();
        registerWarm();
        registerHot();
        registerFrozen();
        registerBoreal();
    }

    private static void registerFrozen() {
        put("frozen:arid:high", Biomes.FROZEN_PEAKS);
        put("frozen:arid:mid", Biomes.SNOWY_SLOPES);
        put("frozen:arid:low", Biomes.SNOWY_PLAINS);

        put("frozen:normal:high", Biomes.FROZEN_PEAKS);
        put("frozen:normal:mid", Biomes.GROVE);
        put("frozen:normal:low", Biomes.SNOWY_PLAINS);

        put("frozen:humid:high", Biomes.JAGGED_PEAKS);
        put("frozen:humid:mid", Biomes.SNOWY_SLOPES);
        put("frozen:humid:low", Biomes.SNOWY_TAIGA);
    }

    private static void registerCold() {
        put("cold:arid:high", Biomes.STONY_PEAKS);
        put("cold:arid:mid", Biomes.WINDSWEPT_HILLS);
        put("cold:arid:low", Biomes.WINDSWEPT_GRAVELLY_HILLS);

        put("cold:normal:high", Biomes.STONY_PEAKS);
        put("cold:normal:mid", Biomes.WINDSWEPT_FOREST);
        put("cold:normal:low", Biomes.TAIGA);

        put("cold:humid:high", Biomes.GROVE);
        put("cold:humid:mid", Biomes.OLD_GROWTH_SPRUCE_TAIGA);
        put("cold:humid:low", Biomes.OLD_GROWTH_PINE_TAIGA);
    }

    private static void registerBoreal() {
        put("boreal:arid:high", Biomes.WINDSWEPT_HILLS);
        put("boreal:arid:mid", Biomes.WINDSWEPT_FOREST);
        put("boreal:arid:low", Biomes.TAIGA);

        put("boreal:normal:high", Biomes.WINDSWEPT_FOREST);
        put("boreal:normal:mid", Biomes.FOREST);
        put("boreal:normal:low", Biomes.BIRCH_FOREST);

        put("boreal:humid:high", Biomes.OLD_GROWTH_BIRCH_FOREST);
        put("boreal:humid:mid", Biomes.DARK_FOREST);
        put("boreal:humid:low", Biomes.SWAMP);
    }

    private static void registerTemperate() {
        put("temperate:arid:high", Biomes.WINDSWEPT_HILLS);
        put("temperate:arid:mid", Biomes.WINDSWEPT_SAVANNA);
        put("temperate:arid:low", Biomes.SAVANNA);

        put("temperate:normal:high", Biomes.WINDSWEPT_FOREST);
        put("temperate:normal:mid", Biomes.FOREST);
        put("temperate:normal:low", Biomes.PLAINS);

        put("temperate:humid:high", Biomes.DARK_FOREST);
        put("temperate:humid:mid", Biomes.FLOWER_FOREST);
        put("temperate:humid:low", Biomes.SWAMP);
    }

    private static void registerWarm() {
        put("warm:arid:high", Biomes.WOODED_BADLANDS);
        put("warm:arid:mid", Biomes.BADLANDS);
        put("warm:arid:low", Biomes.DESERT);

        put("warm:normal:high", Biomes.SAVANNA_PLATEAU);
        put("warm:normal:mid", Biomes.SAVANNA);
        put("warm:normal:low", Biomes.SPARSE_JUNGLE);

        put("warm:humid:high", Biomes.JUNGLE);
        put("warm:humid:mid", Biomes.BAMBOO_JUNGLE);
        put("warm:humid:low", Biomes.MANGROVE_SWAMP);
    }

    private static void registerHot() {
        put("hot:arid:high", Biomes.ERODED_BADLANDS);
        put("hot:arid:mid", Biomes.DESERT);
        put("hot:arid:low", Biomes.DESERT);

        put("hot:normal:high", Biomes.SAVANNA_PLATEAU);
        put("hot:normal:mid", Biomes.SAVANNA);
        put("hot:normal:low", Biomes.SPARSE_JUNGLE);

        put("hot:humid:high", Biomes.JUNGLE);
        put("hot:humid:mid", Biomes.BAMBOO_JUNGLE);
        put("hot:humid:low", Biomes.MANGROVE_SWAMP);
    }

    private static void put(String key, ResourceKey<Biome> biome) {
        climateBiomeMap.put(key, biome);
    }

    private static String moistureKey(float moisture) {
        if (moisture < 0.25f) return "arid";
        if (moisture < 0.55f) return "normal";
        return "humid";
    }

    private static String elevationKey(float elevation) {
        if (elevation > 0.60f) return "high";
        if (elevation > 0.35f) return "mid";
        return "low";
    }

    private static String temperatureKey(float temperature) {
        if (temperature < 0.10f) return "frozen";
        if (temperature < 0.30f) return "cold";
        if (temperature < 0.45f) return "boreal";
        if (temperature < 0.62f) return "temperate";
        if (temperature < 0.78f) return "warm";
        return "hot";
    }

    /**
     * 根据气候参数选择群系
     *
     * @param glacierWeight  冰川特征权重 [0,1]，高值 → 冰川峰群系
     * @param karstWeight   喀斯特特征权重 [0,1]，高值 → 石山群系
     * @param danxiaWeight  丹霞特征权重 [0,1]，高值 → 恶地群系
     */
    public static ResourceKey<Biome> selectBiome(float temperature, float moisture,
                                                  float elevation, float continentality,
                                                  float riverDepth,
                                                  float glacierWeight, float karstWeight, float danxiaWeight) {
        if (continentality < -0.80f) {
            return selectDeepOcean(temperature);
        }
        if (continentality < -0.12f) {
            return selectOcean(temperature);
        }
        if (continentality < 0.0f) {
            return selectCoastal(temperature, moisture);
        }
        if (riverDepth > 0.02f) {
            return temperature < 0.20f ? Biomes.FROZEN_RIVER : Biomes.RIVER;
        }

        // ===== 特殊地形特征优先（覆盖气候映射）=====
        if (glacierWeight > 0.6f && temperature < 0.5f) {
            if (elevation > 0.70f) return Biomes.FROZEN_PEAKS;
            if (elevation > 0.50f) return Biomes.JAGGED_PEAKS;
            if (elevation > 0.35f) return Biomes.SNOWY_SLOPES;
            return Biomes.SNOWY_TAIGA;
        }

        if (danxiaWeight > 0.6f && temperature > 0.45f) {
            if (elevation > 0.55f) return Biomes.ERODED_BADLANDS;
            if (elevation > 0.35f) return Biomes.WOODED_BADLANDS;
            return Biomes.BADLANDS;
        }

        if (karstWeight > 0.6f) {
            if (elevation > 0.65f) return Biomes.STONY_PEAKS;
            if (elevation > 0.40f) return Biomes.WINDSWEPT_FOREST;
            return temperature < 0.35f ? Biomes.TAIGA : Biomes.FOREST;
        }

        String tempKey = temperatureKey(temperature);
        String moistKey = moistureKey(moisture);
        String elevKey = elevationKey(elevation);

        ResourceKey<Biome> biome = climateBiomeMap.get(tempKey + ":" + moistKey + ":" + elevKey);
        if (biome != null) return biome;

        biome = climateBiomeMap.get(tempKey + ":normal:" + elevKey);
        if (biome != null) return biome;

        return defaultBiome;
    }

    public static ResourceKey<Biome> selectBiome(float temperature, float moisture,
                                                  float elevation, float continentality,
                                                  float riverDepth) {
        return selectBiome(temperature, moisture, elevation, continentality, riverDepth, 0f, 0f, 0f);
    }

    private static ResourceKey<Biome> selectDeepOcean(float temperature) {
        if (temperature < 0.15f) return Biomes.DEEP_FROZEN_OCEAN;
        if (temperature < 0.35f) return Biomes.DEEP_COLD_OCEAN;
        if (temperature > 0.65f) return Biomes.DEEP_LUKEWARM_OCEAN;
        return Biomes.DEEP_OCEAN;
    }

    private static ResourceKey<Biome> selectOcean(float temperature) {
        if (temperature < 0.15f) return Biomes.FROZEN_OCEAN;
        if (temperature < 0.35f) return Biomes.COLD_OCEAN;
        if (temperature > 0.65f) return Biomes.LUKEWARM_OCEAN;
        return Biomes.OCEAN;
    }

    private static ResourceKey<Biome> selectCoastal(float temperature, float moisture) {
        if (temperature < 0.15f) return Biomes.SNOWY_BEACH;
        if (moisture > 0.55f && temperature > 0.40f) return Biomes.MANGROVE_SWAMP;
        if (temperature > 0.60f) return Biomes.BEACH;
        return Biomes.STONY_SHORE;
    }

    /**
     * 从 ServerLevel 的注册表解析 Holder<Biome>
     */
    public static Holder<Biome> resolveBiome(ResourceKey<Biome> key, BiomeSource fallback) {
        for (Holder<Biome> holder : fallback.possibleBiomes()) {
            if (holder.is(key)) {
                return holder;
            }
        }
        return fallback.possibleBiomes().stream().findFirst().orElseThrow(
                () -> new IllegalStateException("No biomes available in fallback source"));
    }
}
