package com.geogenesis.worldgen.hydrology;

/**
 * 单列雕刻计划（model 链唯一列语义；legacy 链已于 2026-10-20 退役）。
 *
 * @param blockX          方块 X（世界坐标）
 * @param blockZ          方块 Z（世界坐标）
 * @param originalGroundY 无侵蚀基线高度
 * @param carvedGroundY   计划雕刻后床面高度（湖列 = original：湖不雕刻）
 * @param waterSurfaceY   计划水面高度（不出水列 = carved）
 * @param lipSurfaceY     岸沿（lip）水面高度 —— 与 waterSurfaceY 之差即瀑布水幕
 * @param erosion         河床下切量（≥0；湖列 0）
 * @param erosionMask     侵蚀让步掩码：河心 0（床面不吃侵蚀 delta）、谷外 1
 *                        —— 雕刻量 cut 的淡出同步归零，边界连续无断面墙
 * @param fillWater       本列是否放水
 * @param lakePlan        本列是否湖水面（model 链：世界盆路由发布的单平面湖列）
 * @param lakeNode        恒 null（2026-10-20）：legacy LakeNode 已随 legacy 链退役；
 *                        字段保留以维持落块层签名兼容，消费方（逃逸重算/洪泛精修）均已移除
 * @param lakeLevelY      湖水位（model 链 = 盆单平面；非湖列 NaN）
 */
public record HydrologyBlockCarvedColumn(int blockX, int blockZ, double originalGroundY,
                                         double carvedGroundY, double waterSurfaceY,
                                         double lipSurfaceY, double erosion,
                                         double erosionMask, boolean fillWater,
                                         boolean lakePlan,
                                         Object lakeNode,
                                         double lakeLevelY) {

    /** 兼容旧调用（无湖节点 / 无湖水位）。 */
    public HydrologyBlockCarvedColumn(int blockX, int blockZ, double originalGroundY,
                                      double carvedGroundY, double waterSurfaceY,
                                      double lipSurfaceY, double erosion,
                                      double erosionMask, boolean fillWater,
                                      boolean lakePlan) {
        this(blockX, blockZ, originalGroundY, carvedGroundY, waterSurfaceY, lipSurfaceY,
             erosion, erosionMask, fillWater, lakePlan, null, Double.NaN);
    }

    /** 兼容：有湖节点、无独立湖水位（lakeLevelY = NaN）。 */
    public HydrologyBlockCarvedColumn(int blockX, int blockZ, double originalGroundY,
                                      double carvedGroundY, double waterSurfaceY,
                                      double lipSurfaceY, double erosion,
                                      double erosionMask, boolean fillWater,
                                      boolean lakePlan,
                                      Object lakeNode) {
        this(blockX, blockZ, originalGroundY, carvedGroundY, waterSurfaceY, lipSurfaceY,
             erosion, erosionMask, fillWater, lakePlan, lakeNode, Double.NaN);
    }

    /** 水幕高度（block）：0 = 普通列。 */
    public double fallDrop() {
        double d = lipSurfaceY - waterSurfaceY;
        return d > 0.0 ? d : 0.0;
    }
}
