package com.geogenesis.worldgen.hydrology;

/** 河流单元的确定性水面、河床和纵向坡降。 */
public record RiverWaterProfile(int cell, int downstream, double surface,
                                double bed, double slope, double discharge) {
}
