package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 区域驱动的陆地形态生成器。
 * <p>
 * v7 (Phase 1)：差异调制 — 在共享噪声基底上叠加 per-type 专属噪声偏差，
 * 各类型噪声形态真正不同（Ridge 脊线、平滑高原边缘等），
 * 而断裂风险通过零均值加权混合 + typeWeights 平滑过渡控制。
 * <p>
 * Phase 1：支持统一样条（2 层嵌套：大陆性 c → 内层样条 lo/hi）。
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
    private final TypeGenerators generators;   // lo/hi 类型内层样条（eLand 范围）
    private final Noise moistureNoise;
    private final ContinentField continent;    // Phase 1：用于计算大陆性 c
    private final double continentBias;        // Phase 1：大陆性偏置
    private final TerrainParams params;        // Phase 2.2：语义适配强度等可调旋钮

    public TypeLandShape(TerrainParams p) {
        this.params = p;
        this.generators = new TypeGenerators(p);
        this.regions = new VoronoiRegionField(p.voronoiWarpAmp());
        this.typeNoise = new TypeNoiseProvider();
        this.moistureNoise = new Frequency(new Simplex(401), 1.0 / 1500.0);
        this.continent = new ContinentField(p);
        this.continentBias = p.continentBias();
    }

    public void seed(long worldSeed) {
        regions.seed(worldSeed);
        typeNoise.seed(worldSeed);
        generators.seed(worldSeed);
        continent.seed(worldSeed);
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
     * Phase 1：支持统一样条（2 层嵌套：大陆性 c → 内层样条 lo/hi）。
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
        // 统一使用 3 层嵌套样条（统一样条恒启用，旧 typeWeights 路径已移除）
        return sampleFromUnifiedSpline(blend, wx, wz);
    }

    /**
     * Phase 2：通过 3 层嵌套样条计算 eLand（陆地类型，WIE 加权混合）。
     * <p>
     * 海洋/水域类型由 CellGenerator 经 HeightCurve 单独处理，不走 Voronoi 类型系统，
     * 故此处 typeWeights 仅含陆地类型，无需 isWater 分支。
     */
    private double sampleFromUnifiedSpline(VoronoiRegionField.BlendResult blend, double wx, double wz) {
        // Phase 1 (WIE)：按 typeWeights 加权混合各类型「自己」独立求值的内层样条，
        // 彻底移除 typePosition 类型轴插值 —— 高原↔丘陵混合只取各自 H_t，
        // 绝不引入 MOUNTAINS 节点高度（尖环根因）。c 当前不进高度（与旧行为一致）。
        double c = continent.sample(wx, wz);
        double cBiased = c - continentBias;

        double[] tw = blend.typeWeights;
        if (tw == null) {
            double s = generators.computeSharedNoise(wx, wz);
            return s < ELAND_MIN ? ELAND_MIN : (s > 1.0 ? 1.0 : s);
        }

        // Phase 2: 语义亲和度 —— 用大陆性 c 偏置各类型空间权重（cAffinity_t(c)）。
        // 复用已建样条 MidNode.weight 曲线（=每类型 c 响应，Σ_t aff_t(c) ≡ 1，故均值=0.2）。
        // 采用「加法偏置」而非贝叶斯乘积：eff = spatial × (1 + β·(aff − 0.2))。
        // 理由：乘积会把少数类型（高原/盆地先验低）双重压制到永不主导；加法偏置因子恒 > 0，
        // 保留空间主导性（纯类型 cell 不变），仅按 c 偏置混合，不消灭任何类型。
        // 就地修改 tw（=blend.typeWeights 同一对象），使分类(argmax)/湿度(montW)/PEAK 判定
        // 与高度 WIE 共用同一调制后权重，保持高度↔类型一致。
        // β 为语义亲和度强度，现由 TerrainParams.cAffinityStrength 配置（Phase 2.2 可调旋钮）。
        final double CAFFINITY_BETA = params.cAffinityStrength();
        final double MEAN_AFF = 0.2; // Σ aff_t(c) ≡ 1 over 5 land types
        TerrainClass[] lands = TypeNoiseProvider.LAND_TYPES;
        for (int i = 0; i < lands.length; i++) {
            double aff = generators.typeAffinity(cBiased, i);
            double factor = 1.0 + CAFFINITY_BETA * (aff - MEAN_AFF);
            if (factor < 0.01) factor = 0.01;
            tw[lands[i].ordinal()] *= factor;
        }

        double eLand = 0.0, sumW = 0.0;
        for (int i = 0; i < lands.length; i++) {
            double w = tw[lands[i].ordinal()];
            if (w <= 0.001) continue;
            double typeVal = typeNoise.computeNoise(lands[i], wx, wz);  // 该类型自己的噪声
            double e_t = generators.sampleByType(cBiased, i, typeVal);  // 该类型自己的高度
            eLand += w * e_t;
            sumW += w;
        }
        if (sumW <= 0) {
            eLand = generators.computeSharedNoise(wx, wz);
        } else {
            eLand /= sumW;
        }
        // 放开下限到海平面以下：盆地等类型的内层样条可为负（e<0 → HeightCurve 映射为低于海平面
        // → 积水成湖/洼地）。下限取海洋深度地板 ELAND_MIN，避免异常配置产生过深空洞；上限仍封 1.0。
        // 注意：原 clamp01 把 eLand 钳到 [0,1]，会抹平盆地凹陷（内部全压成 0 平盘），现已修正。
        return eLand < ELAND_MIN ? ELAND_MIN : (eLand > 1.0 ? 1.0 : eLand);
    }

    /** 陆地 e 允许下探到海洋深度地板（盆地凹陷可低于海平面成湖） */
    private static final double ELAND_MIN = -0.35;

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
