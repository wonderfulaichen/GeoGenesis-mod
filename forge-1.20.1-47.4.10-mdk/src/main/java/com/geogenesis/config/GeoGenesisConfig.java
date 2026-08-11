package com.geogenesis.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GeoGenesis Forge COMMON 配置。
 *
 * 包含所有地形参数（大陆场、海洋样条、海岸、海床、省权重、过程形态）。
 * 由 {@code buildParams()} 导出为 TerrainParams 注入引擎。
 *
 * 铁律：增删字段须同步 TerrainParams(defaults) + GeoGenesisGenerator(configParams/configRiverSettings) +
 * GeoGenesisConfigScreen.buildParams + run/config/geogenesis-common.toml。
 */
public final class GeoGenesisConfig {

    public static final ForgeConfigSpec SPEC;
    public static final GeoGenesisConfig INSTANCE;

    // ===== 大陆场 =====
    public final ForgeConfigSpec.DoubleValue continentScale;
    public final ForgeConfigSpec.DoubleValue continentWarp;
    // ===== 大陆 FBM（值叠加，对标参考项目 procedural-island-generator 的 fbm 海岸线） =====
    public final ForgeConfigSpec.IntValue continentFbmOctaves;
    public final ForgeConfigSpec.DoubleValue continentFbmLacunarity;
    public final ForgeConfigSpec.DoubleValue continentFbmPersistence;
    public final ForgeConfigSpec.DoubleValue continentThreshold;
    public final ForgeConfigSpec.DoubleValue continentBias;
    public final ForgeConfigSpec.DoubleValue continentProvinceWarp;

    // ===== 海洋样条控制点 =====
    public final ForgeConfigSpec.DoubleValue deepOceanLoc;
    public final ForgeConfigSpec.DoubleValue shelfLoc;
    public final ForgeConfigSpec.DoubleValue shallowLoc;
    public final ForgeConfigSpec.DoubleValue coastLoc;
    public final ForgeConfigSpec.DoubleValue deepOceanDepth;
    public final ForgeConfigSpec.DoubleValue shelfDepth;
    public final ForgeConfigSpec.DoubleValue shallowDepth;
    public final ForgeConfigSpec.DoubleValue deepOceanDeriv;
    public final ForgeConfigSpec.DoubleValue shelfDeriv;
    public final ForgeConfigSpec.DoubleValue shallowDeriv;
    public final ForgeConfigSpec.DoubleValue coastDeriv;

    // ===== 海岸线过渡区（两阶段 blend，参考 TerraForged） =====
    /** 海洋淡出起点（cBiased 空间），默认 -0.15 */
    public final ForgeConfigSpec.DoubleValue oceanFadeStart;
    /** 陆地高度终点（cBiased 空间），默认 0.08 */
    public final ForgeConfigSpec.DoubleValue landRampEnd;

    // ===== 海洋类型独立 lo/hi（与陆地类型一致的独立控制） =====
    public final ForgeConfigSpec.DoubleValue oceanLo;
    public final ForgeConfigSpec.DoubleValue oceanHi;
    public final ForgeConfigSpec.DoubleValue deepOceanLo;
    public final ForgeConfigSpec.DoubleValue deepOceanHi;
    public final ForgeConfigSpec.DoubleValue shelfLo;
    public final ForgeConfigSpec.DoubleValue shelfHi;
    public final ForgeConfigSpec.DoubleValue subRidgeLo;
    public final ForgeConfigSpec.DoubleValue subRidgeHi;
    public final ForgeConfigSpec.DoubleValue seamountLo;
    public final ForgeConfigSpec.DoubleValue seamountHi;
    public final ForgeConfigSpec.DoubleValue lakeLo;
    public final ForgeConfigSpec.DoubleValue lakeHi;
    public final ForgeConfigSpec.DoubleValue riverLo;
    public final ForgeConfigSpec.DoubleValue riverHi;

    // ===== 海床 =====
    public final ForgeConfigSpec.DoubleValue seabedDetail;
    /** 海洋深度缩放因子（e 单位乘数），>1=更深（海洋面积扩大），<1=更浅（陆地扩大），默认 1.0 */
    public final ForgeConfigSpec.DoubleValue oceanDepthFactor;

    // ===== [已废弃] 省权重 =====
    // 省系统已被地形类型系统取代（TypeLandShape + TypeGenerators）。
    // 这些字段保留仅供旧 config 文件兼容，新引擎不再读取。
    public final ForgeConfigSpec.DoubleValue provinceScale;
    public final ForgeConfigSpec.DoubleValue cratonWeight;
    public final ForgeConfigSpec.DoubleValue beltWeight;
    public final ForgeConfigSpec.DoubleValue plateauWeight;
    public final ForgeConfigSpec.DoubleValue basinWeight;

    // ===== [已废弃] 陆地过程形态（旧省系统） =====
    // 这些字段被类型系统取代，不再读取。保留供旧 config 兼容。
    public final ForgeConfigSpec.DoubleValue plainBase;
    public final ForgeConfigSpec.DoubleValue plainRough;
    public final ForgeConfigSpec.DoubleValue hillsLow;
    public final ForgeConfigSpec.DoubleValue hillsHigh;
    public final ForgeConfigSpec.DoubleValue beltRidgePower;
    public final ForgeConfigSpec.DoubleValue beltFoothill;
    public final ForgeConfigSpec.DoubleValue beltPeak;
    public final ForgeConfigSpec.DoubleValue plateauBase;
    public final ForgeConfigSpec.DoubleValue plateauTop;
    public final ForgeConfigSpec.IntValue plateauSteps;
    public final ForgeConfigSpec.DoubleValue plateauStepStrength;
    public final ForgeConfigSpec.DoubleValue basinBase;

    // ===== 地形起伏振幅（每省固有） =====
    public final ForgeConfigSpec.DoubleValue cratonReliefAmp;
    public final ForgeConfigSpec.DoubleValue beltReliefAmp;
    public final ForgeConfigSpec.DoubleValue plateauReliefAmp;
    public final ForgeConfigSpec.DoubleValue basinReliefAmp;

    // ===== 省场形态曲线 =====
    public final ForgeConfigSpec.DoubleValue beltSharpness;
    public final ForgeConfigSpec.DoubleValue beltWarpAmp;
    public final ForgeConfigSpec.DoubleValue provMixSharpness;
    public final ForgeConfigSpec.DoubleValue mountainMaskScale;
    public final ForgeConfigSpec.DoubleValue microDetailScale;
    public final ForgeConfigSpec.DoubleValue microDetailAmp;

    // ===== 分类阈值 + 雪线 =====
    public final ForgeConfigSpec.DoubleValue elevHigh;
    public final ForgeConfigSpec.DoubleValue reliefHigh;
    public final ForgeConfigSpec.DoubleValue snowLine;
    public final ForgeConfigSpec.DoubleValue snowLatitudeInfluence;
    /** 雪线湿度耦合强度（干燥→雪线升高，湿润→雪线降低），默认 0.15 */
    public final ForgeConfigSpec.DoubleValue snowHumidityInfluence;

    // ===== 气候阈值（温度/湿度/大陆性边界 + 影响权重） =====
    /** 温度阈值：极寒/寒冷分界，默认 -0.6 */
    public final ForgeConfigSpec.DoubleValue tempFrozenThreshold;
    /** 温度阈值：寒冷/温和分界，默认 -0.2 */
    public final ForgeConfigSpec.DoubleValue tempColdThreshold;
    /** 温度阈值：温和/温暖分界，默认 0.2 */
    public final ForgeConfigSpec.DoubleValue tempWarmThreshold;
    /** 温度阈值：温暖/炎热分界，默认 0.5 */
    public final ForgeConfigSpec.DoubleValue tempHotThreshold;

    /** 湿度阈值：干旱/半干旱分界，默认 -0.3 */
    public final ForgeConfigSpec.DoubleValue humidityDryThreshold;
    /** 湿度阈值：半干旱/湿润分界，默认 0.0 */
    public final ForgeConfigSpec.DoubleValue humiditySemiThreshold;
    /** 湿度阈值：湿润/潮湿分界，默认 0.3 */
    public final ForgeConfigSpec.DoubleValue humidityWetThreshold;

    /** 大陆性阈值：深海/近海分界，默认 -0.60 */
    public final ForgeConfigSpec.DoubleValue continentDeepOceanThreshold;
    /** 大陆性阈值：近海/沿海分界，默认 -0.30 */
    public final ForgeConfigSpec.DoubleValue continentNearOceanThreshold;
    /** 大陆性阈值：沿海/过渡分界，默认 -0.10 */
    public final ForgeConfigSpec.DoubleValue continentCoastThreshold;
    /** 大陆性阈值：近岸/近内陆分界，默认 0.10 */
    public final ForgeConfigSpec.DoubleValue continentTransitionalThreshold;
    /** 大陆性阈值：近内陆/内陆分界，默认 0.35 */
    public final ForgeConfigSpec.DoubleValue continentNearInlandThreshold;
    /** 大陆性阈值：内陆/深内陆分界，默认 0.65 */
    public final ForgeConfigSpec.DoubleValue continentInlandThreshold;

    /** 温度对群系分布的影响程度（0-1），默认 1.0 */
    public final ForgeConfigSpec.DoubleValue tempInfluence;
    /** 湿度对群系分布的影响程度（0-1），默认 1.0 */
    public final ForgeConfigSpec.DoubleValue humidityInfluence;
    /** 大陆性对群系分布的影响程度（0-1），默认 1.0 */
    public final ForgeConfigSpec.DoubleValue continentInfluence;

    // ===== 气候/纬度 xz 缩放 =====
    /** 纬度缩放（温度随 z 变化的周期，块）。越大→温度带越宽（纬度变化越慢），默认 6000 */
    public final ForgeConfigSpec.DoubleValue latitudeScale;
    /** 温度噪声 xz 缩放（块），温度扰动场频率 = 1/tempWarpScale，默认 1500 */
    public final ForgeConfigSpec.DoubleValue tempWarpScale;
    /** 湿度噪声 xz 缩放（块），湿度场频率 = 1/humidityScale，默认 800 */
    public final ForgeConfigSpec.DoubleValue humidityScale;

    // ===== 世界高度 =====
    public final ForgeConfigSpec.IntValue seaLevel;
    public final ForgeConfigSpec.IntValue minY;
    public final ForgeConfigSpec.IntValue maxY;
    /** 实际峰顶高度占 maxY 的比例（余量避免触顶世界构建上限）。默认 0.92，范围 [0.5, 1.0] */
    public final ForgeConfigSpec.DoubleValue peakHeightFraction;

    // ===== 垂直缩放 =====
    /** 垂直缩放比例（1-8），用于预览和地形高度夸张 */
    public final ForgeConfigSpec.DoubleValue verticalScale;
    /** 水平缩放比例（0.5-8），所有噪声坐标除以此值 = 地形 XZ 等比缩放，默认 1.0（1:1） */
    public final ForgeConfigSpec.DoubleValue horizontalScale;

    // ===== 大陆性语义亲和度 =====
    /** 大陆性语义亲和度强度（β），调制 c 对各类型空间权重的偏置幅度，默认 5.0 */
    public final ForgeConfigSpec.DoubleValue cAffinityStrength;

    // ===== 海岸线多样化（CoastlineField 输入） =====
    /** 海岸线 warp 振幅（c 空间单位），默认 0.03 */
    public final ForgeConfigSpec.DoubleValue coastlineWarpAmp;
    /** 海岸线 warp 基频世界坐标尺度（块），默认 300。越大变化越平缓 */
    public final ForgeConfigSpec.DoubleValue coastlineWarpScale;
    /** 海岸线 warp 倍频数（octaves），控制分形细节层级数，默认 5，范围 [1, 8] */
    public final ForgeConfigSpec.IntValue coastlineWarpOctaves;
    /** FBM 频率倍增系数（lacunarity），每倍频频率×该值，默认 2.0，范围 [1.5, 3.0] */
    public final ForgeConfigSpec.DoubleValue coastlineWarpLacunarity;
    /** FBM 振幅衰减系数（persistence），每倍频振幅×该值，默认 0.5，范围 [0.25, 0.8] */
    public final ForgeConfigSpec.DoubleValue coastlineWarpPersistence;
    /** 地形类型调制强度（c 空间单位），默认 0.08 */
    public final ForgeConfigSpec.DoubleValue coastTerrainInfluence;
    /** 离岸群岛带宽度（c 空间单位），默认 0.10 */
    public final ForgeConfigSpec.DoubleValue archipelagoBand;
    /** 离岸群岛密度阈值，默认 0.30 */
    public final ForgeConfigSpec.DoubleValue archipelagoDensity;
    /** 离岸群岛噪声尺度（块），默认 120 */
    public final ForgeConfigSpec.DoubleValue archipelagoScale;
    /** 离岸群岛最大高度（e 单位），默认 0.035 */
    public final ForgeConfigSpec.DoubleValue archipelagoHeight;

    // ===== 河流 + 侵蚀（SH 统一水力引擎：侵蚀与河网同源同趟，放电量场涌现物理河流） =====
    /** 河流总开关（false=不标记任何河流/旱谷） */
    public final ForgeConfigSpec.BooleanValue riverEnabled;
    /** (legacy) 河道半宽（世界块）——SH 模式由放电量场自然决定河谷宽度，本参数保留供将来细调 */
    public final ForgeConfigSpec.DoubleValue riverWidth;
    /** (legacy) 河谷下切深度（e 单位）——SH 模式由侵蚀自然刻蚀，本参数保留供将来细调 */
    public final ForgeConfigSpec.DoubleValue riverValleyDepth;
    /** (legacy) 河谷剖面形状 [0,1]——SH 模式由粒子物理决定，本参数保留 */
    public final ForgeConfigSpec.DoubleValue riverShape;
    /** 河道是否灌水（false=旱谷，仅显形为凹槽地形、不填水） */
    public final ForgeConfigSpec.BooleanValue riverWater;
    /** 源湖/湖密度（河流节点场用） */
    public final ForgeConfigSpec.DoubleValue lakeDensity;
    /** 是否启用河流系统（false = 关闭河网检测和河道填水，独立于侵蚀） */
    public final ForgeConfigSpec.BooleanValue riversEnabled;
    /** 是否启用本地粒子侵蚀 */
    public final ForgeConfigSpec.BooleanValue erosionEnabled;
    /** 侵蚀强度倍率 */
    public final ForgeConfigSpec.DoubleValue erosionStrength;
    /** 侵蚀水滴数量倍率 */
    public final ForgeConfigSpec.DoubleValue erosionDropsMul;
    /** 侵蚀速率倍率 */
    public final ForgeConfigSpec.DoubleValue erosionErodeMul;
    /** localCharge 正反馈权重（α）。每粒子累计高差放大侵蚀能力：0=关闭正反馈，越大主流雕越深。默认 1.0，范围 [0, 5] */
    public final ForgeConfigSpec.DoubleValue erosionLocalChargeWeight;
    /** 局部 cascade 级联强度。侵蚀后按高度差向最低邻居 settling，平滑河床底部。0=关闭，默认 0.3，范围 [0, 1] */
    public final ForgeConfigSpec.DoubleValue erosionCascadeStrength;
    /** 粗侵蚀（脊-谷条纹滤镜）骨架层开关。开启时先造大山脊基本型，再由粒子侵蚀做细节。默认 true。 */
    public final ForgeConfigSpec.BooleanValue erosionRidgeEnabled;
    /** 粗侵蚀强度（骨架层）。默认 0.08，范围 [0, 0.5] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeStrength;
    /** 粗侵蚀特征尺度（世界块）= 条纹细胞世界尺寸，越大脊越宽。默认 100，范围 [50, 800] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeScale;
    /** 粗侵蚀细胞内条纹频率（脊-谷密度）。默认 1.2，范围 [0.2, 2.0] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeCellScale;
    /** 粗侵蚀 octave 数（主脊+次级脊+细沟，4 更密）。默认 4，范围 [1, 5] */
    public final ForgeConfigSpec.IntValue erosionRidgeOctaves;
    /** 粗侵蚀坡度累积权重（脊-谷锐度）。默认 0.5，范围 [0, 1] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeGullyWeight;
    /** 粗侵蚀脊线圆润度（0=尖锐 V 形，0.5=原版默认，1=圆滑 U 形）。默认 0.5，范围 [0, 1] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeRounding;
    /** 粗侵蚀骨架 fadeTarget 参考面（陆地中值，e 单位）。默认 0.15 匹配陆地中值；旧 0.25 在低地世界恒负 → 全体下削 4~11 块。范围 [0.02, 0.5] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeLandRef;
    /** 粗侵蚀细节密度（PowInv 指数，越低=小沟越密，越高=主脊越干净）。默认 1.0，范围 [0.7, 3.0] */
    public final ForgeConfigSpec.DoubleValue erosionRidgeDetail;
    /** 河流阈值（背景~峰值间比例）：调大→河网更稀疏（只剩主干）。默认 0.02，范围 [0.001, 0.3]。配合汇聚加权（仅河流粒子累计 discharge）使用 */
    public final ForgeConfigSpec.DoubleValue riverDischargeThreshold;
    /** 河流粒子模式：lc（累计落差）资格下限。lc 超过后粒子开始进入河流模式。默认 0.05，范围 [0.005, 1.0] */
    public final ForgeConfigSpec.DoubleValue erosionRiverLcLo;
    /** 河流粒子模式：lc 资格上限（超过即完全河流模式）。默认 0.15，范围 [0.01, 2.0] */
    public final ForgeConfigSpec.DoubleValue erosionRiverLcHi;
    /** 河流粒子模式：放电量确认下限（路径被多少水量走过）。默认 0.3，范围 [0.05, 50]（汇聚加权后量级缩小 ~10×） */
    public final ForgeConfigSpec.DoubleValue erosionRiverDisLo;
    /** 河流粒子模式：放电量确认上限。默认 1.5，范围 [0.5, 200] */
    public final ForgeConfigSpec.DoubleValue erosionRiverDisHi;
    /** 河流粒子模式总强度：0=关闭（等效旧行为），1=最强（侵蚀×3 沉积×0.2 蒸发×0.1）。默认 0.6，范围 [0, 1] */
    public final ForgeConfigSpec.DoubleValue erosionRiverStrength;
    /** SH 动量场正反馈：粒子顺下游动量场自我加速（河流自我增强）。1.0 对齐 SH 原版，0=关闭。范围 [0, 2] */
    public final ForgeConfigSpec.DoubleValue erosionMomentumTransfer;
    /** SH 多轮迭代轮数：每轮重撒全部液滴 + lrate 场平滑，河道随轮次渐进加深成型。默认 2（2026-08-09 优化：3→2，drops 降 33%，观感微变可回退 3），范围 [1, 16] */
    public final ForgeConfigSpec.IntValue erosionIterations;
    /** SH 场平滑率 lrate：每轮 track 平滑进稳态场的比例（0.1 对齐 SH 原版）。范围 [0.01, 0.5] */
    public final ForgeConfigSpec.DoubleValue erosionLrate;

    // ===== Phase 1: Unified Spline Config (74 fields) =====
    // --- Outer spline: continentality c control points (7 × 2 = 14) ---
    public final ForgeConfigSpec.DoubleValue splineOuterLoc0;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv0;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc1;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv1;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc2;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv2;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc3;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv3;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc4;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv4;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc5;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv5;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc6;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv6;

    // --- Inner spline: PLAIN type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue plainLoLoc0;
    public final ForgeConfigSpec.DoubleValue plainLoVal0;
    public final ForgeConfigSpec.DoubleValue plainLoDeriv0;
    public final ForgeConfigSpec.DoubleValue plainLoLoc1;
    public final ForgeConfigSpec.DoubleValue plainLoVal1;
    public final ForgeConfigSpec.DoubleValue plainLoDeriv1;
    public final ForgeConfigSpec.DoubleValue plainHiLoc0;
    public final ForgeConfigSpec.DoubleValue plainHiVal0;
    public final ForgeConfigSpec.DoubleValue plainHiDeriv0;
    public final ForgeConfigSpec.DoubleValue plainHiLoc1;
    public final ForgeConfigSpec.DoubleValue plainHiVal1;
    public final ForgeConfigSpec.DoubleValue plainHiDeriv1;

    // --- Inner spline: HILLS type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue hillsLoLoc0;
    public final ForgeConfigSpec.DoubleValue hillsLoVal0;
    public final ForgeConfigSpec.DoubleValue hillsLoDeriv0;
    public final ForgeConfigSpec.DoubleValue hillsLoLoc1;
    public final ForgeConfigSpec.DoubleValue hillsLoVal1;
    public final ForgeConfigSpec.DoubleValue hillsLoDeriv1;
    public final ForgeConfigSpec.DoubleValue hillsHiLoc0;
    public final ForgeConfigSpec.DoubleValue hillsHiVal0;
    public final ForgeConfigSpec.DoubleValue hillsHiDeriv0;
    public final ForgeConfigSpec.DoubleValue hillsHiLoc1;
    public final ForgeConfigSpec.DoubleValue hillsHiVal1;
    public final ForgeConfigSpec.DoubleValue hillsHiDeriv1;

    // --- Inner spline: MOUNTAINS type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue mountLoLoc0;
    public final ForgeConfigSpec.DoubleValue mountLoVal0;
    public final ForgeConfigSpec.DoubleValue mountLoDeriv0;
    public final ForgeConfigSpec.DoubleValue mountLoLoc1;
    public final ForgeConfigSpec.DoubleValue mountLoVal1;
    public final ForgeConfigSpec.DoubleValue mountLoDeriv1;
    public final ForgeConfigSpec.DoubleValue mountHiLoc0;
    public final ForgeConfigSpec.DoubleValue mountHiVal0;
    public final ForgeConfigSpec.DoubleValue mountHiDeriv0;
    public final ForgeConfigSpec.DoubleValue mountHiLoc1;
    public final ForgeConfigSpec.DoubleValue mountHiVal1;
    public final ForgeConfigSpec.DoubleValue mountHiDeriv1;

    // --- Inner spline: PLATEAU type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue platLoLoc0;
    public final ForgeConfigSpec.DoubleValue platLoVal0;
    public final ForgeConfigSpec.DoubleValue platLoDeriv0;
    public final ForgeConfigSpec.DoubleValue platLoLoc1;
    public final ForgeConfigSpec.DoubleValue platLoVal1;
    public final ForgeConfigSpec.DoubleValue platLoDeriv1;
    public final ForgeConfigSpec.DoubleValue platHiLoc0;
    public final ForgeConfigSpec.DoubleValue platHiVal0;
    public final ForgeConfigSpec.DoubleValue platHiDeriv0;
    public final ForgeConfigSpec.DoubleValue platHiLoc1;
    public final ForgeConfigSpec.DoubleValue platHiVal1;
    public final ForgeConfigSpec.DoubleValue platHiDeriv1;

    // --- Inner spline: BASIN type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue basinLoLoc0;
    public final ForgeConfigSpec.DoubleValue basinLoVal0;
    public final ForgeConfigSpec.DoubleValue basinLoDeriv0;
    public final ForgeConfigSpec.DoubleValue basinLoLoc1;
    public final ForgeConfigSpec.DoubleValue basinLoVal1;
    public final ForgeConfigSpec.DoubleValue basinLoDeriv1;
    public final ForgeConfigSpec.DoubleValue basinHiLoc0;
    public final ForgeConfigSpec.DoubleValue basinHiVal0;
    public final ForgeConfigSpec.DoubleValue basinHiDeriv0;
    public final ForgeConfigSpec.DoubleValue basinHiLoc1;
    public final ForgeConfigSpec.DoubleValue basinHiVal1;
    public final ForgeConfigSpec.DoubleValue basinHiDeriv1;

    // ===== Phase 2: Mid Spline Config (type distribution) =====
    // Note: MidSplineConfig has 105 fields (7 nodes × 5 types × 3 fields).
    // For TOML config, we use a single MidSplineConfig object rather than 105 individual fields.
    // The MidSplineConfig is built from defaults or loaded from a separate config file.
    private final com.geogenesis.worldgen.terrain.MidSplineConfig midSplineConfig;

    static {
        Pair<GeoGenesisConfig, ForgeConfigSpec> pair =
            new ForgeConfigSpec.Builder().configure(GeoGenesisConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    private GeoGenesisConfig(ForgeConfigSpec.Builder builder) {
        builder.push("GeoGenesis Terrain Generation");

        builder.push("Continent Field");
        continentScale = builder.comment("Continent noise scale (blocks). Frequency = 1/scale. HS parameter: HS = continentScale/1000. Range: 1-8 (HS) = 1000-8000.")
            .defineInRange("continentScale", 4000.0, 1000.0, 8000.0);
        continentWarp = builder.comment("Domain warp intensity as fraction of continentScale (applied to the FBM continent field). 0=disabled (pure FBM, recommended — FBM octaves already provide natural continent shape; warping fine FBM detail creates parallel banding). Raise only if you want extra large-scale continent bending. Default 0.0, range [0,1].")
            .defineInRange("continentWarp", 0.0, 0.0, 1.0);
        continentFbmOctaves = builder.comment("Continent FBM octaves: continentality c field is a multi-octave FBM value stack (each octave freq×lacunarity, amp×persistence). Coastline = c=0 iso-line, so FBM multi-scale detail carves the coast directly → natural fractal shoreline (ref procedural-island-generator). Default 6, range [1,10].")
            .defineInRange("continentFbmOctaves", 6, 1, 10);
        continentFbmLacunarity = builder.comment("Continent FBM frequency multiplier (lacunarity): each octave freq×this. Default 2.0, range [1.5,3.0].")
            .defineInRange("continentFbmLacunarity", 2.0, 1.5, 3.0);
        continentFbmPersistence = builder.comment("Continent FBM amplitude decay (persistence): each octave amp×this; higher = stronger high-freq detail = more jagged coast. Default 0.6, range [0.3,0.85].")
            .defineInRange("continentFbmPersistence", 0.6, 0.3, 0.85);
        continentThreshold = builder.comment("Reference continentality c value ∈ [-1,1]. NOT the coastline; coastline = e=0 natural crossing (see terrain-rebuild §1.4). 0=coast anchor, negative=ocean, positive=land — aligned with vanilla Continentalness.")
            .defineInRange("continentThreshold", 0.0, -1.0, 1.0);
        continentBias = builder.comment("Land/ocean ratio bias (FBM continent field). Positive = more ocean, negative = more land. RMS-normalized FBM concentrates c near 0, so the bias is much lower than the old single-simplex 0.82. Default 0.31 yields ~35% land / 65% ocean (calibrated against the REAL CellGenerator.sample() pipeline via TerrainAreaProbe: the prior simplified c+eFromC formula was wrong).")
            .defineInRange("continentBias", 0.31, -0.6, 1.5);
        continentProvinceWarp = builder.comment("Continent field warp amplitude for province sampling (blocks). EXPERIMENTAL: warping by c biases mountain ridge direction toward the c gradient (see GeoGenesisMod discussion). Safe default 0 = disable. Enable only if you understand the directional bias.")
            .defineInRange("continentProvinceWarp", 0.0, 0.0, 8000.0);
        builder.pop();

        builder.push("Ocean Spline Control Points");
        deepOceanLoc = builder.defineInRange("deepOceanLoc", -0.80, -1.0, 1.0);
        shelfLoc = builder.defineInRange("shelfLoc", -0.50, -1.0, 1.0);
        shallowLoc = builder.defineInRange("shallowLoc", -0.16, -1.0, 1.0);
        coastLoc = builder.defineInRange("coastLoc", -0.04, -1.0, 1.0);
        deepOceanDepth = builder.comment("Deep ocean depth (e units). -0.35 ≈ y=19 floor, adjust for gameplay depth taste.")
            .defineInRange("deepOceanDepth", -0.35, -1.0, 0.0);
        shelfDepth = builder.defineInRange("shelfDepth", -0.25, -1.0, 0.0);
        // 2026-08-06: -0.06→-0.09→-0.14（海岸带 eOcean 加深，e=0 穿越点 cont 提升 → eLand 参与海岸线定位）
        shallowDepth = builder.defineInRange("shallowDepth", -0.14, -1.0, 0.0);
        deepOceanDeriv = builder.defineInRange("deepOceanDeriv", 0.0, -10.0, 10.0);
        shelfDeriv = builder.comment("Shelf derivative steepness: higher = steeper continental slope. Default 0.8.")
            .defineInRange("shelfDeriv", 0.8, -10.0, 10.0);
        shallowDeriv = builder.defineInRange("shallowDeriv", 0.5, -10.0, 10.0);
        coastDeriv = builder.defineInRange("coastDeriv", 0.0, -10.0, 10.0);
        builder.pop();

        builder.push("Coastline Transition");
        // 2026-08-06 二次修正: -0.07→-0.13（过渡带向海侧扩展，e=0 穿越点移到过渡带中部 cont≈0.25~0.4，
        // eLand 参与海岸线定位——用户铁律"海岸线由地形斜率自然决定"）
        oceanFadeStart = builder.comment("Ocean fade start (cBiased space). Ocean depth begins fading to 0 at this continentality. More negative = wider coastal transition (eLand joins coastline positioning). Default -0.13 (2026-08-06), range [-0.5, 0.0].")
            .defineInRange("oceanFadeStart", -0.13, -0.5, 0.0);
        landRampEnd = builder.comment("Land ramp end (cBiased space). Full land height is reached at this continentality. Higher = wider beach zone before full terrain appears. Default 0.05 (2026-08-06), range [0.0, 0.5].")
            .defineInRange("landRampEnd", 0.05, 0.0, 0.5);
        builder.pop();

        // 海洋类型独立 lo/hi（与陆地类型一致的独立控制）
        builder.push("Ocean Type Ranges");
        oceanLo = builder.comment("OCEAN lo (bottom of ocean layer).")
            .defineInRange("oceanLo", -0.35, -1.0, 0.0);
        oceanHi = builder.comment("OCEAN hi (top of ocean layer).")
            .defineInRange("oceanHi", -0.06, -1.0, 0.0);
        deepOceanLo = builder.comment("DEEP_OCEAN lo.")
            .defineInRange("deepOceanLo", -0.50, -1.0, 0.0);
        deepOceanHi = builder.comment("DEEP_OCEAN hi.")
            .defineInRange("deepOceanHi", -0.35, -1.0, 0.0);
        shelfLo = builder.comment("CONTINENTAL_SHELF lo.")
            .defineInRange("shelfLo", -0.25, -1.0, 0.0);
        shelfHi = builder.comment("CONTINENTAL_SHELF hi.")
            .defineInRange("shelfHi", -0.08, -1.0, 0.0);
        subRidgeLo = builder.comment("SUBMARINE_RIDGE lo.")
            .defineInRange("subRidgeLo", -0.20, -1.0, 0.0);
        subRidgeHi = builder.comment("SUBMARINE_RIDGE hi.")
            .defineInRange("subRidgeHi", -0.05, -1.0, 0.0);
        seamountLo = builder.comment("SEAMOUNT lo.")
            .defineInRange("seamountLo", -0.15, -1.0, 0.0);
        seamountHi = builder.comment("SEAMOUNT hi.")
            .defineInRange("seamountHi", -0.02, -1.0, 0.0);
        lakeLo = builder.comment("LAKE lo.")
            .defineInRange("lakeLo", -0.02, -1.0, 0.0);
        lakeHi = builder.comment("LAKE hi.")
            .defineInRange("lakeHi", 0.01, -1.0, 0.0);
        riverLo = builder.comment("RIVER lo.")
            .defineInRange("riverLo", -0.03, -1.0, 0.0);
        riverHi = builder.comment("RIVER hi.")
            .defineInRange("riverHi", 0.00, -1.0, 0.0);
        builder.pop();

        builder.push("Seabed");
        seabedDetail = builder.comment("Seabed detail amplitude (e units).")
            .defineInRange("seabedDetail", 0.03, 0.0, 0.2);
        oceanDepthFactor = builder.comment("Ocean depth multiplier (e units). >1 = deeper ocean, <1 = shallower ocean. Does NOT change land/ocean ratio (ratio controlled by continentBias + continentWarp).")
            .defineInRange("oceanDepthFactor", 1.0, 0.5, 3.0);
        builder.pop();

        builder.push("Province Weights");
        provinceScale = builder.defineInRange("provinceScale", 4000.0, 500.0, 10000.0);
        cratonWeight = builder.defineInRange("cratonWeight", 1.5, 0.0, 5.0);
        beltWeight = builder.defineInRange("beltWeight", 3.5, 0.0, 5.0);
        plateauWeight = builder.defineInRange("plateauWeight", 2.0, 0.0, 5.0);
        basinWeight = builder.defineInRange("basinWeight", 0.8, 0.0, 5.0);
        builder.pop();

        builder.push("Land Process Parameters");
        plainBase = builder.defineInRange("plainBase", 0.01, 0.0, 0.3);
        plainRough = builder.defineInRange("plainRough", 0.04, 0.0, 0.3);
        hillsLow = builder.defineInRange("hillsLow", 0.10, 0.0, 0.5);
        hillsHigh = builder.defineInRange("hillsHigh", 0.25, 0.0, 0.7);
        beltRidgePower = builder.defineInRange("beltRidgePower", 1.5, 0.0, 5.0);
        beltFoothill = builder.defineInRange("beltFoothill", 0.15, 0.0, 0.5);
        beltPeak = builder.defineInRange("beltPeak", 0.95, 0.0, 1.0);
        plateauBase = builder.defineInRange("plateauBase", 0.55, 0.0, 1.0);
        plateauTop = builder.defineInRange("plateauTop", 0.72, 0.0, 1.0);
        plateauSteps = builder.comment("Number of plateau terraces.")
            .defineInRange("plateauSteps", 3, 1, 10);
        plateauStepStrength = builder.defineInRange("plateauStepStrength", 0.6, 0.0, 2.0);
        basinBase = builder.defineInRange("basinBase", 0.02, 0.0, 0.3);
        builder.pop();

        builder.push("Relief Amplitudes");
        cratonReliefAmp = builder.comment("Craton local relief amplitude (e units). Plains/low hills. Kept moderate so HILLS class still forms; denseness is fixed via LandShape frequency, not by killing amplitude.")
            .defineInRange("cratonReliefAmp", 0.10, 0.0, 0.6);
        beltReliefAmp = builder.comment("Orogenic belt relief amplitude (e units). Rugged mountains. Moderate value preserves MOUNTAINS class; fingerprint look fixed by larger detail frequency in LandShape.")
            .defineInRange("beltReliefAmp", 0.35, 0.0, 0.6);
        plateauReliefAmp = builder.comment("Plateau relief amplitude (e units). Flat-topped, low relief.")
            .defineInRange("plateauReliefAmp", 0.03, 0.0, 0.6);
        basinReliefAmp = builder.comment("Basin relief amplitude (e units).")
            .defineInRange("basinReliefAmp", 0.05, 0.0, 0.6);
        builder.pop();

        builder.push("Terrain Shape");
        beltSharpness = builder.comment("Belt (orogenic) peak sharpness: pow(n, sharpness). >1 = pointed peaks, =1 = round, <1 = broad. Default 1.5.")
            .defineInRange("beltSharpness", 1.5, 0.5, 5.0);
        beltWarpAmp = builder.comment("Belt domain warp displacement amplitude (world-space blocks). Warp elongates massifs into natural ranges; 0 = disable warp. Default 120.")
            .defineInRange("beltWarpAmp", 120.0, 0.0, 500.0);
        provMixSharpness = builder.comment("Province mix sharpening exponent (softmax weight power). >1 lets dominant province own its region and express full |r|=1 peak / r=0 valley; boundaries stay smooth. Default 2.5.")
            .defineInRange("provMixSharpness", 2.5, 1.0, 5.0);
        mountainMaskScale = builder.comment("Mountain mask noise scale (world-space blocks). Low-frequency blobs limit mountains to belts/clusters (realism key, ref TerraForged). Default 2200.")
            .defineInRange("mountainMaskScale", 2200.0, 400.0, 8000.0);
        microDetailScale = builder.comment("Surface micro-detail noise scale (blocks). Large period, tiny amplitude (<0.03 e) for texture only, not terrain. Default 450.")
            .defineInRange("microDetailScale", 450.0, 200.0, 2000.0);
        microDetailAmp = builder.comment("Surface micro-detail amplitude (e units). Must stay tiny (<0.03) to avoid competing with relief. Default 0.025.")
            .defineInRange("microDetailAmp", 0.025, 0.0, 0.08);
        builder.pop();

        builder.push("Classification & Snow");
        elevHigh = builder.comment("Elevation×Relief classification: high-elevation threshold (elevation e). Low=more mountains/plateau.")
            .defineInRange("elevHigh", 0.25, 0.0, 1.0);
        reliefHigh = builder.comment("Elevation×Relief classification: high-relief threshold (|relief| e). Low=plain only truly flat.")
            .defineInRange("reliefHigh", 0.04, 0.0, 0.6);
        snowLine = builder.comment("Snow line elevation (e units). High-elevation terrain above this (modulated by latitude) gets snow cover.")
            .defineInRange("snowLine", 0.70, 0.0, 1.0);
        snowLatitudeInfluence = builder.comment("Snow line temperature coupling: snow line rises toward the warm end (e units per half-range).")
            .defineInRange("snowLatitudeInfluence", 0.25, 0.0, 0.6);
        snowHumidityInfluence = builder.comment("Snow line humidity coupling: dry areas shift snow line UP, humid areas shift it DOWN (e units per full humidity range).")
            .defineInRange("snowHumidityInfluence", 0.15, 0.0, 0.5);
        builder.pop();

        builder.push("Climate Thresholds");
        // 温度阈值（4个边界，5段：极寒/寒冷/温和/温暖/炎热）
        tempFrozenThreshold = builder.comment("Temperature threshold: frozen/cold boundary.")
            .defineInRange("tempFrozenThreshold", -0.6, -1.0, 1.0);
        tempColdThreshold = builder.comment("Temperature threshold: cold/warm boundary.")
            .defineInRange("tempColdThreshold", -0.2, -1.0, 1.0);
        tempWarmThreshold = builder.comment("Temperature threshold: warm/hot boundary.")
            .defineInRange("tempWarmThreshold", 0.2, -1.0, 1.0);
        tempHotThreshold = builder.comment("Temperature threshold: hot/very hot boundary.")
            .defineInRange("tempHotThreshold", 0.5, -1.0, 1.0);
        // 湿度阈值（3个边界，4段：干旱/半干旱/湿润/潮湿）
        humidityDryThreshold = builder.comment("Humidity threshold: dry/semi-dry boundary.")
            .defineInRange("humidityDryThreshold", -0.3, -1.0, 1.0);
        humiditySemiThreshold = builder.comment("Humidity threshold: semi-dry/wet boundary.")
            .defineInRange("humiditySemiThreshold", 0.0, -1.0, 1.0);
        humidityWetThreshold = builder.comment("Humidity threshold: wet/humid boundary.")
            .defineInRange("humidityWetThreshold", 0.3, -1.0, 1.0);
        // 大陆性阈值（6个边界，7段：深海/近海/沿海/过渡/近内陆/内陆/深内陆）
        continentDeepOceanThreshold = builder.comment("Continentality threshold: deep ocean/near ocean boundary.")
            .defineInRange("continentDeepOceanThreshold", -0.60, -1.0, 1.0);
        continentNearOceanThreshold = builder.comment("Continentality threshold: near ocean/coastal boundary.")
            .defineInRange("continentNearOceanThreshold", -0.30, -1.0, 1.0);
        continentCoastThreshold = builder.comment("Continentality threshold: coastal/transitional boundary.")
            .defineInRange("continentCoastThreshold", -0.10, -1.0, 1.0);
        continentTransitionalThreshold = builder.comment("Continentality threshold: transitional/near inland boundary.")
            .defineInRange("continentTransitionalThreshold", 0.10, -1.0, 1.0);
        continentNearInlandThreshold = builder.comment("Continentality threshold: near inland/inland boundary.")
            .defineInRange("continentNearInlandThreshold", 0.35, -1.0, 1.0);
        continentInlandThreshold = builder.comment("Continentality threshold: inland/deep inland boundary.")
            .defineInRange("continentInlandThreshold", 0.65, -1.0, 1.0);
        // 影响权重（3个）
        tempInfluence = builder.comment("Temperature influence on biome distribution (0-1). 1.0=full effect, 0.0=neutral.")
            .defineInRange("tempInfluence", 1.0, 0.0, 1.0);
        humidityInfluence = builder.comment("Humidity influence on biome distribution (0-1). 1.0=full effect, 0.0=neutral.")
            .defineInRange("humidityInfluence", 1.0, 0.0, 1.0);
        continentInfluence = builder.comment("Continentality influence on biome distribution (0-1). 1.0=full effect, 0.0=neutral.")
            .defineInRange("continentInfluence", 1.0, 0.0, 1.0);
        // 气候/纬度 xz 缩放
        latitudeScale = builder.comment("Latitude scale (blocks per temperature cycle). Larger = wider climate bands (slower latitude change with z). Default 6000.")
            .defineInRange("latitudeScale", 6000.0, 500.0, 30000.0);
        tempWarpScale = builder.comment("Temperature noise xz scale (blocks). Temperature perturbation field frequency = 1/tempWarpScale. Default 1500.")
            .defineInRange("tempWarpScale", 1500.0, 100.0, 8000.0);
        humidityScale = builder.comment("Humidity noise xz scale (blocks). Humidity field frequency = 1/humidityScale. Default 800.")
            .defineInRange("humidityScale", 800.0, 100.0, 8000.0);
        builder.pop();



        builder.push("World Height");
        seaLevel = builder.comment("Sea level (world Y).")
            .defineInRange("seaLevel", 63, -200, 400);
        minY = builder.comment("Minimum world Y.")
            .defineInRange("minY", -64, -512, 512);
        maxY = builder.comment("Maximum world Y.")
            .defineInRange("maxY", 320, -512, 1024);
        peakHeightFraction = builder.comment("Actual peak height as fraction of maxY (headroom so peaks don't hit the world build ceiling). Default 0.92, range [0.5, 1.0].")
            .defineInRange("peakHeightFraction", 0.92, 0.5, 1.0);
        builder.pop();

        builder.push("Scale");
        horizontalScale = builder.comment("Horizontal scale (0.5-8.0). Block->wu mapping (DEM resample concept): MC blocks divided by this = engine world-units. All terrain features (macro noise + erosion brushes + river carving + source grid) scale uniformly. Default 2.0 (2026-08-02: terrain was too steep H/W, overall frequency widening x2 restores normal slopes; 2026-08-10: wu-ized - engine never knows blocks, mapping happens at GeoGenesisTerrain facade only).")
            .defineInRange("horizontalScale", 2.0, 0.5, 8.0);
        verticalScale = builder.comment("Vertical scale multiplier (1-8). Applied to e->Y height mapping. 1=default, 2=twice as tall.")
            .defineInRange("verticalScale", 1.0, 1.0, 8.0);
        builder.pop();

        builder.push("Semantic Affinity");
        // Phase 2.2: 语义适配旋钮
        cAffinityStrength = builder.comment("大陆性语义亲和度强度 β（调制 c 对各类型空间权重的偏置幅度）。2026-08-06 默认 0 禁用：factor=1+β(aff-0.2) 跨 c 变化 100~200 倍 + BLEND_SHARPEN 放大 → 类型过渡带突变（弧形硬边，用户反馈）。0=纯空间 Voronoi 类型分布（自然过渡）。范围 [0,10]。")
            .defineInRange("cAffinityStrength", 0.0, 0.0, 10.0);
        builder.pop();

        builder.push("Coastline Diversification");
        coastlineWarpAmp = builder.comment("海岸线 warp 振幅（c 空间单位）。注意：大陆性 c 场现已是 FBM（多尺度细节直接刻进海岸线），此 warp 是在 c 上的额外位移补丁。默认 0.15（2026-08-06 破圆弧：0.05 位移仅 ~57 块不可见），范围 [0, 0.3]。")
            .defineInRange("coastlineWarpAmp", 0.15, 0.0, 0.3);
        coastlineWarpScale = builder.comment("海岸线 warp 基频世界坐标尺度（块）。越大变化越平缓。默认 1200（2026-08-06：300 波长在大俯视图不可见，1200 产生半岛/海湾级弯曲），范围 [50, 2000]。")
            .defineInRange("coastlineWarpScale", 1200.0, 50.0, 2000.0);
        coastlineWarpOctaves = builder.comment("海岸线 warp 倍频数（octaves）：控制分形细节尺度层级数。越大海岸线越多尺度（自相似犬牙交错），越小越平滑。默认 6，范围 [1, 8]。")
            .defineInRange("coastlineWarpOctaves", 6, 1, 8);
        coastlineWarpLacunarity = builder.comment("FBM 频率倍增系数（lacunarity）：每倍频频率×该值。默认 2.0，范围 [1.5, 3.0]。")
            .defineInRange("coastlineWarpLacunarity", 2.0, 1.5, 3.0);
        coastlineWarpPersistence = builder.comment("FBM 振幅衰减系数（persistence）：每倍频振幅×该值。默认 0.6（2026-08-06 0.5→0.6 增强高频细齿），范围 [0.25, 0.8]。")
            .defineInRange("coastlineWarpPersistence", 0.6, 0.25, 0.8);
        coastTerrainInfluence = builder.comment("地形类型调制强度（c 空间单位）。山地权重高→推岸出海成岬角/悬崖，平原→拉岸内凹成海湾。默认 0.08，范围 [0, 0.3]。")
            .defineInRange("coastTerrainInfluence", 0.08, 0.0, 0.3);
        archipelagoBand = builder.comment("离岸群岛带宽度（c 空间单位，从 coastLoc 向海侧延伸）。越大群岛可出现的海域范围越宽。默认 0.10，范围 [0, 0.3]。")
            .defineInRange("archipelagoBand", 0.10, 0.0, 0.3);
        archipelagoDensity = builder.comment("离岸群岛密度阈值（噪声 0-1）。超过此阈值才生成岛屿。越低→岛屿越多越密集。默认 0.30，范围 [0, 1]。")
            .defineInRange("archipelagoDensity", 0.30, 0.0, 1.0);
        archipelagoScale = builder.comment("离岸群岛噪声尺度（块）。越大→岛屿团块越大越稀疏。默认 120，范围 [30, 500]。")
            .defineInRange("archipelagoScale", 120.0, 30.0, 500.0);
        archipelagoHeight = builder.comment("离岸群岛最大高度（e 单位）。岛屿在地形 e 上的高度上限。默认 0.035，范围 [0, 0.15]。")
            .defineInRange("archipelagoHeight", 0.035, 0.0, 0.15);
        builder.pop();

        builder.push("River & Erosion");
        riverEnabled = builder.comment("Enable rivers (TF-style node distance-field valley carving). Default true.")
            .define("riverEnabled", true);
        riverWidth = builder.comment("River valley outer radius (world blocks). Controls the outermost valley width (~TF valleyWidth). Default 80, range [30, 200].")
            .defineInRange("riverWidth", 80.0, 30.0, 200.0);
        riverValleyDepth = builder.comment("River bed incision depth (e units). Deeper = more incised valley. Default 0.01 (~4 blocks), range [0.0, 0.06].")
            .defineInRange("riverValleyDepth", 0.01, 0.0, 0.06);
        riverShape = builder.comment("River valley profile shape [0,1]: 0=V-shaped (steep), 1=U-shaped (broad). Default 0.5, range [0, 1].")
            .defineInRange("riverShape", 0.5, 0.0, 1.0);
        riverWater = builder.comment("Fill river channels with water (false = dry valleys, terrain notch only). Default true.")
            .define("riverWater", true);
        lakeDensity = builder.comment("Source-lake / lake density for river node field. Default 0.15, range [0, 1].")
            .defineInRange("lakeDensity", 0.15, 0.0, 1.0);
        riversEnabled = builder.comment("Enable river system (D8 flow detection + river channel filling). Set false to isolate erosion testing. Default true.")
            .define("riversEnabled", true);
        erosionEnabled = builder.comment("Enable local particle erosion (seamless, margin-filled with true neighbour heights). Default true.")
            .define("erosionEnabled", true);
        erosionStrength = builder.comment("Erosion strength multiplier. Default 1.0, range [0, 4].")
            .defineInRange("erosionStrength", 1.0, 0.0, 4.0);
        erosionDropsMul = builder.comment("Erosion droplet count multiplier. Lower = faster (preview). Default 1.0, range [0.1, 4].")
            .defineInRange("erosionDropsMul", 1.0, 0.1, 4.0);
        erosionErodeMul = builder.comment("Erosion erode-rate multiplier. Default 1.0, range [0.1, 4].")
                .defineInRange("erosionErodeMul", 1.0, 0.1, 4.0);
        erosionLocalChargeWeight = builder.comment("localCharge positive feedback weight α. Each droplet's cumulative desc ent amplifies capacity: 0=off, higher=main valleys cut deeper vs tributaries. Default 1.0, range [0, 5].")
                .defineInRange("erosionLocalChargeWeight", 1.0, 0.0, 5.0);
        erosionCascadeStrength = builder.comment("Local cascade settling strength. After erosion, transfer material from higher cells toward lowest 8-neighbor, smoothing riverbeds. 0=off. Default 0.5 (2026-08-10: 0.3->0.5, sharper gully edges), range [0, 1].")
                .defineInRange("erosionCascadeStrength", 0.5, 0.0, 1.0);
        riverDischargeThreshold = builder.comment("River threshold (background-to-peak ratio): larger = sparser network (main trunk only). Default 0.02, range [0.001, 0.3]. Works with convergence weighting (only river-mode droplets accumulate discharge).")
                .defineInRange("riverDischargeThreshold", 0.02, 0.001, 0.3);
        erosionRiverLcLo = builder.comment("River-particle mode: cumulative-drop (lc) eligibility low threshold. Droplets exceeding lc begin transitioning toward river mode (strong erosion + weak deposition). Default 0.05, range [0.005, 1.0].")
                .defineInRange("erosionRiverLcLo", 0.05, 0.005, 1.0);
        erosionRiverLcHi = builder.comment("River-particle mode: lc eligibility high threshold (full river mode above). Default 0.15, range [0.01, 2.0].")
                .defineInRange("erosionRiverLcHi", 0.15, 0.01, 2.0);
        erosionRiverDisLo = builder.comment("River-particle mode: discharge confirmation low threshold (how much water has traversed this path). Default 0.3, range [0.05, 50] (scale ~10x lower after convergence weighting).")
                .defineInRange("erosionRiverDisLo", 0.3, 0.05, 50.0);
        erosionRiverDisHi = builder.comment("River-particle mode: discharge confirmation high threshold. Default 1.5, range [0.5, 200].")
                .defineInRange("erosionRiverDisHi", 1.5, 0.5, 200.0);
        erosionMomentumTransfer = builder.comment("SH momentum-field positive feedback: droplets self-accelerate along downstream momentum field (rivers self-reinforce). 1.0 = SH original, 0 = off. Default 1.0, range [0, 2].")
                .defineInRange("erosionMomentumTransfer", 1.0, 0.0, 2.0);
        erosionIterations = builder.comment("SH multi-pass iteration count: each pass respawns all droplets + lrate field smoothing; channels deepen progressively. Default 2 (2026-08-11: 5->2, deep-gully fix; SH Erosion.cs numIterations=1, 2 keeps momentum field accumulation), range [1, 16].")
                .defineInRange("erosionIterations", 2, 1, 16);
        // NOTE(2026-08-10): toml 旧值 2 会覆盖代码默认——修改默认后必须同步 run/config/geogenesis-common.toml
        erosionLrate = builder.comment("SH field smoothing rate lrate: proportion of each pass's track blended into the steady-state field (0.1 = SH original). Default 0.1, range [0.01, 0.5].")
                .defineInRange("erosionLrate", 0.1, 0.01, 0.5);
        erosionRiverStrength = builder.comment("River-particle mode total strength: 0=off (legacy behaviour), 1=strongest (erode x3, deposit x0.2, evaporate x0.1). Default 0.6, range [0, 1].")
                .defineInRange("erosionRiverStrength", 0.6, 0.0, 1.0);
        // ===== 粗侵蚀骨架层（脊-谷条纹滤镜，Rune Skovbo Johansen 2026 + Luke Mitchell Burst C#）=====
        erosionRidgeEnabled = builder.comment("Coarse ridge-valley skeleton layer (gradient-aligned stripe filter). Builds mountain-ridge basic form before particle detail erosion. Default true.")
                .define("erosionRidgeEnabled", true);
        erosionRidgeStrength = builder.comment("Coarse skeleton erosion strength. Default 0.5 (2026-08-06: 0.35 delta ±0.03e 仍不明显), range [0, 0.75].")
                .defineInRange("erosionRidgeStrength", 0.5, 0.0, 0.75);
        erosionRidgeScale = builder.comment("Coarse skeleton feature size (world blocks) = stripe cell world size; larger = wider ridges. Default 100, range [50, 800].")
                .defineInRange("erosionRidgeScale", 100.0, 50.0, 800.0);
        erosionRidgeCellScale = builder.comment("Coarse skeleton in-cell stripe frequency (ridge-valley density). Default 1.2, range [0.2, 2.0].")
                .defineInRange("erosionRidgeCellScale", 1.2, 0.2, 2.0);
        erosionRidgeOctaves = builder.comment("Coarse skeleton octaves (main + secondary + fine gullies; 4 at spacing=2 no aliasing). Default 4, range [1, 5].")
                .defineInRange("erosionRidgeOctaves", 4, 1, 5);
        erosionRidgeGullyWeight = builder.comment("Coarse skeleton slope-accumulation weight (ridge-valley sharpness). Default 0.65 (2026-08-06: 0.5→0.65 加强条纹), range [0, 1].")
                .defineInRange("erosionRidgeGullyWeight", 0.65, 0.0, 1.0);
        erosionRidgeRounding = builder.comment("Ridge profile rounding (0=sharp V-cut, 0.5=default balanced, 1=max rounded U-shape). Default 0.5, range [0, 1].")
                .defineInRange("erosionRidgeRounding", 0.5, 0.0, 1.0);
        erosionRidgeLandRef = builder.comment("Coarse skeleton fadeTarget reference (land median, e units). Default 0.15 matches land median; old fixed 0.25 caused uniform 4~11-block downcutting on low-elevation worlds. Range [0.02, 0.5].")
                .defineInRange("erosionRidgeLandRef", 0.15, 0.02, 0.5);
        erosionRidgeDetail = builder.comment("Gully detail density (PowInv exponent; lower=finer gullies everywhere, higher=cleaner main ridges). Default 1.0, range [0.7, 3.0].")
                .defineInRange("erosionRidgeDetail", 1.0, 0.7, 3.0);
        builder.pop();

        builder.push("Phase 1 Unified Spline");
        // Outer spline control points (7 × 2 = 14 fields)
        splineOuterLoc0 = builder.comment("Outer spline point 0 location (deep ocean). Default -0.80.")
            .defineInRange("splineOuterLoc0", -0.80, -1.0, 1.0);
        splineOuterDeriv0 = builder.comment("Outer spline point 0 derivative.")
            .defineInRange("splineOuterDeriv0", 0.0, -10.0, 10.0);
        splineOuterLoc1 = builder.comment("Outer spline point 1 location (continental shelf). Default -0.50.")
            .defineInRange("splineOuterLoc1", -0.50, -1.0, 1.0);
        splineOuterDeriv1 = builder.comment("Outer spline point 1 derivative.")
            .defineInRange("splineOuterDeriv1", 0.0, -10.0, 10.0);
        splineOuterLoc2 = builder.comment("Outer spline point 2 location (shallow sea). Default -0.16.")
            .defineInRange("splineOuterLoc2", -0.16, -1.0, 1.0);
        splineOuterDeriv2 = builder.comment("Outer spline point 2 derivative.")
            .defineInRange("splineOuterDeriv2", 0.0, -10.0, 10.0);
        splineOuterLoc3 = builder.comment("Outer spline point 3 location (coastline). Default -0.04.")
            .defineInRange("splineOuterLoc3", -0.04, -1.0, 1.0);
        splineOuterDeriv3 = builder.comment("Outer spline point 3 derivative.")
            .defineInRange("splineOuterDeriv3", 0.0, -10.0, 10.0);
        splineOuterLoc4 = builder.comment("Outer spline point 4 location (near-shore land). Default 0.20.")
            .defineInRange("splineOuterLoc4", 0.20, -1.0, 1.0);
        splineOuterDeriv4 = builder.comment("Outer spline point 4 derivative.")
            .defineInRange("splineOuterDeriv4", 0.0, -10.0, 10.0);
        splineOuterLoc5 = builder.comment("Outer spline point 5 location (inland). Default 0.50.")
            .defineInRange("splineOuterLoc5", 0.50, -1.0, 1.0);
        splineOuterDeriv5 = builder.comment("Outer spline point 5 derivative.")
            .defineInRange("splineOuterDeriv5", 0.0, -10.0, 10.0);
        splineOuterLoc6 = builder.comment("Outer spline point 6 location (deep inland). Default 0.80.")
            .defineInRange("splineOuterLoc6", 0.80, -1.0, 1.0);
        splineOuterDeriv6 = builder.comment("Outer spline point 6 derivative.")
            .defineInRange("splineOuterDeriv6", 0.0, -10.0, 10.0);

        // PLAIN inner spline (12 fields)
        builder.push("PLAIN");
        plainLoLoc0 = builder.defineInRange("plainLoLoc0", 0.0, 0.0, 1.0);
        plainLoVal0 = builder.defineInRange("plainLoVal0", 0.005, -1.0, 1.0);
        plainLoDeriv0 = builder.defineInRange("plainLoDeriv0", 0.0, -10.0, 10.0);
        plainLoLoc1 = builder.defineInRange("plainLoLoc1", 1.0, 0.0, 1.0);
        plainLoVal1 = builder.defineInRange("plainLoVal1", 0.005, -1.0, 1.0);
        plainLoDeriv1 = builder.defineInRange("plainLoDeriv1", 0.0, -10.0, 10.0);
        plainHiLoc0 = builder.defineInRange("plainHiLoc0", 0.0, 0.0, 1.0);
        plainHiVal0 = builder.defineInRange("plainHiVal0", 0.03, -1.0, 1.0);
        plainHiDeriv0 = builder.defineInRange("plainHiDeriv0", 0.0, -10.0, 10.0);
        plainHiLoc1 = builder.defineInRange("plainHiLoc1", 1.0, 0.0, 1.0);
        plainHiVal1 = builder.defineInRange("plainHiVal1", 0.03, -1.0, 1.0);
        plainHiDeriv1 = builder.defineInRange("plainHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // HILLS inner spline (12 fields)
        builder.push("HILLS");
        hillsLoLoc0 = builder.defineInRange("hillsLoLoc0", 0.0, 0.0, 1.0);
        hillsLoVal0 = builder.defineInRange("hillsLoVal0", 0.06, -1.0, 1.0);
        hillsLoDeriv0 = builder.defineInRange("hillsLoDeriv0", 0.0, -10.0, 10.0);
        hillsLoLoc1 = builder.defineInRange("hillsLoLoc1", 1.0, 0.0, 1.0);
        hillsLoVal1 = builder.defineInRange("hillsLoVal1", 0.06, -1.0, 1.0);
        hillsLoDeriv1 = builder.defineInRange("hillsLoDeriv1", 0.0, -10.0, 10.0);
        hillsHiLoc0 = builder.defineInRange("hillsHiLoc0", 0.0, 0.0, 1.0);
        hillsHiVal0 = builder.defineInRange("hillsHiVal0", 0.368, -1.0, 1.0);
        hillsHiDeriv0 = builder.defineInRange("hillsHiDeriv0", 0.0, -10.0, 10.0);
        hillsHiLoc1 = builder.defineInRange("hillsHiLoc1", 1.0, 0.0, 1.0);
        hillsHiVal1 = builder.defineInRange("hillsHiVal1", 0.368, -1.0, 1.0);
        hillsHiDeriv1 = builder.defineInRange("hillsHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // MOUNTAINS inner spline (12 fields)
        builder.push("MOUNTAINS");
        mountLoLoc0 = builder.defineInRange("mountLoLoc0", 0.0, 0.0, 1.0);
        mountLoVal0 = builder.defineInRange("mountLoVal0", 0.45, -1.0, 1.0);
        mountLoDeriv0 = builder.defineInRange("mountLoDeriv0", 0.0, -10.0, 10.0);
        mountLoLoc1 = builder.defineInRange("mountLoLoc1", 1.0, 0.0, 1.0);
        mountLoVal1 = builder.defineInRange("mountLoVal1", 0.45, -1.0, 1.0);
        mountLoDeriv1 = builder.defineInRange("mountLoDeriv1", 0.0, -10.0, 10.0);
        mountHiLoc0 = builder.defineInRange("mountHiLoc0", 0.0, 0.0, 1.0);
        mountHiVal0 = builder.defineInRange("mountHiVal0", 0.95, -1.0, 1.0);
        mountHiDeriv0 = builder.defineInRange("mountHiDeriv0", 0.0, -10.0, 10.0);
        mountHiLoc1 = builder.defineInRange("mountHiLoc1", 1.0, 0.0, 1.0);
        mountHiVal1 = builder.defineInRange("mountHiVal1", 0.95, -1.0, 1.0);
        mountHiDeriv1 = builder.defineInRange("mountHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // PLATEAU inner spline (12 fields)
        builder.push("PLATEAU");
        platLoLoc0 = builder.defineInRange("platLoLoc0", 0.0, 0.0, 1.0);
        platLoVal0 = builder.defineInRange("platLoVal0", 0.41, -1.0, 1.0);
        platLoDeriv0 = builder.defineInRange("platLoDeriv0", 0.0, -10.0, 10.0);
        platLoLoc1 = builder.defineInRange("platLoLoc1", 1.0, 0.0, 1.0);
        platLoVal1 = builder.defineInRange("platLoVal1", 0.41, -1.0, 1.0);
        platLoDeriv1 = builder.defineInRange("platLoDeriv1", 0.0, -10.0, 10.0);
        platHiLoc0 = builder.defineInRange("platHiLoc0", 0.0, 0.0, 1.0);
        platHiVal0 = builder.defineInRange("platHiVal0", 0.706, -1.0, 1.0);
        platHiDeriv0 = builder.defineInRange("platHiDeriv0", 0.0, -10.0, 10.0);
        platHiLoc1 = builder.defineInRange("platHiLoc1", 1.0, 0.0, 1.0);
        platHiVal1 = builder.defineInRange("platHiVal1", 0.706, -1.0, 1.0);
        platHiDeriv1 = builder.defineInRange("platHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // BASIN inner spline (12 fields)
        builder.push("BASIN");
        basinLoLoc0 = builder.defineInRange("basinLoLoc0", 0.0, 0.0, 1.0);
        basinLoVal0 = builder.defineInRange("basinLoVal0", -0.08, -1.0, 1.0);
        basinLoDeriv0 = builder.defineInRange("basinLoDeriv0", 0.0, -10.0, 10.0);
        basinLoLoc1 = builder.defineInRange("basinLoLoc1", 1.0, 0.0, 1.0);
        basinLoVal1 = builder.defineInRange("basinLoVal1", 0.015, -1.0, 1.0);
        basinLoDeriv1 = builder.defineInRange("basinLoDeriv1", 0.0, -10.0, 10.0);
        basinHiLoc0 = builder.defineInRange("basinHiLoc0", 0.0, 0.0, 1.0);
        basinHiVal0 = builder.defineInRange("basinHiVal0", 0.08, -1.0, 1.0);
        basinHiDeriv0 = builder.defineInRange("basinHiDeriv0", 0.0, -10.0, 10.0);
        basinHiLoc1 = builder.defineInRange("basinHiLoc1", 1.0, 0.0, 1.0);
        basinHiVal1 = builder.defineInRange("basinHiVal1", 0.08, -1.0, 1.0);
        basinHiDeriv1 = builder.defineInRange("basinHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        builder.pop(); // Phase 1 Unified Spline

        // Phase 2: Initialize MidSplineConfig from defaults
        // Note: MidSplineConfig has 105 fields (7 nodes × 5 types × 3 fields).
        // For TOML config, we use defaults rather than 105 individual fields.
        // Users can override via a separate config file or API.
        this.midSplineConfig = com.geogenesis.worldgen.terrain.MidSplineConfig.defaults();

        builder.pop(); // GeoGenesis Terrain Generation
    }

    /**
     * 侵蚀/河流运行时配置指纹。
     * 2026-08-06 新增：侵蚀 tile 缓存与预览磁盘缓存的失效依据——配置改动（开/关骨架、
     * 侵蚀/河流参数）后旧缓存自动失效，避免"改配置没变化"。显式字段（编译期检查）。
     */
    public static long configFingerprint() {
        if (INSTANCE == null) return 0L;
        try {
            long h = 1;
            h = h * 31 + (INSTANCE.riversEnabled.get() ? 1 : 0);
            h = h * 31 + (INSTANCE.erosionEnabled.get() ? 1 : 0);
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionStrength.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionDropsMul.get());
            h = h * 31 + (INSTANCE.erosionRidgeEnabled.get() ? 1 : 0);
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeStrength.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeScale.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeCellScale.get());
            h = h * 31 + INSTANCE.erosionRidgeOctaves.get();
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeGullyWeight.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeRounding.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeLandRef.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionRidgeDetail.get());
            // 2026-08-06：海岸线 warp 参数（侵蚀采样依赖地形含海岸线，改动须清侵蚀 tile 缓存）
            h = h * 31 + Double.doubleToLongBits(INSTANCE.coastlineWarpAmp.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.coastlineWarpScale.get());
            h = h * 31 + INSTANCE.coastlineWarpOctaves.get();
            h = h * 31 + Double.doubleToLongBits(INSTANCE.coastlineWarpPersistence.get());
            return h;
        } catch (IllegalStateException e) {
            // Forge 配置未加载（探针/独立预览等非 MC 环境）→ 返回 0（无指纹，缓存不失效）
            return 0L;
        }
    }

    /**
     * 导出 TerrainParams（底层引擎所需）。
     *
     * 仅供游戏运行时调用：依赖 Forge 配置已 load()，通过 {@code ConfigValue.get()} 取实时值。
     */
    public com.geogenesis.worldgen.terrain.TerrainParams buildParams() {
        // 理论峰顶 = 最高陆地类型 e 上限（maxLandHi）× 高度范围 × 峰高比例 + 海平面（mountainCap）
        double landHi = Math.max(Math.max(plainHiVal0.get(), hillsHiVal0.get()),
                Math.max(mountHiVal0.get(), Math.max(platHiVal0.get(), basinHiVal0.get())));
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.get(), continentWarp.get(), continentFbmOctaves.get(), continentFbmLacunarity.get(), continentFbmPersistence.get(), continentThreshold.get(), continentBias.get(), continentProvinceWarp.get(),
            deepOceanLoc.get(), shelfLoc.get(), shallowLoc.get(), coastLoc.get(),
            deepOceanDepth.get(), shelfDepth.get(), shallowDepth.get(),
            deepOceanDeriv.get(), shelfDeriv.get(), shallowDeriv.get(), coastDeriv.get(),
            // coastline transition
            oceanFadeStart.get(), landRampEnd.get(),
            // 海洋类型独立 lo/hi
            oceanLo.get(), oceanHi.get(),
            deepOceanLo.get(), deepOceanHi.get(),
            shelfLo.get(), shelfHi.get(),
            subRidgeLo.get(), subRidgeHi.get(),
            seamountLo.get(), seamountHi.get(),
            lakeLo.get(), lakeHi.get(),
            riverLo.get(), riverHi.get(),
            seabedDetail.get(), oceanDepthFactor.get(),
            provinceScale.get(), cratonWeight.get(), beltWeight.get(),
            plateauWeight.get(), basinWeight.get(),
            plainBase.get(), plainRough.get(), hillsLow.get(), hillsHigh.get(),
            beltRidgePower.get(), beltFoothill.get(), beltPeak.get(),
            plateauBase.get(), plateauTop.get(), plateauSteps.get(), plateauStepStrength.get(),
            basinBase.get(),
            cratonReliefAmp.get(), beltReliefAmp.get(), plateauReliefAmp.get(), basinReliefAmp.get(),
            beltSharpness.get(), beltWarpAmp.get(), provMixSharpness.get(),
            mountainMaskScale.get(), microDetailScale.get(), microDetailAmp.get(),
            elevHigh.get(), reliefHigh.get(), snowLatitudeInfluence.get(), snowHumidityInfluence.get(),

            // preview compat defaults
            horizontalScale.get(), seaLevel.get(), snowLine.get(), minY.get(),
            maxY.get(), peakHeightFraction.get(), (int) Math.round(seaLevel.get() + (maxY.get() - seaLevel.get()) * peakHeightFraction.get() * landHi), (int)(minY.get() * 0.75),
            verticalScale.get(),
            cAffinityStrength.get(),
            // coastline diversification
            coastlineWarpAmp.get(), coastlineWarpScale.get(), coastlineWarpOctaves.get(), coastlineWarpLacunarity.get(), coastlineWarpPersistence.get(), coastTerrainInfluence.get(),
            archipelagoBand.get(), archipelagoDensity.get(), archipelagoScale.get(), archipelagoHeight.get(),
            latitudeScale.get(), tempWarpScale.get(), humidityScale.get(),

            // Phase 1: unified spline config (built from individual fields)
            buildSplineConfig()
        );
    }

    /**
     * 重置所有配置字段到默认值。
     *
     * 反射遍历本类所有 public 的 {@code ConfigValue} 字段（DoubleValue/IntValue 等），
     * 统一调用 {@code set(getDefault())}。相比手写的逐字段 .set()，此方式能自动覆盖
     * 新增字段，彻底杜绝「在 reset 中漏写某字段」类 bug（曾导致地形类型范围/外层样条
     * 不被重置）。{@code SPEC}/{@code INSTANCE}（非 ConfigValue）与 private 的
     * {@code midSplineConfig} 会被自动跳过；样条由独立字段重建，重置字段即足够。
     */
    public void resetToDefault() {
        for (Field f : GeoGenesisConfig.class.getFields()) {
            try {
                Object v = f.get(this);
                if (v instanceof ForgeConfigSpec.ConfigValue) {
                    // 用原始类型调用：规避通配符捕获推导问题（getDefault()/set() 在原始类型下退化为 Object）
                    @SuppressWarnings("unchecked")
                    ForgeConfigSpec.ConfigValue<Object> cv = (ForgeConfigSpec.ConfigValue<Object>) v;
                    cv.set(cv.getDefault());
                }
            } catch (Exception ignored) {
                // 反射访问异常或单个字段写入竞争（Windows 文件锁）忽略，继续重置其余字段
            }
        }
    }

    /**
     * 捕获所有 ConfigValue 字段的当前值，key=字段名，value=字段当前值（保留 Double/Integer
     * /Boolean/String/Enum 原始类型）。供「保存为自定义预设」对当前全局配置拍快照。
     * SPEC/INSTANCE（非 ConfigValue）与 private midSplineConfig 会被自动跳过。
     */
    public Map<String, Object> captureAllValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : GeoGenesisConfig.class.getFields()) {
            try {
                Object v = f.get(this);
                if (v instanceof ForgeConfigSpec.ConfigValue) {
                    ForgeConfigSpec.ConfigValue<Object> cv = (ForgeConfigSpec.ConfigValue<Object>) v;
                    out.put(f.getName(), cv.get());
                }
            } catch (Exception ignored) {
                // 反射访问异常忽略，继续捕获其余字段
            }
        }
        return out;
    }

    /**
     * 按字段名把命名值（以字符串存储）应用回配置：先 resetToDefault 再 set，未知字段忽略。
     * 值按字段当前值的运行时类型解析（Double/Integer/Boolean/String 直转，Enum 用 valueOf）。
     * 供「加载自定义预设」把存档快照还原到全局配置。
     */
    public void applyNamedValues(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            try {
                Field f = GeoGenesisConfig.class.getField(e.getKey());
                Object v = f.get(this);
                if (!(v instanceof ForgeConfigSpec.ConfigValue)) continue;
                ForgeConfigSpec.ConfigValue<Object> cv = (ForgeConfigSpec.ConfigValue<Object>) v;
                Object parsed = parseByType(cv.get(), e.getValue());
                if (parsed != null) cv.set(parsed);
            } catch (Exception ignored) {
                // 字段不存在或类型解析失败忽略，继续应用其余字段
            }
        }
    }

    /** 按当前字段值的运行时类型把字符串解析回对应对象；无法解析返回 null。 */
    private static Object parseByType(Object cur, String s) {
        try {
            if (cur instanceof Double) return Double.parseDouble(s);
            if (cur instanceof Integer) return Integer.parseInt(s);
            if (cur instanceof Boolean) return Boolean.parseBoolean(s);
            if (cur instanceof String) return s;
            if (cur instanceof Enum) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends Enum> ec = (Class<? extends Enum>) cur.getClass();
                return Enum.valueOf(ec, s);
            }
        } catch (Exception ignored) {
            // 解析失败返回 null，由调用方跳过该字段
        }
        return null;
    }

    /** 从独立配置字段构建 SplineConfig（Phase 3：含海洋/水域类型） */
    private com.geogenesis.worldgen.terrain.SplineConfig buildSplineConfig() {
        return new com.geogenesis.worldgen.terrain.SplineConfig(
            splineOuterLoc0.get(), splineOuterDeriv0.get(),
            splineOuterLoc1.get(), splineOuterDeriv1.get(),
            splineOuterLoc2.get(), splineOuterDeriv2.get(),
            splineOuterLoc3.get(), splineOuterDeriv3.get(),
            splineOuterLoc4.get(), splineOuterDeriv4.get(),
            splineOuterLoc5.get(), splineOuterDeriv5.get(),
            splineOuterLoc6.get(), splineOuterDeriv6.get(),
            plainLoLoc0.get(), plainLoVal0.get(), plainLoDeriv0.get(),
            plainLoLoc1.get(), plainLoVal1.get(), plainLoDeriv1.get(),
            plainHiLoc0.get(), plainHiVal0.get(), plainHiDeriv0.get(),
            plainHiLoc1.get(), plainHiVal1.get(), plainHiDeriv1.get(),
            hillsLoLoc0.get(), hillsLoVal0.get(), hillsLoDeriv0.get(),
            hillsLoLoc1.get(), hillsLoVal1.get(), hillsLoDeriv1.get(),
            hillsHiLoc0.get(), hillsHiVal0.get(), hillsHiDeriv0.get(),
            hillsHiLoc1.get(), hillsHiVal1.get(), hillsHiDeriv1.get(),
            mountLoLoc0.get(), mountLoVal0.get(), mountLoDeriv0.get(),
            mountLoLoc1.get(), mountLoVal1.get(), mountLoDeriv1.get(),
            mountHiLoc0.get(), mountHiVal0.get(), mountHiDeriv0.get(),
            mountHiLoc1.get(), mountHiVal1.get(), mountHiDeriv1.get(),
            platLoLoc0.get(), platLoVal0.get(), platLoDeriv0.get(),
            platLoLoc1.get(), platLoVal1.get(), platLoDeriv1.get(),
            platHiLoc0.get(), platHiVal0.get(), platHiDeriv0.get(),
            platHiLoc1.get(), platHiVal1.get(), platHiDeriv1.get(),
            basinLoLoc0.get(), basinLoVal0.get(), basinLoDeriv0.get(),
            basinLoLoc1.get(), basinLoVal1.get(), basinLoDeriv1.get(),
            basinHiLoc0.get(), basinHiVal0.get(), basinHiDeriv0.get(),
            basinHiLoc1.get(), basinHiVal1.get(), basinHiDeriv1.get(),
            // Phase 3: ocean/water type inner splines (use independent lo/hi from config)
            buildOceanSplineConfig(),
            // Phase 2: mid spline config
            midSplineConfig
        );
    }

    /**
     * 导出 TerrainParams 的默认值版本，供独立预览（{@code runPreview}）使用。
     *
     * <p>独立预览脱离 Minecraft/Forge 运行时，Forge 配置从未 load()，调用 {@link #buildParams()}
     * 的 {@code ConfigValue.get()} 会抛 {@code IllegalStateException: Cannot get config value before config is loaded}。
     * 此处读取 spec 的默认值（{@code getDefault()}），与游戏内默认地形保持一致，且无需加载配置。
     *
     * <p>若日后 spec 默认值变更，此方法随 {@link #buildParams()} 同步更新即可，避免与
     * {@code TerrainParams.defaults()} 那样的硬编码副本再次漂移。
     */
    public com.geogenesis.worldgen.terrain.TerrainParams defaultParams() {
        // 理论峰顶 = maxLandHi × 高度范围 × 峰高比例 + 海平面（mountainCap，独立预览路径）
        double landHi = Math.max(Math.max(plainHiVal0.getDefault(), hillsHiVal0.getDefault()),
                Math.max(mountHiVal0.getDefault(), Math.max(platHiVal0.getDefault(), basinHiVal0.getDefault())));
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.getDefault(), continentWarp.getDefault(), continentFbmOctaves.getDefault(), continentFbmLacunarity.getDefault(), continentFbmPersistence.getDefault(), continentThreshold.getDefault(), continentBias.getDefault(), continentProvinceWarp.getDefault(),
            deepOceanLoc.getDefault(), shelfLoc.getDefault(), shallowLoc.getDefault(), coastLoc.getDefault(),
            deepOceanDepth.getDefault(), shelfDepth.getDefault(), shallowDepth.getDefault(),
            deepOceanDeriv.getDefault(), shelfDeriv.getDefault(), shallowDeriv.getDefault(), coastDeriv.getDefault(),
            // coastline transition
            oceanFadeStart.getDefault(), landRampEnd.getDefault(),
            // 海洋类型独立 lo/hi
            oceanLo.getDefault(), oceanHi.getDefault(),
            deepOceanLo.getDefault(), deepOceanHi.getDefault(),
            shelfLo.getDefault(), shelfHi.getDefault(),
            subRidgeLo.getDefault(), subRidgeHi.getDefault(),
            seamountLo.getDefault(), seamountHi.getDefault(),
            lakeLo.getDefault(), lakeHi.getDefault(),
            riverLo.getDefault(), riverHi.getDefault(),
            seabedDetail.getDefault(), oceanDepthFactor.getDefault(),
            provinceScale.getDefault(), cratonWeight.getDefault(), beltWeight.getDefault(),
            plateauWeight.getDefault(), basinWeight.getDefault(),
            plainBase.getDefault(), plainRough.getDefault(), hillsLow.getDefault(), hillsHigh.getDefault(),
            beltRidgePower.getDefault(), beltFoothill.getDefault(), beltPeak.getDefault(),
            plateauBase.getDefault(), plateauTop.getDefault(), plateauSteps.getDefault(), plateauStepStrength.getDefault(),
            basinBase.getDefault(),
            cratonReliefAmp.getDefault(), beltReliefAmp.getDefault(), plateauReliefAmp.getDefault(), basinReliefAmp.getDefault(),
            beltSharpness.getDefault(), beltWarpAmp.getDefault(), provMixSharpness.getDefault(),
            mountainMaskScale.getDefault(), microDetailScale.getDefault(), microDetailAmp.getDefault(),
            elevHigh.getDefault(), reliefHigh.getDefault(), snowLatitudeInfluence.getDefault(), snowHumidityInfluence.getDefault(),

            // preview compat defaults
            horizontalScale.getDefault(), seaLevel.getDefault(), snowLine.getDefault(), minY.getDefault(),
            maxY.getDefault(), 0.92, (int) Math.round(seaLevel.getDefault() + (maxY.getDefault() - seaLevel.getDefault()) * 0.92 * landHi), (int)(minY.getDefault() * 0.75),
            verticalScale.getDefault(),
            cAffinityStrength.getDefault(),
            // coastline diversification
            coastlineWarpAmp.getDefault(), coastlineWarpScale.getDefault(), coastlineWarpOctaves.getDefault(), coastlineWarpLacunarity.getDefault(), coastlineWarpPersistence.getDefault(), coastTerrainInfluence.getDefault(),
            archipelagoBand.getDefault(), archipelagoDensity.getDefault(), archipelagoScale.getDefault(), archipelagoHeight.getDefault(),
            latitudeScale.getDefault(), tempWarpScale.getDefault(), humidityScale.getDefault(),

            // Phase 1: unified spline config (built from default values)
            buildDefaultSplineConfig()
        );
    }

    /** 从配置字段构建 OceanSplineConfig（使用独立的 lo/hi 值） */
    private com.geogenesis.worldgen.terrain.OceanSplineConfig buildOceanSplineConfig() {
        return new com.geogenesis.worldgen.terrain.OceanSplineConfig(
            // OCEAN
            0.0, oceanLo.get(), 0.0, 1.0, oceanLo.get(), 0.0,
            0.0, oceanHi.get(), 0.0, 1.0, oceanHi.get(), 0.0,
            // DEEP_OCEAN
            0.0, deepOceanLo.get(), 0.0, 1.0, deepOceanLo.get(), 0.0,
            0.0, deepOceanHi.get(), 0.0, 1.0, deepOceanHi.get(), 0.0,
            // CONTINENTAL_SHELF
            0.0, shelfLo.get(), 0.0, 1.0, shelfLo.get(), 0.0,
            0.0, shelfHi.get(), 0.0, 1.0, shelfHi.get(), 0.0,
            // SUBMARINE_RIDGE
            0.0, subRidgeLo.get(), 0.0, 1.0, subRidgeLo.get(), 0.0,
            0.0, subRidgeHi.get(), 0.0, 1.0, subRidgeHi.get(), 0.0,
            // SEAMOUNT
            0.0, seamountLo.get(), 0.0, 1.0, seamountLo.get(), 0.0,
            0.0, seamountHi.get(), 0.0, 1.0, seamountHi.get(), 0.0,
            // LAKE
            0.0, lakeLo.get(), 0.0, 1.0, lakeLo.get(), 0.0,
            0.0, lakeHi.get(), 0.0, 1.0, lakeHi.get(), 0.0,
            // RIVER
            0.0, riverLo.get(), 0.0, 1.0, riverLo.get(), 0.0,
            0.0, riverHi.get(), 0.0, 1.0, riverHi.get(), 0.0
        );
    }

    /** 从默认值构建 SplineConfig（Phase 3：含海洋/水域类型） */
    private com.geogenesis.worldgen.terrain.SplineConfig buildDefaultSplineConfig() {
        // 直接使用 SplineConfig.defaults()，因为所有字段都是默认值
        return com.geogenesis.worldgen.terrain.SplineConfig.defaults();
    }
}
