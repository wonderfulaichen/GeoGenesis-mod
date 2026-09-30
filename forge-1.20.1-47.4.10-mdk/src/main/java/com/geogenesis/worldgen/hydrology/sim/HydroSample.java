package com.geogenesis.worldgen.hydrology.sim;

/**
 * 统一查询样本：雕刻层与预览只能消费这一个口径。
 *
 * <p>刻意把 {@code state} / {@code basinId} / {@code spillLevel} / {@code outletKind}
 * 一并带出：旧链的"湖位在这里算一遍、雕刻层再算一遍"正是水位不一致的根因。</p>
 */
public record HydroSample(

        /** 生命周期状态。 */
        FlowState state,

        /** 盆地 id（-1 = 非盆地）。 */
        int basinId,

        /** 所在盆地的最低溢口高程（方块 Y；无盆地为 NaN）。 */
        double spillLevel,

        /** 雕刻前地形高度（方块 Y）。 */
        double terrainY,

        /** 水面高度（方块 Y；NaN = 无水）。 */
        double surfaceY,

        /** 输运量（河）或蓄水量（湖），block³/单位时间。 */
        double discharge,

        /** 河宽（方块，半宽 ×2）。 */
        double width,

        /** 河深（方块）。 */
        double depth,

        /** 终点种类。 */
        OutcomeKind outletKind,

        /** 求解器版本（缓存失效判断用）。 */
        int solverVersion,

        /** 是否未决（需更大半径重解）。 */
        boolean pending) {

    public boolean isWater() {
        return state.isWater();
    }

    public boolean isRiver() {
        return state.isFlowing();
    }
}
