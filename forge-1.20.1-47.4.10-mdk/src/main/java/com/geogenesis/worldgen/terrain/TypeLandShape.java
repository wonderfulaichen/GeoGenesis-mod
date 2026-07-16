package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 区域驱动的陆地形态生成器。
 * <p>
 * v7 (Phase 1)：差异调制 — 在共享噪声基底上叠加 per-type 专属噪声偏差，
 * 各类型噪声形态真正不同（Ridge 脊线、平滑高原边缘等），
 * 而断裂风险通过零均值加权混合 + typeWeights 平滑过渡控制。
 * <p>
 * 地形类型（v6 继承）：
 * <ul>
 *   <li>PLAIN — 极平坦</li>
 *   <li>HILLS — 圆润起伏</li>
 *   <li>MOUNTAINS — 尖锐脊线 + 脊线网络</li>
 *   <li>PLATEAU — 平顶 + smoothstep 边缘渐变</li>
 *   <li>BASIN — 反转凹陷</li>
 * </ul>
 * 在 cell 边界处按 typeWeights 加权混合各类型的独立噪声偏差，
 * 保证跨 cell 连续过渡（详见证断裂分析 §Phase1）。
 */
public final class TypeLandShape {

    private final VoronoiRegionField regions;
    private final TypeNoiseProvider typeNoise;
    private final TypeGenerators generators;   // 保留：lo/hi 范围 + basinModulate
    private final Noise moistureNoise;

    public TypeLandShape() {
        this.regions = new VoronoiRegionField();
        this.typeNoise = new TypeNoiseProvider();
        this.generators = new TypeGenerators();
        this.moistureNoise = new Frequency(new Simplex(401), 1.0 / 1500.0);
    }

    public void seed(long worldSeed) {
        regions.seed(worldSeed);
        typeNoise.seed(worldSeed);
        generators.seed(worldSeed);
        Noises.seedAll(moistureNoise, worldSeed, 0);
    }

    public TypeGenerators typeGenerators() { return generators; }

    /** 返回 Voronoi 混合结果（含连续类型权重），供 CellGenerator 避免双重计算 */
    public VoronoiRegionField.BlendResult sampleBlend(double wx, double wz) {
        return regions.sampleBlend(wx, wz);
    }

    /**
     * v7.5：类型噪声直接主导 eLand（Fix 2）。
     * <p>
     * 旧公式（Phase 1 差异调制）：
     *   base = lo + range×sharedNoise
     *   diff = Σw×(typeNoise − sharedNoise)
     *   eLand = base + morphStrength×diff×range
     * 问题：typeNoise 和 sharedNoise 都是 [0,1] 噪声，差值幅值小（±0.15），
     * 类型噪声对高度贡献仅 ±8 块。丘陵失去可见起伏。
     * <p>
     * 新公式（v7.5）：
     *   typeWeighted = Σ w×typeNoise / Σw  ← 类型噪声直接混合
     *   eLand = lo + range × typeWeighted   ← 类型噪声主导
     * 在纯 HILLS cell 中：eLand = lo + range × hillsNoise ∈ [0.07, 0.37] 全范围。
     * 连续性由 typeWeights 高斯平滑过渡保障（v5 已验证 Δe < 0.01）。
     */
    public double sample(VoronoiRegionField.BlendResult blend, double wx, double wz) {
        double[] tw = blend.typeWeights;
        double typeWeighted = 0;
        double totalW = 0;
        if (tw != null) {
            for (TerrainClass type : TypeNoiseProvider.LAND_TYPES) {
                double w = tw[type.ordinal()];
                if (w > 0.001) {
                    double typeVal = typeNoise.computeNoise(type, wx, wz);
                    typeWeighted += w * typeVal;
                    totalW += w;
                }
            }
        }
        // fallback: typeWeights 耗尽时（不应发生）用 shared noise 兜底
        if (totalW <= 0) {
            typeWeighted = generators.computeSharedNoise(wx, wz);
        } else {
            typeWeighted /= totalW;
        }

        double range = blend.hi - blend.lo;
        double eLand = blend.lo + range * typeWeighted;

        // 钳制
        return eLand < 0 ? 0 : (eLand > 1 ? 1 : eLand);
    }

    /**
     * 采样 eLand（完整流程）。
     */
    public double sample(double wx, double wz) {
        VoronoiRegionField.BlendResult blend = regions.sampleBlend(wx, wz);
        return sample(blend, wx, wz);
    }

    /**
     * 连续主导类型：argmax(typeWeights)。
     */
    public TerrainClass dominantType(double wx, double wz) {
        VoronoiRegionField.BlendResult blend = regions.sampleBlend(wx, wz);
        return dominantFromWeights(blend.typeWeights);
    }

    public TerrainClass sampledTypeAt(double wx, double wz) {
        return dominantType(wx, wz);
    }

    /** 从连续权重取 argmax（从 PLAIN 开始） */
    public static TerrainClass dominantFromWeights(double[] typeWeights) {
        if (typeWeights == null) return TerrainClass.PLAIN;
        int best = TerrainClass.PLAIN.ordinal();
        int n = Math.min(typeWeights.length, TerrainClass.COUNT);
        for (int i = best + 1; i < n; i++) {
            if (typeWeights[i] > typeWeights[best]) best = i;
        }
        return TerrainClass.values()[best];
    }

    public double sampleMoisture(double wx, double wz) {
        double m = moistureNoise.compute(wx, wz);
        return m < -1 ? 0 : (m > 1 ? 1 : (m + 1.0) * 0.5);
    }
}
