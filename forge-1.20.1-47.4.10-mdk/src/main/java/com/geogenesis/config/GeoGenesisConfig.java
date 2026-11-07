package com.geogenesis.config;

import com.geogenesis.worldgen.cave.CaveConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GeoGenesis Forge COMMON 配置。
 *
 * 包含所有地形参数（大陆场、海洋样条、海岸、海床、地形形态、分类/气候、侵蚀、洞穴、矿脉）。
 * 由 {@code buildParams()} 导出为 TerrainParams 注入引擎。
 *
 * 铁律：增删字段须同步 TerrainParams(defaults) + GeoGenesisGenerator(configParams/configRiverSettings) +
 * GeoGenesisConfigScreen.buildParams + run/config/geogenesis-common.toml。
 *
 * <h2>★ 算法轴标注（只影响哪一条地形链）</h2>
 *
 * <p>本项目有<b>两条互斥</b>的地形实现，由<b>存档持久化的算法标记</b>选择
 * （{@code GeoGenesisWorldData.terrainAlgorithm}），<b>不是</b>由配置开关选择：</p>
 * <ul>
 *   <li><b>morph-base-p1</b>（新世界）：{@code CellGenerator.morphBase=true} ⇒
 *       {@code BaseWorldSampler → MorphTerrainSampler → MorphLandField/MorphOceanAssembler}。</li>
 *   <li><b>legacy</b>（旧存档）：{@code morphBase=false} ⇒
 *       {@code sampleCore} 走 {@code ContinentField/TypeLandShape/TypeNoiseProvider/CoastlineField}。</li>
 * </ul>
 *
 * <p>每个字段上的轴标记含义（四选一，写在 javadoc 首行 {@code [TAG]} 里）：</p>
 * <table border="1">
 *   <caption>算法轴枚举</caption>
 *   <tr><th>标记</th><th>含义</th></tr>
 *   <tr><td>{@code [MORPH_ONLY]}</td><td>只影响 morph-base-p1 新世界的地形产出。</td></tr>
 *   <tr><td>{@code [LEGACY_ONLY]}</td><td>只影响 legacy 存档的地形产出（本次保留但标注）。</td></tr>
 *   <tr><td>{@code [BOTH]}</td><td>两条链都影响（典型：进入 {@code TerrainParams} 且被两边共用的场）。</td></tr>
 *   <tr><td>{@code [DIAGNOSTIC]}</td><td>只影响日志/诊断/自检，<b>不改任何地形产出</b>。</td></tr>
 *   <tr><td>{@code [PREVIEW_ONLY]}</td><td>只影响预览 UI 的显示尺 / 色阶，<b>配置不驱动
 *       {@code GeoGenesisGenerator}</b>（生成器用编译期常量 {@code WORLD_MIN_Y/WORLD_MAX_Y/SEA_LEVEL}）。</td></tr>
 * </table>
 *
 * <p>⚠ <b>标注是注释，不是运行时机制</b>：不引入字段、不引入枚举、无任何运行时开销 ——
 * 目的只是让"这个旋钮到底改哪条链"可查，避免再次出现"UI 上有开关、某条链根本不读它"。</p>
 *
 * <p>⚠ 判据来自 2026-11-07 的本轮审计（逐个按键 grep 全部读取点），不是推测。
 * 若将来接线变化，请同步更新对应字段的标记；{@code runConfigKeyGuardProbe} 只守"有没有人读"，
 * <b>不守</b>"读它的那条链是否在跑"。</p>
 */
public final class GeoGenesisConfig {

    public static final ForgeConfigSpec SPEC;
    public static final GeoGenesisConfig INSTANCE;

    // ===== 大陆场 =====
    /** [BOTH] 大陆噪声尺度。morph 侧经 ContinentField，legacy 侧同源 ContinentField。 */
    public final ForgeConfigSpec.DoubleValue continentScale;
    /** [BOTH] 大陆场域扭曲。同上（默认 0 = 关闭）。 */
    public final ForgeConfigSpec.DoubleValue continentWarp;
    // ===== 大陆 FBM（值叠加，对标参考项目 procedural-island-generator 的 fbm 海岸线） =====
    /** [BOTH] 大陆 FBM 倍频数。 */
    public final ForgeConfigSpec.IntValue continentFbmOctaves;
    /** [BOTH] 大陆 FBM lacunarity。 */
    public final ForgeConfigSpec.DoubleValue continentFbmLacunarity;
    /** [BOTH] 大陆 FBM persistence。 */
    public final ForgeConfigSpec.DoubleValue continentFbmPersistence;
    /** [LEGACY_ONLY] 海陆参考阈值。只被 {@code ContinentField.isLand(c)} 读，
     *  而 {@code isLand} 只有 legacy 路径调用；morph 侧的海岸由 {@code MorphOceanAssembler} 三段剖面包办。 */
    /** [LEGACY_ONLY] 海陆占比偏置。只在 legacy {@code sampleCore}/{@code landFactorAt} 里从 c 里减掉；
     *  morph 侧 {@code MorphOceanAssembler} 用自己的固定控制点，不读本键。 */

    // ===== 海洋样条控制点 =====
    /** [BOTH] 深海控制点位置。{@code HeightCurve.eFromC} 两条链共用（{@code oceanLocations()}）。 */
    public final ForgeConfigSpec.DoubleValue deepOceanLoc;
    /** [BOTH] 大陆架控制点位置。同上。 */
    public final ForgeConfigSpec.DoubleValue shelfLoc;
    /** [BOTH] 浅海控制点位置。同上。 */
    public final ForgeConfigSpec.DoubleValue shallowLoc;
    /** [LEGACY_ONLY] 海岸控制点位置作为样条锚点被两条链共用（{@code HeightCurve}），
     *  但 {@code CoastlineField} 的三角窗中心也读它 —— 后者只在 legacy 链上调用。 */
    /** [BOTH] 深海深度。{@code HeightCurve.oceanValues()} 两条链共用。 */
    public final ForgeConfigSpec.DoubleValue deepOceanDepth;
    /** [BOTH] 大陆架深度。同上。 */
    public final ForgeConfigSpec.DoubleValue shelfDepth;
    /** [BOTH] 浅海深度。同上。 */
    public final ForgeConfigSpec.DoubleValue shallowDepth;
    /** [BOTH] 深海控制点导数。{@code SplineUtil.splint} 真读（{@code oceanDerivatives()}）。 */
    public final ForgeConfigSpec.DoubleValue deepOceanDeriv;
    /** [BOTH] 大陆架控制点导数。同上。 */
    public final ForgeConfigSpec.DoubleValue shelfDeriv;
    /** [BOTH] 浅海控制点导数。同上。 */
    public final ForgeConfigSpec.DoubleValue shallowDeriv;
    /** [BOTH] 海岸控制点导数。同上。 */
    public final ForgeConfigSpec.DoubleValue coastDeriv;

    // ===== 海岸线过渡区（两阶段 blend，参考 TerraForged） =====
    /** [LEGACY_ONLY] 海洋淡出起点（cBiased 空间）。二阶段 blend 只在 legacy {@code sampleCore} 里；
     *  morph 侧由 {@code MorphOceanAssembler} 的三段固定剖面取代。 */

    // ===== 海洋类型独立 lo/hi（与陆地类型一致的独立控制） =====

    // ===== 河流（水文模型：汇流场派生河网 + 距离场雕刻，2026-08-29） =====
    /** [BOTH] 河流总开关（旧 RTF 几何河网已下线，水文模型是唯一的河流实现）。
     *  两条链都经 {@code GeoGenesisTerrain}/{@code GeoGenesisGenerator} 读它。 */
    public final ForgeConfigSpec.BooleanValue riverEnabled;

    // ===== 海床 =====
    /** [LEGACY_ONLY] 海床细节振幅。只被 {@code SeaBedDetail} 读，而 {@code SeaBedDetail}
     *  只挂在 legacy {@code sampleCore}/{@code terrainEQuickCompute} 上；
     *  morph 侧深海形态由 {@code MorphOceanAssembler.buildDeepOcean} 的固定 Perlin 决定。 */
    /** [LEGACY_ONLY] 海洋深度乘数。只在 legacy {@code sampleCore}/{@code terrainEQuickCompute}/
     *  {@code TypeLandShape} 里乘到 eOcean 上；morph 侧不读。 */

    // ===== [2026-11-07 已删除] 省权重 / 旧省系统过程形态 / 起伏振幅 / 省场形态曲线 =====
    // 省系统（provinceScale / craton|belt|plateau|basinWeight）与旧省形态
    // （plainBase/plainRough/hillsLow/hillsHigh/beltRidgePower/beltFoothill/beltPeak/
    //  plateauBase/plateauTop/plateauSteps/plateauStepStrength）、
    // 起伏振幅（cratonReliefAmp/plateauReliefAmp/basinReliefAmp）、
    // 省场形态曲线（beltSharpness/beltWarpAmp/provMixSharpness/mountainMaskScale/
    //  microDetailScale/microDetailAmp）均已被类型系统 / morph-base-p1 取代，
    // 生产路径零读取（`runConfigKeyGuardProbe` 实测），已按用户裁定删除。
    // 说明：`FourPairMorphCandidate`/`HillsPlateauMorphCandidate` 里的 `plateauBase`
    //      是诊断侧的同名局部字段（Noise.Node），与配置键无关。

    // ===== 地形起伏振幅（每省固有） =====
    /** [LEGACY_ONLY] 造山带起伏振幅（e 单位）。只被 TypeLandShape/TypeNoiseProvider 的 legacy 地形读取；
     *  morph-base-p1 走 MorphLandField，不读本键。 */

    // ===== 盆地基底（legacy 地形合成） =====

    // ===== 分类阈值 + 雪线 =====
    /** [BOTH] 雪线海拔（e 单位）。{@code CellGenerator} 与 {@code BaseWorldSampler} 都读。 */
    public final ForgeConfigSpec.DoubleValue snowLine;
    /** [BOTH] 雪线温度耦合强度。同上。 */
    public final ForgeConfigSpec.DoubleValue snowLatitudeInfluence;
    /** [BOTH] 雪线湿度耦合强度（干燥→雪线升高，湿润→雪线降低），默认 0.15。同上。 */
    public final ForgeConfigSpec.DoubleValue snowHumidityInfluence;

    // ===== 气候阈值（温度/湿度/大陆性边界 + 影响权重） =====
    // ⚠ 这一组都经 ClimateSpline → Climate.isFrozen()/isCold()/isHot()/isDry()/isWet()
    //    → BiomeClassifier.pickKey() 生效 —— 即它们改的是【群系】而不是地形高度，两条链共用。
    /** [BOTH] 温度阈值：极寒/寒冷分界，默认 -0.6（影响群系选择，不改地形高度）。 */
    public final ForgeConfigSpec.DoubleValue tempFrozenThreshold;
    /** [BOTH] 温度阈值：寒冷/温和分界，默认 -0.2。 */
    public final ForgeConfigSpec.DoubleValue tempColdThreshold;
    /** [BOTH] 温度阈值：温和/温暖分界，默认 0.2。 */
    public final ForgeConfigSpec.DoubleValue tempWarmThreshold;
    /** [BOTH] 温度阈值：温暖/炎热分界，默认 0.5。 */
    public final ForgeConfigSpec.DoubleValue tempHotThreshold;

    /** [BOTH] 湿度阈值：干旱/半干旱分界，默认 -0.3。 */
    public final ForgeConfigSpec.DoubleValue humidityDryThreshold;
    /** [BOTH] 湿度阈值：半干旱/湿润分界，默认 0.0。 */
    public final ForgeConfigSpec.DoubleValue humiditySemiThreshold;
    /** [BOTH] 湿度阈值：湿润/潮湿分界，默认 0.3。 */
    public final ForgeConfigSpec.DoubleValue humidityWetThreshold;

    /** [BOTH] 大陆性阈值：深海/近海分界，默认 -0.60。 */
    public final ForgeConfigSpec.DoubleValue continentDeepOceanThreshold;
    /** [BOTH] 大陆性阈值：近海/沿海分界，默认 -0.30。 */
    public final ForgeConfigSpec.DoubleValue continentNearOceanThreshold;
    /** [BOTH] 大陆性阈值：沿海/过渡分界，默认 -0.10。 */
    public final ForgeConfigSpec.DoubleValue continentCoastThreshold;
    /** [BOTH] 大陆性阈值：近岸/近内陆分界，默认 0.10。 */
    public final ForgeConfigSpec.DoubleValue continentTransitionalThreshold;
    /** [BOTH] 大陆性阈值：近内陆/内陆分界，默认 0.35。 */
    public final ForgeConfigSpec.DoubleValue continentNearInlandThreshold;
    /** [BOTH] 大陆性阈值：内陆/深内陆分界，默认 0.65。 */
    public final ForgeConfigSpec.DoubleValue continentInlandThreshold;

    /** [BOTH] 温度对群系分布的影响程度（0-1），默认 1.0。
     *  legacy 侧读 {@code CellGenerator}；morph 侧经 {@code BaseWorldConfigAdapter → BaseWorldSettings}。 */
    public final ForgeConfigSpec.DoubleValue tempInfluence;
    /** [BOTH] 湿度对群系分布的影响程度（0-1），默认 1.0。同上。 */
    public final ForgeConfigSpec.DoubleValue humidityInfluence;
    /** [BOTH] 大陆性对群系分布的影响程度（0-1），默认 1.0。同上。 */
    public final ForgeConfigSpec.DoubleValue continentInfluence;

    // ===== 气候/纬度 xz 缩放 =====
    /** [BOTH] 纬度缩放（温度随 z 变化的周期，块）。{@code CellGenerator} 与 {@code BaseWorldSampler} 都读。 */
    public final ForgeConfigSpec.DoubleValue latitudeScale;
    /** [BOTH] 温度噪声 xz 缩放（块），温度扰动场频率 = 1/tempWarpScale。同上。 */
    public final ForgeConfigSpec.DoubleValue tempWarpScale;
    /** [BOTH] 湿度噪声 xz 缩放（块），湿度场频率 = 1/humidityScale。同上。 */
    public final ForgeConfigSpec.DoubleValue humidityScale;
    /** [BOTH] 气候区尺寸（wu）：区内温湿恒定的抖动 Voronoi 网格边长。默认 256。同上。 */
    public final ForgeConfigSpec.DoubleValue climateRegionSize;

    // ===== 世界高度 =====
    // ⚠ 这三个键【不驱动 generator】：GeoGenesisGenerator 用编译期常量
    //   WORLD_MIN_Y=-64 / WORLD_MAX_Y=320 / SEA_LEVEL=63（见 getMinY()/getGenDepth()/getSeaLevel()）。
    //   它们只经 TerrainParams 进入预览 UI（色阶/高度条）与 HeightCurve 的世界高度参数，
    //   而生产 buildTerrain 传的是那三个常量。改它们只改【预览显示】。
    /** [PREVIEW_ONLY] 海平面 Y。生产 generator 用常量 {@code SEA_LEVEL=63}；本键只影响预览显示与
     *  {@code HeightCurve} 的 e→Y 映射（生产传入的是常量）。 */
    public final ForgeConfigSpec.IntValue seaLevel;
    /** [PREVIEW_ONLY] 世界最小 Y。生产 generator 用常量 {@code WORLD_MIN_Y=-64}。 */
    public final ForgeConfigSpec.IntValue minY;
    /** [PREVIEW_ONLY] 世界最大 Y。生产 generator 用常量 {@code WORLD_MAX_Y=320}。 */
    public final ForgeConfigSpec.IntValue maxY;
    /** [BOTH] 实际峰顶高度占 maxY 的比例（余量避免触顶世界构建上限）。默认 0.92。
     *  {@code HeightCurve.heightFromE} 真读（两条链的高度映射共用）。 */
    public final ForgeConfigSpec.DoubleValue peakHeightFraction;

    // ===== 垂直缩放 =====
    /** [BOTH] 垂直缩放比例（1-8）。{@code HeightCurve.heightFromE} 与 morph 的地形配方都读。 */
    public final ForgeConfigSpec.DoubleValue verticalScale;
    /** [BOTH] 水平缩放比例（0.5-8）：块→wu 映射，全链路统一（侵蚀/水文/河网/源头网格）。
     *  两条链都读（morph 侧 {@code MorphTerrainSampler}/{@code ProcessContext}）。 */
    public final ForgeConfigSpec.DoubleValue horizontalScale;

    // ===== 大陆性语义亲和度 =====
    /** [LEGACY_ONLY] 大陆性语义亲和度强度（β），默认 0（禁用）。
     *  只被 legacy {@code TypeLandShape} 读；morph 侧类型分布由 {@code MorphLandField} 的 Voronoi 决定。 */

    // ===== 海岸线多样化（CoastlineField 输入） =====
    /** [LEGACY_ONLY] 海岸线 warp 振幅（c 空间单位）。{@code CoastlineField} 只在 legacy
     *  {@code sampleCore}/{@code terrainEQuickCompute} 里被调用；morph 侧岸线由
     *  {@code MorphOceanAssembler} 的固定三段剖面给出。 */

    // ===== [2026-11-07 已删除] 海岸线多样化：coastTerrainInfluence + archipelagoBand/
    //        Density/Scale/Height =====
    // 这 5 个键在 CoastlineField 之后已无任何生产读取（只有 client 预览面板在展示），
    // 属「UI 上有滑块、地形不读」的假开关，已按用户裁定删除。

    // ===== 侵蚀（SH 统一水力引擎：侵蚀与河网同源同趟） =====
    /** [BOTH] 是否启用本地粒子侵蚀。经 {@code ProcessContext.modeFor} 映射到
     *  OFF/PARTICLE，两条链的落块增量共用同一 ProcessRuntime。 */
    public final ForgeConfigSpec.BooleanValue erosionEnabled;
    /**
     * [BOTH] 侵蚀向河道软让步（方案 A，2026-09-09）：默认 true。河床按到河心距离衰减侵蚀 delta
     * （河心→0、谷外→1），使河床与"无侵蚀计划水面"重新同源 → 根治侵蚀导致的河道非单调
     * 阶梯（图1）与填水脱离地形的干滩（图2）。设 false 回退到旧"河床全量跟随侵蚀"行为。
     * 读取点：{@code GeoGenesisTerrain}。
     */
    public final ForgeConfigSpec.BooleanValue erosionYieldToRiver;
    /** [BOTH] 细纹理微侵蚀层（XS r1wu 十字笔刷，2026-08-12；默认关——实测加剧 chunk 边界脊）。 */
    public final ForgeConfigSpec.BooleanValue erosionXSEnabled;
    /** [BOTH] 侵蚀强度倍率。 */
    public final ForgeConfigSpec.DoubleValue erosionStrength;
    /** [BOTH] 侵蚀水滴数量倍率。 */
    public final ForgeConfigSpec.DoubleValue erosionDropsMul;
    /** [BOTH] 侵蚀速率倍率。 */
    public final ForgeConfigSpec.DoubleValue erosionErodeMul;
    /** [BOTH] 局部 cascade 级联强度。侵蚀后按高度差向最低邻居 settling，平滑河床底部。0=关闭，默认 0.3，范围 [0, 1]。 */
    public final ForgeConfigSpec.DoubleValue erosionCascadeStrength;

    // `erosionRoutingAdaptive` 键已删除（2026-11-07）：只被 src/diagnostics 的
    // HydrologyExperimentEngine / RiverLineNetwork 读取，生产链不读 ⇒ 假开关。
    // `erosionLocalChargeWeight` 键已删除（2026-11-07）：ErosionEngine 注释自 2026-08-01
    // 起即写明"引擎不再读取"，全仓仅注释提及 ⇒ 死键。

    // ===================== ★ 2026-09-15 洞穴（游戏性优先，可开关）=====================
    // 洞穴全部挂在 GeoGenesisGenerator.applyCarvers → CaveCarver 上，地形链无关 ⇒ 全部 [BOTH]。
    /** [BOTH] 洞穴档位（REALISTIC 拟真 / VANILLA_LIKE 接近原版 / MINIMAL 精简 / OFF 关闭 / CUSTOM）。 */
    public final ForgeConfigSpec.EnumValue<CaveConfig.Preset> cavePreset;
    /** [BOTH] 洞穴总开关（false ⇒ 地下无洞穴；与档位正交）。 */
    public final ForgeConfigSpec.BooleanValue caveEnabled;
    /** [BOTH] 隧道分量（蜿蜒细管，负责"连通"）。 */
    public final ForgeConfigSpec.BooleanValue caveTunnelEnabled;
    /** [BOTH] 空腔分量（可站立的洞厅，负责"能走"）。 */
    public final ForgeConfigSpec.BooleanValue caveChamberEnabled;
    /** [BOTH] 层调制（把大空腔切成层状，防竖直贯穿）。 */
    public final ForgeConfigSpec.BooleanValue caveLayerEnabled;
    /** [BOTH] 洞穴密度倍率（乘在分量阈值上，越大洞越粗越密）。 */
    public final ForgeConfigSpec.DoubleValue caveDensityMul;
    /** [BOTH] 洞顶保护厚度（0 ⇒ 允许破地表形成入口）。 */
    public final ForgeConfigSpec.IntValue caveSurfaceLid;
    /** [BOTH] 洞穴带最浅深度。 */
    public final ForgeConfigSpec.IntValue caveDepthMin;
    /** [BOTH] 洞穴带最深深度。 */
    public final ForgeConfigSpec.IntValue caveDepthMax;
    /** [BOTH] 岩性门控（石灰岩溶洞大 / 花岗岩几乎无洞）—— 本项目独创。 */
    public final ForgeConfigSpec.BooleanValue caveLithoGating;
    /** [BOTH] 地下洞穴群系（滴水石洞 / 繁茂洞穴）。 */
    public final ForgeConfigSpec.BooleanValue caveBiomesEnabled;

    // ===================== ★ 2026-09-16 矿脉（游戏性优先，可开关）=====================
    // 矿脉挂在 GeoGenesisGenerator.fillFromNoise + VanillaDecorationFilter 上，地形链无关 ⇒ [BOTH]。
    /** [BOTH] 自研地质矿脉开关（true = 叠加在原版矿之上；false = 只留原版矿）。 */
    public final ForgeConfigSpec.BooleanValue oreVeinsEnabled;
    /**
     * [BOTH] ★ 2026-09-18：<b>矿物系统接管开关</b>（预研「路径 D」，见
     * {@code docs/plans/矿物系统-预研-2026-09-18.md}）。
     *
     * <ul>
     *   <li>{@code false}（默认）= <b>VANILLA</b>：完全不干预原版矿 ⇒ <b>零行为变更</b>；</li>
     *   <li>{@code true} = <b>OVERRIDE</b>：从原版装饰管线<b>剔除 {@code minecraft} 命名空间的
     *       金属矿特征</b>（<b>19 项</b>，见 {@code VanillaDecorationFilter.METAL_ORES}），
     *       地下矿改为完全由自研 {@link com.geogenesis.worldgen.ore.OreVeins}
     *       按<b>宿主岩 + 深度带</b>生成。</li>
     * </ul>
     *
     * <p>⚠ <b>多模组兼容</b>：只剔除 {@code minecraft} 命名空间 ⇒ <b>其它任何模组的矿一律不动</b>。
     * 本开关也<b>不</b>影响原版水成细节（{@code disk_*} / {@code underwater_magma}）与岩块团块策略。</p>
     */
    public final ForgeConfigSpec.BooleanValue oreOverrideVanilla;

    // ===================== ★ 2026-09-18 水量平衡（水文 M2）=====================
    // `hydrologyDecayEnabled` / `hydrologyDecayMax` / `hydrologyDecayRef` /
    // `hydrologyDecayExponent` 四个键已删除（2026-11-07）：全仓只有 src/diagnostics 的
    // HydrologyExperimentEngine 读它们（气候衰减实验），生产 model 链不读 ⇒ 假开关。
    // 生产侧的沿程衰减实现仍在（FlowField.DecayClimate），只是不再由配置驱动。
    //
    // `hydrologyModelPipelineEnabled` 键已删除（2026-11-07）：2026-10-20 legacy 线退役后
    // 该键退化为 no-op 常量 true，生产链不读；唯一读者是 src/acceptance 的配置探测
    // （已改为直接探测其他键）。模型链现在是唯一水文实现，没有开关可关。
    //
    // `hydrologySkeletonRouting` 键已删除（2026-11-07）：只被 src/diagnostics 的
    // HydrologyExperimentEngine 读取；生产 model 链有自己的骨架路线，不读此键。

    // `hydrologyDeterministicSkeleton` 字段已删除（2026-10-20，用户决定移除 legacy 线）：
    // 骨架是唯一生产河网线，无开关可关。定义处注释见 builder 段。

    // ===================== ★ 2026-09-18 全流程诊断 =====================
    /**
     * [DIAGNOSTIC] ★ 2026-09-18：<b>世界生成全流程诊断</b>（把每个阶段的耗时打进 {@code latest.log}）。
     *
     * <p>为什么需要：既有 {@code [PERF-TERRAIN]} / {@code [PERF] fillFromNoise}
     * <b>只在超过阈值（50/100ms）时才打印</b> ⇒ 快的块完全没记录，<b>无法统计分布</b>；
     * 且 {@code applyBiomeDecoration}（树/草/矿）、{@code applyCarvers}（洞穴）
     * 此前<b>零插桩</b> —— 而它们在实机是最重的两段。</p>
     *
     * <p>开启后每 {@link #worldgenProfilerEveryChunks} 块打一次<b>滚动汇总</b>
     * （次数/总耗时/均值/P50/P95/max/占比 + 最慢块 Top8），世界卸载时打总计。
     * 输出前缀 {@code [WGP]}。</p>
     *
     * <p>⚠ <b>默认关闭</b>：关闭时 {@code begin()} 返回 0、各记账直接 return
     * ⇒ <b>零开销、产出逐位不变</b>（本诊断不消费任何随机数）。</p>
     */
    public final ForgeConfigSpec.BooleanValue worldgenProfilerEnabled;
    /** [DIAGNOSTIC] 每多少块打一次滚动汇总（0 = 只在世界卸载时打总计）。默认 200。范围 [0, 100000]。 */
    public final ForgeConfigSpec.IntValue worldgenProfilerEveryChunks;

    /** [BOTH] SH 动量场正反馈：粒子顺下游动量场自我加速（河流自我增强）。1.0 对齐 SH 原版，0=关闭。范围 [0, 2]。 */
    public final ForgeConfigSpec.DoubleValue erosionMomentumTransfer;
    /** [BOTH] SH 多轮迭代轮数：每轮重撒全部液滴 + lrate 场平滑，河道随轮次渐进加深成型。默认 2（2026-08-09 优化：3→2，drops 降 33%，观感微变可回退 3），范围 [1, 16]。 */
    public final ForgeConfigSpec.IntValue erosionIterations;
    /** [BOTH] SH 场平滑率 lrate：每轮 track 平滑进稳态场的比例（0.1 对齐 SH 原版）。范围 [0.01, 0.5]。 */
    public final ForgeConfigSpec.DoubleValue erosionLrate;

    // ===== Phase 1: Unified Spline Config (67 fields) =====
    // ⚠ 整段样条都是 [BOTH]：{@code TerrainParams.buildUnifiedSpline()} 两链共用，
    //   外层 location 经 {@code SplineConfig.outerLocations()} 进入 {@code UnifiedSpline}。
    // 内层样条导数仍生效 —— {@code SplineUtil.splint} 真的把 derivatives 参与 Hermite 插值。
    // --- Outer spline: continentality c control points (7 × 1 = 7 fields) ---
    // `splineOuterDeriv0..6` 已删除（2026-11-07）：UnifiedSpline.OuterNode.derivative
    // 全仓无任何读取点（`sample`/`sampleByType`/`typeAffinity` 只用 location 与 midSpline）
    // ⇒ 这 7 个键是死键。SplineConfig 的 outerDeriv0..6 与 SplineUtil.splint 解耦，
    // 内层样条导数仍生效（splineUtil 只在 InnerSpline 路径上调用）。

    // ===== 内层样条：为什么 Loc*/Deriv* 与 Val* 的轴不同（本轮实测，不是推理） =====
    //
    // 生产对内层样条只有两处求值：
    //   ① legacy 链：TypeLandShape → generators.sampleByType(c, i, 0.0 / 1.0)
    //      （全仓仅此一处调用，noiseValue 只取端点 0.0 与 1.0）
    //   ② morph 链：SplineConfig.maxLandHi() → 只读 5 个 *HiVal0
    //      → CellGenerator.softCapLandE + ProcessContext.assemble（侵蚀 softCap）
    //
    // 两个实测结论（探针见 build/configkey-cleanup-20261107/）：
    //   · Loc*/Deriv* 在①里零贡献：2 点样条在端点处 Hermite 基函数
    //     h10(0)=0、h11(1)=0、h01(0)=0 ⇒ 只有 Val0/Val1 留下。
    //     LocDerivContributionProbe：410 个 (type × c × {0,1}) 采样点，
    //     Loc/Deriv 全置极值后【零个】位模式改变（Val 对照 41 个改变）。
    //   · *HiVal0 在②里【有】贡献，但只在 e 越过 0.97*maxLandHi 时才触发：
    //     morph 实测最高 e = 0.8756（Wu 2311,1194），默认阈值 0.9215 ⇒ 默认配置下不触发；
    //     但把 mountHiVal0 压到 0.20 后阈值降到 0.6848，5×5 chunk 全部改变
    //     （SoftCapEngageProbe：25/25）。⇒ 它是真实的 morph 输入，不能标 LEGACY_ONLY。
    //
    // ⚠ 因此：审计结论「Val0/Val1 都只属 legacy」对 Val1/LoVal* 成立，
    //   对 5 个 *HiVal0 【不成立】—— 它们经 maxLandHi 影响 morph 的 softCap。
    //   本轮以实测为准，*HiVal0 标 [BOTH]，其余 Val* 与全部 Loc*/Deriv* 标 [LEGACY_ONLY]。

    // --- Inner spline: PLAIN type (12 fields) ---
    /** [BOTH] hi 下界端点值。除 legacy 采样外，还经 SplineConfig.maxLandHi() 参与 morph 的
     *  侵蚀 softCap 阈值（实测：默认 0.25 不是 maxLandHi 的决定项，但机制上被读取）。 */

    // --- Inner spline: HILLS type (12 fields) ---
    /** [BOTH] hi 下界端点值。除 legacy 采样外，还经 maxLandHi() 参与 morph 侵蚀 softCap
     *  （默认 0.368 与 mountHiVal0 同非决定项，但机制上被读取）。 */

    // --- Inner spline: MOUNTAINS type (12 fields) ---
    /** [BOTH] hi 下界端点值。它是 {@code SplineConfig.maxLandHi()} 的取值来源，因而同时决定两条链的软顶阈值：
     *  <ul>
     *    <li>legacy：{@code CellGenerator.softCapLandE}，阈值 0.97×maxLandHi</li>
     *    <li>morph：{@code ProcessContext.assemble} 的侵蚀软顶（同一 0.97 系数）</li>
     *  </ul>
     *  ⚠ <b>默认配置下不触发</b>：morph 实测自然最高 e = 0.8756，而默认阈值 0.9215。
     *  它只在山峰被调高、或本值被调低使阈值跌破自然峰值时才生效——
     *  压到 0.20 时阈值降到 0.6848，实测 morph 落块 5×5 chunk 25/25 全变（SoftCapEngageProbe）。
     *  因此它是「有条件的 BOTH」，不是常态生效旋钮。 */

    // --- Inner spline: PLATEAU type (12 fields) ---
    /** [BOTH] hi 下界端点值。除 legacy 采样外，还经 maxLandHi() 参与 morph 侵蚀 softCap
     *  （默认 0.706 与 mountHiVal0 同非决定项，但机制上被读取）。 */

    // --- Inner spline: BASIN type (12 fields) ---
    /** [BOTH] hi 下界端点值。除 legacy 采样外，还经 maxLandHi() 参与 morph 侵蚀 softCap
     *  （默认 0.05 与 mountHiVal0 同非决定项，但机制上被读取）。 */

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
        // continentProvinceWarp 已删除（2026-11-07）：省系统下线后无消费者，默认值本就是 0。
        builder.pop();

        builder.push("Ocean Spline Control Points");
        deepOceanLoc = builder.defineInRange("deepOceanLoc", -0.80, -1.0, 1.0);
        shelfLoc = builder.defineInRange("shelfLoc", -0.50, -1.0, 1.0);
        shallowLoc = builder.defineInRange("shallowLoc", -0.16, -1.0, 1.0);
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
        builder.pop();

        // 海洋类型独立 lo/hi（与陆地类型一致的独立控制）
        builder.push("Ocean Type Ranges");
        builder.pop();

        // ===== River Network（RTF 范式：几何河网 + Zone1-4 河谷雕刻，2026-08-26） =====
        // ★ 2026-08-29：旧 RTF 几何河网的 Zone 参数（根河数/河床宽·深/岸阶宽·高/谷幅/淡入比例）
        //   随模型下线一并移除——它们只对已删除的 RiverNetwork 生效，保留即"转了没反应"的死旋钮。
        //   河网形态改由水文模型内部参数（RiverLineParams）决定。
        builder.push("River Network");
        riverEnabled = builder.comment("Master switch for river generation (hydrology model: flow-accumulation derived network + distance-field carving).")
            .define("riverEnabled", true);
        builder.pop();

        builder.push("Seabed");
        builder.pop();

        // `Province Weights` 段（provinceScale / cratonWeight / beltWeight / plateauWeight /
        // basinWeight）已删除（2026-11-07）：类型系统取代省系统后无人读取。
        // `Land Process Parameters` 段的 plainBase/plainRough/hillsLow/hillsHigh/
        // beltRidgePower/beltFoothill/beltPeak/plateauBase/plateauTop/plateauSteps/
        // plateauStepStrength 同批删除；该段仅剩 basinBase（legacy TypeNoiseProvider 读）。
        builder.push("Land Process Parameters");
        builder.pop();

        // `Terrain Shape` 段（beltSharpness / beltWarpAmp / provMixSharpness /
        // mountainMaskScale / microDetailScale / microDetailAmp）已删除（2026-11-07），
        // 整段因此消失，不留空 builder.push。

        builder.push("Relief Amplitudes");
        // cratonReliefAmp / plateauReliefAmp / basinReliefAmp 已删除（2026-11-07）；
        // 仅 beltReliefAmp 仍被 legacy TypeLandShape/TypeNoiseProvider 读取。
        builder.pop();

        builder.push("Classification & Snow");
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
        climateRegionSize = builder.comment("Climate region size (wu). Jittered Voronoi cell size within which temperature/moisture are constant. Larger = bigger climate patches. Default 256.")
            .defineInRange("climateRegionSize", 256.0, 64.0, 2048.0);
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
        builder.pop();

        builder.push("Coastline Diversification");
        // coastTerrainInfluence / archipelagoBand / archipelagoDensity / archipelagoScale /
        // archipelagoHeight 已删除（2026-11-07）：CoastlineField 不读它们，只有预览面板展示。
        builder.pop();

        builder.push("Erosion");
        erosionEnabled = builder.comment("Enable local particle erosion (seamless, margin-filled with true neighbour heights). Default true.")
            .define("erosionEnabled", true);
        erosionYieldToRiver = builder.comment("Erosion yields to river channel (FreeTerraForged-style erosionMask): attenuate the erosion delta applied to the riverbed by distance to channel center (0 at center, 1 outside valley) so the bed stays coherent with the planned monotonic water surface. Fixes erosion-induced non-monotonic terraced waterfalls and dry sand bars poking through the water. Default true; set false to restore old full-follow behavior.")
            .define("erosionYieldToRiver", true);
        erosionXSEnabled = builder.comment("Extra-fine (XS, r1wu cross-brush) micro-erosion detail layer. Adds 1-2 block textures on slopes, but amplifies cross-chunk birth-set discreteness into visible ridges near chunk borders (measured +1.6 blocks at tile borders). Default false (conservative).")
            .define("erosionXSEnabled", false);
        erosionStrength = builder.comment("Erosion strength multiplier. Default 1.0, range [0, 4].")
            .defineInRange("erosionStrength", 1.0, 0.0, 4.0);
        erosionDropsMul = builder.comment("Erosion droplet count multiplier. Lower = faster (preview). Default 1.0, range [0.1, 4].")
            .defineInRange("erosionDropsMul", 1.0, 0.1, 4.0);
        erosionErodeMul = builder.comment("Erosion erode-rate multiplier. Default 1.0, range [0.1, 4].")
                .defineInRange("erosionErodeMul", 1.0, 0.1, 4.0);
        // erosionLocalChargeWeight 已删除（2026-11-07）：ErosionEngine 自 2026-08-01 起即不读它。
        erosionCascadeStrength = builder.comment("Local cascade settling strength. After erosion, transfer material from higher cells toward lowest 8-neighbor, smoothing riverbeds. 0=off. Default 0.5 (2026-08-10: 0.3->0.5, sharper gully edges), range [0, 1].")
                .defineInRange("erosionCascadeStrength", 0.5, 0.0, 1.0);
        erosionMomentumTransfer = builder.comment("SH momentum-field positive feedback: droplets self-accelerate along downstream momentum field (rivers self-reinforce). 1.0 = SH original, 0 = off. Default 1.0, range [0, 2].")
                .defineInRange("erosionMomentumTransfer", 1.0, 0.0, 2.0);
        erosionIterations = builder.comment("SH multi-pass iteration count: each pass respawns all droplets + lrate field smoothing; channels deepen progressively. Default 2 (2026-08-11: 5->2, deep-gully fix; SH Erosion.cs numIterations=1, 2 keeps momentum field accumulation), range [1, 16].")
                .defineInRange("erosionIterations", 2, 1, 16);
        // NOTE(2026-08-10): toml 旧值 2 会覆盖代码默认——修改默认后必须同步 run/config/geogenesis-common.toml
        erosionLrate = builder.comment("SH field smoothing rate lrate: proportion of each pass's track blended into the steady-state field (0.1 = SH original). Default 0.1, range [0.01, 0.5].")
                .defineInRange("erosionLrate", 0.1, 0.01, 0.5);
        builder.pop();

        // ===================== ★ 2026-09-15 洞穴（游戏性优先，可开关）=====================
        //   设计依据：用户要求"不需要完全按地质学设计，MC 是游戏，要考虑游戏性；
        //   洞穴可以开关配置，想模拟现实洞穴就打开，不需要就关闭回默认原版"。
        //
        //   ⚠ 关于"关闭回原版"的技术现实（必须写明，避免误解）：
        //     本项目是【自定义 ChunkGenerator】、没有 NoiseSettings/NoiseChunk
        //     ⇒ 原版 carver 在物理上【无法调用】（这正是当初自研洞穴的原因）。
        //     故 cavePreset=OFF 的语义只能是"【地下无洞穴】"—— 与参考项目 RTG 的
        //     useCaves=false 完全一致。作为补偿，VANILLA_LIKE 档用现有 3D 噪声
        //     换参数去【近似】原版观感（洞更大更圆、无层理、不看岩性）。
        builder.push("Caves");
        cavePreset = builder.comment(
                "Cave preset (gameplay-first, one-click):"
                + " REALISTIC = current default, geological (limestone karst caves are big, granite almost none, horizontal stratified caves);"
                + " VANILLA_LIKE = closer to vanilla feel (bigger rounder chambers, no layering, rock type ignored);"
                + " MINIMAL = sparse thin tunnels only;"
                + " OFF = NO caves at all (vanilla carvers are UNAVAILABLE in this custom ChunkGenerator, so 'off' means an empty underground, same as RTG's useCaves=false);"
                + " CUSTOM = use the individual knobs below as-is.")
                .defineEnum("cavePreset", CaveConfig.Preset.REALISTIC);
        caveEnabled = builder.comment(
                "Master switch for cave carving. false = no caves at all (independent of cavePreset).")
                .define("caveEnabled", true);
        caveTunnelEnabled = builder.comment(
                "Enable TUNNEL component (winding 1D tubes formed by two noise isosurface intersections; provides 'connectivity').")
                .define("caveTunnelEnabled", true);
        caveChamberEnabled = builder.comment(
                "Enable CHAMBER component (walkable hall-like voids; provides 'room to walk'). Disabling leaves only thin tunnels -> players cannot stand up (a failure mode measured before).")
                .define("caveChamberEnabled", true);
        caveLayerEnabled = builder.comment(
                "Enable LAYER modulation (squashes tall chambers into horizontal strata). Prevents vertically-through-going shafts. Disable for vanilla-cheese-like blob caves (accepts the risk of vertical shafts).")
                .define("caveLayerEnabled", true);
        caveDensityMul = builder.comment(
                "Cave density multiplier applied to component thresholds (bigger = thicker & more caves). Default 1.0, range [0.2, 3.0].")
                .defineInRange("caveDensityMul", 1.0, 0.2, 3.0);
        caveSurfaceLid = builder.comment(
                "Roof protection thickness in blocks: cave ceilings stay at least this far below the surface. 0 = caves MAY breach the surface (creates cave entrances, good for exploration). Default 6, range [0, 40].")
                .defineInRange("caveSurfaceLid", 6, 0, 40);
        caveDepthMin = builder.comment(
                "Shallowest cave depth below surface (blocks). Default 8, range [0, 80].")
                .defineInRange("caveDepthMin", 8, 0, 80);
        caveDepthMax = builder.comment(
                "Deepest cave depth below surface (blocks). Default 120, range [40, 320].")
                .defineInRange("caveDepthMax", 120, 40, 320);
        caveLithoGating = builder.comment(
                "Rock-type gating (PROJECT-UNIQUE, absent in all 3 reference projects): limestone karst caves are 1.7x bigger, granite/gneiss almost none (0.55x). Disable for vanilla-like uniform caves.")
                .define("caveLithoGating", true);
        caveBiomesEnabled = builder.comment(
                "Underground cave biomes: DRIPSTONE_CAVES everywhere, LUSH_CAVES in forest climates. Decoration (stalactites/moss/vines) is placed by vanilla automatically. Disable to keep the surface biome underground.")
                .define("caveBiomesEnabled", true);
        builder.pop();

        // ===================== ★ 2026-09-16 矿脉（可开关，与洞穴同款"游戏性优先"）=====================
        //
        //   ⚠ 与洞穴 OFF 语义的【关键区别】（必须写明，避免误解）：
        //     洞穴没有原版替身（自定义生成器调不了原版 carver）⇒ OFF = 地下无洞穴；
        //     而【矿有原版替身】—— 原版 ore_coal/iron/... 由 applyBiomeDecoration 放置、
        //     用的是原版群系特征列表，【始终在生成】，与本开关无关。
        //     故本开关 false 的语义是"关闭【自研地质矿脉】⇒ 只剩原版矿（纯原版体验）"，
        //     而不是"地下无矿"。
        builder.push("Ores");
        oreVeinsEnabled = builder.comment(
                "Self-developed geological ore veins (PROJECT-UNIQUE: ore is placed by HOST ROCK + depth band"
                + " -- e.g. coal only in sandstone/shale, lapis only in limestone, diamond only in gneiss/schist)."
                + " true (default) = ON: these veins are placed ON TOP of the vanilla ore."
                + " false = OFF: keep ONLY vanilla ore (pure-vanilla ore experience)."
                + " NOTE: vanilla ore (ore_coal/ore_iron/... , placed from biome features) is ALWAYS generated"
                + " and is NOT controlled by this switch; it intentionally DOES pollute the geological strata"
                + " (that is vanilla behaviour and is left alone here)."
                + " Takes effect on world (re)load; already-generated chunks are unchanged.")
                .define("oreVeinsEnabled", true);
        oreOverrideVanilla = builder.comment(
                "★ Ore system takeover (see docs/plans/矿物系统-预研-2026-09-18.md, 'path D')."
                + " false (default) = VANILLA: do NOT touch vanilla ore at all (zero behaviour change)."
                + " true = OVERRIDE: REMOVE minecraft metal ore features (19 placed_feature ids: coal/iron/"
                + " gold/redstone/diamond/lapis/copper/emerald/infested variants) from the biome decoration pipeline, so the"
                + " underground ore is placed ONLY by the self-developed OreVeins (host-rock + depth-band"
                + " geology). Vanilla water details (disk_*/underwater_magma) and the strata-protection"
                + " filter are unaffected."
                + " MOD COMPATIBILITY: only the 'minecraft' namespace is removed, so ore from ANY other mod"
                + " is always left untouched."
                + " NOTE: enabling this REDUCES total ore (vanilla ore is currently already gated by our"
                + " rock layers via the stone_ore_replaceables tag) -- use it to A/B compare ore volume."
                + " Takes effect on world (re)load; already-generated chunks are unchanged.")
                .define("oreOverrideVanilla", false);
        builder.pop();

        // ===================== ★ 2026-11-07  Hydrology 段整体删除 =====================
        //
        //   原 `Hydrology` 段只有 5 个键：hydrologySkeletonRouting / hydrologyDecayEnabled /
        //   hydrologyDecayMax / hydrologyDecayRef / hydrologyDecayExponent，
        //   外加一个 no-op 的 hydrologyModelPipelineEnabled。它们要么只被
        //   src/diagnostics 的 HydrologyExperimentEngine 读取（实验路径），
        //   要么早在 2026-10-20 legacy 线退役后就成了常量。
        //   ⇒ 生产 model 链一个都不读，整段按用户裁定删除（debug 进程已不再需要配置）。
        //
        //   `hydrologyDeterministicSkeleton` 更早（2026-10-20）已随 legacy 线一并删除。
        //   旧 TOML 里的这些键会被 Forge 配置解析忽略（未注册键），无害。
        //
        //   ⚠ 保留的历史教训（配置项的归属段落就是它的"文档"）：不要为了"看起来相关"
        //     把新键塞进语义无关的段落；没有键的段落不要留空壳 push/pop。

        // ===================== ★ 2026-09-18 全流程诊断（独立段落）=====================
        //
        //   ⚠ 为什么必须独立成段（我犯过的错，如实记录）：
        //     初版把这两个键加在【Ores 段内】（代码上紧挨着 oreOverrideVanilla 的 define，
        //     但漏看了后面的 builder.pop() 位置）⇒ 配置路径变成
        //     ["GeoGenesis Terrain Generation".Ores].worldgenProfilerEnabled。
        //     用户按直觉在别处找 ⇒ 找不到 ⇒ 打开失败（日志：corrected from null to its default, false）
        //     ⇒ 诊断从未运行。这与"文档里抄数字会漂移"是同一类病：
        //     【配置项放在语义无关的段落里，人就会找不到】。
        builder.push("Diagnostics");
        worldgenProfilerEnabled = builder.comment(
                "World-generation full-pipeline profiler. Logs per-stage timing to latest.log (prefix [WGP])."
                + " WHY: the pre-existing [PERF-TERRAIN] / [PERF] fillFromNoise logs only fire ABOVE a"
                + " threshold (50ms / 100ms), so fast chunks are never recorded and no distribution can be"
                + " computed; and applyBiomeDecoration (trees/grass/ore) plus applyCarvers (caves) had NO"
                + " instrumentation at all -- yet they are the heaviest stages in a real game."
                + " When on, a rolling summary is printed every worldgenProfilerEveryChunks chunks"
                + " (count / total / mean / P50 / P95 / max / share, plus the 8 slowest chunks), and a grand"
                + " total on world unload. Output is also written to geogenesis-wgp.txt (UTF-8) in the"
                + " game directory. false (default) = OFF, zero overhead, bit-identical output"
                + " (the profiler consumes no random numbers).")
                .define("worldgenProfilerEnabled", false);
        worldgenProfilerEveryChunks = builder.comment(
                "Profiler rolling-summary interval, in chunks. 0 = only print the grand total on world unload."
                + " Default 200. Range [0, 100000]")
                .defineInRange("worldgenProfilerEveryChunks", 200, 0, 100000);
        builder.pop();

        builder.push("Phase 1 Unified Spline");
        // Outer spline control points (7 × 1 = 7 fields)
        // `splineOuterDeriv*` 已删除（2026-11-07）：UnifiedSpline.OuterNode.derivative 零读取。

        // PLAIN inner spline (12 fields)
        builder.push("PLAIN");
        builder.pop();

        // HILLS inner spline (12 fields)
        builder.push("HILLS");
        builder.pop();

        // MOUNTAINS inner spline (12 fields)
        builder.push("MOUNTAINS");
        builder.pop();

        // PLATEAU inner spline (12 fields)
        builder.push("PLATEAU");
        builder.pop();

        // BASIN inner spline (12 fields)
        builder.push("BASIN");
        builder.pop();

        builder.pop(); // Phase 1 Unified Spline

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
            h = h * 31 + (INSTANCE.erosionEnabled.get() ? 1 : 0);
            h = h * 31 + (INSTANCE.erosionYieldToRiver.get() ? 1 : 0);
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionStrength.get());
            h = h * 31 + Double.doubleToLongBits(INSTANCE.erosionDropsMul.get());
            // 2026-08-06：海岸线 warp 参数（侵蚀采样依赖地形含海岸线，改动须清侵蚀 tile 缓存）
            // hydrologyModelPipelineEnabled 已从指纹移除（2026-10-20）：它退化为 no-op 常量
            // true，不再区分任何产出；legacy 链移除本身由 PreviewDisplay CACHE_SCHEMA_VERSION 升版承载。
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
        double landHi = com.geogenesis.worldgen.terrain.TerrainParams.MAX_LAND_HI_E;
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.get(), continentWarp.get(), continentFbmOctaves.get(), continentFbmLacunarity.get(), continentFbmPersistence.get(), 0.0, 0.31,
            deepOceanLoc.get(), shelfLoc.get(), shallowLoc.get(), -0.04,
            deepOceanDepth.get(), shelfDepth.get(), shallowDepth.get(),
            deepOceanDeriv.get(), shelfDeriv.get(), shallowDeriv.get(), coastDeriv.get(),
            // coastline transition
            -0.13, 0.05,
            0.03, 1.0,
            0.02,
            0.35,
            snowLatitudeInfluence.get(), snowHumidityInfluence.get(),

            // preview compat defaults
            horizontalScale.get(), seaLevel.get(), snowLine.get(), minY.get(),
            maxY.get(), peakHeightFraction.get(), (int) Math.round(seaLevel.get() + (maxY.get() - seaLevel.get()) * peakHeightFraction.get() * landHi), (int)(minY.get() * 0.75),
            verticalScale.get(),
            0.0,
            // coastline diversification（archipelago*/coastTerrainInfluence 已随键删除）
            0.15, 1200.0, 6, 2.0, 0.6,
            latitudeScale.get(), tempWarpScale.get(), humidityScale.get(), climateRegionSize.get()
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
        double landHi = com.geogenesis.worldgen.terrain.TerrainParams.MAX_LAND_HI_E;
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.getDefault(), continentWarp.getDefault(), continentFbmOctaves.getDefault(), continentFbmLacunarity.getDefault(), continentFbmPersistence.getDefault(), 0.0, 0.31,
            deepOceanLoc.getDefault(), shelfLoc.getDefault(), shallowLoc.getDefault(), -0.04,
            deepOceanDepth.getDefault(), shelfDepth.getDefault(), shallowDepth.getDefault(),
            deepOceanDeriv.getDefault(), shelfDeriv.getDefault(), shallowDeriv.getDefault(), coastDeriv.getDefault(),
            // coastline transition
            -0.13, 0.05,
            0.03, 1.0,
            0.02,
            0.35,
            snowLatitudeInfluence.getDefault(), snowHumidityInfluence.getDefault(),

            // preview compat defaults
            horizontalScale.getDefault(), seaLevel.getDefault(), snowLine.getDefault(), minY.getDefault(),
            // ⚠ 峰高比例过去在这里写死字面量 0.92，与 peakHeightFraction 的 defineInRange 默认值
            //   重复 —— 一旦改默认值就会静默漂移（本轮审计的 5 处文案漂移是同型病）。
            //   改为读同一处声明；当前默认恰好也是 0.92 ⇒ 产出逐位不变。
            maxY.getDefault(), peakHeightFraction.getDefault(),
            (int) Math.round(seaLevel.getDefault() + (maxY.getDefault() - seaLevel.getDefault())
                    * peakHeightFraction.getDefault() * landHi),
            (int)(minY.getDefault() * 0.75),
            verticalScale.getDefault(),
            0.0,
            // coastline diversification（archipelago*/coastTerrainInfluence 已随键删除）
            0.15, 1200.0, 6, 2.0, 0.6,
            latitudeScale.getDefault(), tempWarpScale.getDefault(), humidityScale.getDefault(), climateRegionSize.getDefault()
        );
    }

}
