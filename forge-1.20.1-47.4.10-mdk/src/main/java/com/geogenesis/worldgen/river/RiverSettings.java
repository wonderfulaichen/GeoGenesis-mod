package com.geogenesis.worldgen.river;

/**
 * 河网几何 / 刻蚀参数记录（运行期由 GeoGenesisConfig「River Network」段注入；
 * 独立预览暂用 {@link #defaults()}）。
 *
 * <p>参数语义对齐 {@code terrain-rebuild-design.md §2.2}：
 * 粗格点间距、河宽范围、瀑布落差、源湖概率、流量门控与河谷几何。
 */
public record RiverSettings(
    /** 粗格点间距（块）。默认 40 → 河网尺度 */
    double gridSize,
    /** 最小河宽（块），对应小溪 */
    double minWidth,
    /** 最大河宽（块），对应大江 */
    double maxWidth,
    /** 瀑布判定落差（e 单位），相邻河段端点落差超过即标记 */
    double waterfallDrop,
    /** 源头湖概率（局部汇水洼地附湖盆） */
    double sourceLakeChance,
    /** 源头湖半径（块） */
    double sourceRadius,
    /** 河网密度门控（调整流量阈值灵敏度） */
    double density,
    /** 河床下切深度（e 单位，河心） */
    double bedDepth,
    /** 河谷肩抬升/谷壁深度（e 单位，谷缘） */
    double bankDepth,
    /** 流量门控下界：流量低于此值不刻蚀（消除平坦区密集沟壑） */
    double flowMin,
    /** 流量门控上界：流量达到此值刻蚀满强度 */
    double flowFull,
    /** 刻蚀下界 e：e 低于此值（深海盆）不刻蚀；陆架/海岸（e>-0.35）仍刻蚀成水下河谷 */
    double riverMinE
) {
    public static RiverSettings defaults() {
        return new RiverSettings(
            40.0,    // gridSize
            1.5,     // minWidth
            6.0,     // maxWidth
            0.10,    // waterfallDrop
            0.20,    // sourceLakeChance
            3.0,     // sourceRadius
            1.0,     // density
            0.08,    // bedDepth
            0.02,    // bankDepth
            3.0,     // flowMin
            12.0,    // flowFull
            -0.35     // riverMinE（陆架下限：河口/水下河谷刻蚀到大陆架，深海盆不刻）
        );
    }
}
