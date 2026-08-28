package com.geogenesis.worldgen.hydrology;

/** 流量驱动的河道横断面参数；不直接修改地形，仅描述未来雕刻输入。 */
public record RiverCrossSection(double width, double depth, double bankWidth,
                                double bankHeight, double valleyWidth) {
    public RiverCrossSection {
        if (!(width > 0.0) || !(depth > 0.0) || !(bankWidth >= width)
                || !(bankHeight >= 0.0) || !(valleyWidth >= bankWidth)) {
            throw new IllegalArgumentException("invalid river cross section");
        }
    }
}
