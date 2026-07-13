package com.geogenesis.worldgen.erosion;

/**
 * 多营力侵蚀编排器（v1 局部算子框架）。
 *
 * <p>按固定顺序组合各 {@link ErosionAgent} 作用于统一 e 场：
 * Thermal（坡积软化）→ Coastal（海岸冲刷）→ Glacial/Wind（留桩）。
 * 全部局部算子，跨 chunk 无缝、无 flow-accumulation 的 border 断裂。
 *
 * <p>调用约定：由 {@code GeoGenesisTerrain.generateChunk} 在采样之后、河流刻蚀之前，
 * 对 chunk 级 e 网格（含 pad 邻域）施加，再写回 Cell 并重分类。
 */
public final class ErosionSystem {

    private final ErosionSettings settings;
    private final ErosionAgent[] agents;

    public ErosionSystem(ErosionSettings settings) {
        this.settings = settings;
        this.agents = new ErosionAgent[]{
            new Thermal(),
            new Coastal(),
            new Glacial(),
            new Wind()
        };
    }

    /** 对 e 网格（size = chunkSize + 2*pad）施加全部营力（原地修改）。 */
    public void apply(double[][] e, int size, int pad) {
        for (ErosionAgent a : agents) {
            a.apply(e, size, pad, settings);
        }
    }
}
