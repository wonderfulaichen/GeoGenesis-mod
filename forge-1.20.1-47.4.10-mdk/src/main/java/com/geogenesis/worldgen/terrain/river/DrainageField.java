package com.geogenesis.worldgen.terrain.river;

import com.geogenesis.worldgen.noise.Frequency;
import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.Noises;
import com.geogenesis.worldgen.noise.Simplex;

/**
 * 低频排水场（TF 式 continent 场的等价物）。
 *
 * <p>作用：河网走向（{@code connects} 下坡判定）与河谷基准高度（bedLevel）统一由本场决定。
 * 本场是大尺度 simplex 叠加（freq 1/3000 ~ 1/700），梯度连续、无高频扰动 → 河网顺宏观坡、
 * 连续成网，不破碎。真实地形（terrainE）只作雕刻表面（carve 作用在真实地形上）。
 *
 * <p>这是「河流贴真实地形」且「网络连续」的关键：TF 用低频 {@code continent} 场决定走向与基底，
 * 用真实 {@code noiseHeight} 做雕刻表面；本类即 continent 场的等价实现。</p>
 */
public final class DrainageField {

    private final Noise n1, n2, n3;

    public DrainageField(long seed) {
        // 3 个大尺度 simplex 叠加（fbm），低频主导 → 宏观连续坡
        this.n1 = new Frequency(new Simplex(101), 1.0 / 3000.0);
        this.n2 = new Frequency(new Simplex(202), 1.0 / 1500.0);
        this.n3 = new Frequency(new Simplex(303), 1.0 / 700.0);
        seed(seed);
    }

    public void seed(long worldSeed) {
        Noises.seedAll(n1, worldSeed, 0);
        Noises.seedAll(n2, worldSeed, 0);
        Noises.seedAll(n3, worldSeed, 0);
    }

    /** 采样低频排水场，归一化到 [0,1]。低频主导，宏观连续。 */
    public double sample(double wx, double wz) {
        double v = n1.compute(wx, wz) * 0.6
                 + n2.compute(wx, wz) * 0.3
                 + n3.compute(wx, wz) * 0.1;
        return (v + 1.0) * 0.5; // [-1,1] -> [0,1]
    }
}
