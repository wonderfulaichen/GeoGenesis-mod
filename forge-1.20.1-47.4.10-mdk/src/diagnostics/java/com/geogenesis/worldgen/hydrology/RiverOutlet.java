package com.geogenesis.worldgen.hydrology;

/** 河段终点的排水语义，区分海洋、湖泊、区域边界和异常陆地终止。 */
public record RiverOutlet(int cell, Type type, double flow) {
    public enum Type { OCEAN, LAKE, REGION_BOUNDARY, LAND_SINK }
}
