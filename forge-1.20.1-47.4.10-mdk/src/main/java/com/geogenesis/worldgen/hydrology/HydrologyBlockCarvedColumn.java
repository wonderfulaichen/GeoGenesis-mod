package com.geogenesis.worldgen.hydrology;

/** 单个 Minecraft block 列的水文雕刻计划。 */
public record HydrologyBlockCarvedColumn(int blockX, int blockZ,
                                         double originalGroundY, double carvedGroundY,
                                         double waterSurfaceY,
                                         /** 瀑布水幕顶（唇口水位）：仅跌水列 &gt; waterSurfaceY；普通列等于 waterSurfaceY。 */
                                         double lipSurfaceY,
                                         double erosion,
                                         boolean fillWater) {
    /** 水幕高度（block）：0 = 普通列。 */
    public double fallDrop() {
        double d = lipSurfaceY - waterSurfaceY;
        return d > 0.0 ? d : 0.0;
    }
}
