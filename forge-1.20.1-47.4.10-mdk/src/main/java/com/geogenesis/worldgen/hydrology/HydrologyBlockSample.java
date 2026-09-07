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
 * @param fallDrop 跌水落差（block）：0 = 普通河段；>0 = 本列位于跌水潭侧，
 *                 需在 surfaceY（潭面）之上再填充 fallDrop 高的垂直水幕（瀑布）
 *                 —— 唇口水位 = surfaceY + fallDrop（旧 Streams fillRiver 语义）
 * @param frozen   冻结标记：本列处于瀑布段（唇口侧或跌水侧），carve 面与水面
 *                 必须取本列真值、禁止参与 k=4 格 IDW 竞争带混合——混合会把
 *                 垂直落差抹成缓坡、把崖顶边缘挖出凹坑（悬空沙块平台根因）。
 *                 唇口侧 frozen=true 且 fallDrop=0（只冻结、无水幕）。
 */
public record HydrologyBlockSample(double surfaceY, double bedY,
                                   double width, double depth,
                                   double bankWidth, double valleyWidth,
                                   double discharge, RiverOutlet.Type outletType,
                                   double distToCenter, double fallDrop,
                                   boolean frozen, boolean isLake) {

    /** 兼容旧构造（distToCenter 缺省 = 0，即河道中心；无跌水、不冻结、非湖）。 */
    public HydrologyBlockSample(double surfaceY, double bedY,
                                double width, double depth,
                                double bankWidth, double valleyWidth,
                                double discharge, RiverOutlet.Type outletType) {
        this(surfaceY, bedY, width, depth, bankWidth, valleyWidth,
                discharge, outletType, 0.0, 0.0, false, false);
    }

    /** 跌水列的唇口水位（水幕顶）：非跌水列即自身水面。 */
    public double lipSurfaceY() {
        return surfaceY + fallDrop;
    }
}
