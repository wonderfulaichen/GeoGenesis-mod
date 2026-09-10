package com.geogenesis.worldgen.climate;

/**
 * Whittaker 生物群区（温度 × 降水）解析式分类。
 *
 * <p>取代原先「Köppen 5 带 + 干湿二值」的粗分类。原分类把温度/湿度各自压成离散带后
 * 直接叉乘，导致 <b>热带雨林与热带草原只隔一个布尔阈值</b> → 生态上不可能的相邻（丛林紧挨草原）。
 *
 * <p>Whittaker 图的核心性质（本项目以解析式复现）：
 * <ul>
 *   <li><b>成林/成荒漠的降水阈值随温度升高而升高</b> —— 暖处蒸发强，需要更多降水才成森林；
 *       故同一降水量在寒带是针叶林，在热带只是稀树草原。</li>
 *   <li><b>相邻群系之间必有过渡型</b>：热带草原 → 季雨林 → 雨林，不会跳变。</li>
 *   <li><b>极寒处再湿也不成林</b>（苔原/冰原），由温度轴直接封顶。</li>
 * </ul>
 *
 * <p>参考：FreeTerraForged 用 {@code /biomes.png} 位图表达同一张图；这里用解析式实现，
 * 无外部资源、可调参，并可在诊断探针里直接打印成 ASCII 图做邻接检查。
 *
 * <p>输入均为 [0,1]（由 [-1,1] 的 temperature/humidity 线性映射而来）。
 */
public enum WhittakerType {
    /** 冰原 */
    ICE,
    /** 苔原（极地/高山，无乔木） */
    TUNDRA,
    /** 北方针叶林（泰加林） */
    TAIGA,
    /** 温带草原 */
    GRASSLAND,
    /** 温带落叶林 */
    TEMPERATE_FOREST,
    /** 温带雨林（极湿温带） */
    TEMPERATE_RAINFOREST,
    /** 荒漠（热 → 亚热带沙漠；冷 → 冷荒漠） */
    DESERT,
    /** 热带稀树草原 */
    SAVANNA,
    /** 热带季雨林 */
    SEASONAL_FOREST,
    /** 热带雨林 */
    TROPICAL_RAINFOREST;

    // ===== 温度轴分界（[0,1] 归一化温度）=====
    private static final double T_ICE = 0.12;
    private static final double T_TUNDRA = 0.24;
    private static final double T_BOREAL = 0.42;
    private static final double T_TROPIC = 0.72;

    // ===== 降水轴分界：随温度线性抬升（暖 → 蒸发强，需要更多降水才成林）=====
    // 【2026-09-10】曾为热带单独设固定阈值，导致 t=0.72 处阈值跳变 →
    // 图上出现夹在草原与稀树草原之间的"沙漠碎片"，且世界邻接检查报大量违例。
    // 现统一为同一组线性公式，全温度轴连续。
    private static final double M_DESERT_BASE = 0.16, M_DESERT_SLOPE = 0.24; // 0.16(冷) → 0.40(热)
    private static final double M_GRASS_BASE = 0.40,  M_GRASS_SLOPE = 0.20;  // 0.40 → 0.60
    private static final double M_FOREST_BASE = 0.70, M_FOREST_SLOPE = 0.14; // 0.70 → 0.84

    /**
     * Whittaker 分类。
     *
     * @param temp01  归一化温度 [0,1]（0=极寒，1=极热）
     * @param moist01 归一化降水/湿度 [0,1]（0=极干，1=极湿）
     */
    public static WhittakerType classify(double temp01, double moist01) {
        double t = clamp01(temp01);
        double m = clamp01(moist01);

        // 极地：温度直接封顶，再湿也不成林
        if (t < T_ICE) return ICE;
        if (t < T_TUNDRA) return m > 0.55 ? TUNDRA : ICE;
        // 寒温带：湿 → 针叶林，干 → 苔原
        if (t < T_BOREAL) return m > 0.42 ? TAIGA : TUNDRA;

        // 降水阈值随温度连续抬升；同一区间在热带/温带取不同的名字（稀树草原 vs 草原、
        // 季雨林 vs 落叶林、雨林 vs 温带雨林），保证跨温度带也连续。
        double desertMax = M_DESERT_BASE + M_DESERT_SLOPE * t;
        double grassMax = M_GRASS_BASE + M_GRASS_SLOPE * t;
        double forestMax = M_FOREST_BASE + M_FOREST_SLOPE * t;
        boolean tropic = t >= T_TROPIC;

        if (m <= desertMax) return DESERT;
        if (m <= grassMax) return tropic ? SAVANNA : GRASSLAND;
        if (m <= forestMax) return tropic ? SEASONAL_FOREST : TEMPERATE_FOREST;
        return tropic ? TROPICAL_RAINFOREST : TEMPERATE_RAINFOREST;
    }

    /** 是否寒带（用于山地垂直带谱选择高山变体） */
    public boolean isCold() {
        return this == ICE || this == TUNDRA || this == TAIGA;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
