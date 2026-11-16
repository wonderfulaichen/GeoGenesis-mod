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
    private final TerrainParams params;

    // 温度参数（v6 纬度梯度，2026-09-10 取代旧正弦锚点模型）
    private final double tempFreq;    // 温度纬度角频率 = 1/latitudeScale（由 TerrainParams 注入，可配置）
    private final Noise tempWarp;     // 温度噪声扰动
    private final Noise humidityNoise; // 独立湿度噪声
    /** 气候区（抖动 Voronoi）：区内温湿恒定，消除群系椒盐碎斑 */
    private final ClimateRegion climateRegion;
    /** 区界扰动噪声：让 Voronoi 边界蜿蜒（否则边界是直线/大块多边形） */
    private final Noise regionWarp;

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

    /** P1 is selected by the world's persisted algorithm, not by the erosion/river TOML flags. */
    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY, boolean morphBase) {
        this.morphBase = morphBase;
        // ★ 2026-11-06【无开关】物理粒子侵蚀是唯一实现。
        //   morphBase=false（旧 B）仍走老地形；但侵蚀本身不再有第二条调度路径。
        this.minWorldY = minWorldY;
        this.maxWorldY = maxWorldY;
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.params = p;

        this.tempFreq = 1.0 / p.latitudeScale();   // 纬度缩放可配置

        // 气候噪声（xz 缩放可配置）
        this.tempWarp = new Frequency(new Simplex(501), 1.0 / p.tempWarpScale());
        this.humidityNoise = new Frequency(new Simplex(502), 1.0 / p.humidityScale());
        this.climateRegion = new ClimateRegion(p.climateRegionSize());

        clearEqCache();   // terrainEQuick 缓存哨兵初始化
        // 波长 ≈1.5 个气候区：边界以该尺度蜿蜒，观感自然
        this.regionWarp = new Frequency(new Simplex(503), 1.0 / (p.climateRegionSize() * 1.5));
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

    /** 一次性播种所有噪声节点 */
    public void seed(long worldSeed) {
        baseWorld = new com.geogenesis.worldgen.core.BaseWorldSampler(
                worldSeed, params, (int) minWorldY, (int) maxWorldY,
                com.geogenesis.config.BaseWorldConfigAdapter.snapshot());
        initProcessErosion(worldSeed);
        continent.seed(worldSeed);
        clearEqCache();                   // ★ D13：地形 e 缓存随世界种子失效
        Noises.seedAll(tempWarp, worldSeed, 0);
        Noises.seedAll(humidityNoise, worldSeed, 0);
        Noises.seedAll(regionWarp, worldSeed, 0);
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

    /**
     * 采样单格完整数据 —— <b>唯一实现</b>：morph P1 元数据。
     *
     * <p>坐标语义 = wu（2026-08-10 wu 化）。</p>
     *
     * <p>★ 2026-11-07：legacy 链（{@code morphBase=false}）已整体删除。原方法内那段
     * 双轨气候 + Whittaker + 雪线 + {@code classify()} 代码<b>只对 legacy 生效</b>
     * （第一行 {@code if (morphBase) return ...} 让 morph 根本走不到），
     * 已随 {@code sampleCore} 一并移除。那些字段 morph 侧由
     * {@code MorphCellAdapter.from()} 从 {@code BaseColumn} 一次填满
     * （含 temperature / humidity / precipitation / biomeType / snowLineE / isSnow /
     * terrainType / variantTerrain / 岩层字段）。</p>
     */
    public Cell sample(double wx, double wz) {
        return com.geogenesis.worldgen.terrain.morph.MorphCellAdapter.from(baseWorld.sample(wx, wz));
    }

    /** 排水高程（真实地表 e，含海陆混合）。河流节点场用它做下坡汇流，海洋侧 e<0 → 不接河。 */
    public double terrainE(double wx, double wz) { return baseWorld.sampleE(wx, wz); }

    /**
     * 归一化降水采样（★ 2026-09-11 Phase C）—— 供水文汇流加权使用。
     *
     * <p>走 {@link #sample}（纯噪声 + 气候，<b>不含侵蚀</b>）→ 廉价且<b>不触发侵蚀 tile</b>。
     * 水文侧按粗格点调用（见 {@code FlowField.PRECIP_COARSE}）以避免过采样。</p>
     */
    public double precipitationAt(double wx, double wz) {
        return sample(wx, wz).precipitation;
    }

    // ===== 火山中心「海陆判定」缓存（LF）=====
    // 纯函数 (wx,wz) → e（同 seed 恒定）。
    //
    //   两个不变量都必须成立：
    //   ① 【单条读取 key+value 原子】—— 键与值必须由【同一不可变 entry】承载，
    //      读者只取一次槽引用即同时拿到 key 与 value。旧实现用两个 AtomicLongArray
    //      （eqKeys/eqVals 各写一次）：读者命中 key 之后、读 value 之前，若有另一线程
    //      用【不同坐标】覆盖同一槽，则会读到【别人坐标的值】——这不是撕裂读，而是
    //      静默错值（无任何异常/NaN 提示），且液滴并行与水文多线程下真实可发生。
    //   ② 【槽只定位不当身份】—— 哈希槽只是哈希表定位，身份由 entry 内的完整
    //      key 比较决定；碰撞只是增未命中，绝不返回异槽/异坐标的值。
    private static final long EQ_EMPTY = Long.MIN_VALUE;

    /** 键值对的不可变快照（entry 为 null = 空槽）。替换引用 = 单条 API 的原子发布。 */
    private record KeyedValue(long key, long bits) {}

    /** 清空陆地门控缓存（换种子/构造时调用）。按引用置 null 清空（与 seed/配置变更同义）。 */
    private void clearEqCache() {
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

    /**
     * 轻量地形 e（跳过气候/分类/height 映射/shape 赋值）—— <b>带直接映射缓存</b>。
     *
     * <p>缓存是<b>纯加速</b>：值为纯函数结果，命中与未命中等价（除 2^-64 的哨兵碰撞）。</p>
     */
    public double terrainEQuick(double wx, double wz) {
        return baseWorld.sampleE(wx, wz);
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
        double cBiased = continent.sample(wx, wz) - params.continentBias();
        double v = smoothstep(-0.01, 0.01, cBiased);
        lfEntries.set(slot, new KeyedValue(key, Double.doubleToRawLongBits(v)));
        return v;
    }

    /**
     * ★ 2026-09-12 地质 Phase T1 设计备注：<b>「构造决定地形类型」（而非"构造推高地形"）</b>
     *
     * <p>⚠ 该设计的实现 {@code applyTectonicWeights} 已于 2026-11-12 删除 ——
     * 它自 Phase T6 起<b>零调用</b>，从未接进 morph 生产链。此处保留设计知识，
     * 供将来重建"构造决定整体地形"时参考：</p>
     *
     * <p>做法是按板块边界应力调制 {@code typeWeights}，让山脉/海沟/裂谷/洋脊沿
     * <b>地质边界</b>分布：</p>
     * <ul>
     *   <li><b>汇聚</b>：陆壳→提升 MOUNTAINS（造山带）；洋壳→提升 DEEP_OCEAN（俯冲海沟）</li>
     *   <li><b>离散</b>：陆壳→提升 BASIN（裂谷）；洋壳→提升 OCEAN（洋中脊，浅于深海）</li>
     *   <li><b>走滑 / 内部</b>：不调制</li>
     * </ul>
     *
     * <p><b>为何必须用权重调制、不可直接加高程偏置</b>：直接抬升 {@code e} 会连锁增强
     * 地形雨（{@code PrecipField.orographicGain} 基于高度差），推高全局降水并稀释
     * 「干旱区河细」的区分度（实测 head 最干桶 0.922→0.959）。改用权重调制后，
     * 山脉由<b>既有 MOUNTAINS 配方</b>生成，不引入额外高度偏置 → 不干扰气候/水文标定。</p>
     *
     * <p><b>用连续量、不用离散枚举</b>：{@code btype} 在边界类型切换处跳变 ⇒ 权重骤变
     * ⇒ 线状疤痕；必须用连续 {@code stress} 加平滑正部
     * （{@link TectonicField#smoothPos}），且两分支恒执行以消除折痕。</p>
     *
     * <p><b>壳属性判据</b>：分支依据是 {@link TectonicField#shellFromC}（连续 c 场派生的
     * 壳属性），<b>不是</b>形态学 {@code landW/oceanW} —— 后者会与 c 场互相矛盾，
     * 导致"火山弧在 eLand≈0 处造陆"。也<b>不可</b>用"所属板块常量"：
     * 本项目 {@code PLATE_SPACING=2000wu} 而 c 波长≈4000wu ⇒ 一个大陆仅约 2 个板块，
     * 相邻板块壳属性差 P50=0.41 ⇒ 等效阶跃 96 块 = 必然的 Voronoi 直线网（实测否决）。</p>
     */

    /**
     * 纯陆地形态 eLand（不含气候/分类）。
     *
     * <p>⚠ <b>仅诊断</b>（2026-11-12 核查）：生产不用（`LandEConformityProbe` 是唯一调用方）。
     * 实现是转调 {@code baseWorld.sampleTerrain(...).landE}，与直接调 {@code BaseWorldSampler} 等价；
     * 保留只为不打断探针。</p>
     */
    public double landE(double wx, double wz) { return baseWorld.sampleTerrain(wx, wz).landE; }

    /**
     * 地形类型连续权重（按类型分桶统计用）。
     *
     * <p>⚠ <b>仅诊断</b>（2026-11-12 核查）：生产不用 —— 生产分类在
     * {@link com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler#sampleColumn} 内部完成。
     * 调用方：`LandEConformityProbe` / `TectonicProbe` / `TerrainStripePngProbe` /
     * `TerrainUpgradeProbe` / `TerrainUpgradeRender`。</p>
     */
    public double[] typeWeightsAt(double wx, double wz) { return baseWorld.sampleTerrain(wx, wz).typeWeights; }

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

    /** 结构定位用的薄入口：明确只返回 B 高度，绝不触发 P tile 生成。 */
    public double sampleHeightNonBlocking(double wuX, double wuZ) {
        return baseWorld.height(wuX, wuZ);
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
        // ★ 阶段六（2026-11-17）：海洋分支也需重新分类——否则海山/洋中脊
        //   只在 `sampleColumn` 那一次生效，侵蚀后若仍 <0 就再也不会被重判。
        //   火山分支同理（基线教训："侵蚀后火山消失"）。
        TerrainClass ct = TerrainClass.dominantFromWeights(cell.typeWeights);
        cell.terrainType = classifyTerrain(e, cell.eLand, ct, cell.temperature,
                cell.humidity, cell.typeWeights, cell.coastCoord,
                cell.landFeat, cell.oceanFeat);
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

    /**
     * wu 语义完整采样（基础 sample + 唯一 P 增量），与 extractFromTile 同源。
     *
     * <p>⚠ <b>仅诊断</b>（2026-11-12 核查）：生产 chunk 采样走
     * {@link #sample} + {@link #extractFromTile} 两步，不调本方法。
     * 保留它是因为约 48 个水文/侵蚀探针以它取"含侵蚀的完整 Cell"；
     * 删掉要改 48 处且收益为零。</p>
     */
    public Cell sampleWu(double wuX, double wuZ) {
        Cell cell = sample(wuX, wuZ);
        applyTileDelta(cell, wuX, wuZ);
        return cell;
    }

    /**
     * 仅采样侵蚀后高度，与 sampleWu().height 同源，跳过气候、分类和坡度计算。
     * 增量后的第二次 softCap 必须保留；height-only 探针覆盖真实压缩窗口及负对照。
     * 可触发侵蚀 tile 生成，不是非阻塞 peek。
     *
     * <p>⚠ <b>仅诊断</b>（2026-11-12 核查）：生产链路不用本方法 ——
     * {@code ModelHydrologyProvider} 走的是 {@code input.processHeightAtBlock}
     * → {@code ProcessModelTerrainInput.processHeightAtBlock} → {@code runtime.requestExact}。
     * 保留它是因为 15 个水文/地形探针以此取"含侵蚀高度"；
     * 生产侧真正的等价入口是 {@link #erosionDeltaE}。</p>
     */
    public double erodedHeight(double wuX, double wuZ) {
        // ★ 2026-11-06【唯一实现】精确 P 提供 "B + 粒子侵蚀" 后的连续高度。
        if (processRuntime == null) {
            return baseWorld.height(wuX, wuZ);
        }
        try {
            return processRuntime.exactP(wuX, wuZ).yP();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return baseWorld.height(wuX, wuZ);
        } catch (java.util.concurrent.ExecutionException e) {
            LOGGER.warn("[PROCESS] erodedHeight exactP 失败于 ({},{})：{}", wuX, wuZ,
                    e.getCause() == null ? e.toString() : e.getCause().toString());
            return baseWorld.height(wuX, wuZ);
        }
    }

    /**
     * ★ 2026-09-13 修复（用户反馈"火山只在大范围预览显示，关掉就没有了"）：
     * <b>火山优先判定</b>（侵蚀后静态重分类使用）。
     *
     * <p>历史：原 {@code CellGenerator.classify(...)}（已于 2026-11-12 删除，零调用者）
     * 也有这段判定，而 {@code classifyTerrain}（侵蚀回写后调用）<b>没有</b>，且其签名里根本没有
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
        return classifyTerrain(ne, eLand, cellType, temperature, humidity, typeWeights,
                coastCoord, landFeat, null);
    }

    /**
     * ★ 阶段六（2026-11-17）：带海洋特征的完整分类。
     *
     * <p>海洋亚型（海山/洋中脊）必须<b>显式判</b>——{@link TerrainClass#dominantFromWeights}
     * 从 {@code PLAIN}（ordinal 8）起搜，永远选不到 ordinal 3/4 的
     * SUBMARINE_RIDGE/SEAMOUNT。</p>
     *
     * <p>判据：仅深水（{@code ne < −0.08}）才可能是海山/洋中脊——真实海山都在深水，
     * 浅水/陆架不该被标成海山。阈值沿用基线口径（seamount&gt;0.02 / ridge&gt;0.05）。</p>
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double humidity,
                                                double[] typeWeights,
                                                double coastCoord,
                                                LandFeatures.FeatureResult landFeat,
                                                OceanFeatures.FeatureResult oceanFeat) {
        if (ne < 0.0) {
            if (oceanFeat != null && ne < -0.08) {
                if (oceanFeat.seamount > 0.02) return TerrainClass.SEAMOUNT;
                if (oceanFeat.ridge > 0.05) return TerrainClass.SUBMARINE_RIDGE;
            }
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
            return TerrainClass.dominantFromWeights(typeWeights);
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
        double maxLandHi = TerrainParams.MAX_LAND_HI_E;
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

    /** HeightCurve 引用 */
    public HeightCurve heightCurve() { return heightCurve; }

}
