package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 多频率共享噪声基底 + 类型高度范围（由 TerrainParams 注入）。
 * 
 * Phase 1：支持统一样条（2 层嵌套：大陆性 c → 内层样条 lo/hi）。
 */
public final class TypeGenerators {

    // ===== 类型 eLand 高度范围（从 TerrainParams 注入，非硬编码） =====
    private final double[] lo = new double[TerrainClass.COUNT];
    private final double[] hi = new double[TerrainClass.COUNT];

    // ===== Phase 1：统一样条 =====
    private final UnifiedSpline unifiedSpline;
    private final boolean useUnifiedSpline;

    public TypeGenerators(TerrainParams p) {
        // Phase 1：构建统一样条
        this.unifiedSpline = p.buildUnifiedSpline();
        this.useUnifiedSpline = false; // 2026-07-20: 关闭样条路径，恢复旧的 typeWeights 加权系统
        
        // 向后兼容：保留旧的 center ± halfRange
        setRange(TerrainClass.PLAIN,     p.plainCenter(), p.plainHalfRange());
        setRange(TerrainClass.HILLS,     p.hillsCenter(), p.hillsHalfRange());
        setRange(TerrainClass.MOUNTAINS, p.mountainsCenter(), p.mountainsHalfRange());
        setRange(TerrainClass.PLATEAU,   p.plateauCenter(), p.plateauHalfRange());
        setRange(TerrainClass.BASIN,     p.basinCenter(), p.basinHalfRange());

        // low/high init same as before
        this.lowFreq  = new Frequency(new Simplex(300), 1.0 / 1200.0);
        this.midFreq  = new Frequency(new Simplex(301), 1.0 / 400.0);
        this.highFreq = new Frequency(new Simplex(302), 1.0 / 150.0);
        this.detailFreq = new Frequency(new Simplex(303), 1.0 / 50.0);
        this.warpA = new Frequency(new Simplex(320), 1.0 / 800.0);
        this.warpB = new Frequency(new Simplex(321), 1.0 / 800.0);
        this.warpAmp = 300.0;
        this.wLow = 0.45; this.wMid = 0.30; this.wHigh = 0.18; this.wDetail = 0.07;
    }

    private void setRange(TerrainClass tc, double center, double halfRange) {
        int idx = tc.ordinal();
        lo[idx] = center - halfRange;
        hi[idx] = center + halfRange;
    }

    public double getTypeLo(TerrainClass tc) { return lo[tc.ordinal()]; }
    public double getTypeHi(TerrainClass tc) { return hi[tc.ordinal()]; }
    public double getTypeCenter(TerrainClass tc) { return (lo[tc.ordinal()] + hi[tc.ordinal()]) * 0.5; }
    public double getTypeHalfRange(TerrainClass tc) { return (hi[tc.ordinal()] - lo[tc.ordinal()]) * 0.5; }

    /**
     * Phase 1：通过 2 层嵌套样条计算 eLand。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sampleFromSpline(double c, double noiseValue) {
        if (useUnifiedSpline && unifiedSpline != null) {
            return unifiedSpline.sample(c, noiseValue);
        }
        // fallback：使用旧的 center ± halfRange
        return 0.0;
    }

    /**
     * Phase 2：通过 3 层嵌套样条计算 eLand。
     * 
     * @param c 大陆性值（-1.0 到 1.0）
     * @param typePosition 类型在类型轴上的位置（0.0 到 1.0）
     * @param noiseValue 地形类型噪声值（0.0 到 1.0）
     * @return eLand 值
     */
    public double sampleFromSpline(double c, double typePosition, double noiseValue) {
        if (useUnifiedSpline && unifiedSpline != null) {
            return unifiedSpline.sample(c, typePosition, noiseValue);
        }
        // fallback：使用旧的 center ± halfRange
        return 0.0;
    }

    /**
     * Phase 1：获取统一样条（供 TypeLandShape 使用）。
     */
    public UnifiedSpline getUnifiedSpline() {
        return unifiedSpline;
    }

    /**
     * 是否使用统一样条。
     */
    public boolean isUsingUnifiedSpline() {
        return useUnifiedSpline && unifiedSpline != null;
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
