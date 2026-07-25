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
        this.typeNoise = new TypeNoiseProvider(p.beltReliefAmp());
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
     * 默认使用内部计算的 cBiased（向后兼容）。
     */
    public double sample(VoronoiRegionField.BlendResult blend, double wx, double wz) {
        double c = continent.sample(wx, wz);
        double cBiased = c - continentBias;
        return sampleFromUnifiedSpline(blend, wx, wz, cBiased);
    }

    /**
     * v8：带有效岸线坐标的 sample 重载。
     * <p>
     * 由 CellGenerator 传入经 CoastlineField warp 位移后的 {@code effectiveCBiased}，
     * 替代内部重新采样 continent，使地形类型高度在海陆位移后的"有效岸线坐标"处正确升高，
     * 实现真正的地形岬角/悬崖（而非低矮平地）。
     */
    public double sample(VoronoiRegionField.BlendResult blend, double wx, double wz, double effectiveCBiased) {
        return sampleFromUnifiedSpline(blend, wx, wz, effectiveCBiased);
    }

    /**
     * Phase 2：通过 3 层嵌套样条计算 eLand（陆地类型，WIE 加权混合）。
     * <p>
     * v8：接受外部传入的 {@code effectiveCBiased}（经 CoastlineField warp 位移后的有效岸线坐标），
     * 替代内部重新采样 continent，使地形类型在位移后的海岸处正确升高。
     * <p>
     * 海洋/水域类型由 CellGenerator 经 HeightCurve 单独处理，不走 Voronoi 类型系统，
     * 故此处 typeWeights 仅含陆地类型，无需 isWater 分支。
     */
    private double sampleFromUnifiedSpline(VoronoiRegionField.BlendResult blend, double wx, double wz, double effectiveCBiased) {
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
            double aff = generators.typeAffinity(effectiveCBiased, i);
            double factor = 1.0 + CAFFINITY_BETA * (aff - MEAN_AFF);
            if (factor < 0.01) factor = 0.01;
            tw[lands[i].ordinal()] *= factor;
        }

        // v14+ 差异调制：以共享噪声为基底（保证连续），叠加有限的 per-type 噪声特征
        // <p>
        // <b>断裂根因回顾</b>：v7-v13 用 per-type 独立噪声全量加权：
        // `eLand = Σ w_t · H_t(noise_t(x,z))`，5 类独立噪声随机梯度叠加
        // → per-block Δe 可达 0.04-0.07 e。
        // <p>
        // <b>差异调制公式</b>：
        // <pre>
        //   blendLo    = Σ_t w_t(c) · lo_t(c)
        //   blendHi    = Σ_t w_t(c) · hi_t(c)
        //   shared     = computeSharedNoise(wx, wz)           // 单条连续 FBM
        //   perTypeAvg = Σ_t w_t · typeNoise_t / Σ w_t        // 类型加权平均噪声
        //   modulated  = shared + STRENGTH · (perTypeAvg − shared)  // 零均值偏差
        //   eLand      = blendLo + (blendHi − blendLo) · modulated
        // </pre>
        // shared 是连续函数；perTypeAvg - shared 是零均值小幅偏差，梯度幅值受限
        // （~0.003/block，×STRENGTH 0.4 → 贡献 Δe ~0.001/block），不引起断裂。
        // 同时 PLATEAU 的平顶边缘/MOUNTAINS 脊线等特征通过 per-type 噪声自然表达。
        // STRENGTH=0 → 纯共享（无类型特征），=1 → 纯 per-type（断裂风险）。
        final double MORPH_STRENGTH = 0.4;
        double blendLo = 0.0, blendHi = 0.0;
        for (int i = 0; i < lands.length; i++) {
            double w = tw[lands[i].ordinal()];
            if (w <= 0.001) continue;
            double lo_t = generators.sampleByType(effectiveCBiased, i, 0.0); // 该类型在 c 处的下限
            double hi_t = generators.sampleByType(effectiveCBiased, i, 1.0); // 该类型在 c 处的上限
            blendLo += w * lo_t;
            blendHi += w * hi_t;
        }
        double shared = generators.computeSharedNoise(wx, wz);
        // 类型加权平均噪声（用于差异调制）
        double perTypeSum = 0, sumW = 0;
        for (int i = 0; i < lands.length; i++) {
            double w = tw[lands[i].ordinal()];
            if (w <= 0.001) continue;
            perTypeSum += w * typeNoise.computeNoise(lands[i], wx, wz);
            sumW += w;
        }
        double perTypeAvg = sumW > 0 ? perTypeSum / sumW : shared;
        double modulated = shared + MORPH_STRENGTH * (perTypeAvg - shared);
        if (modulated < 0.0) modulated = 0.0;
        else if (modulated > 1.0) modulated = 1.0;
        double eLand = blendLo + (blendHi - blendLo) * modulated;
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
