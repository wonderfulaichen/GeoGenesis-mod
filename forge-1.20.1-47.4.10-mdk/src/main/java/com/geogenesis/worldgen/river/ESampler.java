package com.geogenesis.worldgen.river;

/**
 * 函数式 e 场采样（确定性几何河网 · Phase 1）。
 *
 * <p>坐标语义 = wu（引擎铁律）。游戏内用 {@code gen::terrainEQuick} 包装；
 * 探针进程同样包装独立 CellGenerator。</p>
 */
@FunctionalInterface
public interface ESampler {

    /** 采样世界坐标 (wx,wz) 的归一化地形 e（侵蚀前场，构建期确定性） */
    double eAt(double wx, double wz);
}
