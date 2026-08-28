package com.geogenesis.worldgen.hydrology;

/** 水文结果到未来游戏河流适配层的只读契约，不依赖 Minecraft。 */
public record HydrologyRiverSample(int cell, int downstream, double surfaceY,
                                   double bedY, double width, double depth,
                                   double bankWidth, double valleyWidth,
                                   double discharge, RiverOutlet.Type outletType) {
}
