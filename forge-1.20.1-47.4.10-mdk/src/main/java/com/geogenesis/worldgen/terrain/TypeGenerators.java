package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 多频率共享噪声基底（消除 PLAIN 等窄范围 cell 内的视觉平坦问题）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>所有类型共享同一噪声场 → 无断裂</li>
 *   <li>4 个频率叠加：低频（地形骨架）、中频（中等起伏）、高频（小细节）、超高频（岩石级纹理）</li>
 *   <li>噪声值 ∈ [0,1] 在所有 cell 都有充分变化，避免 PLAIN 看起来"平"</li>
 *   <li>Voronoi cell 仅决定映射目标范围（lo, hi），不决定噪声结构</li>
 * </ul>
 */
public final class TypeGenerators {

    // ===== 各类型 eLand 输出范围 =====
    // 基于 MC 模组高度 Y = 63 + eLand × 257
    static final double PLAIN_LO  = 0.015, PLAIN_HI = 0.06;
    static final double HILLS_LO  = 0.06,  HILLS_HI = 0.25;
    static final double MOUNT_LO  = 0.45,  MOUNT_HI = 0.95;   // 山脉从高原顶部开始
    static final double PLAT_LO   = 0.20,  PLAT_HI  = 0.45;   // 高原降低，不与山脉重叠
    static final double BASIN_LO  = 0.015, BASIN_HI = 0.08;

    public static double getTypeLo(TerrainClass tc) {
        return switch (tc) {
            case PLAIN     -> PLAIN_LO;
            case HILLS     -> HILLS_LO;
            case MOUNTAINS -> MOUNT_LO;
            case PLATEAU   -> PLAT_LO;
            case BASIN     -> BASIN_LO;
            default        -> 0.0;
        };
    }

    public static double getTypeHi(TerrainClass tc) {
        return switch (tc) {
            case PLAIN     -> PLAIN_HI;
            case HILLS     -> HILLS_HI;
            case MOUNTAINS -> MOUNT_HI;
            case PLATEAU   -> PLAT_HI;
            case BASIN     -> BASIN_HI;
            default        -> 0.0;
        };
    }

    // ===== 多频率共享噪声 =====
    private final Noise lowFreq;     // 极低频：地形骨架（山脉走向、大区域）
    private final Noise midFreq;     // 中频：中等起伏
    private final Noise highFreq;    // 高频：细节
    private final Noise detailFreq;  // 超高频：岩石级纹理
    private final Noise warpA, warpB; // 域扭曲

    private final double warpAmp;
    // 各频率权重（和为 1.0）
    private final double wLow, wMid, wHigh, wDetail;

    public TypeGenerators() {
        // 极低频：地形骨架，~1/1200（每 1200 块一个完整波）
        this.lowFreq  = new Frequency(new Simplex(300), 1.0 / 1200.0);
        // 中频：~1/400
        this.midFreq  = new Frequency(new Simplex(301), 1.0 / 400.0);
        // 高频：~1/150
        this.highFreq = new Frequency(new Simplex(302), 1.0 / 150.0);
        // 超高频：~1/50（让所有 cell 都有可见细节）
        this.detailFreq = new Frequency(new Simplex(303), 1.0 / 50.0);
        // 域扭曲：低频 1/800 + 高强度 warpAmp=300（> cell 间距 400 的一半）
        // 关键：足够强的 warp 才能让 cell 中心的锐利峰消失
        this.warpA = new Frequency(new Simplex(320), 1.0 / 800.0);
        this.warpB = new Frequency(new Simplex(321), 1.0 / 800.0);

        this.warpAmp = 300.0;
        this.wLow = 0.45;
        this.wMid = 0.30;
        this.wHigh = 0.18;
        this.wDetail = 0.07;
    }

    public void seed(long worldSeed) {
        Noises.seedAll(lowFreq, worldSeed, 0);
        Noises.seedAll(midFreq, worldSeed, 0);
        Noises.seedAll(highFreq, worldSeed, 0);
        Noises.seedAll(detailFreq, worldSeed, 0);
        Noises.seedAll(warpA, worldSeed, 0);
        Noises.seedAll(warpB, worldSeed, 0);
    }

    /**
     * 计算多频率共享噪声基底值 ∈ [0,1]。
     * 域扭曲后 4 个频率叠加（所有频率都用扭曲后坐标），确保所有 cell 都有充分变化。
     * 关键：warp 强度 300 远超 1/1200 频率的 1/2 波长，足以打散任何 cell 网格感。
     */
    public double computeSharedNoise(double wx, double wz) {
        // 域扭曲
        double bx = warpAmp * warpA.compute(wx, wz);
        double bz = warpAmp * warpB.compute(wx, wz);
        double wxw = wx + bx, wzw = wz + bz;

        // 所有频率都使用扭曲后坐标（参考 TerraForged Domain.warp）
        double nLow  = (lowFreq.compute(wxw, wzw) + 1.0) * 0.5;
        double nMid  = (midFreq.compute(wxw, wzw) + 1.0) * 0.5;
        double nHigh = (highFreq.compute(wxw, wzw) + 1.0) * 0.5;
        double nDet  = (detailFreq.compute(wx, wz) + 1.0) * 0.5;  // 超高频用原始坐标（细节不应被扭曲，否则会"软化"）

        // 加权混合
        double combined = wLow * nLow + wMid * nMid + wHigh * nHigh + wDetail * nDet;
        return clamp01(combined);
    }

    /**
     * 盆地噪声调制：反转 + 中心低
     */
    public static double basinModulate(double base) {
        // 反转：base 高 → 输出低；base 低 → 输出高
        // 但保留正向偏移以避免最低过 0
        double inv = 0.85 - 0.7 * base;
        return inv < 0 ? 0 : (inv > 1 ? 1 : inv);
    }

    // ===== 工具 =====
    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
