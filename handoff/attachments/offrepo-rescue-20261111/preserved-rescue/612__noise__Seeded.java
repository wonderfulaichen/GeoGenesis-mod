package com.geogenesis.worldgen.noise;

/**
 * 标记接口：该噪声节点需要世界种子来初始化（置换表、随机抖动等）。
 * 组合噪声在构造完成后由 {@link Noises#seedAll(Noise, long, int)} 统一播种。
 */
public interface Seeded {

    /**
     * 用世界种子与楼层层级初始化（层级用于同种子下的子域分离）。
     */
    void seed(long seed, int level);

    /**
     * 是否已播种（未播种时不应采样，调试用）。
     */
    default boolean isSeeded() {
        return true;
    }
}
