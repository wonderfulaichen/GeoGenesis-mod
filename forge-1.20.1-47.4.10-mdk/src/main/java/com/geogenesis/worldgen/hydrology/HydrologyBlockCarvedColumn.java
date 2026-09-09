package com.geogenesis.worldgen.hydrology;

/** 单个 Minecraft block 列的水文雕刻计划。 */
public record HydrologyBlockCarvedColumn(int blockX, int blockZ,
                                         double originalGroundY, double carvedGroundY,
                                         double waterSurfaceY,
                                         /** 瀑布水幕顶（唇口水位）：仅跌水列 &gt; waterSurfaceY；普通列等于 waterSurfaceY。 */
                                         double lipSurfaceY,
                                         double erosion,
                                         /**
                                          * 侵蚀让步系数（方案 A，RTF 式 erosionMask，2026-09-09）：
                                          * 本列河床应吃多少侵蚀 delta。河心→0（河床严格等于计划
                                          * carved，与计划水面自洽、单调、无干滩/阶梯），谷外→1
                                          * （全量侵蚀）。[width, valley] 间 smoothstep 过渡 → 与
                                          * 雕刻量 cut 的淡出同步归零，边界连续无断面墙。
                                          */
                                         double erosionMask,
                                         boolean fillWater,
                                         boolean lakePlan) {
    /** 水幕高度（block）：0 = 普通列。 */
    public double fallDrop() {
        double d = lipSurfaceY - waterSurfaceY;
        return d > 0.0 ? d : 0.0;
    }
}
