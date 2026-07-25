package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.ClimateSpline;
import com.geogenesis.worldgen.noise.*;
import com.geogenesis.worldgen.river.HeightProvider;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程（v8.3 连续混合海岸线）：
 *   ContinentField.sample → c ∈ [-1,1]
 *   ├─ HeightCurve.eFromC(c) → eOcean（海洋地形噪声场，含 seabed 细节，负值）
 *   ├─ TypeLandShape.sample → eLand（陆地地形噪声场，独立类型噪声配方）
 *   ├─ 大陆性 c 只决定「类型」与「海陆 mask」（海陆位置），绝不直接进高度
 *   └─ 连续混合（恢复早期 e=eOcean+eLand 的自然混合，带平滑 gating）：
 *         cont = smoothstep(oceanFadeStart, landRampEnd, cEdge)   // 0 纯海 → 1 纯陆
 *         e    = (1-cont)·eOcean + cont·eLand
 *       · cEdge < oceanFadeStart: cont=0 → e=eOcean（深海，陆地噪声完全淡出）
 *       · cEdge > landRampEnd:    cont=1 → e=eLand（内陆，海深完全淡出）
 *       · 过渡带内: 真实海岸线 = e=0 等值线，落在 (1-cont)·eOcean+cont·eLand=0
 *         即 cont=−eOcean/(eLand−eOcean)，由两侧地形噪声共同决定 → 自然岬角/海湾
 *         （早期 v8 两阶段把 e 硬锚在 coastLoc 是回归，已废除）
 *
 * 气候（v2 增强模型）：
 *   温度 = sin²(z) 纬度基值 × 海洋性修正 − 海拔递减率 + 噪声
 *   湿度 = 大陆性距海 + 山地雨影 + 噪声
 */
public final class CellGenerator implements HeightProvider {

    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final TypeLandShape typeLandShape;
    private final SeaBedDetail seaBed;
    private final OceanFeatures oceanFeatures;
    private final LandFeatures landFeatures;
    private final CoastlineField coastline;
    private final double continentBias;
    private final double seabedAmp;
    private final double oceanDepthFactor;
    /** 海洋淡出起点（cBiased），在此以上海洋深度开始衰减到 0 */
    private final double oceanFadeStart;
    /** 陆地高度终点（cBiased），在此达到全量陆地高度 */
    private final double landRampEnd;
    /** coastLoc 保留为过渡带中心概念（配置/向后兼容），v8.3 已不再硬锚 e=0 */
    private final double coastLoc;
    private final TerrainParams params;

    // 温度参数（v5.10 正弦纬度模型，参考 TF/RTF）
    private final double tempFreq;    // 温度纬度角频率 = 1/latitudeScale（由 TerrainParams 注入，可配置）
    private final Noise tempWarp;     // 温度噪声扰动
    private final Noise humidityNoise; // 独立湿度噪声

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.typeLandShape = new TypeLandShape(p);
        this.seaBed = new SeaBedDetail(p);
        this.oceanFeatures = new OceanFeatures();
        this.landFeatures = new LandFeatures();
        this.coastline = new CoastlineField(p);
        this.continentBias = p.continentBias();
        this.seabedAmp = p.seabedDetail();
        this.oceanDepthFactor = p.oceanDepthFactor();
        this.oceanFadeStart = p.oceanFadeStart();
        this.landRampEnd = p.landRampEnd();
        this.coastLoc = p.coastLoc(); // 复用已有 coastLoc 作为两段分界
        this.params = p;
        this.tempFreq = 1.0 / p.latitudeScale();   // 纬度缩放可配置

        // 气候噪声（xz 缩放可配置）
        this.tempWarp = new Frequency(new Simplex(501), 1.0 / p.tempWarpScale());
        this.humidityNoise = new Frequency(new Simplex(502), 1.0 / p.humidityScale());
    }

    /** 一次性播种所有噪声节点 + 设置海山中心水深检查器 */
    public void seed(long worldSeed) {
        continent.seed(worldSeed);
        typeLandShape.seed(worldSeed);
        seaBed.seed(worldSeed);
        // 海山中心水深检查：计算中心点的真实 eOcean（含 seabed，不含海山增量）
        // 仅在 eOcean_at_center < -0.20（足够深）时才允许生成海山
        oceanFeatures.setSeamountDepthChecker((wx, wz) -> {
            double c = continent.sample(wx, wz);
            double cBiased = c - continentBias;
            double eBase = heightCurve.eFromC(cBiased);
            double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
            double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
            double eOcean = (eBase + seabed) * oceanDepthFactor;
            return Math.min(eOcean, 0.0);
        });
        coastline.seed(worldSeed);
        oceanFeatures.seed(worldSeed);
        landFeatures.seed(worldSeed);
        Noises.seedAll(tempWarp, worldSeed, 0);
        Noises.seedAll(humidityNoise, worldSeed, 0);
    }

    /** 世界高度下界 */
    public double minY() { return heightCurve.heightFromE(-1.0); }

    /** 世界高度上界 */
    public double maxY() { return heightCurve.heightFromE(1.0); }

    /** 海平面 Y */
    public double seaLevel() {
        return heightCurve.heightFromE(heightCurve.seaE());
    }

    /**
     * 采样单格完整数据 — 统一连续场 e(x,z)。
     */
    public Cell sample(double wx, double wz) {
        Cell cell = new Cell();

        // 1. 大陆性 c
        double c = continent.sample(wx, wz);
        cell.continent = c;
        double cBiased = c - continentBias;

        // 2. 海洋基面 eOcean（不 clamp 到 0——陆地区域 eBase 为正，自然地增高）
        double eBase = heightCurve.eFromC(cBiased);
        double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
        double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
        double eOcean = eBase + seabed;
        eOcean = eOcean * oceanDepthFactor;
        // 海洋特征：计算洋中脊/海山增量，叠加到 eOcean 使海床产生实际地形。
        OceanFeatures.FeatureResult oceanFeat = oceanFeatures.compute(wx, wz, Math.min(eOcean, 0.0), cBiased);
        eOcean += oceanFeat.total; // 海山/洋中脊抬升海床（仅海洋侧生效，陆地侧 blend 天然淡出）

        // 3. 连续类型混合结果
        TerrainCharacterField.BlendResult cellBlend = typeLandShape.sampleBlend(wx, wz);
        cell.typeWeights = cellBlend.typeWeights;

        // 4. 海岸线域扭曲（v8 CoastlineField）— 在海岸过渡带施加 c-space 噪声位移。
        //    得到唯一的有效岸线坐标 cEdge，同时用于 eLand 计算和 blend 门控，
        //    保证一致性（避免 eLand 算在位置A、blend 用位置B 的不匹配问题）。
        double cEdge = cBiased + coastline.warpDisplacement(wx, wz, cBiased);

        // 5. 陆地形态 eLand — 用有效岸线坐标 cEdge（替代原始 cBiased），
        //    使山地/高原在位移后的海岸处正确升高（真岬角/悬崖）。
        double eLand = typeLandShape.sample(cellBlend, wx, wz, cEdge);

        // 6. 陆地火山特征（c 不再参与陆地高度——地形高度全权由类型噪声决定）
        LandFeatures.FeatureResult landFeat = landFeatures.compute(wx, wz);
        eLand += landFeat.total;

        cell.eLand = eLand;

        // 7. 连续主导类型
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 8. 连续混合（v8.3）：海陆地形 = 海洋噪声场 与 陆地噪声场 的平滑插值。
        //    真实海岸线 = 插值结果 e=0 的等值线，由两噪声场实际数值共同决定，
        //    不再硬锚 coastLoc。cEdge 仅作软权重，决定两场各占多少。
        //    · cEdge < oceanFadeStart: cont=0  → e = eOcean（深海，陆地噪声完全淡出）
        //    · cEdge > landRampEnd:    cont=1  → e = eLand（内陆，海深完全淡出）
        //    · 过渡带内: e=0 落在 (1-cont)·eOcean + cont·eLand = 0 处
        //        ⟹ cont = −eOcean/(eLand−eOcean)，由两侧地形噪声游走 → 自然岬角/海湾
        double cont = smoothstep(oceanFadeStart, landRampEnd, cEdge); // 0(纯海)→1(纯陆)
        double e = (1.0 - cont) * eOcean + cont * eLand;
        cell.e = e;
        cell.eOcean = eOcean;
        cell.blendCont = cont;
        cell.height = heightCurve.heightFromE(e);
        cell.coastCoord = cEdge;

        // 8. 气候（增强模型 v2）
        //    温度：纬度基值 + 海拔递减率 + 海洋性修正 + 噪声
        //    湿度：大陆性距海 + 山区雨影 + 噪声
        double sinVal = Math.sin(wz * tempFreq);
        double temp = sinVal * sinVal * 2.0 - 1.0; // 纬度基值 [-1, 1]
        // 海洋性修正：海岸（c≈0）温差小，内陆（c>0.5）温差大
        double continentFactor = clamp(c * 1.5, 0.0, 1.0);
        temp = temp * (0.85 + 0.15 * continentFactor);
        // 海拔递减率：每 eLand 冷 0.15（山顶比山脚冷约 0.1 = ~5.8°C）
        temp -= eLand * 0.15;
        // 噪声扰动
        temp += tempWarp.compute(wx, wz) * 0.10;
        temp = clamp(temp, -1.0, 1.0);

        // 湿度模型 v2：大陆性距海 + 山区雨影 + 噪声
        double montW = cellBlend.typeWeights != null && cellBlend.typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? cellBlend.typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;
        // 海岸（c≈0）湿 -> 内陆（c>0.8）干
        double humBase = 1.0 - clamp(c * 1.25, 0.0, 1.0);
        double hum = humBase * 2.0 - 1.0; // map [0,1]→[-1,1]
        // 山地雨影：山脉区域降低湿度（简化处理）
        hum -= montW * 0.3;
        // 噪声扰动
        hum += humidityNoise.compute(wx, wz) * 0.25;
        hum = clamp(hum, -1.0, 1.0);

        // 气候影响权重（tempInfluence / humidityInfluence / continentInfluence）：
        // 缩放原始气候值，使该维度对「群系分类 + 雪线」的影响可在配置屏调节。
        //   =1.0 完全生效（默认，行为同旧版）；=0.0 退化为中性（温和/平均）；=0.5 折半。
        //   同时作用于 BiomeClassifier 的 isX() 判定与 classify 的雪线计算。
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double tempInf = cfg != null ? cfgDbl(cfg.tempInfluence, 1.0) : 1.0;
        double humInf = cfg != null ? cfgDbl(cfg.humidityInfluence, 1.0) : 1.0;
        double contInf = cfg != null ? cfgDbl(cfg.continentInfluence, 1.0) : 1.0;
        double tempE = clamp(temp * tempInf, -1.0, 1.0);
        double humE = clamp(hum * humInf, -1.0, 1.0);
        double contE = clamp(c * contInf, -1.0, 1.0);

        cell.climate = new com.geogenesis.worldgen.climate.Climate(tempE, humE, contE);
        cell.temperature = tempE;
        cell.humidity = humE;

        // 9. 分类（使用连续 typeWeights + 已计算的温度/湿度 + 海洋特征）
        cell.terrainType = classify(c, e, eLand, cellType, cellBlend.typeWeights, tempE, humE, oceanFeat, landFeat, cEdge);
        cell.continentNoise = c;
        cell.shape = eLand * 2.0 - 1.0;

        return cell;
    }

    /**
     * 连续形态分类（使用 typeWeights 连续权重替代离散 argmax）。
     * 海洋区域按特征分量细分：大陆架 / 洋中脊 / 海山 / 海洋 / 深海。
     */
    public TerrainClass classify(double c, double e, double eLand,
                                  TerrainClass cellType, double[] typeWeights,
                                  double temperature, double humidity,
                                  OceanFeatures.FeatureResult oceanFeat,
                                  LandFeatures.FeatureResult landFeat,
                                  double cEdge) {
        if (e < 0.0) {
            // 海洋地形细分
            double ridgeAmp = oceanFeat != null ? oceanFeat.ridge : 0;
            double seamountAmp = oceanFeat != null ? oceanFeat.seamount : 0;
            // SUBMARINE_RIDGE / SEAMOUNT 仅在大陆架以下（e < -0.08）的较深水域分类
            // 避免浅水/近岸被误判为海山
            if (ridgeAmp > 0.03 && e < -0.08) return TerrainClass.SUBMARINE_RIDGE;
            if (seamountAmp > 0.02 && e < -0.08) return TerrainClass.SEAMOUNT;
            if (e > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return e < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // BEACH：贴真实海岸线（e≈0 的陆地侧窄条）+ 位于海陆过渡带（cEdge 在 fade..ramp 内）。
        // 用有效岸线坐标 cEdge 判定，使沙滩跟随 e=0 等值线游走，而非钉在固定 c 阈值；
        // 过渡带约束排除内陆低地（盆地/平原）误判为沙滩。
        if (e < 0.04 && e > -0.03 && cEdge > oceanFadeStart && cEdge < landRampEnd) {
            return TerrainClass.BEACH;
        }

        // 火山优先（可见特征，用户核心诉求：陆地需有火山地形）。
        if (landFeat != null) {
            if (landFeat.single > 0.05) return TerrainClass.VOLCANO;
            if (landFeat.field > 0.04) return TerrainClass.VOLCANIC_FIELD;
        }

        // 取 MOUNTAINS 连续权重（而非离散 cellType == MOUNTAINS）
        double mountW = typeWeights != null && typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;

        // PEAK/SNOW 使用 post-blend e（而非预 blend eLand），
        // 确保海岸过渡带不被误标为雪峰材质（用户反馈：海岸边 SNOW 贴图硬切下降）。
        if (mountW > 0.35 && e > 0.60) {
            return TerrainClass.PEAK;
        }
        // SNOW 判定：高海拔 + 寒冷温度 + 湿度条件
        // 双曲线模型：cold+wet → 雪线低（容易积雪），cold+dry → 雪线高（难积雪）
        // 使用实例化 params 而非静态温度样条
        double snowBase = params.snowLine();
        double snowTempInf = params.snowLatitudeInfluence();
        double snowHumInf = params.snowHumidityInfluence();
        double tNorm = (temperature + 1.0) / 2.0;  // [0,1], cold=0, hot=1
        double hNorm = (humidity + 1.0) / 2.0;     // [0,1], dry=0, wet=1
        double effectiveSnowElev = snowBase + (tNorm - 0.5) * snowTempInf - (hNorm - 0.5) * snowHumInf;
        effectiveSnowElev = Math.max(0.02, Math.min(1.0, effectiveSnowElev));
        if (e > effectiveSnowElev && e > 0.15) {
            return TerrainClass.SNOW;
        }
        return cellType;
    }

    /**
     * 静态分类方法（侵蚀回写后重分类用）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature, double humidity) {
        return classifyTerrain(ne, eLand, cellType, temperature, humidity, null, 0.0);
    }

    /**
     * 静态分类方法（带连续类型权重 + coastCoord 海岸约束）。
     * PEAK/SNOW 使用 typeWeights 连续阈值，避免离散 argmax 跳变。
     * 海洋区域按深度细分：大陆架 / 海洋 / 深海（无特征分量，仅靠 e）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double humidity,
                                                double[] typeWeights,
                                                double coastCoord) {
        if (ne < 0.0) {
            if (ne > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return ne < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // BEACH：贴真实海岸线（ne≈0 陆地侧窄条）+ 位于海陆过渡带（coastCoord 在 fade..ramp 内）。
        // coastCoord ≈ cEdge；过渡带约束排除内陆低地误判为沙滩。
        if (ne > -0.03 && ne < 0.04 && coastCoord > 0.0 && coastCoord < 0.08) {
            return TerrainClass.BEACH;
        }

        // 使用连续 typeWeights 判断 PEAK（用 ne 替代 eLand，与主 classify 一致）
        double mountW = typeWeights != null && typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;
        if (mountW > 0.35 && ne > 0.60) return TerrainClass.PEAK;

        // SNOW 判定：高海拔 + 寒冷温度 + 湿度（双曲线模型）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double snowBase = cfg != null ? cfgDbl(cfg.snowLine, 0.70) : 0.70;
        double snowTempInf = cfg != null ? cfgDbl(cfg.snowLatitudeInfluence, 0.25) : 0.25;
        double snowHumInf = cfg != null ? cfgDbl(cfg.snowHumidityInfluence, 0.15) : 0.15;
        double tNorm = (temperature + 1.0) / 2.0;
        double hNorm = (humidity + 1.0) / 2.0;
        double effectiveSnowElev = snowBase + (tNorm - 0.5) * snowTempInf - (hNorm - 0.5) * snowHumInf;
        effectiveSnowElev = Math.max(0.02, Math.min(1.0, effectiveSnowElev));
        if (ne > effectiveSnowElev && ne > 0.15) return TerrainClass.SNOW;
        if (typeWeights != null && typeWeights.length >= TerrainClass.COUNT) {
            return TypeLandShape.dominantFromWeights(typeWeights);
        }
        return cellType;
    }

    // ===== 内联工具 =====

    /**
     * 计算温度的"寒冷权重"（样条连续值）。
     * 综合 frozenWeight + coldWeight，用于 SNOW/冰雪判定。
     * 在温度阈值附近平滑过渡，避免硬边界跳变。
     */
    private static double temperatureColdWeight(double temperature) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            ClimateSpline spl = ClimateSpline.temperature(
                cfg.tempFrozenThreshold.get(),
                cfg.tempColdThreshold.get(),
                cfg.tempWarmThreshold.get(),
                cfg.tempHotThreshold.get());
            // frozen + cold 区域权重之和
            return spl.zoneWeight(temperature, ClimateSpline.TEMP_FROZEN)
                 + spl.zoneWeight(temperature, ClimateSpline.TEMP_COLD);
        }
        // 默认：简单线性插值
        return temperature < -0.6 ? 1.0 : temperature < -0.2 ? (-0.2 - temperature) / 0.4 : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 安全读取 Forge 配置值。
     * 游戏内（config 已加载）→ 正常返回 toml 值；独立预览/探针（config 未加载运行时）
     * 的 {@code ConfigValue.get()} 会抛 IllegalStateException，此时回退到代码默认值。
     * 仅在配置未加载时改变行为，不影响游戏内结果。
     */
    private static double cfgDbl(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }

    /** 采样大陆性快捷接口 */
    public double sampleContinent(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** HeightProvider：返回真实陆地 e（海洋→NaN） */
    @Override
    public double landHeight(int wx, int wz) {
        double e = sample(wx, wz).e;
        return e > 0.0 ? e : Double.NaN;
    }

    /** HeightProvider：返回统一 e（含海洋海床） */
    @Override
    public double terrainE(int wx, int wz) {
        return sample(wx, wz).e;
    }

    /** HeightCurve 引用 */
    public HeightCurve heightCurve() { return heightCurve; }

    /** TypeLandShape 引用 */
    public TypeLandShape typeLandShape() { return typeLandShape; }
}
