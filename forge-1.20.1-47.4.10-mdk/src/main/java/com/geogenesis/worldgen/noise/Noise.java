package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;

/**
 * 零 Minecraft 依赖的二维噪声接口（地形高度采样只用 x,z）。
 * 所有节点实现 {@link #compute(double, double)}，并给出可序列化端点。
 */
public interface Noise {

    /**
     * 输入世界 block 坐标 (x, z)，返回标量噪声值。
     * 约定：基础噪声返回 [-1,1]，运算节点保持区间约定由实现保证。
     */
    double compute(double x, double z);

    /**
     * 该噪声在地球上可能的最小值（用于归一化/调参）。
     */
    default double minValue() {
        return -1.0;
    }

    /**
     * 该噪声在地球上可能的最大值。
     */
    default double maxValue() {
        return 1.0;
    }

    /**
     * 递归替换子节点（噪声图变换用）。叶子节点返回自身。
     */
    default Noise mapAll(Visitor visitor) {
        return visitor.apply(this);
    }

    /** 遍历访问者（函数式）。 */
    @FunctionalInterface
    interface Visitor {
        Noise apply(Noise noise);
    }

    /**
     * 所有可序列化噪声节点的统一编码键：{@code type} 区分具体实现，
     * 由 {@link Noises#DIRECT_CODEC} 分发。
     */
    Codec<Noise> CODEC = Noises.DIRECT_CODEC;
}
