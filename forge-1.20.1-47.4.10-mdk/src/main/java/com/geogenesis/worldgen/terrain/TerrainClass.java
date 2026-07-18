package com.geogenesis.worldgen.terrain;

/**
 * 12 地形类型枚举（地理标准，纯分类，不持颜色）。
 * 由地形形态连续判定（非量化椒盐），与 BiomeClassifier 配合映射原版群系。
 */
public enum TerrainClass {
    OCEAN,              // 海洋（深海以外）
    DEEP_OCEAN,         // 深海
    CONTINENTAL_SHELF,  // 大陆架（浅海，e > -0.08）
    SUBMARINE_RIDGE,    // 洋中脊（海底山脉）
    SEAMOUNT,           // 海山/海底火山
    LAKE,               // 湖泊
    RIVER,              // 河流
    BEACH,              // 海滩
    PLAIN,              // 平原
    HILLS,              // 丘陵
    PLATEAU,            // 高原
    MOUNTAINS,          // 山地
    PEAK,               // 山峰
    BASIN,              // 盆地
    SNOW;               // 雪原（温度驱动）

    /** 总类型数 */
    public static final int COUNT = values().length;

    /** 按索引获取（用于 GeoPalette terrainTypeId） */
    public static TerrainClass byId(int id) {
        var vals = values();
        return id >= 0 && id < vals.length ? vals[id] : PLAIN;
    }

    /** 当前索引（用于 GeoPalette 图例） */
    public int id() {
        return ordinal();
    }

    /** 是否为海洋类型（含大陆架、洋中脊、海山） */
    public boolean isOcean() {
        return this == OCEAN || this == DEEP_OCEAN || this == CONTINENTAL_SHELF
            || this == SUBMARINE_RIDGE || this == SEAMOUNT;
    }

    /** 是否为水域类型 */
    public boolean isWater() {
        return isOcean() || this == LAKE || this == RIVER;
    }
}
