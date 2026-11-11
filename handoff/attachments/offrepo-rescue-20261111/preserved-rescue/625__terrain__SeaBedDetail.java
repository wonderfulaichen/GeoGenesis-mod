package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 海床细节生成：零均值小幅噪声叠加，只做海床起伏、不改变宏观深度剖面。
 *
 * 对齐 {@code ocean-river-architecture.md §2.2}：
 * - d ∈ [-1,1] = 0.7*低频 + 0.3*中频（Frequency 包裹 Simplex）
 * - 调用方 × seabedDetail 后叠加在 e=c 上
 * - 细节不过海岸线（e > 0 时 clobber 到 c）
 */
public final class SeaBedDetail {

    private final Noise root;
    private final double amplitude;

    public SeaBedDetail(TerrainParams p) {
        this.amplitude = p.seabedDetail();

        // 低频细节（Simplex，频率 ~1/200）
        Noise low = new Frequency(new Simplex(300), 1.0 / 200.0);
        // 中频细节（Simplex，频率 ~1/80）
        Noise mid = new Frequency(new Simplex(301), 1.0 / 80.0);

        // 加权混合：0.7*低 + 0.3*中（零均值，因 Simplex ∈ [-1,1]）
        Noise midScaled = new Multiply(mid, new Constant(0.3));
        Noise lowScaled = new Multiply(low, new Constant(0.7));
        this.root = new Add(lowScaled, midScaled);
    }

    /** 播种 */
    public void seed(long worldSeed) {
        Noises.seedAll(root, worldSeed, 0);
    }

    /**
     * 采样海床细节 d ∈ [-1,1]。
     * 调用方用 × amplitude 后叠加在 e=c 上。
     */
    public double sample(double wx, double wz) {
        return root.compute(wx, wz);
    }

    /** 振幅 */
    public double amplitude() { return amplitude; }
}
