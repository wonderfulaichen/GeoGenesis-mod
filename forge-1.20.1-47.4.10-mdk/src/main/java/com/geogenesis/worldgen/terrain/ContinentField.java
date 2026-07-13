package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 大陆场生成器：低频 Simplex + Warp → 连续大陆性 c ∈ [0,1]。
 *
 * 范式：单个 Simplex 经 Frequency 缩放（1/continentScale）→ Warp 域扭曲 →
 * 归一化到 [0,1]。无需 Voronoi 细胞点。
 *
 * 用法：
 *   field.seed(worldSeed); // 播种所有 Seeded 节点
 *   double c = field.sample(wx, wz); // 世界坐标
 */
public final class ContinentField {

    private final Noise root;
    private final double threshold;

    /** 构建噪声图（低频 Simplex + Warp 扭曲） */
    public ContinentField(TerrainParams p) {
        this.threshold = p.continentThreshold();

        // 低频大陆基底
        Simplex base = new Simplex(0);
        Frequency freq = new Frequency(base, 1.0 / p.continentScale());

        // 域扭曲（用另一个 Simplex 但同频率 → 通过 Frequency 自然分化）
        Simplex warpX = new Simplex(1);
        Simplex warpZ = new Simplex(2);
        Frequency warpFreqX = new Frequency(warpX, 1.0 / p.continentScale());
        Frequency warpFreqZ = new Frequency(warpZ, 1.0 / p.continentScale());

        this.root = new Warp(freq, warpFreqX, warpFreqZ, p.continentWarp());
    }

    /** 一次性播种所有 Seeded 节点 */
    public void seed(long worldSeed) {
        Noises.seedAll(root, worldSeed, 0);
    }

    /**
     * 采样大陆性 c ∈ [0,1]（深海→内陆）。
     * @param wx 世界 X 坐标
     * @param wz 世界 Z 坐标
     * @return 连续大陆性，≈0=深海，≈1=内陆
     */
    public double sample(double wx, double wz) {
        // 经 Warp 的噪声 ∈ [-1,1]
        double raw = root.compute(wx, wz);
        // 归一化到 [0,1]
        return NoiseUtil.clamp((raw + 1.0) * 0.5, 0.0, 1.0);
    }

    /** 海陆布尔判定 */
    public boolean isLand(double c) {
        return c >= threshold;
    }
}
