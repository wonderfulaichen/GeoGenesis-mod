package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 区域驱动的陆地形态生成器。
 * <p>
 * v5 核心改进：每种地形类型使用独立噪声配方（而非共享 FBM），
 * 产生视觉上截然不同的地形形态：
 * <ul>
 *   <li>PLAIN — 极平坦</li>
 *   <li>HILLS — 圆润起伏</li>
 *   <li>MOUNTAINS — 尖锐脊线</li>
 *   <li>PLATEAU — 阶地台地</li>
 *   <li>BASIN — 凹陷盆地</li>
 * </ul>
 * 在 cell 边界处按 typeWeights 加权混合各类型的独立噪声，
 * 保证连续过渡。
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
     * 利用预计算的 blend 结果采样 eLand ∈ [0,1]。
     * 每类型的噪声按 typeWeights 加权混合 — 在 cell 边界处连续过渡。
     */
    public double sample(VoronoiRegionField.BlendResult blend, double wx, double wz) {
        // 1. 按 typeWeights 混合各类型的独立噪声
        double blendedNoise = 0;
        double totalW = 0;
        for (TerrainClass tc : TypeNoiseProvider.LAND_TYPES) {
            double w = blend.typeWeights[tc.ordinal()];
            if (w > 0.001) {
                blendedNoise += w * typeNoise.computeNoise(tc, wx, wz);
                totalW += w;
            }
        }
        double base = totalW > 0 ? blendedNoise / totalW : 0.5;

        // 2. 盆地连续调制（保留，增强了 BASIN 反转效果）
        double basinW = blend.typeWeights[TerrainClass.BASIN.ordinal()];
        if (basinW > 0.01) {
            double basinBase = TypeGenerators.basinModulate(base);
            base = base * (1.0 - basinW) + basinBase * basinW;
        }

        // 3. 映射到混合范围
        double range = blend.hi - blend.lo;
        double eLand = blend.lo + range * base;

        // 4. 钳制
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
