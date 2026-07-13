package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.NoiseUtil;

/**
 * 海洋深度样条（offset.json 式 cubic Hermite）。
 *
 * 大陆性 c 仅作 x 坐标，深度/高度 e 完全由控制点 value 决定。
 * 样条覆盖 e ∈ [-1, +1]（深海→高空），海洋侧为负、陆地侧为正。
 *
 * 提供 {@code eFromHeightF} 的单调二分逆解（MC 用）。
 */
public final class HeightCurve {

    private final double[] locations;
    private final double[] values;
    private final double[] derivatives;

    // 世界高度上下限 + 海平面（非对称映射：e=0 → seaLevel）
    private final double minY;
    private final double maxY;
    private final double seaLevelY;

    public HeightCurve(TerrainParams p, double minWorldY, double maxWorldY) {
        this.minY = minWorldY;
        this.maxY = maxWorldY;
        this.seaLevelY = p.seaLevel();
        this.locations = p.oceanLocations();
        this.values = p.oceanValues();
        this.derivatives = p.oceanDerivatives();
    }

    /**
     * 由大陆性 c ∈ [0,1] 映射到归一化高度 e ∈ [-1,1]。
     * 海洋侧（c < coastLoc）e ≈ 负值（深度由值决定）；
     * 陆地侧 e = c 但仅作 blendE 坐标，最终 e 由 eLand 决定。
     */
    public double eFromC(double c) {
        return SplineUtil.splint(locations, values, derivatives, c);
    }

    /**
     * 由世界高度 Y 反解 e（非对称二分逆解，MC 用）。
     * e ∈ [-1,0] → [minY, seaLevel]; e ∈ [0,+1] → [seaLevel, maxY]。
     */
    public double eFromHeightF(double worldY) {
        if (worldY <= seaLevelY) {
            double denom = seaLevelY - minY;
            if (denom <= 0) return -1.0;
            double t = (worldY - minY) / denom; // [0,1]
            return -1.0 + t; // [-1, 0]
        } else {
            double denom = maxY - seaLevelY;
            if (denom <= 0) return 1.0;
            double t = (worldY - seaLevelY) / denom; // [0,1]
            return t; // [0, +1]
        }
    }

    /**
     * 由 e 映射到世界高度 Y（非对称映射）。
     * e ∈ [-1,0] → [minY, seaLevel]; e ∈ [0,+1] → [seaLevel, maxY]。
     */
    public double heightFromE(double e) {
        if (e <= 0.0) {
            double t = NoiseUtil.clamp(-e, 0.0, 1.0); // 0→海平面, 1→世界底
            return seaLevelY - t * (seaLevelY - minY);
        } else {
            double t = NoiseUtil.clamp(e, 0.0, 1.0);   // 0→海平面, 1→世界顶
            return seaLevelY + t * (maxY - seaLevelY);
        }
    }

    /** 海平面 e 值（coastLoc 处的 spline 值 ≈ 0） */
    public double seaE() {
        return eFromC(locations.length >= 4 ? locations[3] : 0.48);
    }

    /** 海平面世界 Y */
    public double seaLevelY() {
        return seaLevelY;
    }
}
