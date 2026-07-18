package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.TerrainClass;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * 零依赖群系分类（不持颜色，仅分类逻辑）。
 *
 * 按 TerrainClass × Climate 映射到原版群系 ResourceKey。
 * 保留了 BiomeMapper 的 pickKey 委托模式。
 */
public final class BiomeClassifier {

    private BiomeClassifier() {}

    /** 旧 API 兼容（GeoPalette 引用） */
    public enum BiomeClass {
        OCEAN, DEEP_OCEAN, BEACH, PLAIN, HILLS, PLATEAU, MOUNTAINS,
        PEAK, LAKE, RIVER, BASIN, SNOW, SWAMP, FOREST, TAIGA, DESERT, SAVANNA, JUNGLE, TUNDRA
    }

    /**
     * 根据 Cell 的地形类型和气候选择原版群系 ResourceKey。
     */
    public static ResourceKey<Biome> pickKey(Cell cell) {
        TerrainClass terrain = cell.terrainType;
        Climate climate = cell.climate;

        return switch (terrain) {
            case DEEP_OCEAN -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                // MC 没有 DEEP_WARM_OCEAN，热深海也归 LUKEWARM
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case OCEAN -> {
                if (climate.isFrozen()) yield Biomes.FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.COLD_OCEAN;
                if (climate.isHot()) yield Biomes.WARM_OCEAN;
                yield Biomes.LUKEWARM_OCEAN;
            }
            case CONTINENTAL_SHELF -> {
                if (climate.isFrozen()) yield Biomes.FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.COLD_OCEAN;
                if (climate.isHot()) yield Biomes.WARM_OCEAN;
                yield Biomes.LUKEWARM_OCEAN;
            }
            case SUBMARINE_RIDGE -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case SEAMOUNT -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
                if (climate.isHot()) yield Biomes.LUKEWARM_OCEAN;
                yield Biomes.DEEP_LUKEWARM_OCEAN;
            }
            case LAKE      -> Biomes.SWAMP;
            case RIVER     -> Biomes.RIVER;

            case BEACH -> climate.isCold()
                ? Biomes.SNOWY_BEACH : Biomes.BEACH;

            case PLAIN -> climate.isCold()
                ? Biomes.SNOWY_PLAINS
                : climate.isDry() ? Biomes.SAVANNA : Biomes.PLAINS;

            case HILLS -> climate.isCold()
                ? Biomes.WINDSWEPT_HILLS
                : climate.isDry() ? Biomes.WINDSWEPT_SAVANNA : Biomes.FOREST;

            case PLATEAU -> climate.isCold()
                ? Biomes.SNOWY_PLAINS
                : climate.isDry() ? Biomes.SAVANNA_PLATEAU : Biomes.BIRCH_FOREST;

            case MOUNTAINS -> cell.isSnow
                ? Biomes.STONY_PEAKS
                : Biomes.JUNGLE;

            case PEAK -> cell.isSnow
                ? Biomes.FROZEN_PEAKS
                : Biomes.JAGGED_PEAKS;

            case BASIN -> climate.isDry()
                ? Biomes.DESERT : Biomes.MEADOW;

            case SNOW -> Biomes.SNOWY_PLAINS;
        };
    }

    /** 将 ResourceKey 转为 ResourceLocation（用于 BiomeResolver） */
    public static ResourceLocation location(Cell cell) {
        return pickKey(cell).location();
    }

    /** 旧 API 兼容：Cell → BiomeClass（GeoPalette 用） */
    public static BiomeClass classify(Cell cell) {
        return switch (cell.terrainType) {
            case DEEP_OCEAN -> BiomeClass.DEEP_OCEAN;
            case OCEAN     -> BiomeClass.OCEAN;
            case CONTINENTAL_SHELF -> BiomeClass.OCEAN;
            case SUBMARINE_RIDGE   -> BiomeClass.OCEAN;
            case SEAMOUNT          -> BiomeClass.OCEAN;
            case LAKE      -> BiomeClass.LAKE;
            case RIVER     -> BiomeClass.RIVER;
            case BEACH    -> BiomeClass.BEACH;
            case PLAIN    -> BiomeClass.PLAIN;
            case HILLS    -> BiomeClass.HILLS;
            case PLATEAU  -> BiomeClass.PLATEAU;
            case MOUNTAINS -> BiomeClass.MOUNTAINS;
            case PEAK     -> BiomeClass.PEAK;
            case BASIN    -> BiomeClass.BASIN;
            case SNOW     -> BiomeClass.SNOW;
        };
    }
}
