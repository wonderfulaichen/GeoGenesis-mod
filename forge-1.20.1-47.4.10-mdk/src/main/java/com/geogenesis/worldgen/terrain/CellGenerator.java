package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.ClimateRegion;
import com.geogenesis.worldgen.climate.ClimateSpline;
import com.geogenesis.worldgen.climate.Latitude;
import com.geogenesis.worldgen.climate.LatPrecipProfile;
import com.geogenesis.worldgen.climate.PrecipField;
import com.geogenesis.worldgen.climate.WhittakerType;
import com.geogenesis.worldgen.climate.WindField;
import com.geogenesis.worldgen.noise.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程（v8.4 海陆类型化，2026-08-06 用户铁律："海陆就是 2 个大地形类型"）：
 *   ContinentField.sample → c ∈ [-1,1]
 *   ├─ 类型场 TerrainCharacterField：OCEAN/DEEP_OCEAN 与 5 陆地类型共同参与 Voronoi 细胞竞争，
 *   │    细胞类型概率由 c 调制（c 低→海洋细胞多，c 高→陆地细胞多；过渡带概率对半）
 *   ├─ TypeLandShape.sample → e = Σw·lo + Σw·(hi-lo)·modulated（全类型混合）：
 *   │    陆地类型 lo/hi = 类型样条区间；海洋类型 lo=hi=深度样条+seabed
 *   ├─ e 自然连续穿过 0 → 海陆边界 = Voronoi 类型竞争（400 块折线），
 *   │    与地形类型过渡（PLAIN↔MOUNTAINS）完全同构——海岸线由地形场自然决定
 *   └─ 大陆性 c 只做：细胞概率偏置 + 海洋深度样条 + 气候（湿度/温度），不直接决定海陆边界
 *
 * 气候（v3 增强模型，2026-09-10）：
 *   温度 = 纬度基值（赤道热 → 两极冷，单调递减）× 海洋性修正 − 海拔递减率 + 噪声
 *   湿度 = 大陆性距海 + 山地雨影 + 噪声
 */
public final class CellGenerator {


    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final TypeLandShape typeLandShape;
    private final SeaBedDetail seaBed;
    private final OceanFeatures oceanFeatures;
    private final LandFeatures landFeatures;
    private final CoastlineField coastline;
    /**
     * ★ 2026-09-12 地质系统 Phase T1 总开关：构造骨架是否参与高程合成。
     * {@code false} → 完全不调用 TectonicField，输出<b>逐位退回</b>接入前（可回滚）。
     * 接 Forge 配置推迟到验证有效后（避免产生死配置，见体检报告 C8）。
     */
    private static final boolean TECTONIC_ENABLED = true;

    /**
     * ★ 2026-09-13 Phase T7(P0.5)：<b>海床护栏</b> —— 海床特征累计抬升与海平面的最小余量
     * （e 单位，≈3.8 块）。
     */
    private static final double SEABED_MARGIN = 0.02;
    /** 海床护栏软饱和的过渡宽度（e 单位）；见 {@link TectonicField#smoothMin}。 */
    private static final double SEABED_SOFT_SAT = 0.03;

    /** 构造骨架场（见 {@link #TECTONIC_ENABLED}）。 */
    private final TectonicField tectonic;

    /**
     * ★ 2026-09-12 地质 Phase T4 开关：地层/岩性是否计算。
     * {@code false} → 不调用 {@link StratumField}，{@code Cell} 的岩性字段保持默认。
     */
    private static final boolean STRATA_ENABLED = true;

    /** 地层场（构造环境 → 地层序列 → 出露岩性）。 */
    private final StratumField strata;

    /**
     * ★ 2026-09-12 地质 Phase T5 开关：褶皱/断层形变是否参与高程合成。
     * {@code false} → 不调用 {@link TectonicDeformation}，输出<b>逐位退回</b>（可回滚）。
     */
    private static final boolean DEFORM_ENABLED = true;

    /** 构造形变场（褶皱 / 断层）。 */
    private final TectonicDeformation deform;
    private final double continentBias;
    private final double seabedAmp;
    private final double oceanDepthFactor;
    /** 极寒阈值：温度低于此值即常年积雪（与 WhittakerType.T_TUNDRA=0.24 对齐：tempE < -0.52） */
    private static final double POLAR_SNOW_TEMP = -0.52;
    /** 海洋淡出起点（cBiased），在此以上海洋深度开始衰减到 0 */
    private final double oceanFadeStart;
    /** 陆地高度终点（cBiased），在此达到全量陆地高度 */
    private final double landRampEnd;
    /** coastLoc 保留为过渡带中心概念（配置/向后兼容），v8.3 已不再硬锚 e=0 */
    private final double coastLoc;
    private final TerrainParams params;

    // 温度参数（v6 纬度梯度，2026-09-10 取代旧正弦锚点模型）
    private final double tempFreq;    // 温度纬度角频率 = 1/latitudeScale（由 TerrainParams 注入，可配置）
    private final Noise tempWarp;     // 温度噪声扰动
    private final Noise humidityNoise; // 独立湿度噪声
    /** 气候区（抖动 Voronoi）：区内温湿恒定，消除群系椒盐碎斑 */
    private final ClimateRegion climateRegion;
    /** 降水的地形调制场（★ 2026-09-11 Phase B）：地形雨 / 雨影 / 焚风 */
    private final PrecipField precipField;
    /**
     * 温度 e 单位的"每单位多少 °C"换算（焚风用）。
     * {@code Climate.temperature ∈ [-1,1]} 对应约 −40…+40 °C → 1 e 单位 ≈ 40 °C。
     */
    private static final double DEG_C_PER_E_UNIT = 40.0;

    /**
     * 纬度降水廓线参考值（★ Phase D）：把 {@link LatPrecipProfile} 折算到均值 ≈1，
     * 使"区域层降水"与旧"区域层湿度"量级可比，避免切换后全图群系整体漂移。
     * 0.70 ≈ 廓线在 lat01∈[0,1] 上的平均（控制点 1.40 / 0.30 / 1.00 / 0.35 / 0.25）。
     */
    private static final double LAT_PRECIP_REF = 0.70;
    /** 气候区尺寸（wu），区界扰动幅度按它取 */
    private final double regionSize;
    /** 区界扰动噪声：让 Voronoi 边界蜿蜒（否则边界是直线/大块多边形） */
    private final Noise regionWarp;
    /** 绿洲斑块噪声（大尺度）：河流绿洲的断续门控（RTG SurfaceRiverOasis 范式） */
    private final Noise oasisNoise;

    private final boolean morphBase;
    private final double minWorldY, maxWorldY;
    private com.geogenesis.worldgen.core.BaseWorldSampler baseWorld;

    /**
     * 侵蚀运行时（{@code worldgen.process.ProcessRuntime}）—— <b>生产唯一侵蚀实现</b>。
     *
     * <p>2026-11-06 重构裁定：粒子侵蚀内核（{@code worldgen.erosion.ErosionEngine}）
     * 保持不变，但<b>调度与 tile 组织</b>统一由 ProcessRuntime 承载；
     * 旧 {@code CellGenerator} tile 管线与骨架条纹滤镜（RidgeValleyErosion）
     * 已从生产路径移除，不再有第二条分支。</p>
     *
     * <p>因此本字段恒为 {@code processRuntime != null}（在 {@link #seed(long)} 中创建）。
     * 保留方法 {@link #processErosionEnabled()} 只为诊断/断言可读性，不构成开关。</p>
     */
    private com.geogenesis.worldgen.process.ProcessRuntime processRuntime;
    private com.geogenesis.worldgen.process.ProcessContext processContext;
    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this(p, minWorldY, maxWorldY, false);
    }

    /** P1 is selected by the world's persisted algorithm, not by the erosion/river TOML flags. */
    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY, boolean morphBase) {
        this.morphBase = morphBase;
        // ★ 2026-11-06【无开关】物理粒子侵蚀是唯一实现。
        //   morphBase=false（旧 B）仍走老地形；但侵蚀本身不再有第二条调度路径。
        this.minWorldY = minWorldY;
        this.maxWorldY = maxWorldY;
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.typeLandShape = new TypeLandShape(p);
        this.seaBed = new SeaBedDetail(p);
        this.oceanFeatures = new OceanFeatures();
        this.landFeatures = new LandFeatures();
        this.coastline = new CoastlineField(p);
        this.tectonic = new TectonicField(0L);   // 种子在 seed() 注入
        this.strata = new StratumField(0L);      // ★ T4：地层场（种子在 seed() 注入）
        this.deform = new TectonicDeformation(0L); // ★ T5：褶皱/断层形变场
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
        this.climateRegion = new ClimateRegion(p.climateRegionSize());
        this.regionSize = p.climateRegionSize();

        // 降水地形调制场（★ Phase B）：高度取用走"廉价 + 绝不触发侵蚀 tile"铁律 ——
        // heightFromE(terrainEQuick(·)) 是纯噪声场，与 FlowAccumProbe 的 baseGround 同源。
        // 种子在 seed() 里注入（此处先给 0）。
        this.precipField = new PrecipField(0L,
            (x, z) -> heightCurve.heightFromE(terrainEQuick(x, z)),
            p.latitudeScale(), PrecipField.Params.defaults(), WindField.Params.defaults(),
            heightCurve.seaLevelY());   // ★ Phase E：注入海平面 → 上风向海域回灌湿度
        clearEqCache();   // terrainEQuick 缓存哨兵初始化
        // 波长 ≈1.5 个气候区：边界以该尺度蜿蜒，观感自然
        this.regionWarp = new Frequency(new Simplex(503), 1.0 / (p.climateRegionSize() * 1.5));
        // 波长 ≈0.75 个气候区：绿洲斑块与气候区同尺度，是"断续的绿洲群"而非细碎噪点
        this.oasisNoise = new Frequency(new Simplex(504), 1.0 / (p.climateRegionSize() * 0.75));
    }

    /**
     * 建立物理侵蚀运行时 —— <b>生产唯一侵蚀实现</b>，无条件创建。
     *
     * <p>上下文在此冻结：定义 = 当前 TerrainParams/B 设置 + 粒子参数快照 + 海平面。
     * 种子变化时重建，与 {@link #seed(long)} 的其它子系统同步。</p>
     *
     * <p><b>2026-11-06：{@code erosionEnabled} 接通</b>。此前该键只被配置屏/预览面板读写，
     * 生产路径完全不读 ⇒ 关掉「液滴粒子侵蚀」毫无效果（配置键守卫点名项的 P0-A）。
     * 现在关闭时走 {@link com.geogenesis.worldgen.process.ProcessContext.Mode#OFF}：
     * {@code eP == eB}、零 tile 构建，整个 P 层退化为纯 B 地形。</p>
     *
     * <p>预算取生产可用值（非探针值）：4 个 worker + 64 队列 + 1024 完成槽。
     * tile 是纯函数 ⇒ 预算只影响时序，不进语义身份。</p>
     */
    private void initProcessErosion(long worldSeed) {
        if (processRuntime != null) processRuntime.close();
        var snap = com.geogenesis.config.ParticleErosionConfigAdapter.snapshot();
        // XS / strength / hardness 来自 Forge 配置（不在 TerrainParams 上），读失败走默认值。
        var cfg = com.geogenesis.config.GeoGenesisConfig.INSTANCE;
        boolean xs = false;
        double strength = 1.0;
        boolean erosionOn = true;
        if (cfg != null) {
            try { erosionOn = cfg.erosionEnabled.get(); } catch (IllegalStateException ignored) { }
            try { xs = cfg.erosionXSEnabled.get(); } catch (IllegalStateException ignored) { }
            try { strength = cfg.erosionStrength.get(); } catch (IllegalStateException ignored) { }
        }
        var mode = com.geogenesis.worldgen.process.ProcessContext.modeFor(erosionOn);
        var def = new com.geogenesis.worldgen.process.ProcessContext.Definition(
                worldSeed, "morphBase+processRuntime", params,
                com.geogenesis.config.BaseWorldConfigAdapter.snapshot(),
                (int) minWorldY, (int) maxWorldY,
                mode, snap, xs, (float) strength,
                true, "geogenesis-common.toml");
        processContext = new com.geogenesis.worldgen.process.ProcessContext(def);
        // ★ 2026-11-06：队列 64 → 512。原 64 在实机里长期打满（一次会话 269,508 次拒绝），
        //   使 processErosionDeltaE 静默按"未侵蚀"兜底。现在拒绝会内联执行（不再丢格），
        //   加大队列是为了让内联成为罕见的兜底而非常态。
        processRuntime = new com.geogenesis.worldgen.process.ProcessRuntime(
                processContext, new com.geogenesis.worldgen.process.ProcessRuntime.Budget(4, 512, 1024));
        LOGGER.info("[PROCESS] 物理侵蚀运行时就绪 mode={}（erosionEnabled={}）identity 前缀={}",
                mode, erosionOn,
                processContext.identity().substring(0, Math.min(80, processContext.identity().length())));
    }

    /**
     * 新 P 层提供的侵蚀增量（e 单位，含 tile 边界 blend）。
     *
     * <p>与旧路径的关键差异：新 P 的 delta 定义在 <b>B 的 floored 输入</b>上
     * （{@code float(max(eB,-0.05))}），且 tile 为 48wu owner + 40wu halo。
     * 这里取 {@code exactP} 的 e 增量，由 {@link #applyProcessDelta} 施加到 cell。
     */
    private double processErosionDeltaE(double wuX, double wuZ) {
        if (processRuntime == null) return 0.0;
        try {
            var s = processRuntime.exactP(wuX, wuZ);
            return s.yP() == 0.0 && s.base().yB() == 0.0 ? 0.0 : s.deltaE();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0.0;
        } catch (java.util.concurrent.ExecutionException e) {
            LOGGER.warn("[PROCESS] exactP 失败于 ({},{})，本格不施加增量：{}", wuX, wuZ,
                    e.getCause() == null ? e.toString() : e.getCause().toString());
            return 0.0;
        }
    }

    /** 释放新 P 层执行器（world 关闭 / 重建时调用）。幂等。 */
    public void closeProcessErosion() {
        if (processRuntime != null) {
            processRuntime.close();
            processRuntime = null;
            processContext = null;
        }
    }

    public boolean processErosionEnabled() { return processRuntime != null; }

    /** 新 P 层运行时；model 侧据此绑定同一份 B/P。 */
    public com.geogenesis.worldgen.process.ProcessRuntime processRuntime() { return processRuntime; }

    /** 一次性播种所有噪声节点 + 设置海山中心水深检查器 */
    public void seed(long worldSeed) {
        if (morphBase) {
            baseWorld = new com.geogenesis.worldgen.core.BaseWorldSampler(
                    worldSeed, params, (int) minWorldY, (int) maxWorldY,
                    com.geogenesis.config.BaseWorldConfigAdapter.snapshot());
        }
        initProcessErosion(worldSeed);
        continent.seed(worldSeed);
        typeLandShape.seed(worldSeed);
        seaBed.seed(worldSeed);
        precipField.setSeed(worldSeed);   // ★ Phase B：降水场随世界种子重置
        tectonic.setSeed(worldSeed);      // ★ 地质 Phase T1：构造格局随世界种子重置
        strata.setSeed(worldSeed);        // ★ 地质 Phase T4：地层分布随世界种子重置
        deform.setSeed(worldSeed);        // ★ 地质 Phase T5：褶皱/断层相位随世界种子重置
        clearEqCache();                   // ★ D13：地形 e 缓存随世界种子失效
        // 海山中心水深检查：计算中心点的真实 eOcean（含 seabed，不含海山增量）
        // 仅在 eOcean_at_center < -0.20（足够深）时才允许生成海山
        oceanFeatures.setSeamountDepthChecker((wx, wz) -> {
            double c = continent.sample(wx, wz);
            double cBiased = c - continentBias;
            double eBase = heightCurve.eFromC(cBiased);
            // 2026-08-06 修复：depthMod 语义 = "深海加深 / 浅海不变"（x 越低值越大）→
            // 1-smoothstep(-0.6,-0.2,eBase)。原实现方向反（浅海 1.8/深海 0.6）。
            double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
            double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
            double eOcean = (eBase + seabed) * oceanDepthFactor;
            return Math.min(eOcean, 0.0);
        });
        coastline.seed(worldSeed);
        oceanFeatures.seed(worldSeed);
        landFeatures.seed(worldSeed);
        // ★ 2026-09-13 Phase T7(P4)：陆地火山布点改由【俯冲带火山活动强度】驱动
        //   （沿俯冲带富集，而非随机散布）。注入而非直接持有 —— 与 setSeamountDepthChecker
        //   同一模式（保持本类不感知构造场）。
        landFeatures.setArcVolcanismProvider(tectonic::arcVolcanism);
        // ★ 2026-09-14 P5：注入【中心点海陆判定】—— 火山按中心决定去留（整体生成），
        //   替代原先"逐点 × landFactor"的斜切（见 landFactorAt 注释）。
        landFeatures.setLandFactorProvider(this::landFactorAt);
        Noises.seedAll(tempWarp, worldSeed, 0);
        Noises.seedAll(humidityNoise, worldSeed, 0);
        Noises.seedAll(regionWarp, worldSeed, 0);
        Noises.seedAll(oasisNoise, worldSeed, 0);
        climateRegion.seed(worldSeed);
    }

    /** 世界高度下界 */
    public double minY() { return heightCurve.heightFromE(-1.0); }

    /** 世界高度上界 */
    public double maxY() { return heightCurve.heightFromE(1.0); }

    /** 海平面 Y */
    public double seaLevel() {
        return heightCurve.heightFromE(heightCurve.seaE());
    }

    /** TerrainParams 访问器（门面做块→wu 换算需要）。 */
    public TerrainParams params() { return params; }

    /**
     * 采样单格地形场（不含气候与分类）。供 {@link #sample} 与 {@link #terrainE}/{@link #landE} 复用。
     * 返回已设置 e/eLand/eOcean/blendCont/coastCoord/typeWeights/height/shape 的 Cell。
     *
     * <p><b>2026-08-10 wu 化（尺度解耦）</b>：坐标语义 = <b>wu（world unit）</b>，不再感知 MC 块。
     * 块→wu 换算只在 MC 门面 {@code GeoGenesisTerrain}（/horizontalScale）。噪声频率常量
     * 数值不变（语义块→wu），HS=1 时与旧实现逐位等价。</p>
     */
    public boolean usesMorphBase() { return morphBase; }

    private Cell sampleMorphCore(double x, double z) {
        return com.geogenesis.worldgen.terrain.morph.MorphCellAdapter.from(baseWorld.sampleTerrain(x, z));
    }

    private Cell sampleCore(double wx, double wz) {
        if (morphBase) return sampleMorphCore(wx, wz);
        double sx = wx, sz = wz;

        Cell cell = new Cell();

        // 1. 大陆性 c
        double c = continent.sample(sx, sz);
        cell.continent = c;
        double cBiased = c - continentBias;

        // 2. 海洋基面 eOcean（不 clamp 到 0——陆地区域 eBase 为正，自然地增高）
        double eBase = heightCurve.eFromC(cBiased);
        // 2026-08-06 修复：depthMod = "深海加深/浅海不变" → 1-smoothstep(-0.6,-0.2,eBase)
        double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
        double seabed = seabedAmp * depthMod * seaBed.sample(sx, sz);
        double eOcean = eBase + seabed;
        eOcean = eOcean * oceanDepthFactor;
        // ★ 2026-09-13 Phase T6：构造采样【提前】到海洋特征之前 —— 海洋特征的
        //   「洋中脊位置」（构造离散带门控）与「火山弧」（俯冲带离轴隆起）都依赖构造场。
        //   同一次采样随后供 T1 权重调制 / T4 岩性 / T5 形变复用 ⇒ 不增加采样次数。
        TectonicField.Sample tectSample = TECTONIC_ENABLED ? tectonic.sample(sx, sz) : null;

        // 海洋特征：计算洋中脊/海山/火山弧增量，叠加到 eOcean 使海床产生实际地形。
        double arcProf = tectSample != null ? tectonic.oceanProfile(tectSample) : 0.0;
        OceanFeatures.FeatureResult oceanFeat =
            oceanFeatures.compute(sx, sz, Math.min(eOcean, 0.0), cBiased, tectSample, arcProf);
        eOcean += oceanFeat.total; // 海山/洋中脊/弧抬升海床（仅海洋侧生效，陆地侧 blend 天然淡出）
        cell.oceanFeat = oceanFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // 3. 连续类型混合结果
        TerrainCharacterField.BlendResult cellBlend = typeLandShape.sampleBlend(sx, sz);
        cell.typeWeights = cellBlend.typeWeights;
        // ★ 2026-09-12 地质 Phase T1：构造决定类型（汇聚→造山/海沟，离散→裂谷/洋脊）
        //   ★ 2026-09-13：复用上方已采样的 Sample（原先在本行重复采样一次构造场）
        if (tectSample != null) applyTectonicWeights(cell.typeWeights, tectSample, sx, sz, cBiased);

        // 4. 海岸线域扭曲（v8 CoastlineField）— 海洋深度/类型样条用的 c 空间位移（保留轻量扰动）。
        double cEdge = cBiased + coastline.warpDisplacement(sx, sz, cBiased);

        // 5. 全类型混合 e（2026-08-06 海陆类型化）：OCEAN/DEEP_OCEAN 已是类型场成员，
        //    e = Σw·lo + Σw·(hi-lo)·modulated → e 自然连续穿过 0，海陆边界 = Voronoi 类型竞争。
        double eLand = typeLandShape.sample(cellBlend, sx, sz, cEdge);

        // 6. 特征增量的海陆门控（★ 2026-09-13 修复：改用【与地形同源】的门控）
        //
        //   【修复的问题（用户反馈"平原上的火山特别矮：平原 68 格、火山才 78 格"）】
        //   原用 `landW = 1 − oceanW`（oceanW 来自**类型权重场**）作陆地特征权重。
        //   但类型权重场与地形海陆（由 eLand 定义）**可以不一致**：实测一座位于
        //   e=+0.6552（明显内陆高地）的火山，其 `oceanW=0.4207` ⇒ `landW=0.58`
        //   ⇒ 火山只按 58% 抬升（0.161 e ≈ 38 格，而非应有的 65 格）。
        //   这与本项目反复出现的"两个场互相矛盾"是同一类问题
        //   （参见 `shellFromC` / 大陆架判据的注释）。
        //
        //   【正解】门控直接由**特征前的地形** eLandBase 推出（与海岸线同源，定义上不可能矛盾）：
        //     landFactor = smoothstep(−ε, +ε, eLandBase)   // 连续 0→1，ε=0.01
        //     海洋特征 × (1 − landFactor)（陆侧自然淡出）
        //   海岸线上 eLandBase=0 → 各半，过渡连续（无硬边）。
        //   ★ 这也是用户在"大陆架"反馈中确认有效的同一思路。
        //
        //   ★★ 2026-09-14 P5：<b>陆地特征不再乘 landFactor(x,z)</b> ★★
        //     用户反馈"火山山体靠海就被海陆过渡机制影响，导致火山口被强行降低"。
        //     逐点缩放（total × landFactor(x,z)）会让山体<b>靠海一侧被压缩</b>（斜切），
        //     且火口下凹（0.02~0.07e）在 eLandBase≈0 处几乎被抹平。
        //     现改为在 {@link LandFeatures} 内按<b>火山中心</b>取一次 landFactor
        //     （{@code landFactorAt}，经 {@link LandFeatures#setLandFactorProvider} 注入），
        //     对整座火山<b>整体</b>缩放 ⇒ 形状与火口完整、中心在深海则整座不生成
        //     （杜绝陆地火山造陆，与 {@link OceanFeatures} 的海山中心判定同构）。
        double eLandBase = eLand;                                  // 特征前地形 = 海陆定义
        double landFactor = smoothstep(-0.01, 0.01, eLandBase);
        LandFeatures.FeatureResult landFeat = landFeatures.compute(sx, sz, tectSample);
        double oceanW = (cellBlend.typeWeights[TerrainClass.OCEAN.ordinal()]
            + cellBlend.typeWeights[TerrainClass.DEEP_OCEAN.ordinal()]);
        // ★ 2026-09-14 P5：海陆判定已下沉到 LandFeatures 的【火山中心】，
        //   按中心因子【整体】缩放（见 LandFeatures.centerFactorOf）⇒ 此处直接相加。
        //   【为何不再乘 landFactor(x,z)】那是逐点缩放 ⇒ 斜切山体 + 压浅火口
        //   （用户反馈"火山山体靠海就被海陆过渡机制影响，火山口被强行降低"）。
        eLand += landFeat.total;

        // ★ 2026-09-12 地质 Phase T4：地层/岩性（构造环境 → 地层序列 → 出露岩性）。
        //   纯数据层，不参与 e 合成（不影响地形/气候/水文）；
        //   为 ROCK_LAYER / ROCK_TYPE 预览图层提供此前完全缺失的数据源。
        //   ★ 2026-09-13 P0.5：海陆判据改用【壳属性 shellS】（= shellFromC(c)），
        //     与海陆同源 ⇒ 不会像 oceanW 那样给出"c 说海、类型场说陆"的矛盾答案。
        TectonicField.Sample tsAll = null;
        if (STRATA_ENABLED || DEFORM_ENABLED) {
            tsAll = tectSample != null ? tectSample : tectonic.sample(sx, sz);
        }
        if (STRATA_ENABLED) {
            cell.rockLayer = strata.layerAt(sx, sz);
            boolean isLandShell = TectonicField.shellFromC(cBiased) > 0.5;
            cell.rockTypeId = StratumField.rockTypeId(tsAll, isLandShell, cell.rockLayer);
            // ★ 2026-09-14 Phase T9/T9b：整条地层序列 + 各层厚度（打包）→ 供方块层铺垂直岩层
            cell.rockSeqPacked = strata.packSequence(tsAll, isLandShell, sx, sz);
            // ★ 2026-09-14 Phase T10：区域倾斜/褶皱偏移（水平地层的界面起伏）
            cell.rockTilt = strata.tiltAt(sx, sz);
        }

        // ★ 2026-09-12 地质 Phase T5：构造形变（褶皱 / 断层）。
        //   叠加到 eLand（形变本质是"地形被构造应力改造"）。
        //   设计为近零均值：褶皱用正弦（天然零均值），断层断块用中心化哈希
        //   → 不整体抬升/压低地形，避免 T1 那次"整体抬升连锁改变降水与河宽"的教训。
        if (DEFORM_ENABLED && tsAll != null) {
            eLand += deform.offset(tsAll, sx, sz);
        }

        // 2026-08-06 修复：移除整体 ×0.9 余量缩放。该设计为旧显式脊谷抬升（+0.17e）预留空间，
        // 显式抬升 2026-08-05 已全部移除 → ×0.9 只是无谓压低整个地形 10%
        // （用户反馈"有什么在限制着"；山脉 hi 0.95 实际仅 0.855）。softCapLandE（>0.9215 才压缩）
        // 仍是保险。样条/滑块设置的高度现在如实生效。
        cell.eLand = eLand;
        cell.landFeat = landFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // ★★★ 2026-09-13 Phase T7(P0.5)：**海床护栏**（从构造上杜绝"海洋特征造陆"）★★★
        //
        //   【问题（实测）】海床特征（海山/洋中脊/火山弧）按 `× oceanW` 软加权叠加时，
        //   在【eLand ≈ 0 且 oceanW 仍较大】的点上会把地形推过 e=0 ⇒ 冒出一座"孤岛"。
        //   实测（OceanTypeProbe 差分归因，seed=12345）：
        //     洋中脊 22 例 / 3460 作用点  ·  火山弧 17 例 / 602  ·  海山 0 例
        //   逐点诊断定机制：`eOcean=−0.250`（c 场说深海，弧的深海门控通过）
        //   但 `eLand=−0.0033`（类型场说陆）——**两场不一致**是该现象的唯一成因。
        //
        //   【为何"× oceanW" 挡不住】`oceanW` 是类型场的海洋权重，在 eLand≈0 处仍可达 0.68
        //   （类型场在海陆过渡带本就是渐变）⇒ 弧贡献 0.10×0.68=0.068e ≫ 0.0033。
        //   （另注：`OceanFeatures` 内部**已有**基于 eOcean 的深海门控 `arcFade`，
        //     故这个 `× oceanW` 是**重复门控**，且引入了类型场的矛盾。）
        //
        //   【正解：护栏，而非调小特征】让海洋特征的<b>累计抬升</b>永不把地形推过海平面：
        //       oceanFeat.total·oceanW ≤ (−eLand) − MARGIN
        //   深海处 `eLand` 很负 ⇒ 上限远大于特征幅度 ⇒ **完全不生效**（特征形态不变）；
        //   只在 `eLand≈0`（矛盾点）才压缩 ⇒ 那些孤岛消失，且 MARGIN 保证被压缩点仍 <0
        //   ⇒ **不新增/移除海岸线**（既有标定不受影响）。用 C¹ 的 smoothMin 避免折痕。
        //
        //   ★ 必须放在此处（**T5 形变之后**）——初版放在第 6 步（形变前）时护栏算漏了
        //     `deform.offset`（可达 +0.045e）的抬升，实测仍有 14/13 例漏网（洋中脊 22→13、
        //     弧 17→14 只降不归零）。移到最终 `eLand` 之后才真正封住。
        //   ★ 权重同样改用同源门控 (1 − landFactor)：海侧 eLandBase<0 → ≈1（满值），
        //     陆侧 → 0（自然淡出），海岸线处各半（连续）。原用 oceanW（类型场）会
        //     在"类型场与地形不一致"处既可能放大也可能缩小海洋特征。
        double oceanFeatW = oceanFeat.total * (1.0 - landFactor);
        if (oceanFeatW > 0.0) {
            double cap = -eLand - SEABED_MARGIN;
            oceanFeatW = cap <= 0.0 ? 0.0 : TectonicField.smoothMin(oceanFeatW, cap, SEABED_SOFT_SAT);
        }
        // （诊断可用 `cell.e − cell.eLand` 反推本值：低处 softCapLandE 不介入 ⇒ 精确；
        //   故不新增 Cell 字段，避免扩大改动面。）

        // 7. 连续主导类型（可能为 OCEAN/DEEP_OCEAN）
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 8. 海陆统一 e = 类型混合 + 海洋特征增量（海山/洋中脊按海洋权重平滑淡入；
        //    上方已过"海床护栏"，保证这一项不会把地形推过海平面）
        double e = softCapLandE(eLand + oceanFeatW);
        cell.e = e;
        cell.eClimate = e;   // 侵蚀前 e：群系垂直带判定专用（applyTileDelta 不覆写）
        cell.variantTerrain = ditheredTerrain(cell.typeWeights, wx, wz);
        cell.eOcean = eOcean;      // 海洋基面（预特征，分类/诊断用）
        cell.blendCont = oceanW;   // 语义（2026-08-06）：海洋类型权重和（原 cont 已废除）
        cell.height = heightCurve.heightFromE(e);
        cell.coastCoord = cEdge;
        cell.shape = eLand * 2.0 - 1.0;
        return cell;
    }

    /**
     * 采样单格完整数据 — 统一连续场 e(x,z)，叠加气候与分类。
     * 坐标语义 = wu（2026-08-10 wu 化，见 {@link #sampleCore}）。
     */
    public Cell sample(double wx, double wz) {
        if (morphBase) return com.geogenesis.worldgen.terrain.morph.MorphCellAdapter.from(baseWorld.sample(wx, wz));
        Cell cell = sampleCore(wx, wz);
        double sx = wx, sz = wz;

        // 8. 气候（v5 双轨模型，对齐 RTF ClimateModule，2026-09-10）
        //    RTF 原文：cell.biome = BiomeType.get(regionTemperature, regionMoisture)
        //              cell.temperature/moisture = 在【查询位置】采样的连续值
        //    ① 区域层（区内恒定）→ 只决定 Whittaker 群区 → 群系成片、不椒盐
        //    ② 连续层（逐格平滑）→ 暴露到 Cell.climate → 预览/下游看到平滑渐变
        //    【教训】此前把暴露的温湿度也做成区域恒定 → 温度/湿度/群系三个图层全成
        //      平顶马赛克（用户实测"多边形"）。双轨后温湿是连续曲线，群系仍是成片。
        // 区界扰动（参考 RTF 的 biomeEdgeShape：多倍频噪声 + offsetX/offsetZ）。
        // 【教训】单频扰动只能把 Voronoi 的直线"弯一下"，长直段依然可见（用户实测仍有直边多边形）；
        // 多倍频在 1.5×/0.75×/0.375× 区尺度同时起伏 → 打断所有可见尺度的直线段。
        double rx = wx + regionWarpOffset(wx, wz);
        double rz = wz + regionWarpOffset(wz, wx);
        ClimateRegion.Sample reg = climateRegion.sample(rx, rz);

        // ① 区域层：只算群区
        // ★ 2026-09-11 Phase D：Whittaker 的"降水轴"由【区域层湿度】换成【区域层降水】——
        //   区域层湿度 × 纬度降水廓线（ITCZ 峰 / 副热带谷 / 西风带次峰 / 极地低）。
        //
        //   为什么必须用【区域层】而非逐格连续的 cell.precipitation：
        //   逐格值含 96wu 尺度的地形雨/雨影 → 群区会随之地形破碎，退化成"椒盐群系"
        //   （本项目在温湿度上踩过这个坑，见下方"双轨模型"注释）。
        //   区域层 → 区内恒定 → 群系仍成片；纬度结构则带来了真正的"行星级"分异。
        double regionMoist01 = clamp(regionMoisture(reg) * 0.5 + 0.5, 0.0, 1.0);
        double regionPrecip01 = clamp(
            regionMoist01 * LatPrecipProfile.at(Latitude.latitude01(wz, params.latitudeScale()))
                / LAT_PRECIP_REF, 0.0, 1.0);
        cell.biomeType = WhittakerType.classify(
            clamp(regionTemperature(reg) * 0.5 + 0.5, 0.0, 1.0), regionPrecip01);

        // ② 连续层：纬度 + 低频噪声，再叠加逐格地形修正
        double temp = temperatureAt(wx, wz);
        temp *= 0.85 + 0.15 * clamp(cell.continent * 1.5, 0.0, 1.0);    // 大陆性温差（逐格）
        temp -= elevationTempDrop(cell.e);                              // 海拔递减（逐格）

        // ★ 2026-09-11 Phase B：降水地形调制（地形雨 / 雨影 / 焚风）。
        //   一次"上风向回扫"同时得出三者；焚风为背风坡干绝热下沉增温，并入温度。
        PrecipField.Mod pm = precipField.at(wx, wz);
        temp += pm.foehnWarm() / DEG_C_PER_E_UNIT;
        temp = clamp(temp, -1.0, 1.0);

        double hum = humidityNoise.compute(wx, wz) * 0.75;               // 低频平滑湿度噪声
        hum = continentMoisture(hum, cell.continent);                     // 大陆性：沿海湿 / 内陆干（逐格）
        hum -= elevationMoistureDrop(cell.e);                             // 高海拔变干（逐格）
        // ★ 2026-09-11 Phase E：水文 → 气候反向耦合。
        //   上风向为海面时下风向增湿（水汽来源），完成"地形→气候→水文→气候"闭环。
        //   注：Whittaker 群区用的是【区域层】温/降水，故此处逐格增湿【不会】产生椒盐群系；
        //   但它会经 precipitationFrom 提高降水 → 进而加宽下游河宽（Phase C 链），
        //   水源为地形固定的海面 → 不存在"河越宽→越湿→河更宽"的正反馈发散。
        hum += pm.waterMoist();
        hum = clamp(hum, -1.0, 1.0);

        // 气候影响权重（tempInfluence / humidityInfluence / continentInfluence）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double tempInf = cfg != null ? cfgDbl(cfg.tempInfluence, 1.0) : 1.0;
        double humInf = cfg != null ? cfgDbl(cfg.humidityInfluence, 1.0) : 1.0;
        double contInf = cfg != null ? cfgDbl(cfg.continentInfluence, 1.0) : 1.0;
        double tempE = clamp(temp * tempInf, -1.0, 1.0);
        double humE = clamp(hum * humInf, -1.0, 1.0);
        double contE = clamp(cell.continent * contInf, -1.0, 1.0);

        // ★ 2026-09-11 Phase B：降水 = 纬度廓线 × 湿度 × (1+地形雨) × (1−雨影) × 温度门控
        double precipE = precipitationFrom(humE, tempE,
            Latitude.latitude01(wz, params.latitudeScale()), pm);

        cell.climate = new com.geogenesis.worldgen.climate.Climate(tempE, humE, contE, precipE);
        cell.temperature = tempE;
        cell.humidity = humE;
        cell.precipitation = precipE;

        // 9. 雪线（单一来源）：配置基准 + 纬度（温度）耦合 + 湿度耦合。
        //    —— 干区雪线升高、湿区降低；暖区升高、寒区降低。
        //    —— isSnow 供 BiomeClassifier 与地表铺雪共用，取代此前散落的硬编码阈值。
        cell.snowLineE = params.snowLine()
            + params.snowLatitudeInfluence() * tempE
            - params.snowHumidityInfluence() * humE;
        // 两种情况要雪：① 海拔超过雪线；② 气候本身极寒（对应 ICE/TUNDRA 群的 SNOWY_PLAINS）。
        // ② 不可省：本模组 buildSurface 为空实现（原版 SurfaceSystem 的铺雪不会执行），
        //   若只按海拔判雪，极地平坦地带会顶着「雪原」群系却没有雪。
        cell.isSnow = cell.eClimate > 0.0
            && (cell.eClimate > cell.snowLineE || tempE < POLAR_SNOW_TEMP);

        // 9. 分类（使用 sampleCore 已缓存 FeatureResult，避免重复 compute）
        if (!morphBase) {
            cell.terrainType = classify(cell.continent, cell.e, cell.eLand,
                TypeLandShape.dominantFromWeights(cell.typeWeights), cell.typeWeights,
                tempE, humE, cell.oceanFeat, cell.landFeat, cell.coastCoord);
        }
        cell.continentNoise = cell.continent;
        cell.oasisNoise = oasisNoise.compute(wx, wz);

        return cell;
    }

    /** 排水高程（真实地表 e，含海陆混合）。河流节点场用它做下坡汇流，海洋侧 e<0 → 不接河。 */
    public double terrainE(double wx, double wz) { return sampleCore(wx, wz).e; }

    /**
     * 归一化降水采样（★ 2026-09-11 Phase C）—— 供水文汇流加权使用。
     *
     * <p>走 {@link #sample}（纯噪声 + 气候，<b>不含侵蚀</b>）→ 廉价且<b>不触发侵蚀 tile</b>。
     * 水文侧按粗格点调用（见 {@code FlowField.PRECIP_COARSE}）以避免过采样。</p>
     */
    public double precipitationAt(double wx, double wz) {
        return sample(wx, wz).precipitation;
    }

    /** 大陆性 c 采样（wu 语义，纯位置函数）。waterTable 水面推导用（RTF 范式河网）。 */
    public double continentAt(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    // ===== terrainEQuick 直接映射缓存（★ 2026-09-11，D13 性能）=====
    // 纯函数 (wx,wz) → e（同 seed 恒定）。实测两个热点会【在同一批坐标】各采样一次：
    //   routingE（FlowField 选线场）与 computeFill（填洼层，走 groundYAt）
    //   → 天然 2× 冗余；跨 region 的 margin 重叠再放大。
    // 缓存命中 ~20ns vs 计算 ~6µs（runBench 实测 16384 次 = 97.61ms）。
    //
    //   两个不变量都必须成立：
    //   ① 【单条读取 key+value 原子】—— 键与值必须由【同一不可变 entry】承载，
    //      读者只取一次槽引用即同时拿到 key 与 value。旧实现用两个 AtomicLongArray
    //      （eqKeys/eqVals 各写一次）：读者命中 key 之后、读 value 之前，若有另一线程
    //      用【不同坐标】覆盖同一槽，则会读到【别人坐标的值】——这不是撕裂读，而是
    //      静默错值（无任何异常/NaN 提示），且液滴并行与水文多线程下真实可发生。
    //   ② 【槽只定位不当身份】—— 哈希槽只是哈希表定位，身份由 entry 内的完整
    //      key（EQ 为 wu 位模式混合键；HR 为 qx/qz 量化坐标）比较决定；碰撞只是
    //      增未命中，绝不返回异槽/异坐标的值。
    //   容量不变（EQ_CACHE_SIZE / LF_CACHE_SIZE），产出与旧实现同分布。
    private static final int EQ_CACHE_BITS = 17;                 // 131072 槽 ≈ 2 MB
    private static final int EQ_CACHE_SIZE = 1 << EQ_CACHE_BITS;
    private static final int EQ_CACHE_MASK = EQ_CACHE_SIZE - 1;
    /** 空槽哨兵（构造/播种时填充；真实键恰为此值者会被重映射，概率 2^-64）。 */
    private static final long EQ_EMPTY = Long.MIN_VALUE;

    /** 键值对的不可变快照（entry 为 null = 空槽）。替换引用 = 单条 API 的原子发布。 */
    private record KeyedValue(long key, long bits) {}
    private final AtomicReferenceArray<KeyedValue> eqEntries =
            new AtomicReferenceArray<>(EQ_CACHE_SIZE);
    private final AtomicLong eqHits = new AtomicLong();
    private final AtomicLong eqMisses = new AtomicLong();

    /** 清空 terrainEQuick 缓存（换种子/构造时调用）。按引用置 null 清空（与 seed/配置变更同义）。 */
    private void clearEqCache() {
        for (int i = 0; i < EQ_CACHE_SIZE; i++) eqEntries.set(i, null);
        eqHits.set(0);
        eqMisses.set(0);
        for (int i = 0; i < LF_CACHE_SIZE; i++) lfEntries.set(i, null);
    }

    // ===== ★ 2026-09-14 P5：火山中心「海陆判定」缓存 =====
    //   火山中心是【哈希确定的离散点】（单体格 800wu，每格至多一座），
    //   其半径（最大 300wu）内的所有采样点都要反复查询"这个中心在陆上吗"
    //   ⇒ 空间局部性极强，命中率 > 99%。
    //   【为何必须缓存】不加缓存时每次查询要重算"半个 sampleCore"
    //   （continent + 类型场 Voronoi + 海岸线扭曲 + 构造权重），而调用频率约
    //   0.7 次/采样点（单体 8%×9 邻居格 + 火山区 12%×9×区域占比）
    //   ⇒ 实测开销约 +40%。同地形 e 缓存（不可变 entry 单条原子读），
    //   键域用 LF_SALT 隔离，避免与 e 缓存互相覆盖。
    private static final int LF_CACHE_BITS = 12;                 // 4096 槽 ≈ 64 KB
    private static final int LF_CACHE_SIZE = 1 << LF_CACHE_BITS;
    private final AtomicReferenceArray<KeyedValue> lfEntries =
            new AtomicReferenceArray<>(LF_CACHE_SIZE);
    private static final long LF_SALT = 0x5DEECE66DL;

    /** 缓存命中/未命中统计（诊断用）。 */
    public long eqCacheHits() { return eqHits.get(); }

    /** 缓存未命中数（诊断用）。 */
    public long eqCacheMisses() { return eqMisses.get(); }

    /**
     * 轻量地形 e（跳过气候/分类/height 映射/shape 赋值）—— <b>带直接映射缓存</b>。
     *
     * <p>缓存是<b>纯加速</b>：值为纯函数结果，命中与未命中等价（除 2^-64 的哨兵碰撞）。</p>
     */
    public double terrainEQuick(double wx, double wz) {
        if (morphBase) return baseWorld.sampleE(wx, wz);
        long kx = Double.doubleToRawLongBits(wx);
        long kz = Double.doubleToRawLongBits(wz);
        long key = kx ^ Long.rotateLeft(kz, 32);
        if (key == EQ_EMPTY) key = Long.MAX_VALUE;               // 避开哨兵
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - EQ_CACHE_BITS));
        KeyedValue entry = eqEntries.get(slot);                  // ★ 单条原子读取：key+value 同快照
        if (entry != null && entry.key() == key) {
            eqHits.incrementAndGet();
            return Double.longBitsToDouble(entry.bits());
        }
        eqMisses.incrementAndGet();
        double v = terrainEQuickCompute(wx, wz);
        eqEntries.set(slot, new KeyedValue(key, Double.doubleToRawLongBits(v)));
        return v;
    }

    /**
     * ★ 2026-09-14 P5：<b>特征前的基面「陆地门控」</b>（供 {@link LandFeatures} 按火山中心判定海陆）。
     *
     * <h3>为何需要（用户反馈"火山山体靠海就被海陆过渡机制影响，火山口被强行降低"）</h3>
     * <p>原实现把 {@code landFactor} 施加在<b>每个采样点</b>：
     * {@code eLand += landFeat.total * landFactor(x,z)}。而 {@code landFactor} 的过渡带
     * 仅 {@code ε=0.01e}（≈2.4 格）⇒ 对一座横跨海岸线的火山，山体的<b>靠海一侧被按比例压缩</b>
     * （= 斜切），且<b>火口下凹也被同比例压浅</b>（火口深度 0.02~0.07e 在 eLandBase≈0 处
     * 几乎被完全抹平）。地质上火山是<b>刚性离散体</b>：它不随基底的海陆过渡而"变软"。</p>
     *
     * <h3>正解：判定改到「中心点」，与海山完全同构</h3>
     * <p>{@link OceanFeatures} 早有正确先例（{@code seamountCenterDepthCheck}）：
     * 按<b>中心点</b>的水深决定这座海山生成与否，生成后整体叠加。本方法提供同样的
     * 「某点是否算陆地」，由 {@link LandFeatures} 在<b>火山中心</b>调用一次，
     * 决定整座火山的去留 ⇒ 山体不再被斜切、火口完整。</p>
     *
     * <p><b>为何是离散去留而非连续缩放</b>：中心距边界 &lt; 半径时，无论用哪种连续
     * 缩放，都必然使山体两侧不对称（= 斜切）。而"离散去留"只让海岸线上的火山
     * 要么完整、要么没有 —— 中心点哈希是<b>离散事件</b>，其判定边界不产生地形折痕
     * （对比：若用连续量做门控坐标，等值线会成为直线/折痕）。</p>
     *
     * <p><b>无自引用</b>：本方法算的是 {@code eLandBase}（<b>未叠加</b>任何特征的基面），
     * 与 {@link #sampleCore} 第 6 步一致 —— 绝不能调用 {@link #terrainEQuick}，
     * 那会经 {@code landFeatures.compute} 递归回本方法。</p>
     *
     * <p><b>非纯加速</b>：缓存只影响性能，命中与未命中等价（值为纯函数结果）。</p>
     *
     * @return 陆地门控 ∈ [0,1]：1 = 明确陆地（火山完整生成），0 = 明确海洋（不生成）
     */
    public double landFactorAt(double wx, double wz) {
        long kx = Double.doubleToRawLongBits(wx);
        long kz = Double.doubleToRawLongBits(wz);
        long key = (kx ^ Long.rotateLeft(kz, 29)) + LF_SALT;
        if (key == EQ_EMPTY) key = Long.MAX_VALUE;
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - LF_CACHE_BITS));
        KeyedValue entry = lfEntries.get(slot);                  // ★ 单条原子读取：key+value 同快照
        if (entry != null && entry.key() == key) {
            return Double.longBitsToDouble(entry.bits());
        }
        // ★★★ 2026-09-14 性能修复（用户"比几小时前慢"，卡在 0%）★★★
        //
        //   【原实现（性能回归根因，已删）】为精确复刻 eLandBase，本方法重采样了
        //   几乎整个 sampleCore：continent + typeLandShape.sampleBlend（7×7 Voronoi）
        //   + tectonic.sample + coastline.warpDisplacement + typeLandShape.sample。
        //   实测 <b>78,568 ns/次</b> —— 是 terrainEQuick 的 <b>8.75 倍</b>！
        //   而它被 terrainEQuick 链式触发（terrainEQuick → landFeatures.compute
        //   → centerFactorOf → 本方法）⇒ 河网构建（每 region 数万格）被拖慢近 9 倍。
        //
        //   【为何不需要精确】本方法唯一用途是回答"这个<b>火山中心</b>在陆上吗"，
        //   属【二值去留】判定（LandFeatures.centerFactorOf）。而
        //   {@link ContinentField} 正是本项目的<b>海陆场</b>（{@code shellFromC} 与它同源）
        //   ⇒ 直接用它判定即可，无需经过"类型场 Voronoi + 海岸线扭曲"那套形态学管线。
        //   两者在海岸带的细微差异，对"整座火山生成与否"无观感影响。
        double cBiased = continent.sample(wx, wz) - continentBias;
        double v = smoothstep(-0.01, 0.01, cBiased);
        lfEntries.set(slot, new KeyedValue(key, Double.doubleToRawLongBits(v)));
        return v;
    }

    /** 轻量地形 e 的【实际计算】（跳过气候/分类/height 映射/shape 赋值）。
     *  坐标语义 = wu（2026-08-10 wu 化，见 {@link #sampleCore}）。
     *  <p>★ 2026-09-11：外部请调用带缓存的 {@link #terrainEQuick}。</p> */
    private double terrainEQuickCompute(double wx, double wz) {
        double sx = wx, sz = wz;

        // 1. 大陆性 c
        double c = continent.sample(sx, sz);
        double cBiased = c - continentBias;

        // 2. 海洋基面
        double eBase = heightCurve.eFromC(cBiased);
        double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
        double seabed = seabedAmp * depthMod * seaBed.sample(sx, sz);
        double eOcean = (eBase + seabed) * oceanDepthFactor;

        // ★ 2026-09-13 Phase T6：与 sampleCore 同序 —— 构造采样提前，供海洋特征
        //   （洋中脊门控 / 火山弧）与 T1 权重调制复用（保证 tile 与直接采样逐位一致）。
        TectonicField.Sample tsQ = TECTONIC_ENABLED ? tectonic.sample(sx, sz) : null;

        // 3. 海洋特征
        double arcProfQ = tsQ != null ? tectonic.oceanProfile(tsQ) : 0.0;
        OceanFeatures.FeatureResult oceanFeat =
            oceanFeatures.compute(sx, sz, Math.min(eOcean, 0.0), cBiased, tsQ, arcProfQ);
        eOcean += oceanFeat.total;

        // 4. 类型混合（Voronoi 场）
        TerrainCharacterField.BlendResult cellBlend = typeLandShape.sampleBlend(sx, sz);
        // ★ 地质 Phase T1：与 sampleCore 相同的构造调制（复用上方已采样的 tsQ）
        if (tsQ != null) applyTectonicWeights(cellBlend.typeWeights, tsQ, sx, sz, cBiased);

        // 5. 海岸线扭曲
        double cEdge = cBiased + coastline.warpDisplacement(sx, sz, cBiased);

        // 6. 全类型混合 e
        double eLand = typeLandShape.sample(cellBlend, sx, sz, cEdge);

        // 7. 特征增量（★ P4：传入构造采样，使陆地火山沿俯冲带弧轴成链）
        //    ★ 与 sampleCore 一致：海陆门控改用【与地形同源】的 landFactor（见 sampleCore 注释）
        double eLandBaseQ = eLand;
        double landFactorQ = smoothstep(-0.01, 0.01, eLandBaseQ);
        LandFeatures.FeatureResult landFeat = landFeatures.compute(sx, sz, tsQ);
        double oceanW = cellBlend.typeWeights[TerrainClass.OCEAN.ordinal()]
            + cellBlend.typeWeights[TerrainClass.DEEP_OCEAN.ordinal()];
        // ★ 2026-09-14 P5：与 sampleCore 同源 —— 中心因子已内建于 LandFeatures ⇒ 直接相加
        eLand += landFeat.total;

        // ★ 地质 Phase T5：与 sampleCore 相同的构造形变（保证 tile 与直接采样一致）
        if (DEFORM_ENABLED) {
            TectonicField.Sample tsD = tsQ != null ? tsQ : tectonic.sample(sx, sz);
            eLand += deform.offset(tsD, sx, sz);
        }

        // ★ P0.5 海床护栏：与 sampleCore 逐位一致（必须同样在 T5 形变【之后】）
        double oceanFeatWQ = oceanFeat.total * (1.0 - landFactorQ);
        if (oceanFeatWQ > 0.0) {
            double capQ = -eLand - SEABED_MARGIN;
            oceanFeatWQ = capQ <= 0.0 ? 0.0 : TectonicField.smoothMin(oceanFeatWQ, capQ, SEABED_SOFT_SAT);
        }

        // 8. 海陆统一 e（★ P0.5：用受海床护栏约束的 oceanFeatWQ，与 sampleCore 一致）
        return softCapLandE(eLand + oceanFeatWQ);
    }

    /**
     * ★ 2026-09-12 地质 Phase T1：<b>构造决定地形类型</b>（而非"构造推高地形"）。
     *
     * <p>按板块边界类型调制 {@code typeWeights}，让山脉/海沟/裂谷/洋脊沿<b>地质边界</b>分布：
     * <ul>
     *   <li><b>汇聚</b>：陆地→提升 MOUNTAINS（造山带）；海洋→提升 DEEP_OCEAN（俯冲海沟）</li>
     *   <li><b>离散</b>：陆地→提升 BASIN（裂谷）；海洋→提升 OCEAN（洋中脊，浅于深海）</li>
     *   <li><b>走滑 / 内部</b>：不调制（worldgen 亦无垂向贡献）</li>
     * </ul>
     *
     * <p><b>为何用权重调制而非直接加高程偏置</b>：直接抬升 {@code e} 会连锁增强地形雨
     * （PrecipField 的 orographicGain 基于高度差），推高全局降水并稀释 Phase C
     * 「干旱区河细」的区分度（实测 head 最干桶 0.922→0.959）。改为调制类型权重后，
     * 山脉由<b>既有 MOUNTAINS 配方</b>生成，不引入额外高度偏置 → 不干扰气候/水文标定。
     *
     * <p>调制后重新归一化，保证权重和为 1（{@code dominantFromWeights} 依赖此性质）。
     *
     * <p>★ 2026-09-13 Phase T6：改为<b>接收已采样的 {@code ts}</b>（调用方在海洋特征之前
     * 已采样一次并复用），避免同一位置重复采样构造场。</p>
     */
    private void applyTectonicWeights(double[] w, TectonicField.Sample ts, double sx, double sz,
                                      double cBiased) {
        // ★ Phase T2：汇聚造山带按走向串珠化（独立山峰），离散保持连续（真实裂谷是线状）
        double g = tectonic.boundaryStrengthChained(ts, sx, sz);
        // ★ 阈值必须低到不可见：任何提前 return 都是地形跳变源（环状细线/串珠虚线）。
        if (g <= 1e-6) return;

        // ★★ 2026-09-13 Phase T7（P0）：分支判据由【形态学 landW/oceanW】改为
        //    【地质学板块壳属性 shellS】★★
        //
        //   见 TectonicField.plateShell 的长注释。核心差异：
        //     · 旧：landW/oceanW 来自【类型权重场】（形态学）—— 与板块无关，
        //           且与 c 场可互相矛盾（实测"火山弧在 eLand≈0 处造陆"即源于此）。
        //     · 新：shellS 来自【该点所属板块的壳属性】（由 c 场在板块种子处取值推导）
        //           —— 这才是参考项目 worldgen 的 (陆,陆)/(陆,洋)/(洋,洋) 分支依据。
        //
        //   地质语义随之正确（对齐参考项目 boundary_profile）：
        //     陆-陆汇聚 → 两侧 shell≈1 → 都造山；陆-洋汇聚 → 陆侧造山 + 洋侧海沟；
        //     洋-洋汇聚 → 都海沟。离散同理（陆壳裂谷 / 洋壳洋中脊）。
        //   ★ 关键收益：陆-洋汇聚不再"两边都造山"，而是陆侧造山、洋侧海沟。
        //
        //   ★ 为何不会产生"直线网伪影"（实测已验证）：
        //     shellS 直接由【连续的 c 场】得出（多倍频 FBM，处处 C¹）——
        //     **与海陆同源**且**绝无阶跃**。
        //     ※ 曾尝试"壳属性 = 所属板块的常数"（更贴近参考项目的 is_continental[pid]），
        //       被 TectonicProbe[8] **实测否决**：本项目 PLATE_SPACING=2000wu 而 c 波长
        //       ≈4000wu ⇒ 一个大陆仅约 2 个板块（参考项目 ~10 个）⇒ 相邻板块壳属性差
        //       P50=0.41 / 48.4% 是"一陆一洋" ⇒ 等效阶跃 96 块 = 必然的 Voronoi 直线网。
        //       详见 TectonicField.shellFromC 的注释。
        //
        //   （旧注释保留以免后人误改回：用 landW/oceanW 分叉 = 让形态学冒充地质学。）
        double shellS = TectonicField.shellFromC(cBiased);   // 0=洋壳 1=陆壳（连续、与海陆同源）
        // ★ 2026-09-12 修复（伪影）：改用【连续应力】stress 加权，取代 switch(btype)。
        //   btype 在类型边界跳变 → 权重调制骤变 → eLand 骤变 → 线状疤痕。
        //   stress ∈[-1,1] 连续 → 过渡平滑。
        // ★ 2026-09-12 第五次伪影修复：Math.max(0,·) → 平滑正部，并**去掉 if 分支**。
        //   原写法沿 stress=0 等值线切换公式 → 一阶不连续 → 地形折痕（笔直断裂线）。
        //   改后两个分支恒执行（cw/dw 恒>0），公式处处一致 → 无折痕。
        double cw = TectonicField.smoothPos(ts.stress(), TectonicField.STRESS_POS_EPS);
        double dw = TectonicField.smoothPos(-ts.stress(), TectonicField.STRESS_POS_EPS);
        double addC = TectonicField.CONVERGENT_BOOST * g * cw;   // 汇聚 → 造山（陆壳）/ 海沟（洋壳）
        w[TerrainClass.MOUNTAINS.ordinal()] *= 1.0 + addC * shellS;
        w[TerrainClass.DEEP_OCEAN.ordinal()] *= 1.0 + addC * (1.0 - shellS);
        double addD = TectonicField.DIVERGENT_BOOST * g * dw;    // 离散 → 裂谷（陆壳）/ 洋中脊（洋壳）
        w[TerrainClass.BASIN.ordinal()] *= 1.0 + addD * shellS;
        w[TerrainClass.OCEAN.ordinal()] *= 1.0 + addD * (1.0 - shellS);
        double sum = 0.0;
        for (double v : w) sum += v;
        if (sum > 1e-15) {
            for (int i = 0; i < w.length; i++) w[i] /= sum;
        }
    }

    /** 纯陆地形态 eLand（侵蚀边际采样用，不含气候/分类）。 */
    public double landE(double wx, double wz) { return sampleCore(wx, wz).eLand; }

    /** 地形类型连续权重（诊断探针按类型分桶统计用）。 */
    public double[] typeWeightsAt(double wx, double wz) { return sampleCore(wx, wz).typeWeights; }

    /**
     * 把新 P 层 delta 装配到 chunk cells：与 {@link #sampleWu} 共用同一增量施加逻辑。
     */
    public void extractFromTile(Cell[] cells, int chunkX, int chunkZ) {
        double hs = params.horizontalScale();
        double invHs = (hs > 0.01 && hs != 1.0) ? 1.0 / hs : 1.0;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                int bx = chunkX * 16 + lx, bz = chunkZ * 16 + lz;
                double wuX = bx * invHs, wuZ = bz * invHs;
                applyTileDelta(cell, wuX, wuZ); // 含侵蚀增量 + height 重算 + 陆地重分类
            }
        }
    }

    /** 生产唯一侵蚀增量：精确 P 请求，不做缓存窥探、不含旧 tile 的第二分支。 */
    public double erosionDeltaE(double wuX, double wuZ) {
        return processErosionDeltaE(wuX, wuZ);
    }

    /** 保留诊断签名；请求即返回与生产一致的精确 delta（不再有 empty 分支）。 */
    public java.util.OptionalDouble peekErosionDeltaE(double wuX, double wuZ) {
        return java.util.OptionalDouble.of(erosionDeltaE(wuX, wuZ));
    }

    /** 结构定位用的薄入口：明确只返回 B 高度，绝不触发 P tile 生成。 */
    public double sampleHeightNonBlocking(double wuX, double wuZ) {
        return morphBase ? baseWorld.height(wuX, wuZ) : sample(wuX, wuZ).height;
    }

    /** 群系快速路径：直接按精确 P delta 装配 cell，不再查询旧 tile 缓存。 */
    public boolean applyCachedTileDelta(Cell cell, double wuX, double wuZ) {
        applyProcessDelta(cell, erosionDeltaE(wuX, wuZ));
        cell.gradient = baseGradient(wuX, wuZ);
        return true;
    }

    /**
     * P 增量施加核心：e/height 更新 + NaN/Inf 守卫 + softCap + 陆地重分类。
     * applyTileDelta / applyCachedTileDelta / sampleWu 共用；坡度由调用方按 B 差分计算。
     */
    private void applyProcessDelta(Cell cell, double delta) {
        double newE = cell.e + delta;
        if (Double.isNaN(newE) || Double.isInfinite(newE)) newE = cell.e;
        double e = softCapLandE(newE);
        cell.e = e;
        cell.height = heightCurve.heightFromE(e);
        if (e >= 0) {
            TerrainClass ct = TypeLandShape.dominantFromWeights(cell.typeWeights);
            cell.terrainType = classifyTerrain(e, cell.eLand, ct, cell.temperature,
                cell.humidity, cell.typeWeights, cell.coastCoord, cell.landFeat);
        }
    }

    /** 装配增量并按基础地形差分计算坡度（B 差分，不依赖旧 tile postErosion 场）。 */
    private void applyTileDelta(Cell cell, double wuX, double wuZ) {
        applyProcessDelta(cell, processErosionDeltaE(wuX, wuZ));
        cell.gradient = baseGradient(wuX, wuZ);
    }

    private float baseGradient(double wuX, double wuZ) {
        double denom = 2.0 * Math.max(0.01, params.horizontalScale());
        return (float) Math.hypot(baseWorld.heightDifferenceX(wuX, wuZ) / denom,
                baseWorld.heightDifferenceZ(wuX, wuZ) / denom);
    }

    /** wu 语义完整采样（基础 sample + 唯一 P 增量），与 extractFromTile 同源。 */
    public Cell sampleWu(double wuX, double wuZ) {
        Cell cell = sample(wuX, wuZ);
        applyTileDelta(cell, wuX, wuZ);
        return cell;
    }

    /**
     * 仅采样侵蚀后高度，与 sampleWu().height 同源，跳过气候、分类和坡度计算。
     * 增量后的第二次 softCap 必须保留；height-only 探针覆盖真实压缩窗口及负对照。
     * 可触发侵蚀 tile 生成，不是非阻塞 peek。
     */
    public double erodedHeight(double wuX, double wuZ) {
        // ★ 2026-11-06【唯一实现】精确 P 提供 "B + 粒子侵蚀" 后的连续高度。
        if (processRuntime == null) {
            return morphBase ? baseWorld.height(wuX, wuZ) : baseHeightFallback(wuX, wuZ);
        }
        try {
            return processRuntime.exactP(wuX, wuZ).yP();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return morphBase ? baseWorld.height(wuX, wuZ) : baseHeightFallback(wuX, wuZ);
        } catch (java.util.concurrent.ExecutionException e) {
            LOGGER.warn("[PROCESS] erodedHeight exactP 失败于 ({},{})：{}", wuX, wuZ,
                    e.getCause() == null ? e.toString() : e.getCause().toString());
            return morphBase ? baseWorld.height(wuX, wuZ) : baseHeightFallback(wuX, wuZ);
        }
    }

    /** 旧 B 路径的基础高度（无物理侵蚀可用）。 */
    private double baseHeightFallback(double wuX, double wuZ) {
        double e0 = terrainEQuick(wuX, wuZ);
        return heightCurve.heightFromE(softCapLandE(e0));
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
            // ★ 2026-09-13 Phase T6：火山弧（离轴隆起）也是"海中的显著凸起"，
            //   语义上同属 SEAMOUNT（海底火山）。不新增枚举 —— 见 TerrainClass 说明。
            //   注意 oceanFeat.arc 是【带符号】剖面（>0 弧 / <0 海沟窄槽），故取正部判断。
            double arcAmp = oceanFeat != null ? Math.max(0.0, oceanFeat.arc) : 0;
            // ★★★ 2026-09-13 修复（用户反馈"大陆架好像并没有包住整个大陆"）★★★
            //   深度分带判据由 `oceanFeat.baseE`（c 场推算的海底基面）改为 **eLand**。
            //
            //   【为何是 bug】**海岸线是由 eLand 定义的**（e = eLand + 海洋特征，e=0 即海岸）。
            //   用 c 场推算的 baseE 分带 ⇒ 陆架带与真实海岸线**错位**：
            //   实测沿海海洋点仅 **52.2%** 被判 SHELF，且与构造体制无关
            //   （活动边缘 60.8% / 被动边缘 46.9% —— 若真是"活动边缘无陆架"的地质正确性，
            //    该比例应与构造强相关，实测反之 ⇒ 纯属两个场错位）。
            //   改用 eLand 后同一实测升至 **81.4%**（其余为海山/洋中脊优先 + 真属陆坡）。
            //
            //   【为何不破坏原注释的初衷】原用 baseE 是为了"不含特征增量，防止海山顶峰
            //   被抬升后自相矛盾地判成 SHELF"（见下方 2026-08-06 记录）。
            //   而 `eLand` **同样不含海洋特征增量**（features 在 oceanFeat.total 里单独叠加）
            //   ⇒ 该防线完整保留，同时海岸线对齐。两全。
            //
            //   【分带随之成为沿岸等深带】地质正确的陆架→陆坡→深海平原序列：
            //     eLand > −0.08           → 大陆架
            //     −0.18 < eLand ≤ −0.08   → 海洋（陆坡 / 浅海）
            //     eLand ≤ −0.18           → 深海（深海平原）
            //   （2026-08-06 旧记录：原用逐点 e 会自相矛盾 —— 海山顶峰 e>−0.08 被判 SHELF，
            //     探针实测 SEAMOUNT 仅 11 个。改用 eLand 后该问题不复现，因它不含特征增量。）
            // SEAMOUNT 判定优先于 RIDGE（海山独立特征，避免被洋中脊抢走）。
            if ((seamountAmp > 0.02 || arcAmp > 0.02) && eLand < -0.08) return TerrainClass.SEAMOUNT;
            // 2026-08-06 调稀洋中脊：阈值 0.03→0.05（用户反馈"洋中脊有点多"）——边缘弱贡献区
            // 归入 OCEAN/DEEP_OCEAN，仅脊线主体保留 RIDGE 类型（地形抬升不受影响）。
            if (ridgeAmp > 0.05 && eLand < -0.08) return TerrainClass.SUBMARINE_RIDGE;
            if (eLand > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return eLand < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // 【2026-08-03 用户决策】BEACH 不再作为独立地形类型（沙滩是海岸过渡带而非地形形态）：
        // 海岸窄条自然落入后续陆地类型（PLAIN/HILLS 等），群系层面仍由 BiomeClassifier 按气候映射。

        // 火山优先（可见特征，用户核心诉求：陆地需有火山地形）。
        TerrainClass vol = volcanoClass(landFeat);
        if (vol != null) return vol;

        // 【2026-08-05 用户决策】PEAK 不再作为独立地形类型（山峰=山脉的高海拔部分，
        // 与 BEACH/SNOW 同为状态/过渡而非独立形态）：高海拔山地直接落入 MOUNTAINS
        // （typeWeights dominant），雪峰/尖峰群系由 BiomeClassifier 按 cell.e>0.60 映射，不丢失。
        return cellType;
    }

    /**
     * ★ 2026-09-13 修复（用户反馈"火山只在大范围预览显示，关掉就没有了"）：
     * <b>火山优先判定</b>（主 {@link #classify} 与侵蚀后静态重分类<b>共用</b>）。
     *
     * <p><b>为何要抽成共用函数</b>：原先 {@code classify} 有这段判定，而
     * {@code classifyTerrain}（侵蚀回写后调用）<b>没有</b>，且其签名里根本没有
     * {@code landFeat} 参数 ⇒ 侵蚀 tile 生成后（近距离，预览放大时）该区块的
     * {@code VOLCANO/VOLCANIC_FIELD} 被<b>静默重分类</b>成底层类型（PLAIN/MOUNTAINS…）
     * ⇒ 用户看到的"远处有火山、走近就没了"。
     * 抽成一处可保证<b>两个入口的阈值永不漂移</b>。</p>
     *
     * @return 火山类型；非火山返回 {@code null}（调用方继续后续判定）
     */
    private static TerrainClass volcanoClass(LandFeatures.FeatureResult landFeat) {
        if (landFeat == null) return null;
        // ★ 2026-09-13 修复（用户反馈"火山中间竟然是旁边的地形"）：
        //   判据由【净抬升 single/field（含火口下凹）】改为【山体掩码 singleEdifice/fieldEdifice
        //   （不扣火口）】。原因：火口在中心下凹 0.02~0.07 e，当 amp 较小时
        //   `single(中心) = amp − crater` 会跌破阈值（实测 amp=0.12, crater=0.07
        //   → 恰好 0.05，严格大于不成立）⇒ 中心被判成周围类型，火山只剩一圈"环形"。
        //   而地质上<b>火口内部仍属这座火山</b>（火山口湖亦是火山的一部分）⇒ 应按"山体覆盖"判。
        //
        // ★ 2026-09-13 阈值修正（用户反馈"火山类型包不全火山的山体"）：
        //   原阈值 0.05 / 0.04 是**任意常数**，与"地形上看得见"没有关系。
        //   陆地 1 e = (maxY−seaLevel)×peakFraction = 257×0.92 ≈ 236 格 ⇒
        //   0.05 e ≈ 11.8 格 —— 远高于"可见"的门槛（~2 格 ≈ 0.0085 e）。
        //   后果：山体外缘（抬升 2~11 格、肉眼明显是火山锥面）被判成周围类型。
        //   现按【可见地形】定阈值：0.010 e ≈ 2.4 格 ⇒ 类型覆盖≈可见山体。
        if (landFeat.singleEdifice > 0.010) return TerrainClass.VOLCANO;
        if (landFeat.fieldEdifice > 0.010) return TerrainClass.VOLCANIC_FIELD;
        return null;
    }

    /**
     * 静态分类方法（侵蚀回写后重分类用）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature, double humidity) {
        return classifyTerrain(ne, eLand, cellType, temperature, humidity, null, 0.0, null);
    }

    /**
     * 静态分类方法（带连续类型权重 + coastCoord 海岸约束）。
     * PEAK/SNOW 使用 typeWeights 连续阈值，避免离散 argmax 跳变。
     * 海洋区域按深度细分：大陆架 / 海洋 / 深海（无特征分量，仅靠 e）。
     *
     * <p>★ 2026-09-13 新增 {@code landFeat} 参数：侵蚀回写后必须与 {@link #classify}
     * 用<b>同一套火山判定</b>，否则火山类型会在侵蚀 tile 生成后被抹掉（见
     * {@link #volcanoClass} 注释）。传 {@code null} 表示"该调用点不掌握火山信息"
     * （退化为不判火山，与旧行为一致）。</p>
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double humidity,
                                                double[] typeWeights,
                                                double coastCoord,
                                                LandFeatures.FeatureResult landFeat) {
        if (ne < 0.0) {
            if (ne > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return ne < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // 【2026-08-03 用户决策】BEACH/SNOW 不再作为独立地形类型（见主 classify 注释）。
        // ★ 火山优先（与主 classify 一致）——修复"侵蚀后火山消失"。
        TerrainClass vol = volcanoClass(landFeat);
        if (vol != null) return vol;
        // 【2026-08-05 用户决策】PEAK 不再独立分类（山峰=山脉高海拔部分，群系由
        // BiomeClassifier 按 cell.e 阈值映射），此处直接取 typeWeights 主导类型。
        if (typeWeights != null && typeWeights.length >= TerrainClass.COUNT) {
            return TypeLandShape.dominantFromWeights(typeWeights);
        }
        return cellType;
    }

    /** 兼容旧签名的重载（不判火山）。 */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double humidity,
                                                double[] typeWeights,
                                                double coastCoord) {
        return classifyTerrain(ne, eLand, cellType, temperature, humidity,
                typeWeights, coastCoord, null);
    }

    // ===== 内联工具 =====

    /**
     * 降水合成（★ 2026-09-11 Phase B）。
     *
     * <pre>
     * precip = humidity01        // 水汽供给（湿度 [−1,1] → [0,1]）
     *        × LatPrecipProfile   // 纬度结构（ITCZ 峰 / 副热带谷 / 西风带次峰 / 极地低）
     *        × (1 + 地形雨)        // 迎风坡抬升凝结
     *        × (1 − 雨影)          // 背风坡减雨
     *        × tempGate           // 冷空气持水能力低（下限 0.55，不归零）
     * </pre>
     *
     * <p><b>Phase B 只"产出"降水</b>（写入 {@link Cell#precipitation} 与
     * {@link com.geogenesis.worldgen.climate.Climate#precipitation}）：
     * <b>尚未驱动水文</b>（Phase C 才把汇流累积改为受降水加权），
     * 也<b>尚未参与 Whittaker 群区</b>（Phase D 才切换，避免群系立刻漂移）。</p>
     *
     * @return 归一化降水 [0, 1.5]
     */
    private static double precipitationFrom(double humE, double tempE, double lat01,
                                            PrecipField.Mod pm) {
        double humidity01 = clamp(humE * 0.5 + 0.5, 0.0, 1.0);
        double base = humidity01 * LatPrecipProfile.at(lat01);
        double tempGate = 0.55 + 0.45 * ((tempE + 1.0) * 0.5);   // 冷 → 0.55，热 → 1.0
        double v = base * (1.0 + pm.orographicGain()) * (1.0 - pm.shadowLoss()) * tempGate;
        return clamp(v, 0.0, 1.5);
    }

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

    /**
     * 抖动后的主导地形类型（<b>仅用于群系变体选择</b>，见 {@code Cell.variantTerrain}）。
     *
     * <p>地形类型场是两个 Voronoi 站点高斯权重相等处 → 边界为<b>直线段</b>（站点上下排列时
     * 即长水平线，实测达 424 wu）。若群系变体直接按 {@link Cell#terrainType} 切换，
     * 群系边界就沿这条直线走。此处对每类权重叠加独立高频噪声后取最大：
     * 边界附近两类权重接近，噪声使主导类型随机翻转 → 变体边界被打散。
     *
     * @param weights 5 类地形权重（按 {@link TerrainClass#ordinal()} 索引，和=1）
     */
    private TerrainClass ditheredTerrain(double[] weights, double x, double z) {
        if (weights == null || weights.length == 0) return null;
        TerrainClass[] all = TerrainClass.values();
        int best = -1;
        double bestW = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < weights.length && i < all.length; i++) {
            // 每类独立噪声（坐标加不同偏移），波长 ≈ 区尺寸/3 ≈ 128 wu（2026-09-10：调大，变体斑块更完整）
            double dith = regionWarp.compute(x * 3.0 + i * 733.0, z * 3.0 - i * 1499.0) * 0.12;
            double w = weights[i] + dith;
            if (w > bestW) {
                bestW = w;
                best = i;
            }
        }
        return best >= 0 ? all[best] : null;
    }

    /**
     * 气候区边界的 3 倍频扰动位移（wu）。
     * 用同一噪声按坐标倍频叠加（1、2、4 倍），省掉额外噪声实例；
     * 幅值 0.25/0.12/0.06 × regionSize，合计最大约 0.43 个区尺度。
     */
    private double regionWarpOffset(double x, double z) {
        double o = regionWarp.compute(x, z) * 0.25
                 + regionWarp.compute(x * 2.0, z * 2.0) * 0.12
                 + regionWarp.compute(x * 4.0, z * 4.0) * 0.06
                 + regionWarp.compute(x * 8.0, z * 8.0) * 0.04
                 + regionWarp.compute(x * 16.0, z * 16.0) * 0.02;
        return o * regionSize;
    }

    /**
     * 区域层温度：纬度基值 + 区域级扰动。区界处与次近区混合 → C0 连续。
     */
    private double regionTemperature(ClimateRegion.Sample reg) {
        double t1 = temperatureAt(reg.centerX(), reg.centerZ());
        if (reg.blend() <= 0.0) return t1;
        double t2 = temperatureAt(reg.centerX2(), reg.centerZ2());
        return t1 + (t2 - t1) * reg.blend();
    }

    /** 单点温度基值：纬度梯度（赤道 +1 → 两极 −1）+ 温度扰动噪声 */
    private double temperatureAt(double x, double z) {
        double lat = Latitude.latitude01(z, 1.0 / tempFreq);
        return (1.0 - 2.0 * lat) + tempWarp.compute(x, z) * 0.15;
    }

    /** 区域层湿度：区域噪声，区界处与次近区混合 → C0 连续 */
    private double regionMoisture(ClimateRegion.Sample reg) {
        double m1 = humidityNoise.compute(reg.centerX(), reg.centerZ());
        if (reg.blend() <= 0.0) return m1;
        double m2 = humidityNoise.compute(reg.centerX2(), reg.centerZ2());
        return m1 + (m2 - m1) * reg.blend();
    }

    /**
     * 海拔递减率（参考 FreeTerraForged 的 ClimateModule.modifyTemp）。
     * e≤0.35 基本不降温，0.35→0.65 线性增强，e≥0.65 达到最大 0.16。
     * 逐格生效 → 山体保有真实垂直气候分异，群系因而"看得见地形"。
     */
    private static double elevationTempDrop(double e) {
        if (e <= 0.35) return 0.0;
        if (e >= 0.65) return 0.16;
        return 0.16 * (e - 0.35) / 0.30;
    }

    /**
     * 高海拔/山地雨影导致的减湿：e≤0.25 无影响，0.25→0.60 线性增强，最大 0.20。
     *
     * <p>【2026-09-10】原先用类型权重 {@code montW} 驱动（{@code -montW*0.25}），
     * 但地形类型权重在类型边界处变化很快 → 相邻格湿度可跳变 0.2 以上，
     * 直接跨过 Whittaker 的中间分区（探针实测违例）。改用连续高程 e 后同一效应平滑得多。
     */
    private static double elevationMoistureDrop(double e) {
        if (e <= 0.25) return 0.0;
        if (e >= 0.60) return 0.20;
        return 0.20 * (e - 0.25) / 0.35;
    }

    /**
     * 大陆性对湿度的调制（参考 FreeTerraForged 的 ClimateModule.modifyMoisture）。
     * 海洋/沿海 → 拉向湿润；深内陆 → 拉向干燥（大陆性才是湿度主控，噪声只提供区域差异）。
     *
     * @param hum       区域级湿度 [-1,1]
     * @param continent 大陆性 [-1,1]（负=海洋，0=海岸，正=内陆）
     */
    private static double continentMoisture(double hum, double continent) {
        double c01 = clamp(continent * 0.5 + 0.5, 0.0, 1.0); // 0=深海 0.5=海岸 1=深内陆
        double m01 = hum * 0.5 + 0.5;
        double limit = 0.5;                                   // 海岸线位置
        if (c01 < limit) {
            double k = (limit - c01) / limit * 0.55;          // 海洋侧：最多把湿度拉向 1 的 55%
            m01 += (1.0 - m01) * k;
        } else {
            double k = (c01 - limit) / (1.0 - limit) * 0.55;  // 内陆侧：最多拉向 0 的 55%
            m01 -= m01 * k;
        }
        return clamp(m01 * 2.0 - 1.0, -1.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 陆地 e 软钳制（实际地形 < 理论峰顶铁律）：
     * 类型高端 maxLandHi（默认 0.95 → 理论峰顶 Y≈288）之上渐近压缩、永不触顶，
     * 实际 e 恒低于 maxLandHi，HeightCurve 的 clamp(e×verticalScale,0,1) 永不触发 → 无压平山尖、
     * 不超配置地形图表的理论最高高度。海洋侧（e≤0）不受影响（clamp 下界 -1 保底）。
     */
    private double softCapLandE(double e) {
        if (e <= 0.0) return Math.max(-1.0, e);
        double maxLandHi = params.splineConfig().maxLandHi();
        // 2026-08-01：压缩窗口起点 0.92→0.97×maxLandHi（0.874→0.922）。
        // 原起点太贴上限：山峰自然分布 p99≈0.8745（MOUNTAINS p99=0.89）落在窗口内 →
        // 峰尖被压平（用户"山脉碰生成上限"观感，HeightHitProbe 实测 max=0.9044 且 2% 进入压缩区）。
        // 0.97 后 99% 山峰自然尖峰保留，仅超设计极限（>0.92）的极端叠加兜底压缩。
        double softStart = maxLandHi * 0.97;
        if (e <= softStart) return e;
        double cap = maxLandHi * 0.98;       // 实际天花板（默认 0.931），低于理论 0.95
        double span = cap - softStart;
        return softStart + span * (1.0 - 1.0 / (1.0 + (e - softStart) / span));
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 安全读取 Forge 配置值（委托公共门面 {@link com.geogenesis.config.ConfigSafe}）。
     * 游戏内（config 已加载）→ 正常返回 toml 值；独立预览/探针（config 未加载运行时）
     * 的 {@code ConfigValue.get()} 会抛 IllegalStateException，此时回退到代码默认值。
     * 仅在配置未加载时改变行为，不影响游戏内结果。
     */
    private static double cfgDbl(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fallback) {
        return com.geogenesis.config.ConfigSafe.dbl(v, fallback);
    }

    private static boolean cfgBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean fallback) {
        return com.geogenesis.config.ConfigSafe.bool(v, fallback);
    }

    /** 采样大陆性快捷接口 */
    public double sampleContinent(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** HeightCurve 引用 */
    public HeightCurve heightCurve() { return heightCurve; }

    /** TypeLandShape 引用 */
    public TypeLandShape typeLandShape() { return typeLandShape; }

}
