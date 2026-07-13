package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 大陆场生成器：低频 Simplex + Warp → 连续大陆性 c ∈ [-1,1]。
 *
 * 范式：单个 Simplex 经 Frequency 缩放（1/continentScale）→ Warp 域扭曲 →
 * 直接返回原始噪声值（[-1,1]），对齐 MC 原版 Continentalness。
 * 负=海洋、正=陆地、0=海岸锚点。无需 Voronoi 细胞点。
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
     * 采样大陆性 c ∈ [-1,1]（负=海洋、正=陆地、0=海岸锚点），对齐 MC 原版 Continentalness。
     * @param wx 世界 X 坐标
     * @param wz 世界 Z 坐标
     * @return 连续大陆性，≈-1=深海，≈0=海岸，≈+1=内陆核心
     */
    public double sample(double wx, double wz) {
        // 经 Warp 的噪声 ∈ [-1,1]；直接返回，不做归一化（对齐 MC 原版区间）
        return root.compute(wx, wz);
    }

    /** 海陆布尔判定（c≥threshold→陆地；[-1,1]区间 threshold 默认 0.0，即 c≥0 为陆、c<0 为海） */
    public boolean isLand(double c) {
        return c >= threshold;
    }
}
