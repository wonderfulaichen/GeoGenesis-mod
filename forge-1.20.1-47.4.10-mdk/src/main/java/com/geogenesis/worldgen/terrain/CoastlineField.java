package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 海岸线域扭曲 — 在 cBiased 空间施加多尺度分形（FBM）warp，使海岸线犬牙交错、自相似分形。
 * <p>
 * 仅做一件事：对海岸过渡带（coastBandWindow）内施加 c-space 噪声位移，
 * 让原先平滑的等值海岸线变得蜿蜒曲折。位移量在 coastLoc 处最大，向两侧
 * 线性衰减到 0，保证深海/内陆不受影响。
 * <p>
 * 多尺度机制（对标参考项目 procedural-island-generator 的 FBM 海岸线）：
 * 单频 simplex 无论振幅多大都只能产出"平滑圆弧"等值线；改用多倍频 FBM
 * （基频 warpScale，每倍频频率×lacunarity、振幅×persistence，叠加 octaves 层）
 * 后，从 ~300 块到 ~19 块都有细节，等值线天然分形 → 大半岛/中岬角/小锯齿并存。
 * <p>
 * 使用方式（CellGenerator.sample）：
 * <pre>
 *   double cWarp = coastline.warpDisplacement(wx, wz, cBiased);
 *   double cEdge = cBiased + cWarp;   // 唯一有效岸线坐标
 *   eLand = landShape.sample(blend, wx, wz, cEdge);   // eLand 用 cEdge
 *   blend 门控也直接用 cEdge                         // blend 用同一 cEdge
 * </pre>
 * <p>
 * 关键设计原则：eLand 计算 与 blend 门控使用同一个 cEdge，避免不一致。
 * 不含地形类型调制和群岛场（如有需要作为独立 addend 加在 e 上）。
 */
public final class CoastlineField {

    private final double coastLoc;
    private final double warpBand;   // coastBandWindow 半径（c 空间）

    private final double warpAmp;    // c-space 总振幅
    private final int octaves;        // FBM 倍频层数
    private final double lacunarity; // 每倍频频率倍增
    private final double persistence;// 每倍频振幅衰减
    private final double warpScale;  // 基频世界坐标尺度（块）

    // 多倍频 FBM：每个 octave 一个独立 simplex（不同 seedOffset + 频率翻倍）
    private final Noise[] octaveNoise;
    private final double[] octaveAmp;
    private final double normFactor; // 振幅总和，用于把 FBM 归一化到 ~[-1,1]

    public CoastlineField(TerrainParams p) {
        this.coastLoc = p.coastLoc();
        // band 半径取过渡带半宽，使窗口恰好覆盖 oceanFadeStart ~ landRampEnd
        double half = (p.landRampEnd() - p.oceanFadeStart()) * 0.5;
        this.warpBand = Math.max(half, 0.01);

        this.warpAmp = p.coastlineWarpAmp();
        this.warpScale = Math.max(p.coastlineWarpScale(), 10.0);
        this.octaves = Math.max(1, Math.min(8, p.coastlineWarpOctaves()));
        this.lacunarity = p.coastlineWarpLacunarity();
        this.persistence = p.coastlineWarpPersistence();

        // 构建多倍频 FBM：octave i 频率 = base / lacunarity^i，振幅 = persistence^i
        this.octaveNoise = new Noise[octaves];
        this.octaveAmp = new double[octaves];
        double sum = 0.0;
        double freq = 1.0 / warpScale;
        double amp = 1.0;
        for (int i = 0; i < octaves; i++) {
            octaveNoise[i] = new Frequency(new Simplex(101 + i * 37), freq);
            octaveAmp[i] = amp;
            sum += amp;
            freq *= lacunarity;
            amp *= persistence;
        }
        this.normFactor = sum;
    }

    /** 播种噪声 */
    public void seed(long worldSeed) {
        for (Noise n : octaveNoise) {
            Noises.seedAll(n, worldSeed, 0);
        }
    }

    // ===== 唯一公共方法 =====

    /**
     * 海岸带 c-space 域扭曲位移。
     * <p>
     * 仅在 coastBandWindow 内有效（深海/内陆 return 0）。
     * 三角窗峰值在 coastLoc 处为 1.0，向两侧线性衰减。
     *
     * @return c-space 位移量（单位与 cBiased 相同），直接加到 cBiased 上
     */
    public double warpDisplacement(double wx, double wz, double cBiased) {
        double w = coastBandWindow(cBiased);
        if (w <= 0) return 0.0;
        // 多倍频 FBM 叠加后归一化到 [-1, 1]，乘总振幅和窗
        double fbm = 0.0;
        for (int i = 0; i < octaves; i++) {
            fbm += octaveNoise[i].compute(wx, wz) * octaveAmp[i];
        }
        fbm /= normFactor;
        return fbm * warpAmp * w;
    }

    // ===== 内部 =====

    /** 海岸带三角窗：在 coastLoc 处峰值 1.0，向两侧线性衰减到 0 */
    private double coastBandWindow(double cBiased) {
        double lo = coastLoc - warpBand;
        double hi = coastLoc + warpBand;
        if (cBiased <= lo || cBiased >= hi) return 0.0;
        if (cBiased < coastLoc) {
            return (cBiased - lo) / warpBand;
        } else {
            return (hi - cBiased) / warpBand;
        }
    }
}
