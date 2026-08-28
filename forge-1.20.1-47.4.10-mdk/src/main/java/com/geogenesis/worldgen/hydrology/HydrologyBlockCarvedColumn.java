package com.geogenesis.worldgen.hydrology;

/** 单个 Minecraft block 列的水文雕刻计划。 */
public record HydrologyBlockCarvedColumn(int blockX, int blockZ,
                                         double originalGroundY, double carvedGroundY,
                                         double waterSurfaceY, double erosion,
                                         boolean fillWater) {
}
