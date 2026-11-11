package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 大陆场生成器：多倍频 FBM（值叠加）— 大陆性 c ∈ [-1,1]。
 *
 * 范式：c = 归一化 FBM。各 octave 为独立 Simplex，频率 1/(continentScale·lac^i)、
 * 振幅 persistence^i，叠加后按 RMS（sqrt(Σamp²)）归一化，使 c 整体方差≈单 simplex
 * 方差，从而由 continentBias 校准的海陆比保持稳定（不受 FBM 多尺度分布影响）。
 *
 * 海岸线 = c=0 等值线。FBM 把 60~125 块（默认 6 octaves，波长 4000→125 块）的多尺度
 * 细节直接刻进 c，海岸线自然犬牙交错、自相似分形（对标参考项目 procedural-island-generator
 * 的 fbm 海岸线：FBM 是海陆主因，海岸由等高线涌现）。
 *
 * 可选域扭曲（continentWarp）：用低频噪声位移采样坐标，弯曲大陆边界（任意大尺度），
 * 各 octave 位移 < λ/4 防 Jacobian 折叠。
 *
 * 用法：
 *   field.seed(worldSeed);        // 播种所有 Seeded 节点
 *   double c = field.sample(wx, wz); // c∈[-1,1]，负=海洋、正=陆地、0=海岸锚点
 */
public final class ContinentField {

    private final Noise root;
    private final double threshold;

    /** 构建噪声图（多倍频 FBM 值叠加，可选域扭曲） */
    public ContinentField(TerrainParams p) {
        this.threshold = p.continentThreshold();
        double scale = p.continentScale();
        int octaves = clampOct(p.continentFbmOctaves());
        double lac = p.continentFbmLacunarity();
        double per = p.continentFbmPersistence();
        double warpAmt = p.continentWarp() * scale; // 域扭曲振幅（块）

        // 多倍频 FBM：octave i, freq = 1/(scale·lac^i), amp = per^i
        // 基频 = 大陆块尺度（保留 macro 大陆结构），高频 octave 提供海岸线破碎细节。
        Noise sum = new Constant(0.0);
        double q = 0.0; // Σamp² 用于 RMS 归一化
        double freq = 1.0 / scale;
        double amp = 1.0;
        for (int i = 0; i < octaves; i++) {
            Noise o = new Frequency(new Simplex(7000 + i * 97), freq);
            sum = new Add(sum, new Multiply(o, new Constant(amp)));
            q += amp * amp;
            freq *= lac;
            amp *= per;
        }
        // RMS 归一化：c 整体方差≈单 simplex 方差 → continentBias 海陆比校准保持稳定。
        double norm = Math.sqrt(q);
        Noise fbm = new Multiply(sum, new Constant(1.0 / norm));

        // 可选域扭曲（低频弯曲大陆边界；位移 < λ/4 防 Jacobian 折叠）
        if (warpAmt > 1e-6) {
            double wf = 1.0 / scale;
            Noise wx = new Frequency(new Simplex(7100), wf);
            Noise wz = new Frequency(new Simplex(7101), wf);
            Noise wx2 = new Frequency(new Simplex(7102), wf * 2.0);
            Noise wz2 = new Frequency(new Simplex(7103), wf * 2.0);
            // 2-octave warp（主低频 + 半幅二次谐波），更自然的大陆弯曲
            Noise warpX = new Add(wx, new Multiply(wx2, new Constant(0.5)));
            Noise warpZ = new Add(wz, new Multiply(wz2, new Constant(0.5)));
            this.root = new Warp(fbm, warpX, warpZ, warpAmt);
        } else {
            this.root = fbm;
        }
    }

    private static int clampOct(int v) {
        return Math.max(1, Math.min(10, v));
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
        return root.compute(wx, wz);
    }

    /** 海陆布尔判定（c≥threshold→陆地；[-1,1]区间 threshold 默认 0.0，即 c≥0 为陆、c<0 为海） */
    public boolean isLand(double c) {
        return c >= threshold;
    }
}
