package com.geogenesis.worldgen.hydrology;

/**
 * Minecraft block 坐标对应的连续水文采样结果（河线距离场版）。
 *
 * @param surfaceY 水面世界 Y（沿线纵剖面）
 * @param bedY     河床世界 Y（水面 − depth）
 * @param width    河道半宽（block）
 * @param depth    河床深（水面以下，block）
 * @param bankWidth 岸带宽（block，= 半宽 × bankFactor）
 * @param valleyWidth 雕刻影响半径（block；距离超过此值不雕）
 * @param discharge 汇水面积（wu²）
 * @param outletType 出口类型（null=普通河段）
 * @param distToCenter 到河线中心距离（block）—— 距离场雕刻核心量
 */
public record HydrologyBlockSample(double surfaceY, double bedY,
                                   double width, double depth,
                                   double bankWidth, double valleyWidth,
                                   double discharge, RiverOutlet.Type outletType,
                                   double distToCenter) {

    /** 兼容旧构造（distToCenter 缺省 = 0，即河道中心）。 */
    public HydrologyBlockSample(double surfaceY, double bedY,
                                double width, double depth,
                                double bankWidth, double valleyWidth,
                                double discharge, RiverOutlet.Type outletType) {
        this(surfaceY, bedY, width, depth, bankWidth, valleyWidth,
                discharge, outletType, 0.0);
    }
}
