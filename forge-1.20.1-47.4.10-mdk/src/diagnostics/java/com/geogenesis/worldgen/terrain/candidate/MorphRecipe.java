package com.geogenesis.worldgen.terrain.candidate;

/** 节点描述；源信号不含最终线性外壳，高度单位由目录声明。 */
interface MorphRecipe {
    enum Role { LOW, PLATEAU, BADLANDS, MOUNTAIN }

    double raw(double x, double z);
    double amplitude(double x, double z);
    double bias(double x, double z);
    default Role role() { return Role.LOW; }
    default boolean land() { return false; }
    default boolean clamped() { return false; }
    default boolean ceiling() { return false; }
}
