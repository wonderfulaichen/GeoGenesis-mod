package com.geogenesis.worldgen.hydrology.sim;

/**
 * chunk / block 侧的统一查询接口 —— 生产只允许通过它消费水文。
 *
 * <p>旧链有 {@code sampleBlock} / {@code sampleBlockAll} / {@code sampleAll} /
 * {@code lakeHits} 等多个入口，各自推导湖位 ⇒ 必然不一致。新核心只暴露这两个方法。</p>
 */
public interface HydroQuery {

    /** 按世界方块坐标查询。无水文意义的格返回 {@code null}（调用方按"无河"处理）。 */
    HydroSample sample(double worldX, double worldZ);

    /** 按整块坐标查询（等价于 {@link #sample}，语义更贴近 chunk 落块）。 */
    default HydroSample sampleColumn(int blockX, int blockZ) {
        return sample(blockX + 0.5, blockZ + 0.5);
    }

    /** 该点是否属于某盆地（用于湖岸判定的快速排除）。 */
    default boolean inBasin(double worldX, double worldZ) {
        HydroSample s = sample(worldX, worldZ);
        return s != null && s.basinId() >= 0;
    }
}
