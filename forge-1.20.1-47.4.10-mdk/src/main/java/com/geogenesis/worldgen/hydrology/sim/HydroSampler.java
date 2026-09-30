package com.geogenesis.worldgen.hydrology.sim;

/**
 * 新水文核心的输入契约：地形高度 + 侧向补给 + 沿程衰减（零 MC 依赖）。
 *
 * <p><b>只允许一个地形口径</b>：返回"雕刻前的真实地形高度（方块 Y）"。禁止在这里混入
 * 侵蚀增量、旧河床、旧湖面 —— 旧链的 {@code e 空间 / 块空间} 与
 * {@code height / groundYAt / sampleWu} 三套口径混用正是本项目反复出错的根因。</p>
 *
 * <p>实现必须对同一 {@code (x, z)} 返回同一值（纯函数），且与查询顺序无关。</p>
 */
public interface HydroSampler {

    /** 雕刻前真实地形高度（方块 Y）。 */
    double height(double blockX, double blockZ);

    /**
     * 侧向补给（降水 + 基流），单位 block³/单位时间（每格）。
     * 允许为 0（干旱），不允许为负。
     */
    default double source(double blockX, double blockZ) {
        return 1.0;
    }

    /**
     * 沿程衰减系数（1/block）：蒸发 + 入渗。
     * 0 = 湿润区，水可长距离入海；{@code >0} = 干旱区，影响半径有限（可据此截断端口递归）。
     */
    default double decay(double blockX, double blockZ) {
        return 0.0;
    }
}
