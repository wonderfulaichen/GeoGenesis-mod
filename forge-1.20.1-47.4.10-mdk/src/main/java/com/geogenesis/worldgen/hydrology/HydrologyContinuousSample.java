package com.geogenesis.worldgen.hydrology;

/** 任意世界坐标的连续水文采样结果。 */
public record HydrologyContinuousSample(double surfaceY, double bedY,
                                        double width, double depth,
                                        double bankWidth, double valleyWidth,
                                        double discharge, RiverOutlet.Type outletType) {
}
