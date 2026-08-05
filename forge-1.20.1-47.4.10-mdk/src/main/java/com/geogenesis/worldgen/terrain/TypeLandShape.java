package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 连续性格场驱动的陆地形态生成器。
 * <p>
 * 用 {@link TerrainCharacterField} 的连续噪声场 + softmax 取代 Voronoi 细胞网格，
 * 实现任意尺度连续类型区域（如北美中部平原）。
 * <p>
 * 各类型的地形噪声形态（PLAIN 超平坦、MOUNTAINS Ridge 脊线、PLATEAU 平顶边缘等）
 * 完全独立且不同——由 {@link TypeNoiseProvider} 各自配方实现。类型权重仅决定
 * 各类型在空间上的主导区域大小和过渡位置。
 */
public final class TypeLandShape {

    private final TerrainCharacterField character;
    private final TypeNoiseProvider typeNoise;
    private final TypeGenerators generators;
    private final Noise moistureNoise;
    private final ContinentField continent;
    private final double continentBias;
    private final TerrainParams params;

    public TypeLandShape(TerrainParams p) {
        this.params = p;
        this.generators = new TypeGenerators(p);
        this.character = new TerrainCharacterField();
        this.typeNoise = new TypeNoiseProvider(p.beltReliefAmp());
        this.moistureNoise = new Frequency(new Simplex(401), 1.0 / 1500.0);
        this.continent = new ContinentField(p);
        this.continentBias = p.continentBias();
    }

    public void seed(long worldSeed) {
        character.seed(worldSeed);
        typeNoise.seed(worldSeed);
        generators.seed(worldSeed);
        continent.seed(worldSeed);
        Noises.seedAll(moistureNoise, worldSeed, 0);
    }

    public TypeGenerators typeGenerators() { return generators; }

    /** 返回连续类型权重混合结果 */
    public TerrainCharacterField.BlendResult sampleBlend(double wx, double wz) {
        return character.sampleBlend(wx, wz);
    }

    /**
     * 采样 eLand，默认使用内部计算的 cBiased。
     */
    public double sample(TerrainCharacterField.BlendResult blend, double wx, double wz) {
        double c = continent.sample(wx, wz);
        double cBiased = c - continentBias;
        return sampleFromUnifiedSpline(blend, wx, wz, cBiased);
    }

    /**
     * 带有效岸线坐标的 sample 重载。
     * 由 CellGenerator 传入经 CoastlineField warp 位移后的 effectiveCBiased。
     */
    public double sample(TerrainCharacterField.BlendResult blend, double wx, double wz, double effectiveCBiased) {
        return sampleFromUnifiedSpline(blend, wx, wz, effectiveCBiased);
    }

    /**
     * Phase 2：通过 3 层嵌套样条计算 eLand（陆地类型，WIE 加权混合）。
     * <p>
     * v8：接受外部传入的 {@code effectiveCBiased}（经 CoastlineField warp 位移后的有效岸线坐标），
     * 替代内部重新采样 continent，使地形类型在位移后的海岸处正确升高。
     * <p>
     * 海洋/水域类型由 CellGenerator 经 HeightCurve 单独处理，
     * 故此处 typeWeights 仅含陆地类型。
     */
    private double sampleFromUnifiedSpline(TerrainCharacterField.BlendResult blend, double wx, double wz, double effectiveCBiased) {
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
        // （~0.003/block，×STRENGTH 0.55 → 贡献 Δe ~0.0016/block），不引起断裂。
        // 同时 PLATEAU 的平顶边缘/MOUNTAINS 脊线等特征通过 per-type 噪声自然表达。
        // STRENGTH=0 → 纯共享（无类型特征），=1 → 纯 per-type（断裂风险）。
        // 0.55：0.4 时类型形态特征被压得太弱（山地脊线/高原起伏 40% 贡献 → 各类型趋同）。
        // 2026-08-05 定稿 0.75：用户反馈类型内部高度差不明显。0.55→0.75 让类型噪声更多参与调制
        // （HILLS localStd 0.0394→0.0437、MOUNTAINS 0.0384→0.0408，探针 seed=12345）；
        // 0.85 会使山脉边缘出现海平面洼地（min 0.000），0.75 是落差与形态的平衡点。
        final double MORPH_STRENGTH = 0.75;
        // 类型高度混合权重锐化（BLEND_SHARPEN）：
        // 主导类型在 5 类 Voronoi 近平局中权重常仅 ~0.25，其他 4 类 lo/hi 把高度拉向均值
        // → 山脉主导区 eLand 仅 ~0.28（应 ~0.70）、高原几乎不出现（"山脉不像山脉、
        // 高原不像高原、类型混合不自然"的根因）。对高度混合权重做 power 锐化并归一化，
        // 使主导类型回归自身 lo/hi 区间，恢复地形辨识度。锐化是连续权重场的连续函数
        // → 保持 C0 连续，不引入过渡带断裂（与 SEARCH_RADIUS 截断造成的 1 格断裂无关）。
        // 仅作用于高度混合；argmax 分类 / mountW / platW / plainW（原始 tw）不受影响。
        // 2026-08-06 定稿 3.0：4.0 实测 max 0.742 无提升（blendHi 已近顶），且使山脉 min 0.000（海平面洼地）。
        final double BLEND_SHARPEN = 3.0;
        double sumSharp = 0.0;
        double[] sharpW = new double[lands.length];
        for (int i = 0; i < lands.length; i++) {
            double w = tw[lands[i].ordinal()];
            double s = w <= 0.001 ? 0.0 : Math.pow(w, BLEND_SHARPEN);
            sharpW[i] = s;
            sumSharp += s;
        }
        if (sumSharp <= 1e-6) sumSharp = 1.0; // 退化兜底（不锐化）
        double blendLo = 0.0, blendHi = 0.0;
        for (int i = 0; i < lands.length; i++) {
            double wn = sharpW[i] / sumSharp;
            double lo_t = generators.sampleByType(effectiveCBiased, i, 0.0); // 该类型在 c 处的下限
            double hi_t = generators.sampleByType(effectiveCBiased, i, 1.0); // 该类型在 c 处的上限
            blendLo += wn * lo_t;
            blendHi += wn * hi_t;
        }
        double shared = generators.computeSharedNoise(wx, wz);
        // 类型加权平均噪声（用于差异调制，按锐化权重归一化）
        double perTypeSum = 0;
        for (int i = 0; i < lands.length; i++) {
            double wn = sharpW[i] / sumSharp;
            perTypeSum += wn * typeNoise.computeNoise(lands[i], wx, wz);
        }
        double perTypeAvg = perTypeSum; // 已归一化 → ∈[0,1]
        double modulated = shared + MORPH_STRENGTH * (perTypeAvg - shared);
        if (modulated < 0.0) modulated = 0.0;
        else if (modulated > 1.0) modulated = 1.0;
        // 2026-08-06 定稿：对比度拉伸 k=1.5。根因：modulated 均值 0.5、高尾 ~0.75 → 实际峰值
        // （0.742≈236 格）接近不了样条 hi（0.95→288 格）。线性拉伸放大高/低尾（连续单调无断裂，
        // 低尾 <0.167 钳 0 平台 ~0.2%）。实测 MOUNTAINS max 0.789≈250 格（用户目标）、
        // min 0.245 正常、maxDeltaY=8.65 无断裂。
        modulated = 0.5 + 1.5 * (modulated - 0.5);
        if (modulated < 0.0) modulated = 0.0;
        else if (modulated > 1.0) modulated = 1.0;
        double eLand = blendLo + (blendHi - blendLo) * modulated;
        // 2026-08-05 定稿：移除旧显式抬升（platRaise/mountRaise/plainLower）。
        // 旧显式项在样条之外叠加高度（纯高原 +0.10e≈24 格），使"滑块设的最高"超限
        // （用户反馈：拉到 150 实际 197）。BLEND_SHARPEN 锐化后主导区已回归自身 lo/hi
        // 区间（A/B 实测：PLATEAU 0.572→0.490 仍高台且更平、MOUNTAINS 0.543→0.534 不变、
        // PLAIN -0.004→0.034 仍低平），样条现为唯一高度来源 → 滑块 lo/hi = 实际地形区间。
        // 放开下限到海平面以下：盆地等类型的内层样条可为负（e<0 → HeightCurve 映射为低于海平面
        // → 积水成湖/洼地）。下限取海洋深度地板 ELAND_MIN，避免异常配置产生过深空洞；上限仍封 1.0。
        return eLand < ELAND_MIN ? ELAND_MIN : (eLand > 1.0 ? 1.0 : eLand);
    }

    /** 陆地 e 允许下探到海洋深度地板（盆地凹陷可低于海平面成湖） */
    private static final double ELAND_MIN = -0.35;

    /**
     * 采样 eLand（完整流程）。
     */
    public double sample(double wx, double wz) {
        TerrainCharacterField.BlendResult blend = character.sampleBlend(wx, wz);
        return sample(blend, wx, wz);
    }

    /**
     * 连续主导类型：argmax(typeWeights)。
     */
    public TerrainClass dominantType(double wx, double wz) {
        TerrainCharacterField.BlendResult blend = character.sampleBlend(wx, wz);
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
