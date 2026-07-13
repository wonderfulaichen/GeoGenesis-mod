package com.geogenesis.worldgen.river;

/**
 * 高度场提供接口（解耦 RiverField 与地形引擎）。
 *
 * <p>RiverField 在粗格点下坡汇流时，通过该接口查询真实陆地高度场 e，
 * 不依赖具体地形实现，从而与 {@code CellGenerator} 的 {@code sample} 解耦。
 */
public interface HeightProvider {

    /**
     * 返回世界坐标 (wx, wz) 处的陆地归一化高度 e ∈ [-1, 1]。
     * 海洋 / 未定义区域返回 {@link Double#NaN}（河流可顺地形排入，但不穿过海洋汇流）。
     *
     * @param wx 世界 X（整数块坐标）
     * @param wz 世界 Z（整数块坐标）
     */
    double landHeight(int wx, int wz);

    /**
     * 返回世界坐标 (wx, wz) 处的「统一 e」∈ [-1, 1]：海洋为曲线海床 e（负值），
     * 陆地为最终 e（正值）。与 {@link #landHeight} 不同，本方法不把海洋当 NaN，
     * 使河网能沿陆架海床连续汇入海洋（河口 / 水下河谷），避免海陆割裂。
     *
     * @param wx 世界 X（整数块坐标）
     * @param wz 世界 Z（整数块坐标）
     */
    double terrainE(int wx, int wz);
}
