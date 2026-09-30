package com.geogenesis.worldgen.hydrology.sim;

/**
 * 每个水文格的水文生命周期状态（唯一一套，禁止旁路另立枚举）。
 *
 * <p>用户定义的生命周期：<b>源头 → 汇流 → 洼地蓄水 → 最低溢口 → 继续下泄 → 入海</b>。
 * 本枚举就是这条链在格级上的投影；任何单元都必须能被归类到其中之一（{@link #NONE} 除外）。</p>
 */
public enum FlowState {

    /** 无水文意义的格（高于所有水体、且不产生流量）。 */
    NONE,

    /** 源头：产生侧向径流、且其全部上游入流为 0 的陆地格。 */
    SOURCE,

    /** 汇流/河道：侧向径流经 D8 边汇入的过水格，水面由流量决定。 */
    CHANNEL,

    /** 入湖口：河道水面被湖面接管的第一格（水面开始由蓄水而非流量决定）。 */
    BASIN_ENTRY,

    /** 洼地蓄水：湖面以下的格（静水），水位由该盆地自己的水量平衡求得。 */
    LAKE_STORAGE,

    /** 最低溢口：水越过盆地围合坎的那一格（水位 = 盆地 spillLevel）。 */
    SPILLWAY,

    /** 继续下泄：溢口外侧、由上游湖供水的过水格。 */
    DOWNSTREAM,

    /** 入海：与海连通、水面 = 海平面的格。 */
    SEA,

    /** 跨 tile 未决：水在本 tile 内未到海/盆地出口，需相邻 tile 继续（不是断头）。 */
    BOUNDARY_PENDING;

    /** 是否为"水体"（可渲染为水面的格）。 */
    public boolean isWater() {
        return this == LAKE_STORAGE || this == SEA;
    }

    /** 是否为"流动"（河线应经过的格）。 */
    public boolean isFlowing() {
        return this == SOURCE || this == CHANNEL || this == BASIN_ENTRY
                || this == SPILLWAY || this == DOWNSTREAM;
    }
}
