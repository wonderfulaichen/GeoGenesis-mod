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
                                         boolean lakePlan,
                                         /**
                                          * ★ 2026-09-17：湖节点引用（仅湖列非 null）。
                                          * 供落块层在【雕刻后的最终地形】上重算湖水位
                                          * （{@code LakeNode.escapeWaterLevel}）——
                                          * 修"水位求解早于雕刻"的阶段错位。见
                                          * {@code GeoGenesisTerrain.LAKE_ESCAPE_LEVEL}。
                                          */
                                         com.geogenesis.worldgen.hydrology.riverline
                                                 .RiverLineRegion.LakeNode lakeNode,
                                         /**
                                          * ★ 2026-09-17：本列所属湖的<b>水位</b>（NaN = 不受湖影响）。
                                          * <p>关键：被 {@code inFlood} 判为"非湖列"的列
                                          * （{@code lakePlan=false}）<b>也会带上湖节点 + 本水位</b>
                                          * —— 实测"低于水位却干"的岸线格全部来自这一类列
                                          * （粗格 BFS 把它们错杀），需要由落块层的
                                          * <b>1 块精度洪泛</b>在同一水位上重判。
                                          * 见 {@code GeoGenesisTerrain.LAKE_FINE_FLOOD}。</p>
                                          */
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
                                         com.geogenesis.worldgen.hydrology.riverline
                                               .RiverLineRegion.LakeNode lakeNode) {
                                         this(blockX, blockZ, originalGroundY, carvedGroundY, waterSurfaceY, lipSurfaceY,
                                         erosion, erosionMask, fillWater, lakePlan, lakeNode, Double.NaN);
                                         }
    /** 水幕高度（block）：0 = 普通列。 */
    public double fallDrop() {
        double d = lipSurfaceY - waterSurfaceY;
        return d > 0.0 ? d : 0.0;
    }
}
