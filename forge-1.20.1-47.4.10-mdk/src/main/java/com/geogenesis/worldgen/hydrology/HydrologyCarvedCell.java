package com.geogenesis.worldgen.hydrology;

/** 水文雕刻实验结果：保留原始高度并给出受限后的目标高度。 */
public record HydrologyCarvedCell(int cell, double originalHeight, double carvedHeight,
                                  double erosion, HydrologyRiverSample sample) {
}
