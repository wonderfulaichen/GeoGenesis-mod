package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.CrustField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.OceanFeatures;
import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.tectonic.TectonicTrend;

/**
 * <b>morph 地形的唯一入口</b>：{@code sampleE(wuX, wuZ)} 给出 e，{@code height} 给出世界 Y。
 *
 * <h3>用法</h3>
 * <pre>
 *   TerrainParams params = ...;                       // 复用项目现有的参数记录
 *   MorphTerrainSampler s = new MorphTerrainSampler(seed, params, -64, 320);
 *   double e = s.sampleE(wuX, wuZ);                   // e ∈ [−1, +1]，0 = 海平面
 *   double y = s.height(wuX, wuZ);                    // = curve.heightFromE(sampleE(wuX, wuZ))
 * </pre>
 *
 * <h3>★ 坐标单位是 <b>wu</b>，不是块</h3>
 * <p>两个方法的入参都是<b>世界单位（wu）</b>，与 {@link ContinentField#sample} 同一口径。
 * 本项目把"块 → wu"的除法收在门面层（{@code GeoGenesisTerrain.toWu}：{@code block / horizontalScale}，
 * {@code HS=1} 时恒等），本类<b>不</b>做这个换算，也不知道 {@code horizontalScale} 是多少。
 * 调用方必须<b>先</b>换算再进来 —— 直接把块坐标传进来不会报错，只会让地形整体缩小
 * {@code horizontalScale} 倍，而且看起来仍然"像正常地形"。</p>
 *
 * <h3>★ {@code height} 不做额外换算</h3>
 * <p>{@code height(x,z)} <b>就是</b> {@code HeightCurve.heightFromE(sampleE(x,z))}：
 * 曲线由本类持有（{@code params} + {@code minY/maxY} 构造），调用方不需要（也不应该）
 * 再乘一次 {@code verticalScale}、再加一次 {@code seaLevel}。重复换算会让地形整体错位，
 * 而且错得很"像"正常地形，很难发现。</p>
 *
 * <h3>★ 一条链（与正典图同源）</h3>
 * <pre>
 *   ContinentField.seed(seed)                  ← 大陆性 c ∈ [−1,1]
 *   MorphCatalog(seed)                          ← 18 条目的族种子 = seed + 固定偏移
 *   MorphAssembly.of(seed)                      ← 分区 / 45 票槽 / 山形遮罩 / 台地覆盖
 *   MorphLandField(catalog, assembly, scale)    ← 陆地 morph 场（tile 局部 Voronoi 几何）
 *   MorphOceanAssembler(land::e, continent::sample, seed + 3).enableCoastalDetail(seed)
 *   HeightCurve(params, minY, maxY)
 * </pre>
 * <p>顺序与种子偏移必须与正典一致：{@code +3} 是深海噪声、{@code enableCoastalDetail(seed)}
 * 用<b>原</b>种子（不是 +3）。任何一处偏移改动都会换图。</p>
 *
 * <h3>★ 海岸线位置<b>不是</b> {@code c = 0}</h3>
 * <p>这是最容易误读的一点。{@code c = 0} 只是大陆场的零点，<b>不等于</b>实际海岸线。
 * {@link MorphOceanAssembler} 会把 {@code c} 线性映射成
 * {@code edge = (c + 1) / 2}，再按三条控制点把 {@code edge} 分成"深海 → 浅陆架 → 岸段"，
 * 岸段内部还叠了随陆高增长的岸宽与 cliff 因子。于是：</p>
 * <ul>
 *   <li><b>水面位置</b>（{@code e = 0}）由<b>岸段剖面的零点</b>决定，与 {@code c = 0} 有一处
 *       与陆高相关的偏移；</li>
 *   <li>要判"这里是不是海"，请读 {@link #sea}（它读<b>实际高度</b>，不是阈值）；</li>
 *   <li>要判陆地/海洋的粗分，{@code sampleE > 0} 才是权威判据。</li>
 * </ul>
 *
 * <h3>★ 线程模型</h3>
 * <p>{@link MorphLandField} 内部有 tile LRU 与 last-point 缓存，全部由实例监视器保护，
 * 并发调用者<b>互相阻塞（互斥）</b>。{@link ContinentField} 与
 * {@link MorphOceanAssembler} 的噪声节点是纯函数，可安全并发读。
 * <b>本类不提供取消 API</b>：无法中断进行中的 tile 构建。</p>
 *
 * <h3>★ 未证事项（必须知道）</h3>
 * <ul>
 *   <li>world 折线来自反 warp 牛顿解，残差小<b>不证明</b> {@code (x,z)↦(u,v)} 是单射
 *       ⇒ 世界几何是<b>近似</b>，"无限世界"未被证明正确。</li>
 *   <li>本类只做地形 <b>e/Y</b>：不含洞穴、矿脉、含水层、群系、装饰、结构与水文。
 *       把它接进 {@code CellGenerator} 之前，那些系统都还悬空。</li>
 * </ul>
 */
public final class MorphTerrainSampler {

    /** 陆地场默认区域尺度（块），与参考预设 {@code terrainRegionSize} 同值。 */
    static final float REGION_SCALE = 1200f;
    /** 深海噪声的种子偏移（与正典口径一致）。 */
    static final long OCEAN_SEED_OFFSET = 3L;

    private final long seed;
    /** 地形参数（{@code landFactorAt} 需用 {@code continentBias}）。 */
    private final TerrainParams params;
    private final MorphCatalog catalog;
    private final MorphAssembly assembly;
    private final MorphLandField land;
    private final ContinentField continent;
    private final TectonicField tectonic;
    private final TectonicTrend trend;
    private final MorphOceanAssembler ocean;
    private final HeightCurve curve;
    /**
     * ★ 阶段六（2026-11-17）：火山/熔岩台地特征（陆侧）。几何代码本就完整，
     * 此前只是缺接线（2026-11-06 重构 CellGenerator 时丢失）。
     */
    /** ★ 阶段六：海山 / 洋中脊 / 弧沟特征（洋侧）。 */
    private final OceanFeatures oceanFeatures;

    /**
     * ★ 构造特征开关（唯一读取点）——{@code true}（默认）时海山/洋中脊
     * （{@link OceanFeatures}）与火山（{@code MorphAssembly} 引用本常量）参与合成；
     * false = 两者全关，逐位复现开火前的基准。
     *
     * <p>开关四铁律自查：① {@code geogenesis.tectonicFeatures} 全仓仅此处读取
     * （MorphAssembly 为保持「同一属性同一值」，直接引用本常量，不重复读取）；
     * 默认开（{@code !"false".equals} 惯用法）；② 单属性单语义（构造特征层整体
     * 开关，海侧/陆侧两类特征一起进出）；③ 本注释与代码同源；
     * ④ 产出变更已升 CACHE_SCHEMA_VERSION（当前 137）。</p>
     */
    static final boolean TECTONIC_FEATURES =
            !"false".equals(System.getProperty("geogenesis.tectonicFeatures"));

    /**
     * 海床守护余量（e）：洋侧特征抬升的软上限 = {@code −eLand − SEABED_MARGIN}。
     *
     * <p>基线教训（{@code build/terrain-upgrade/baseline} 的 CellGenerator 注释）：
     * 不加守护时洋中脊/弧会<b>凭空长出 22/17 座不该有的岛</b>。深海处上限远大于
     * 特征幅度 ⇒ 完全不生效（形态不变），只在 {@code eLand≈0} 的矛盾点压缩。</p>
     */
    private static final double SEABED_MARGIN = 0.02;
    /** {@code smoothMin} 的软饱和宽度（e），C¹ 避免折痕。 */
    private static final double SEABED_SOFT_SAT = 0.03;

    /**
     * ★ 阶段五（2026-11-16）：构造趋势开关——{@code true}（默认）时板块边界
     * 通过 {@link TectonicTrend} 影响陆地地势趋势（会聚隆起/张裂凹陷）；
     * 仅 A/B 对照与回退时置 false（false 精确复现 schema134）。</p>
     *
     * <p>开关四铁律自查：① {@code geogenesis.tectonicTrend} 全仓仅本一处读取，
     * 默认开（{@code !"false".equals} 惯用法）；② 单属性单语义；
     * ③ 本注释与代码同源；④ 产出变更已升 CACHE_SCHEMA_VERSION（134→135）。</p>
     */
    private static final boolean TECTONIC_TREND =
            !"false".equals(System.getProperty("geogenesis.tectonicTrend"));

    /**
     * ★ 桥接 S2（2026-11-13）：深海基准开关——{@code true}（默认）时海床
     * {@code deep} 来源从 Perlin 噪声切到 {@link CrustField}.eBase（构造物理量）；
     * 仅 A/B 对照与回退时置 false。
     *
     * <p>开关四铁律自查：① {@code geogenesis.crustOceanEBase} 全仓只有本一处读取，
     * 默认开（{@code !"false".equals} 惯用法，与 {@code geogenesis.baseSampleCache} 同款）；
     * ② 一个属性只表达这一件事；③ 本注释与代码同源；④ 产出变更已升
     * {@code PreviewDisplay.CACHE_SCHEMA_VERSION}（129→130）。</p>
     */
    private static final boolean CRUST_OCEAN_EBASE =
            !"false".equals(System.getProperty("geogenesis.crustOceanEBase"));

    /**
     * ★ 阶段二（2026-11-14）：陆缘剖面——<b>主动陆缘削减</b>的幅度（格）。
     *
     * <p>2200 地质米 × (127 格/e ÷ 11000 m/e) = 25.4 格：主动陆缘（俯冲带邻近）的
     * 陆架控制点从 −7 格压到 ≈−32 格 ⇒ 海岸直接临 2~4km 深水（无陆架）。
     * 被动陆缘（margin=0）保持 −7 格（地球陆架 <200m）。</p>
     */
    static final double ACTIVE_SHELF_DROP_BLOCKS = 2200.0 / 11000.0 * 127.0;

    /**
     * 主动陆缘因子 0~1：会聚（stress 正叶瓣）× 离边界近（distRaw 衰减）。
     *
     * <p>对照沙箱标定（audit 第十轮）：MARGIN_SCALE=90wu（≈640km，
     * "离会聚边界一个陆壳宽度"）——曾取 260wu 覆盖大半个大陆 ⇒ 陆架全被削。</p>
     */
    static double marginFactor(TectonicField.Sample ts) {
        double gate = smoothstep(0.25, 0.85, ts.stress());
        double decay = Math.exp(-(ts.distRaw() * ts.distRaw()) / (90.0 * 90.0));
        return gate * decay;
    }

    /** Hermite smoothstep（本地副本，与 CrustField/LithoPhysical 同式）。 */
    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * @param seed  世界种子
     * @param params 地形参数（由 {@code GeoGenesisConfig.buildParams()} 或
     *               {@link TerrainParams#defaults()} 提供；本类不读配置、不持有静态状态）
     * @param minY  世界最小 Y（仅用于 {@code height} 的映射）
     * @param maxY  世界最大 Y（同上）
     */
    public MorphTerrainSampler(long seed, TerrainParams params, int minY, int maxY) {
        this(seed, params, minY, maxY, REGION_SCALE);
    }

    /**
     * 可指定区域尺度的构造器：尺度越小 ⇒ Voronoi 类型格越密、地貌斑块越碎。
     *
     * @param regionScale 区域尺度（块），必须 &gt; 0
     */
    public MorphTerrainSampler(long seed, TerrainParams params, int minY, int maxY, float regionScale) {
        if (params == null) throw new IllegalArgumentException("params is required");
        if (!(regionScale > 0) || !Float.isFinite(regionScale)) {
            throw new IllegalArgumentException("region scale must be finite and > 0: " + regionScale);
        }
        this.seed = seed;
        this.params = params;

        this.catalog = new MorphCatalog(seed);
        this.assembly = MorphAssembly.of(seed);

        // 大陆性与正典同源：同一个 ContinentField 实现、同一个 seed、同一个 params。
        this.continent = new ContinentField(params);
        this.continent.seed(seed);

        // ★ 桥接 S2（2026-11-13）：构造场自持实例——与 BaseWorldSampler.tectonic 同种子，
        //   确定性纯函数 ⇒ 两份实例逐位等价（R3 双 sampler 经同一构造路径自动覆盖）。
        this.tectonic = new TectonicField(seed);

        // ★ 阶段五（2026-11-16）：构造趋势注入——板块边界 → 整体地势趋势。
        //   必须在 land 之前建：趋势回调要同时读 tectonic 与 continent。
        //   开关 false ⇒ trendFn=null ⇒ land 与 schema134 逐位相同。
        //
        // crust01 由本 sampler 现算（持有 ContinentField，是唯一权威来源）；
        // MorphLandField 传 NaN 表示"请回调自负其责"。
        this.trend = TECTONIC_TREND ? new TectonicTrend(tectonic) : null;
        MorphLandField.TrendFn trendFn = trend == null ? null
                : (x, z, ignored) -> trend.trend(x, z, crust01Of(x, z));
        this.land = new MorphLandField(catalog, assembly, regionScale,
                new MorphKernel(MorphLandRecipe.all(catalog), catalog.seed(), true), trendFn);
        // ★ 阶段六修正（2026-11-17）：火山中心的陆上门控——接 ContinentField 原始 c。
        //   为什么不用 covered/landE：生产的陆海汇合在 MorphOceanAssembler（edge 坐标，
        //   edge=(c+1)/2），edge > CP_INLAND(0.502) 才完全取陆地层；混合带内锥体被海岸
        //   剖面压向海床（门控缺位实测 ~90% 火山不可见）。门控收到全可见线：c > 0.004。
        //   （VolcanoShapeProbe 会断言每座火山锥顶 sampleE > 0——门控失守立刻红。）
        this.land.setVolcanoLandProbe(continent::sample);

        // 深海噪声用 seed+3（正典口径）；近岸细节噪声用原 seed。
        // ★ 桥接 S2：开关默认开时注入 CrustField.eBase（e 量纲）接管海床 deep 来源（A3：
        //   三段剖面逐位保留，只换 deep）；null = 旧 Perlin 路径（A/B 与回退）。
        this.ocean = new MorphOceanAssembler(land::e, continent::sample, seed + OCEAN_SEED_OFFSET)
                .enableCoastalDetail(seed)
                .withCrustProfile(CRUST_OCEAN_EBASE
                        ? (x, z) -> {
                            var ts = tectonic.sample(x, z);
                            var cs = CrustField.of(ts, x, z, seed);
                            // 深海锚：平台模型 eBase（e 量纲）→ 格，钳到浅水锚之上（S2 近似保留）
                            // 浅水锚：−7 格 − 主动陆缘削减（margin × 25.4 格 = 2200 地质米）
                            //   margin = smoothstep(0.25,0.85,stress)·exp(−(distRaw/90)²)
                            //   （阶段二陆缘剖面：主动陆缘没有陆架——海岸直接临 2~4km 深水）
                            double shallowEff = MorphOceanAssembler.shallowBlocks()
                                    - marginFactor(ts) * ACTIVE_SHELF_DROP_BLOCKS;
                            double deep = Math.min(cs.eBase() * MorphOceanAssembler.BLOCKS_PER_E_SEA,
                                    shallowEff);
                            return new MorphOceanAssembler.CrustAnchors(deep, shallowEff);
                        }
                        : null);

        this.curve = new HeightCurve(params, minY, maxY);

        // ★ 阶段六修正（2026-11-17）：只保留<b>洋侧</b>特征接线（海山/洋中脊/弧沟）。
        //   陆侧火山已归位到<b>类型层</b>（{@code VolcanoRecipe} 覆盖族 +
        //   {@code MorphLandField} 的火山 overlay）——LandFeatures 是"外挂特征层"，
        //   尺度与类型系统不匹配（网格 800wu / 半径 140~300wu / 高 47~99 格），
        //   用户裁定为架构错误。
        //
        //   洋侧保留的理由：海山/洋中脊属于洋底几何（MorphOceanAssembler 的职责域），
        //   且海洋内容已由用户裁定"够了"——用特征方式注入是既有已验收的做法。
        if (TECTONIC_FEATURES) {
            this.oceanFeatures = new OceanFeatures();
            this.oceanFeatures.seed(seed);
            // 海山中心深度门控：中心必须够深（e ≤ −0.20）才生成，否则浅海会长疙瘩
            this.oceanFeatures.setSeamountDepthChecker((x, z) -> ocean.e(x, z));
        } else {
            this.oceanFeatures = null;
        }
    }

    /** 陆壳度 ∈[0,1]：由大陆场 c ∈[−1,1] 换算（0.5 = 海岸）。 */
    private double crust01Of(double x, double z) {
        return (continent.sample(x, z) + 1.0) * 0.5;
    }

    // ============================================================ 主查询
    /**
     * 世界点的高度场值 {@code e ∈ [−1, +1]}（0 = 海平面，负 = 水下）。
     *
     * <p><b>入参单位 = wu</b>（与 {@link ContinentField#sample} 同一口径），<b>不是块</b>。
     * 块 → wu 的除法在门面层（{@code GeoGenesisTerrain.toWu}），必须在<b>调用本方法之前</b>
     * 完成 —— 本类不知道 {@code horizontalScale}，也不该知道。</p>
     *
     * <p>⚠ {@code e > 0} 才是"陆地"的权威判据。{@code c = 0} <b>不是</b>海岸线：
     * 装配器把 {@code c} 映射成 {@code edge = (c+1)/2} 后再走三段剖面，
     * 水面位置与 {@code c = 0} 之间存在随陆高变化的偏移（见类注释）。</p>
     */
    public double sampleE(double wuX, double wuZ) {
        double eBase = ocean.e(wuX, wuZ);
        if (oceanFeatures == null) {
            return eBase;   // 关特征：逐位复现 schema135
        }
        // 洋侧：海山/洋中脊/弧沟（陆侧火山在 MorphLandField 的火山覆盖层内）
        TectonicField.Sample ts = tectonic.sample(wuX, wuZ);
        OceanFeatures.FeatureResult of = oceanFeatures.compute(
                wuX, wuZ, Math.min(eBase, 0.0), eBase, ts, tectonic.oceanProfile(ts));
        // 海床守护：洋侧累计抬升不得把地形推过海平面（软限，C¹）
        double oceanFeatW = of.total * (1.0 - oceanWeightAt(wuX, wuZ));
        if (oceanFeatW > 0.0) {
            double cap = -eBase - 0.02;
            oceanFeatW = cap <= 0.0 ? 0.0 : smoothMin(oceanFeatW, cap, 0.03);
        }
        return Math.max(-1.0, Math.min(1.0, eBase + oceanFeatW));
    }

    /** 洋侧权重 ∈[0,1]：海陆过渡处特征自然淡出（基线同款 smoothstep）。 */
    private double oceanWeightAt(double wx, double wz) {
        double cBiased = continent.sample(wx, wz) - params.continentBias();
        return 1.0 - smoothstep(-0.01, 0.01, cBiased);
    }

    /** 软最小值（C¹）：与 {@code TectonicField.smoothMin} 同式。 */
    private static double smoothMin(double a, double b, double k) {
        if (k <= 1e-9) {
            return Math.min(a, b);
        }
        double h = Math.max(0.0, Math.min(1.0, 0.5 + 0.5 * (b - a) / k));
        return b + (a - b) * h - k * h * (1.0 - h);
    }

    /**
     * 世界点的高度 Y：{@code curve.heightFromE(sampleE(wuX, wuZ))}，<b>不做任何额外换算</b>。
     *
     * <p>入参单位同样是 <b>wu</b>。</p>
     */
    public double height(double wuX, double wuZ) {
        return curve.heightFromE(sampleE(wuX, wuZ));
    }

    // ============================================================ 分段查询

    /**
     * 陆地侧 e（<b>未经海洋装配</b>）：与 {@link #sampleE} 的区别只在
     * 海岸带被换成了陆地层的原始值。用于区分"陆地配方输出"与"海陆缝合结果"。
     */
    public double landE(double wuX, double wuZ) {
        return land.e(wuX, wuZ);
    }

    /**
     * 大陆性 {@code c ∈ [−1,1]}（负 = 偏海洋侧，正 = 偏陆地侧）。
     *
     * <p>⚠ 这只是大陆场的零点约定，<b>不是</b>海岸线：实际水面由
     * {@link MorphOceanAssembler} 的岸段剖面决定（见类注释）。判"是否水面以上"请用
     * {@link #sampleE} 的符号，判分段请用 {@link #sea}。</p>
     */
    public double continentality(double wuX, double wuZ) {
        return continent.sample(wuX, wuZ);
    }

    /** 海洋分段分类（读<b>实际高度</b>，不是固定 edge 阈值 —— 见装配器的类注释）。 */
    public MorphOceanAssembler.Sea sea(double wuX, double wuZ) {
        return ocean.sea(wuX, wuZ);
    }

    /** 覆盖层 select 值 ∈ [0,1]（0 = 纯区域地貌、1 = 纯山地）。 */
    public double mountainMask(double wuX, double wuZ) {
        return land.mask(wuX, wuZ);
    }

    /** 基础地貌列，分类只提供语义，绝不反馈高度。坐标仍是wu。 */
    public com.geogenesis.worldgen.core.BaseColumn sampleColumn(double x, double z) {
        var c = new com.geogenesis.worldgen.core.BaseColumn();
        // ★ 阶段六修正（2026-11-17）：e 直接走 sampleE（洋侧特征在 sampleE 内合成；
        //   陆侧火山在类型层 MorphLandField 的火山覆盖族内，随 land.e 一起产出）
        c.e = sampleE(x, z);
        c.height = curve.heightFromE(c.e);
        c.continent = continentality(x, z);
        c.landE = landE(x, z);
        c.coastCoord = (c.continent + 1.0) * 0.5;
        c.shape = c.landE * 2.0 - 1.0;
        c.typeWeights = new double[com.geogenesis.worldgen.terrain.TerrainClass.COUNT];
        if (c.e < 0.0) {
            // ★ 阶段六：海洋亚型（海山/洋中脊）——特征存在时优先判亚型
            var oceanSub = oceanFeatureClass(c.oceanFeat, c.e);
            c.terrainType = oceanSub != null ? oceanSub
                    : c.e > -0.08 ? com.geogenesis.worldgen.terrain.TerrainClass.CONTINENTAL_SHELF
                    : c.e < -0.18 ? com.geogenesis.worldgen.terrain.TerrainClass.DEEP_OCEAN
                    : com.geogenesis.worldgen.terrain.TerrainClass.OCEAN;
            c.typeWeights[c.terrainType.ordinal()] = 1.0;
            c.oceanWeight = 1.0;
            c.oceanE = c.e;
        } else {
            MorphLandField.Sample s = land.sample(x, z);
            for (int i = 0; i < s.weight().length; i++) {
                addType(c.typeWeights, catalog.entry(i), x, z, s.weight()[i] * (1.0 - s.alpha()));
            }
            c.typeWeights[com.geogenesis.worldgen.terrain.TerrainClass.PLATEAU.ordinal()] += s.alpha();
            // ★ 阶段六修正二（2026-11-17）：火山类型<b>平滑过渡</b>——不再 gate>0.10
            //   硬覆盖（用户："我们好像没做火山类型的平滑过渡"）。与台地同模式：
            //   把火山作为权重项放进 typeWeights 参与 argmax 竞争。
            //   · gate ≤0.10（锥体外）：无权重
            //   · gate ∈(0.10, 0.45)：smoothstep 0→1 缓升——边缘带与
            //     PLAIN/HILLS/MOUNTAINS 竞争 ⇒ 类型图/纹理由内向外渐变，无硬边
            //   · gate ≥0.45（锥体主体）：权重 1.5（票槽权重归一化后单类型 ≤1）
            //     ⇒ 核心必为 VOLCANO（几何与类型同源，VolcanoShapeProbe 守护）
            double volW = smoothstep(0.10, 0.45, s.volcanoGate());
            if (volW > 0.0) {
                c.typeWeights[com.geogenesis.worldgen.terrain.TerrainClass.VOLCANO.ordinal()]
                        += 1.5 * volW;
                // ★ 归一化回 Σ=1（权重分布契约，P1 断言 |Σ−1|<1e-6）：
                //   argmax 不受等比例缩放影响；无火山格 sum=1.0 ⇒ 除以 1 逐位不变。
                double sum = 0;
                for (double w : c.typeWeights) {
                    sum += w;
                }
                if (sum > 0) {
                    for (int i = 0; i < c.typeWeights.length; i++) {
                        c.typeWeights[i] /= sum;
                    }
                }
            }
            int best = com.geogenesis.worldgen.terrain.TerrainClass.PLAIN.ordinal();
            for (int i = 0; i < c.typeWeights.length; i++) {
                if (c.typeWeights[i] > c.typeWeights[best]) best = i;
            }
            c.terrainType = com.geogenesis.worldgen.terrain.TerrainClass.byId(best);
        }
        c.variantTerrain = c.terrainType;
        return c;
    }

    /**
     * 海洋亚型判定：海山 / 洋中脊。仅在够深（e &lt; −0.08）时生效——
     * 浅水/陆架不该被标成海山（真实海山都在深水）。
     */
    private static com.geogenesis.worldgen.terrain.TerrainClass oceanFeatureClass(
            OceanFeatures.FeatureResult of, double e) {
        if (of == null || e > -0.08) {
            return null;
        }
        if (of.seamount > 0.02) {
            return com.geogenesis.worldgen.terrain.TerrainClass.SEAMOUNT;
        }
        if (of.ridge > 0.05) {
            return com.geogenesis.worldgen.terrain.TerrainClass.SUBMARINE_RIDGE;
        }
        return null;
    }

    private static void addType(double[] out, MorphCatalog.Entry entry, double x, double z, double w) {
        if (w == 0.0) return;
        if (entry.isComposite()) {
            double alpha = entry.alpha(x, z);
            addType(out, entry.left(), x, z, w * (1.0 - alpha));
            addType(out, entry.right(), x, z, w * alpha);
            return;
        }
        var type = switch (entry.kind()) {
            case PLATEAU, BADLANDS -> com.geogenesis.worldgen.terrain.TerrainClass.PLATEAU;
            case MOUNTAIN -> com.geogenesis.worldgen.terrain.TerrainClass.MOUNTAINS;
            case LOW -> entry.index() <= 1 ? com.geogenesis.worldgen.terrain.TerrainClass.PLAIN
                    : com.geogenesis.worldgen.terrain.TerrainClass.HILLS;
        };
        out[type.ordinal()] += w;
    }

    // ============================================================ 元信息

    public long seed() { return seed; }

    /** 高度曲线（{@code e ↔ Y} 的唯一换算点）。 */
    public HeightCurve heightCurve() { return curve; }

    /** 陆地 morph 场（tile 缓存/权重查询/统计）。 */
    public MorphLandField landField() { return land; }

    /** 海陆装配器（深海噪声/近岸剖面/分类）。 */
    public MorphOceanAssembler oceanAssembler() { return ocean; }

    /** 大陆场（海岸线来源）。 */
    public ContinentField continentField() { return continent; }

    /** 18 条目目录（条目/票槽查询）。 */
    public MorphCatalog catalog() { return catalog; }
}
