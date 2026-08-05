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
     *
     * <p>v3（2026-07-22）：加入大陆性分支判断。
     * 大陆性通过 Climate.isCoastal() / isInland() 参与群系选择：
     * <ul>
     *   <li>沿海（c~0）：海洋调节，温度温和、湿度高 → 丛林/沼泽/森林等湿润群系</li>
     *   <li>内陆（c>0.5）：气候极端，干燥/昼夜温差大 → 沙漠/稀树草原等干燥群系</li>
     * </ul>
     * 对应现实地理：西海岸温带雨林 vs 内陆大陆性草原/沙漠。
     */
    public static ResourceKey<Biome> pickKey(Cell cell) {
        TerrainClass terrain = cell.terrainType;
        Climate climate = cell.climate;

        return switch (terrain) {
            case DEEP_OCEAN -> {
                if (climate.isFrozen()) yield Biomes.DEEP_FROZEN_OCEAN;
                if (climate.isCold()) yield Biomes.DEEP_COLD_OCEAN;
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

            // === 陆地地形：大陆性参与群系分化 ===

            case PLAIN -> {
                if (climate.isCold()) {
                    yield Biomes.SNOWY_PLAINS;
                }
                // 沿海湿热 → 热带雨林（亚马逊/刚果盆地）
                if (climate.isHot() && climate.isCoastal() && climate.isWet()) {
                    yield Biomes.JUNGLE;
                }
                // 内陆干热 → 大陆性沙漠（戈壁/阿拉伯半岛）
                if (climate.isInland() && climate.isDry() && climate.isHot()) {
                    yield Biomes.DESERT;
                }
                // 内陆干旱 → 稀树草原（非洲萨赫勒/中亚草原）
                if (climate.isInland() && climate.isDry()) {
                    yield Biomes.SAVANNA;
                }
                // 沿海温湿 → 温带森林（西欧/北美东海岸）
                if (climate.isCoastal() && climate.isWet()) {
                    yield Biomes.FOREST;
                }
                yield Biomes.PLAINS;
            }

            case HILLS -> {
                if (climate.isCold()) {
                    yield Biomes.WINDSWEPT_HILLS;
                }
                // 沿海湿热丘陵 → 热带雨林山地
                if (climate.isHot() && climate.isCoastal() && climate.isWet()) {
                    yield Biomes.JUNGLE;
                }
                // 内陆干旱丘陵 → 干燥稀树草原丘陵
                if (climate.isInland() && climate.isDry()) {
                    yield Biomes.WINDSWEPT_SAVANNA;
                }
                // 沿海温湿丘陵 → 针叶林（北欧/阿拉斯加沿海）
                if (climate.isCoastal() && climate.isWet()) {
                    yield Biomes.TAIGA;
                }
                yield Biomes.FOREST;
            }

            case PLATEAU -> {
                if (climate.isCold()) {
                    yield Biomes.SNOWY_PLAINS;
                }
                // 内陆干燥高原 → 稀树草原高地（东非高原/青藏高原南麓）
                if (climate.isInland() && climate.isDry()) {
                    yield Biomes.SAVANNA_PLATEAU;
                }
                // 沿海湿润高原 → 桦木森林（云贵高原/阿巴拉契亚）
                if (climate.isCoastal() && climate.isWet()) {
                    yield Biomes.BIRCH_FOREST;
                }
                yield Biomes.WINDSWEPT_SAVANNA;
            }

            case MOUNTAINS -> {
                // 【2026-08-05 用户决策】PEAK 不再独立分类 → 雪峰/尖峰群系并入高海拔山地：
                // e>0.60（与原 PEAK 判定一致）的山顶区用 FROZEN/JAGGED_PEAKS，其余山地按气候映射。
                if (cell.e > 0.60) {
                    yield cell.isSnow ? Biomes.FROZEN_PEAKS : Biomes.JAGGED_PEAKS;
                }
                if (cell.isSnow) {
                    yield Biomes.STONY_PEAKS;
                }
                // 热带湿润山地 → 云林/热带山地雨林（安第斯东麓/喜马拉雅南麓）
                if (climate.isHot() && climate.isWet()) {
                    yield Biomes.JUNGLE;
                }
                // 热带干旱山地 → 干燥山地（安第斯西麓/兴都库什）
                if (climate.isHot() && climate.isDry()) {
                    yield Biomes.SAVANNA;
                }
                // 寒温带山地 → 针叶林（落基山/阿尔卑斯）
                if (climate.isCold()) {
                    yield Biomes.TAIGA;
                }
                // 内陆干燥山地 → 稀树草原
                if (climate.isInland() && climate.isDry()) {
                    yield Biomes.SAVANNA;
                }
                // 温带山地 → 森林（默认）
                yield Biomes.FOREST;
            }

            case BASIN -> climate.isDry()
                ? Biomes.DESERT : Biomes.MEADOW;

            case VOLCANO -> {
                // 火山 = 岩石山地形态，复用岩石群系（未来可换黑曜石/玄武岩地表）
                if (cell.isSnow) yield Biomes.FROZEN_PEAKS;
                if (climate.isHot() && climate.isWet()) yield Biomes.JUNGLE;
                if (climate.isCold()) yield Biomes.TAIGA;
                yield Biomes.JAGGED_PEAKS;
            }
            case VOLCANIC_FIELD -> {
                // 火山群 = 低矮岩石丘陵，复用丘陵群系
                if (climate.isCold()) yield Biomes.WINDSWEPT_HILLS;
                if (climate.isCoastal() && climate.isWet()) yield Biomes.TAIGA;
                yield Biomes.WINDSWEPT_HILLS;
            }
            case PEAK -> Biomes.JAGGED_PEAKS; // 不应命中（2026-08-05 分类不再产出），保持 switch 穷举
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
            case VOLCANO  -> BiomeClass.MOUNTAINS;
            case VOLCANIC_FIELD -> BiomeClass.HILLS;
        };
    }
}
