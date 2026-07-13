package com.geogenesis.worldgen.erosion;

/**
 * 单营力侵蚀算子接口（局部算子范式）。
 *
 * <p>算子对统一高度场 {@code e[][]}（以 (originX,originZ) 为原点，size=chunkSize+2*pad）
 * 施加本营力修饰。<b>必须为局部</b>：仅访问 (i,j) 的 pad 邻域，不超越 tile 边界，
 * 故跨 chunk 无缝、无 flow-accumulation 的 border 断裂。
 */
public interface ErosionAgent {

    /**
     * @param e    统一高度场（e ∈ [-1,1]），可原地修改
     * @param size 网格边长（含 pad）
     * @param pad  边界填充宽度（真实 chunk 区为 [pad, size-pad)）
     * @param s    侵蚀参数
     */
    void apply(double[][] e, int size, int pad, ErosionSettings s);
}
