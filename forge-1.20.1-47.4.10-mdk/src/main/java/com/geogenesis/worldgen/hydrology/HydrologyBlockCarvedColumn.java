package com.geogenesis.worldgen.hydrology;

/** 单个 Minecraft block 列的水文雕刻计划。 */
public record HydrologyBlockCarvedColumn(int blockX, int blockZ,
                                         double originalGroundY, double carvedGroundY,
                                         double waterSurfaceY,
                                         /** 瀑布水幕顶（唇口水位）：仅跌水列 &gt; waterSurfaceY；普通列等于 waterSurfaceY。 */
                                         double lipSurfaceY,
                                         double erosion,
                                         boolean fillWater,
                                         /** ★ 归一化距河心距离（2026-09-08）：distToCenter / 半宽，
                                          *  0=河道中心，1=岸缘（灌水门控①边界），&gt;1=错位带。
                                          *  落块侧用它做河道内侵蚀衰减（用户："河道内只需要
                                          *  一点点侵蚀影响"）。 */
                                         double distOverWidth) {
    /** 水幕高度（block）：0 = 普通列。 */
    public double fallDrop() {
        double d = lipSurfaceY - waterSurfaceY;
        return d > 0.0 ? d : 0.0;
    }
}
