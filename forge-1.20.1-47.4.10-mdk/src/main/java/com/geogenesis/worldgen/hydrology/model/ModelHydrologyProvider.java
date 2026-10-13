package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>model 链 → 生产列 适配器（区域缓存 + 邻区异步预热）</h1>
 *
 * <h2>为什么需要区域缓存</h2>
 * <p>{@link HydrologyPipeline#run} 是【窗口式】求解，单窗口实测
 * （{@code runPipelinePerfProbe}，r≈512/step4/50k）约 <b>5.6 秒</b> ——
 * 逐 chunk 同步求解必然卡死世界生成。而同一窗口的产出对窗口内所有 chunk
 * 共享 ⇒ 按【世界对齐 region】缓存一次、邻区后台预热是唯一可行结构
 * （方案 §P3：区域缓存 + 异步预热）。</p>
 *
 * <h2>窗口布局（与 RegionContractProbe 验证口径严格一致）</h2>
 * <pre>
 *   region = 512×512 block（世界对齐，floorDiv）
 *   窗口中心 = region 中心；半径 = 256 + 96(halo) = 352（step=4 整除）
 *   ⇒ 区域内所有点距窗口缘 ≥ 96 块 = RegionContractProbe 的 interior 口径
 *     （该探针实测：双方 interior 同点水位逐位一致 max|Δ|=0、河道一致 99.2%）
 * </pre>
 *
 * <h2>列语义（★ 写回代数的推导，勿改）</h2>
 * <p>{@code GeoGenesisTerrain.applyHydrologyValley} 的写回是
 * {@code placed = eroded − erosion − rawDelta×(1−mask)}，其中 rawDelta = 侵蚀增量。
 * model 链的 bed 来自 {@code sampleWu}（<b>侵蚀后</b>地形）⇒ model 意图的河道床是
 * "侵蚀后地形 − depth"。取 <b>mask=1、erosion=depth</b>：</p>
 * <pre>
 *   placed = eroded − depth − rawDelta×0 = eroded − depth   ⇒ 恰为 model 意图
 *   cut = erosion + rawDelta×(1−mask) = depth ≥ 0            ⇒ RIVER_CUT_NONNEGATIVE 恒过
 *   placed ≤ eroded 恒成立                                 ⇒ "只下挖"铁律恒成立
 * </pre>
 * <p>⚠ 这与 legacy carver 的"河心 mask=0（计划相对无侵蚀基线）"<b>语义相反</b>，
 * 但两者都满足同一写回公式 —— 差别只在雕刻的参照系：legacy 参照无侵蚀基线，
 * model 参照侵蚀后地形（因为 model 就是在侵蚀后地形上求解的水）。自洽即可。</p>
 *
 * <h2>湖列</h2>
 * <p>{@code lakePlan=true} + {@code lakeNode=null} + {@code lakeLevelY=水位}：
 * 写回走湖分支（湖不雕刻、按 spill 洪泛）、逃逸水位重算在 lakeNode==null 时
 * <b>自动跳过</b>（模型水位已是权威）、{@code lakeGroupOf} 对 null 节点按
 * <b>水位</b>分组 —— 三处均读码确认，消费端零改动。块级洪泛
 * （{@code lakeFineFlood}）仍由写回层做 1 块精度精修。</p>
 *
 * <h2>确定性</h2>
 * <p>region 求解是 (seed, region 坐标, 配置) 的纯函数；缓存只改变时序不改变内容
 * （与侵蚀 tile 缓存同一论证）。失败的 future 必须移除，绝不缓存半成品。</p>
 *
 * <p><b>已知边界（v1，如实）：</b></p>
 * <ul>
 *   <li>气候用与全部探针验证一致的固定 Climate(0.004,0.002,0.3)
 *       （空间化降水驱动是后续工作；改了会使既有实测基线全部失效）；</li>
 *   <li>{@code fillRiverDistance}（河流绿洲的距离度量）仍走 legacy 河网 ——
 *       预览与游戏同源（都走 legacy 距离）但与 model 可见河道不同源；</li>
 *   <li>无瀑布/水幕（lipSurfaceY = waterSurfaceY）；</li>
 *   <li>并发与冷 tile 竞争未实机压测。</li>
 * </ul>
 */
public final class ModelHydrologyProvider {

    /** region 边长（block，世界对齐）。必须整除于 step，且 halo 后的窗口半径整除于 step。 */
    /**
     * ★ 2026-10-08【受控实验旋钮】：region 边长 / halo / 步长改为可配置，用于分离
     * 「窗口截断上游」与「step=4 分辨率」这两个导致"游戏 ≠ 渲染图"的候选根因。
     * 默认值 = 生产原值（512 / 96 / 4）⇒ 零行为变更。
     * <p>用法（诊断）：{@code -Dgeogenesis.modelRegion=2048 -Dgeogenesis.modelHalo=0}</p>
     * <p>⚠ 若某 region 放大后与"正典单窗口 r=700"的湖格数吻合 ⇒ 根因是【窗口截断】；
     * 若仍不吻合但把 STEP 降到 1 后吻合 ⇒ 根因是【分辨率】。二者对策完全不同。</p>
     */
    private static final int REGION = Integer.getInteger("geogenesis.modelRegion", 512);
    /**
     * 窗口保护带（block）—— ★ 2026-10-09 仍是**待定项**。
     *
     * <h3>为什么这个值重要</h3>
     * <p>窗口外的影响若没被充分吸收，会出现两类缺陷：
     * ① 内陆 region 找不到海 ⇒ {@code EquilibriumWater} 的 {@code resolved} 全 false
     * ⇒ 湖整块消失（实测 7 个 region 里 4 个如此）；
     * ② 湖/河在 region 缝上断半截。</p>
     *
     * <h3>用户提示的参照：侵蚀 tile 的比例</h3>
     * <p>用户："我记得我地形侵蚀也是按大陆来分的。" 核实 `CellGenerator:912`：
     * <pre>
     *   ERODE_TILE_CENTER = 48 wu    // 有效输出区（中心）
     *   ERODE_TILE_BORDER = 40 wu    // 边界上下文（两侧）
     *   ERODE_TILE_SIZE   = 128 wu   // 总窗口
     * </pre>
     * ⇒ border/center = **83%**（相邻 tile 共享约 60% 上下文）。
     * 而水文原为 `HALO=96 / REGION=512` = **19%**，远小于侵蚀。</p>
     *
     * <h3>⚠ 实测结论：HALO 不是"越大越好"（本轮数据）</h3>
     * <table border="1">
     *   <tr><th>配置</th><th>湖</th><th>说明</th></tr>
     *   <tr><td>HALO=96</td><td>0.53%</td><td>内陆 region 全灭（resolved=0）</td></tr>
     *   <tr><td>HALO=96 + inlandResolved</td><td>3.72%（r=300 小窗）</td><td>靠放宽判据补回</td></tr>
     *   <tr><td><b>HALO=512</b></td><td><b>4.98%（r=300 小窗）</b></td><td>窗口自然含海，<b>开关已无关</b></td></tr>
     * </table>
     * <p>⇒ <b>HALO 够大时 `inlandResolved` 完全无效</b>（两种配置结果逐位相同）
     * —— 这证明"窗口够大"才是正解，放宽判据只是权宜。
     * 但 HALO=512 的湖量（4.98%）高于 HALO=96 的 3.72%，因为**窗口越大看到的流域越多**。</p>
     *
     * <h3>⇒ 当前取值与理由</h3>
     * <p><b>默认仍为 96（= 生产历史值，零行为变更）</b>，因为：
     * ① HALO=512 未经完整验收（正典规格出图 >50 分钟未跑完）；
     * ② 单次求解面积 ×2.6，世界加载代价未评估；
     * ③ 湖量的"正确值"本身依赖窗口，无绝对基准。</p>
     * <p>要实验：{@code -Dgeogenesis.modelHalo=512}。</p>
     */
    private static final int HALO = Integer.getInteger("geogenesis.modelHalo", 96);
    /** 网格步长（block）—— 生产形态（方案 P3 实测口径）。 */
    private static final int STEP = Integer.getInteger("geogenesis.modelStep", 4);
    /** 流线采样数（生产形态实测 5.6s 的档位）。 */
    private static final int SAMPLES = 50_000;
    /** 窗口半径 = region 半 + halo。 */
    private static final int RADIUS = REGION / 2 + HALO;      // 352，/4 = 88 ✓
    /**
     * 内容 LRU 容量 —— ★ 2026-10-09 改为【按 region 面积自适应】。
     *
     * <p>原为固定 12（注释写"单 region ≈ 3 MB ⇒ 12 ≈ 36 MB"）—— 那是在
     * REGION=512/STEP=4（177² 格）下成立的估算。一旦放大 region / 缩小 step，
     * 单 region 内存按 <b>格数 × 每格字节数</b> 增长：
     * <pre>
     *   REGION=512  STEP=4 ⇒ 177²  = 31k 格  ⇒ ~3 MB
     *   REGION=1408 STEP=1 ⇒ 1409² = 2.0M 格 ⇒ ~190 MB
     *   REGION=1408 STEP=1 +HALO=96 ⇒ 1601² = 2.6M 格 ⇒ ~250 MB
     * </pre>
     * 固定 12 会变成 <b>3 GB</b> ⇒ OOM。故改为"总预算 512 MB 内能放几个就放几个"，
     * 下限 2（保证邻区预热/命中仍有意义）。</p>
     */
    private static final int CACHE_CAP = computeCacheCap();

    private static int computeCacheCap() {
        int n = 2 * RADIUS / STEP + 1;
        long bytesPerRegion = (long) n * n * 96L;      // 粗估：~12 个 double/bool 数组
        int byBudget = (int) Math.max(2, (512L << 20) / Math.max(1L, bytesPerRegion));
        return Math.min(byBudget, Integer.getInteger("geogenesis.modelCacheCap", 12));
    }
    /** 后台预热队列上限（超过则跳过本轮预热，防瞬移/高速移动时无限堆积）。 */
    private static final int PREHEAT_QUEUE_CAP = 8;

    /**
     * 跨区水量传递：窗外 margin 带宽（块，`-Dgeogenesis.modelMargin`）。
     * ★ 默认 <b>0 = 关闭</b> —— 实测注入方式有伪影（见 computeMarginInflow javadoc）。
     */
    private static final int MARGIN_BLOCKS = Integer.getInteger("geogenesis.modelMargin", 0);
    /** margin 带采样步长（块）。 */
    private static final int MARGIN_STEP = 8;

    /** 与全部探针/契约验证一致的固定气候（空间化气候 = 后续工作，见类 javadoc）。 */
    private static final LakeWaterBalance.Climate CLIMATE =
            new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    /** 单 region 求解产物（不可变；发布后只读 ⇒ LRU 淘汰不影响在途读者）。 */
    private record RegionData(HydrologyPipeline.Result pipe,
                              RiverCenterlines.Result centerline,
                              SegmentIndex skeletonSeg,
                              int originX, int originZ) { }

    private final CellGenerator gen;
    /** block → wu 换算（sample/sampleWu 的坐标语义是 wu，见 CellGenerator.sample javadoc）。 */
    private final double invHs;
    /**
     * ★ 本实例的求解策略指纹（构造时确定，含 {@code gen.params} hash + Forge 配置指纹 +
     * 实验系统参数）。进 {@link CacheKey} ⇒ 不同参数/配置的实例<b>绝不复用</b>彼此的缓存。
     * 见 {@link #instanceStrategyFingerprint}。
     */
    private final long strategy;
    /**
     * 内容缓存（LRU）—— ★ static 共享，按 seed 键控。
     *
     * <h3>为什么必须 static（2026-10 实机实测驱动）</h3>
     * <p>实机日志证实存在【多个地形实例】：
     * {@code [HYDRO] modelPipeline} 与 {@code [RIVER] terrain init} 各出现 <b>5 次</b>
     * （4 次在主菜单 Render thread、1 次世界生成 Worker-Main），
     * 因为 {@code GeoGenesisGenerator.setWorldSeed} 会 {@code sharedTerrain = null}
     * ⇒ 之后每个构造点各建一个实例、各持一份实例级缓存。</p>
     * <p>后果（实测，两个 region 的原始日志）：
     * <pre>
     *   region (0,0) solved in  4933 ms (reaches=16,  lake=3,  channel=49)     ← 预览实例
     *   region (0,0) solved in 13355 ms (reaches=218, lake=7,  channel=2719)   ← 世界实例
     *   region (1,0) solved in  3823 ms (reaches=94,  lake=0,  channel=763)
     *   region (1,0) solved in 18490 ms (reaches=774, lake=60, channel=5553)
     * </pre>
     * ① 每个 region 被求解两次（单次 14~18 秒 ⇒ 一半算力白烧）；
     * ② <b>同一 (seed, region) 两次产出不同</b> —— 违反本类 javadoc 的
     *    "缓存只改时序不改内容" 断言（差异来自不同 CellGenerator 实例的状态）。</p>
     * <p>修法：缓存提升为 static，用 {@code seed} 做键的一部分 ⇒
     * 任何实例对同一 (seed, region) 命中的是同一份结果，换 seed 自动失效。</p>
     */
    private static final LinkedHashMap<CacheKey, RegionData> SHARED_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    /** 单飞（single-flight）：同一 (seed, region) 只求解一次。 */
    private static final ConcurrentHashMap<CacheKey, CompletableFuture<RegionData>> SHARED_INFLIGHT =
            new ConcurrentHashMap<>();

    /** 缓存键 = (世界种子, region 坐标, 求解策略指纹)。
     *
     *  <p>★ 分层实验（2026-10-10）：{@code strategy} 把【分层/既有】两条求解策略的缓存彻底隔离
     *  —— 旧键只有 (seed,rx,rz)，一旦开关切换就会命中另一策略的产物（静默返还错水）。
     *  指纹含运行期配置（系统属性 + 配置项），故改了旋钮也不会复用旧缓存。</p>
     */
    private record CacheKey(long seed, int rx, int rz, long strategy) { }

    /* ======================================================================
     * ★★★ 2026-10-10【分层水文受控实验（`-Dgeogenesis.hierarchicalHydrology=true`）】★★★
     *
     * 契约（详见 {@link HierarchicalHydrology}）：
     *   粗层 = 只读 `terrainEQuick→heightFromE`（零 sampleWu、零 tile 预热），pass-1 只跑粗层；
     *   细层 = 本 region + 固定 halo 的 `sampleWu` 重管线，窗外一格外环取【粗层水位接口】
     *          （下游权威），【绝不】求解任何邻区的完整重管线 —— 借邻区只借粗层端口；
     *   盆地形态：粗层只是【候选】，细层真实侵蚀盆地为【最终】⇒ 候选消失/分裂/合并/移动/
     *          水位变动【不判失败】；只有真实冲突（非有限水位/越域索引）才抛带坐标异常
     *          （不回退、不无限扩域 —— 也不给它无限扩域）。
     *
     * 默认关：{@code HIER=false} ⇒ 全部走既有路径，产出逐位不变。
     * ====================================================================== */
    static final boolean HIER = Boolean.getBoolean("geogenesis.hierarchicalHydrology");
    /**
     * 粗层保护带。必须让粗域【覆盖】细层窗外一格外环（该环在 {@code ±(RADIUS + STEP)} 处）
     * ⇒ 硬下限 {@code HALO + STEP}（= 100 ⇒ 粗半径 356，恰是该环所在的格点，且 356 % STEP == 0）。
     * {@link HierarchicalHydrology.Params} 会在构造时再对齐同余，防手改出错。
     */
    private static final int HIER_COARSE_HALO =
            Integer.getInteger("geogenesis.hierCoarseHalo", HALO + STEP);
    private static final HierarchicalHydrology.Params HIER_PARAMS = HierarchicalHydrology.Params.of(
            REGION, HALO, STEP, HIER_COARSE_HALO);

    /**
     * ★★ 2026-10-12【跨 tract 缝共享湖域（{@link SharedLakeDomain}）】★★
     *
     * <p>实测现场（{@code scratch/FINDINGS.md}）：{@code (-513,-250)} 在两个相邻 tract 的窗口里
     * <b>bed 逐位相同、湖布尔/水位不同</b>（176.89 vs 175.31）⇒ 缝上 377/489 像素是湖墙。</p>
     *
     * <p><b>只在分层实验链上生效</b>（{@code HIER=true}），并要求显式打开
     * {@code -Dgeogenesis.sharedLakeDomains=true} ⇒ <b>默认关、HIER 关时彻底不参与</b>，
     * 既有生产产出逐位不变。</p>
     */
    static final boolean SHARED_LAKE =
            HIER && Boolean.getBoolean("geogenesis.sharedLakeDomains");
    /**
     * 单 chunk 候选缝域数的<b>告警阈值</b>（真上界 = 每轴 ≤2 boundary × ≤2 alongTile ⇒ ≤8；
     * 实测 2~4）。<b>不是门</b>：超阈值只如实计数 + 留痕（见 {@link #sharedLakeDecision}）。
     *
     * <p><b>成本为何天然有界（不靠运行期预算门）</b>：每<b>点</b>候选恒为 1 竖 + 1 横（≤2）；
     * 每 <b>chunk</b> 的四角并集 ≤8；每个缝键<b>全局只建一次</b>（域缓存，键含 seed+策略）。
     * 故域构建总数 ≤ 该次扫描触及的缝键数（沿缝 ≈ 缝长/tract），与列数无关。
     * 若改用"每 chunk 新取域数"预算，反而会让产出取决于"缓存里恰好有什么" ⇒ 禁止。</p>
     */
    private static final int SHARED_LAKE_CHUNK_WARN =
            Integer.getInteger("geogenesis.sharedLakeChunkWarn", 8);
    /**
     * 共享域缓存容量。每个域 = 一次同窗粗筛选（≈0.4~0.7 s）；确有跨缝盆地时才再付一次
     * 同窗<b>侵蚀</b>细解（≈数秒，与一次 tract 细解同量级）。
     */
    private static final int SHARED_LAKE_CACHE_CAP =
            Integer.getInteger("geogenesis.sharedLakeDomainCap", 24);

    /**
     * 探针用 before/after 开关（<b>只影响列改写</b>，不改变任何缓存内容与域结果）。
     * 生产恒 false；只有诊断在单 JVM 里做同坐标快照时才置位。
     */
    static volatile boolean sharedLakeProbeOff = false;

    /** 共享湖域是否在本进程真正参与列裁决（生产 = {@link #SHARED_LAKE}）。 */
    static boolean sharedActive() {
        return SHARED_LAKE && !sharedLakeProbeOff;
    }

    /** 域缓存键 = (seed, 策略指纹, 缝键) —— 与 region/粗层缓存同一隔离原则。 */
    private record DomKey(long seed, long strategy, int axis, int boundary, int alongTile) { }

    /**
     * 会改变本链路产出的【实验系统属性】清单（显式维护；见实例指纹 javadoc 为何不用全枚举）。
     */
    private static final String[] EXPERIMENT_KEYS = {
            // ---- 分层实验 ----
            "geogenesis.hierarchicalHydrology", "geogenesis.hierCoarseHalo",
            "geogenesis.hierStrictContract",
            // ---- 跨 tract 缝共享湖域（只在分层链上生效）----
            "geogenesis.sharedLakeDomains",
            "geogenesis.sharedLakeDomainCap", "geogenesis.sharedLakeChunkWarn",
            // ---- model 链拓扑 / 窗口 ----
            "geogenesis.modelRiverModel", "geogenesis.modelRegion", "geogenesis.modelHalo",
            "geogenesis.modelStep", "geogenesis.modelMargin",
            // ---- 骨架 ----
            "geogenesis.skeletonTrace", "geogenesis.skeletonCrossPass", "geogenesis.networkFilter",
            "geogenesis.skeletonMinNodes", "geogenesis.skeletonSelfRadius",
            "geogenesis.skeletonSelfExempt", "geogenesis.skeletonMaxRivers",
            "geogenesis.skeletonMeanderAmp", "geogenesis.channelMinCells",
            "geogenesis.sourceSpacing", "geogenesis.stepCrossGuard",
            // ---- 地形口径 / 床面 ----
            "geogenesis.hydroBedEroded", "geogenesis.valleyMin", "geogenesis.valleyTrough",
            "geogenesis.bedConcave", "geogenesis.bedConcaveStep",
            // ---- 湖 / 判据 ----
            "geogenesis.lakeSinkOff", "geogenesis.localLakeLevel", "geogenesis.flatBreak",
            "geogenesis.inlandResolved", "geogenesis.intlandResolved", "geogenesis.sewGap",
            // ---- 几何收尾 ----
            "geogenesis.particleAmp", "geogenesis.particleMomentum",
            // ---- 阈值 / 耦合 / 流向 ----
            "geogenesis.riverThresholdFrac", "hydro.couplingMaxRounds", "hydro.directionRule",
            "hydro.visitCap", "hydro.floorMul", "hydro.absFloorMul", "hydro.relFloor",
            "hydro.dropTinyBasins", "hydro.minVisibleArea", "hydro.channelUseRawFlux",
            "hydro.deadOnGeoWet", "hydro.momentum", "hydro.recoverKeepChainInk",
            "hydro.relaxFloorGate", "hydro.ledgerOff",
    };

    /**
     * 纯诊断 / 纯记忆化开关 —— <b>不</b>纳入缓存身份。
     *
     * <p>纳入只会让身份无谓变化（例：{@code -Dgeogenesis.stutterDiag} 只影响日志，
     * 改它不该让 region/预览缓存全部失效）。列在此处作为<b>留痕与自检</b>：若日后发现
     * 其中某个键"改了会改产出"，说明它应从本清单移到 {@link #EXPERIMENT_KEYS}。</p>
     */
    private static final String[] DIAGNOSTIC_ONLY_KEYS = {
            "geogenesis.stutterDiag", "geogenesis.lakeDiag", "geogenesis.riverBreakDiag",
            "geogenesis.riverWidthDiag", "geogenesis.logSrc", "geogenesis.skeletonEndDiag",
            "geogenesis.joinChk", "geogenesis.gapWatch", "geogenesis.skelDump",
            "geogenesis.hydrologyConcurrencyDiag", "geogenesis.worldgenProfilerEnabled",
            "geogenesis.worldgenProfilerEveryChunks", "geogenesis.modelCacheCap",
            "geogenesis.modelSolveThreads", "hydro.stageTimer", "hydro.couplingDiagnostics",
            "geogenesis.prewarmTiles", "geogenesis.hydroSampleCache",
    };

    /** 类加载自检：两个清单必须不相交（同一键既"改产出"又"纯诊断"是维护错误）。 */
    private static void assertKeyListsDisjoint() {
        java.util.Set<String> diag =
                new java.util.HashSet<>(java.util.Arrays.asList(EXPERIMENT_KEYS));
        for (String k : DIAGNOSTIC_ONLY_KEYS) {
            if (diag.contains(k)) {
                throw new IllegalStateException(
                        "实验指纹键清单与纯诊断键清单重叠: " + k);
            }
        }
    }

    /**
     * ★★ 实验系统参数的【纯函数】指纹 —— <b>只含系统实验参数</b>，不含 {@code gen.params}
     * 与 Forge 配置。
     *
     * <h3>用途</h3>
     * <p>{@code PreviewDisplay.cacheSchemaHash} 可把本值混入磁盘缓存键 ⇒ 实验旋钮一变，
     * 预览缓存即失效。地形参数与 Forge 配置由该处<b>既有</b>的
     * {@code params.hashCode() + GeoGenesisConfig.configFingerprint()} 负责，不在此重复。</p>
     */
    public static long outputStrategyFingerprint() {
        return hashOf(EXPERIMENT_KEYS);
    }

    /** 对键集做 FNV-1a 指纹（排序 ⇒ 与字典序/枚举顺序无关，确定性）。 */
    private static long hashOf(String[] keys) {
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(java.util.Arrays.asList(keys));
        StringBuilder sb = new StringBuilder(512);
        for (String k : sorted) sb.append(k).append('=').append(System.getProperty(k, "-")).append('|');
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < sb.length(); i++) {
            h ^= sb.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * 实例级求解策略指纹：{@code gen.params} 完整 hash ⊕ Forge 配置指纹 ⊕ 实验系统参数。
     *
     * <h3>★ 为什么必须是【实例】而非 static（本轮修正）</h3>
     * <p>原为 {@code static final STRATEGY = strategyFingerprint()} —— <b>类初始化只捕获第一次</b>
     * ⇒ 预览在同一进程里打开<b>不同地形参数 / 不同配置</b>的世界时仍用第一次的指纹
     * ⇒ <b>多配置混用同一份 region/粗层缓存</b>（静默返还错水）。</p>
     * <p>⇒ 本方法在<b>构造时</b>调用，结果存入实例 {@code final strategy}：不同
     * {@code params}/{@code config} 的实例必然得到不同键。</p>
     */
    private static long instanceStrategyFingerprint(CellGenerator gen) {
        assertKeyListsDisjoint();       // 自检：清单绝不重叠（否则身份来源不清）
        long h = 0xcbf29ce484222325L;
        h = mix(h, gen.params() == null ? 0L : gen.params().hashCode());
        try {
            h = mix(h, com.geogenesis.config.GeoGenesisConfig.configFingerprint());
            var cfg = com.geogenesis.config.GeoGenesisConfig.INSTANCE;
            h = mix(h, cfg.hydrologyDeterministicSkeleton.get() ? 1 : 0);
            h = mix(h, cfg.hydrologyModelPipelineEnabled.get() ? 1 : 0);
            h = mix(h, cfg.hydrologySkeletonRouting.get() ? 1 : 0);
            h = mix(h, cfg.hydrologyDecayEnabled.get() ? 1 : 0);
            h = mix(h, cfg.riverEnabled.get() ? 1 : 0);
        } catch (RuntimeException | LinkageError ignored) {
            h = mix(h, 0L);     // 无 Forge 环境（探针）：不留未加载异常
        }
        return mix(h, outputStrategyFingerprint());
    }

    private static long mix(long h, long v) {
        return (h ^ v) * 0x100000001b3L;
    }

    // ---- 分层实验读视图（package-private；主 agent 的探针据此写判据）----

    /** 分层开关是否开启。 */
    static boolean hierarchical() { return HIER; }

    /** 分层固定域参数（探针与生产同源）。 */
    static HierarchicalHydrology.Params hierParams() { return HIER_PARAMS; }

    /** 求解策略指纹（缓存隔离用；探针可断言"开关切换 ⇒ 指纹变化"）。 */
    long strategyFingerprintValue() { return strategy; }

    /** 取某 tract 的粗层产物（探针直接校验端口/盆地权威/水位接口）。 */
    HierarchicalHydrology.Coarse coarseLayerFor(int rx, int rz) { return coarseFor(rx, rz); }

    /**
     * ★ 最近一次细层求解的【盆地形态对比】结果（探针据此读诊断；null = 尚未求解）。
     *
     * <p><b>形态差异（候选消失/分裂/合并/出口移动/水位变动）不是失败</b> ——
     * 粗层只是候选，细层真实侵蚀盆地才是最终形态。契约只看基本数据合法
     * （{@link HierarchicalHydrology.MorphologyComparison#contractSatisfied()}）。</p>
     */
    private static volatile HierarchicalHydrology.MorphologyComparison LAST_FINE_VALIDATION = null;

    /** 最近一次细层求解的【独立连通性审计】（孤立/环/边框未验证，探针据此判 FAIL）。 */
    private static volatile HierarchicalHydrology.Connectivity LAST_FINE_CONNECTIVITY = null;

    /** 读视图：最近一次细层盆地形态对比（探针用）。 */
    static HierarchicalHydrology.MorphologyComparison lastFineValidation() {
        return LAST_FINE_VALIDATION;
    }

    /** 读视图：最近一次细层连通性审计（探针用）。 */
    static HierarchicalHydrology.Connectivity lastFineConnectivity() { return LAST_FINE_CONNECTIVITY; }

    /**
     * 粗层固定域缓存（分层实验）—— 与细层缓存独立，键同为 {@link CacheKey}（含策略指纹）。
     * 粗层是 (seed, rx, rz, 配置) 的纯函数 ⇒ 缓存只改时序不改内容。
     */
    private static final LinkedHashMap<CacheKey, HierarchicalHydrology.Coarse> COARSE_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    private static final ConcurrentHashMap<CacheKey, CompletableFuture<HierarchicalHydrology.Coarse>>
            COARSE_INFLIGHT = new ConcurrentHashMap<>();

    /**
     * 取（或建）本 tract 的<b>粗层</b>固定域产物 —— pass-1 的唯一内容。
     *
     * <p><b>铁律</b>：粗层绝不 {@code sampleWu}、绝不预热侵蚀 tile（{@link HierarchicalHydrology#coarseTerrain}）；
     * 也<b>绝不取任何邻区</b> ⇒ 扇出恒为 1、不依赖生成顺序。</p>
     */
    private HierarchicalHydrology.Coarse coarseFor(int rx, int rz) {
        CacheKey key = new CacheKey(seed, rx, rz, strategy);
        synchronized (COARSE_CACHE) {
            HierarchicalHydrology.Coarse hit = COARSE_CACHE.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<HierarchicalHydrology.Coarse> existing = COARSE_INFLIGHT.get(key);
        if (existing != null) return awaitCoarse(existing);
        CompletableFuture<HierarchicalHydrology.Coarse> mine = new CompletableFuture<>();
        CompletableFuture<HierarchicalHydrology.Coarse> raced = COARSE_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) return awaitCoarse(raced);
        try {
            HierarchicalHydrology.Coarse c =
                    HierarchicalHydrology.buildCoarse(gen, HIER_PARAMS, rx, rz, CLIMATE);
            cachePutCoarse(key, c);
            mine.complete(c);
            COARSE_INFLIGHT.remove(key, mine);
            return c;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            COARSE_INFLIGHT.remove(key, mine);      // ★ 失败绝不缓存
            throw e;
        }
    }

    private static HierarchicalHydrology.Coarse awaitCoarse(
            CompletableFuture<HierarchicalHydrology.Coarse> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private static void cachePutCoarse(CacheKey key, HierarchicalHydrology.Coarse c) {
        synchronized (COARSE_CACHE) {
            COARSE_CACHE.put(key, c);
            while (COARSE_CACHE.size() > CACHE_CAP) {
                COARSE_CACHE.remove(COARSE_CACHE.keySet().iterator().next());
            }
        }
    }

    // ======================================================================
    // ★★ 2026-10-12【跨 tract 缝共享湖域】—— 有界 / 确定性 / 不借邻区细层
    //
    //   ① 每 chunk 预解析候选缝键（点→键唯一；【不设缝带门】—— 见 columnsFor 的说明）；
    //   ② 候选缝域由世界坐标 + 固定域参数导出（与提问 region 无关），按稳定顺序遍历；
    //   ③ 域 = 关于缝对称的 704² 块方窗（半边长 = REGION/2 + HALO = 现有窗口预算）；
    //   ④ 先用【廉价粗层】同窗筛选：无跨缝盆地 ⇒ 立即返回（永不细解）；
    //   ⑤ 确有跨缝盆地才付一次【真实侵蚀地形】同窗求解；
    //   ⑥ 受控体 = 整个跨缝盆地（单平面 + 干环 + 不触框 + 在【最近键归属域】内），否则拒绝；
    //   ⑦ 受控即【整盆地一并采用】（有则同一水位、无则一并判干）⇒ 缝两侧同裁决。
    //   ⚠ 这里【绝不】调用 regionFor / 任何邻区细解，也不读"缓存里恰好有什么"。
    // ======================================================================

    /** 共享缝域缓存（LRU，键含 seed + 策略指纹；与 region/粗层缓存同一隔离原则）。 */
    private static final LinkedHashMap<DomKey, SharedLakeDomain.Domain> DOMAIN_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    /**
     * 域单飞（single-flight）：同一 (seed, 策略, 缝键) 只构建一次。
     * 无它时并发 {域未命中} 会各自重跑粗/细解（实测并发重复构建）⇒ 必须与 region/粗层同款。
     */
    private static final ConcurrentHashMap<DomKey, CompletableFuture<SharedLakeDomain.Domain>>
            DOMAIN_INFLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DomKey, CompletableFuture<SharedLakeDomain.Domain>>
            DOMAIN_FINE_INFLIGHT = new ConcurrentHashMap<>();

    private SharedLakeDomain.Domain resolveDomain(SharedLakeDomain.Domain prepared) {
        if (prepared.status() != SharedLakeDomain.Status.PENDING) return prepared;
        var key = prepared.key();
        DomKey k = new DomKey(seed, strategy, key.axis(), key.boundary(), key.alongTile());
        synchronized (DOMAIN_CACHE) {
            var hit = DOMAIN_CACHE.get(k);
            if (hit != null && hit.status() != SharedLakeDomain.Status.PENDING) return hit;
        }
        var mine = new CompletableFuture<SharedLakeDomain.Domain>();
        var raced = DOMAIN_FINE_INFLIGHT.putIfAbsent(k, mine);
        if (raced != null) return awaitDomain(raced);
        try {
            SharedLakeDomain.Domain d;
            synchronized (DOMAIN_CACHE) { d = DOMAIN_CACHE.get(k); }
            if (d == null || d.status() == SharedLakeDomain.Status.PENDING) d = prepared.resolved();
            cachePutDomain(k, d);
            mine.complete(d);
            return d;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            DOMAIN_FINE_INFLIGHT.remove(k, mine);
        }
    }

    /** 取（或建）一个缝域 —— 纯函数 (seed, 策略, 缝键) ⇒ 缓存只改时序不改内容。 */
    private SharedLakeDomain.Domain domainFor(SharedLakeDomain.Key key) {
        DomKey k = new DomKey(seed, strategy, key.axis(), key.boundary(), key.alongTile());
        synchronized (DOMAIN_CACHE) {
            SharedLakeDomain.Domain hit = DOMAIN_CACHE.get(k);
            if (hit != null) return hit;
        }
        CompletableFuture<SharedLakeDomain.Domain> existing = DOMAIN_INFLIGHT.get(k);
        if (existing != null) return awaitDomain(existing);
        CompletableFuture<SharedLakeDomain.Domain> mine = new CompletableFuture<>();
        CompletableFuture<SharedLakeDomain.Domain> raced = DOMAIN_INFLIGHT.putIfAbsent(k, mine);
        if (raced != null) return awaitDomain(raced);          // 输了 ⇒ 等赢家
        try {
            SharedLakeDomain.Domain d;
            synchronized (DOMAIN_CACHE) { d = DOMAIN_CACHE.get(k); }
            var fine = DOMAIN_FINE_INFLIGHT.get(k);
            if (fine != null) d = awaitDomain(fine);
            else if (d == null) d = buildDomain(key);
            cachePutDomain(k, d);                              // ★ 先发布缓存
            mine.complete(d);                                  //   再完成
            DOMAIN_INFLIGHT.remove(k, mine);
            return d;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            DOMAIN_INFLIGHT.remove(k, mine);                   // ★ 失败绝不缓存（可重试）
            throw e;
        }
    }

    /** 构建一个缝域：先廉价筛选（邻 tract 粗层无跨缝盆地 ⇒ 跳过建窗），否则对称窗求解。 */
    private SharedLakeDomain.Domain buildDomain(SharedLakeDomain.Key key) {
        //   廉价筛选（复用【已缓存】的相邻 tract 粗层）：两侧邻 tract 粗层都看不到跨这条缝线的盆地
        //   ⇒ 无需建窗。⚠ 必要条件筛（非充分证明）：漏判只会"该点不受控"，绝不造假水位 ⇒ 安全侧。
        return screenOut(key)
                ? SharedLakeDomain.screened(key, HIER_PARAMS)
                : SharedLakeDomain.prepare(sharedCtx(), key);
    }

    private static SharedLakeDomain.Domain awaitDomain(
            CompletableFuture<SharedLakeDomain.Domain> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private static void cachePutDomain(DomKey k, SharedLakeDomain.Domain d) {
        synchronized (DOMAIN_CACHE) {
            var existing = DOMAIN_CACHE.get(k);
            if (d.status() == SharedLakeDomain.Status.PENDING && existing != null
                    && existing.status() != SharedLakeDomain.Status.PENDING) return;
            DOMAIN_CACHE.put(k, d);
            while (DOMAIN_CACHE.size() > SHARED_LAKE_CACHE_CAP) {
                DOMAIN_CACHE.remove(DOMAIN_CACHE.keySet().iterator().next());
            }
        }
    }

    /** 相邻两 tract 的粗层里是否都看不到跨该缝线的盆地（true ⇒ 可跳过建窗）。 */
    private boolean screenOut(SharedLakeDomain.Key key) {
        HierarchicalHydrology.Params hp = HIER_PARAMS;
        int line = key.boundary();
        int lo = Math.floorDiv(line - 1, hp.tract());     // 缝线两侧的 tract
        int hi = line / hp.tract();
        HierarchicalHydrology.Coarse cLo = coarseFor(
                key.axis() == 0 ? lo : key.alongTile(), key.axis() == 0 ? key.alongTile() : lo);
        long beforeHi = HierarchicalHydrology.Diag.COARSE_SOLVES.get();
        HierarchicalHydrology.Coarse cHi = coarseFor(
                key.axis() == 0 ? hi : key.alongTile(), key.axis() == 0 ? key.alongTile() : hi);
        boolean lowCross = anyCrosses(cLo, key.axis(), line);
        // #region agent log: L4 低侧已确认候选时，高侧是否仍发生无必要粗求解
        if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
            com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("L4", "screen-high-" + key.label(),
                    "lowCross=" + lowCross + " highCoarseSolves="
                            + (HierarchicalHydrology.Diag.COARSE_SOLVES.get() - beforeHi));
        }
        // #endregion
        return !lowCross && !anyCrosses(cHi, key.axis(), line);
    }

    private static boolean anyCrosses(HierarchicalHydrology.Coarse c, int axis, int line) {
        if (c == null) return false;
        for (HierarchicalHydrology.BasinFootprint fp
                : HierarchicalHydrology.basinFootprints(c).values()) {
            if (HierarchicalHydrology.footprintCrossesBlock(fp, c, axis, line)) {
                HierarchicalHydrology.Diag.SHARED_SCREEN_CROSS.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /** 构建上下文：与生产同源参数；窗外权威 = 坐标 owner 的粗层水位接口（与细层路径同口径）。 */
    private SharedLakeDomain.Ctx sharedCtx() {
        SharedLakeDomain.OwnerLevel owner = (bx, bz) ->
                HierarchicalHydrology.owner(bx, bz, HIER_PARAMS, this::coarseFor).levelAt(bx, bz);
        return new SharedLakeDomain.Ctx(gen, gen.params(), HIER_PARAMS, STEP, CLIMATE, owner);
    }

    /**
     * 该世界点的共享域裁决（探针读视图 + 列消费入口）。
     *
     * <p>按候选缝域的<b>稳定顺序</b>遍历（轴 → 缝位置 → 沿缝 tile），收集所有"受控"裁决：
     * 恰好一个受控 ⇒ 采用；零个 ⇒ 不受控；多个且<b>平面一致</b> ⇒ 采用同一平面；
     * 多个且<b>平面不同</b> ⇒ {@code COMPETING}（未决：如实计数、不并集、不改写）。</p>
     *
     * <p><b>无"预算门"</b>：候选键由 chunk 盒几何导出（纯函数）⇒ 同 chunk 在任何顺序、
     * 任何缓存状态下都得到同一组域。域内容也是纯函数 ⇒ 淘汰/重建不改产出。
     * 只有 {@link #SHARED_LAKE_CHUNK_WARN} 会如实计数"异常多的候选"。</p>
     *
     * @param keys 候选缝键（按 chunk 预解析，稳定顺序）
     */
    SharedLakeDomain.Decision sharedLakeDecision(int worldX, int worldZ,
                                                 List<SharedLakeDomain.Key> keys) {
        if (!sharedActive()) return SharedLakeDomain.Decision.NONE;
        SharedLakeDomain.Decision best = null;
        for (SharedLakeDomain.Key key : keys) {
            SharedLakeDomain.Domain prepared = domainFor(key);
            if (prepared.cellAt(worldX, worldZ) < 0) continue;
            SharedLakeDomain.Decision d = resolveDomain(prepared).decide(worldX, worldZ);
            if (!d.controlled()) continue;
            if (best == null) {
                best = d;
                continue;
            }
            if (!samePlane(best, d)) {
                HierarchicalHydrology.Diag.SHARED_COMPETING.incrementAndGet();
                HierarchicalHydrology.Diag.sharedSample(String.format(
                        "COMPETING (%d,%d) %s(%.2f) vs %s(%.2f) ⇒ 未决：不并集、不改写",
                        worldX, worldZ, best.key().label(), best.surface(),
                        d.key().label(), d.surface()));
                return new SharedLakeDomain.Decision(false, false, Double.NaN, null, -1,
                        SharedLakeDomain.Status.COMPETING);
            }
        }
        return best == null ? SharedLakeDomain.Decision.NONE : best;
    }

    /** 单点查询（探针用）。 */
    SharedLakeDomain.Decision sharedLakeDecision(int worldX, int worldZ) {
        return sharedLakeDecision(worldX, worldZ,
                SharedLakeDomain.candidates(worldX, worldZ, HIER_PARAMS));
    }

    /**
     * 【按 chunk 预解析】该 chunk 的候选缝域（一次取域，避免逐列 {@code domainFor} 反复加锁/查询）。
     * 返回值供逐列 {@link #decideShared} 只读使用（纯读、无分配）。
     */
    private List<SharedLakeDomain.Domain> resolveChunkDomains(int blockX0, int blockZ0) {
        List<SharedLakeDomain.Key> keys = SharedLakeDomain.keysForBox(
                blockX0, blockZ0, blockX0 + 15, blockZ0 + 15, HIER_PARAMS);
        if (keys.size() > SHARED_LAKE_CHUNK_WARN) {
            HierarchicalHydrology.Diag.layer("sharedLake", "chunkCandidatesOverWarn", keys.size());
        }
        List<SharedLakeDomain.Domain> doms = new ArrayList<>(keys.size());
        for (SharedLakeDomain.Key k : keys) {
            // #region agent log: L2 候选缝域首次成本及真实半径，实机确认后清理
            boolean diagnostic = com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled();
            long t0 = diagnostic ? System.nanoTime() : 0;
            int tiles = diagnostic ? gen.erosionTileCacheSize() : 0;
            long evictions = diagnostic ? gen.tileCacheStats().evictions() : 0;
            SharedLakeDomain.Domain d = domainFor(k);
            if (d.intersectsBox(blockX0, blockZ0, blockX0 + 15, blockZ0 + 15)) {
                d = resolveDomain(d);
                doms.add(d);
            }
            if (diagnostic && System.nanoTime() - t0 > 10_000_000L) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("L2", "shared-domain-" + k.label(),
                        "chunk=" + Math.floorDiv(blockX0, 16) + "," + Math.floorDiv(blockZ0, 16)
                                + " ms=" + (System.nanoTime() - t0) / 1e6 + " " + d.label()
                                + " half=" + d.geom().halfSpan() + " fineCells=" + d.fineCells()
                                + " tiles=" + tiles + "->" + gen.erosionTileCacheSize()
                                + " evictions=" + (gen.tileCacheStats().evictions() - evictions));
            }
            // #endregion
        }
        return doms;
    }

    /**
     * 逐列裁决（只读已解析域；无锁、无分配）。多域受控且平面不一致 ⇒ {@code COMPETING}。
     *
     * @param doms 本 chunk 预解析的候选缝域（{@link #resolveChunkDomains}）
     */
    private static SharedLakeDomain.Decision decideShared(List<SharedLakeDomain.Domain> doms,
                                                          int worldX, int worldZ) {
        SharedLakeDomain.Decision best = null;
        for (int i = 0; i < doms.size(); i++) {
            SharedLakeDomain.Decision d = doms.get(i).decide(worldX, worldZ);
            if (!d.controlled()) continue;
            if (best == null) { best = d; continue; }
            if (!samePlane(best, d)) {
                HierarchicalHydrology.Diag.SHARED_COMPETING.incrementAndGet();
                return new SharedLakeDomain.Decision(false, false, Double.NaN, null, -1,
                        SharedLakeDomain.Status.COMPETING);
            }
        }
        return best == null ? SharedLakeDomain.Decision.NONE : best;
    }

    /** 两条裁决是否同一平面（同键，或同湖布尔且水位一致）。 */
    private static boolean samePlane(SharedLakeDomain.Decision a, SharedLakeDomain.Decision b) {
        if (a.key().equals(b.key())) return true;
        return a.lake() == b.lake()
                && (!a.lake() || Math.abs(a.surface() - b.surface()) <= 1e-9);
    }

    /** 探针收尾用：放弃全部域（连同 region 缓存）—— 绝不让实验产物泄漏到后续段。 */
    static void clearSharedCaches() {
        synchronized (DOMAIN_CACHE) {
            DOMAIN_CACHE.clear();
        }
        DOMAIN_INFLIGHT.clear();
        DOMAIN_FINE_INFLIGHT.clear();
    }

    /** 域缓存规模（探针断言"有界"）。 */
    static int sharedDomainCacheSize() {
        synchronized (DOMAIN_CACHE) {
            return DOMAIN_CACHE.size();
        }
    }

    /** 域在途数（探针断言"单飞：并发同键只构建一次"）。 */
    static int sharedDomainInflightSize() {
        return DOMAIN_INFLIGHT.size() + DOMAIN_FINE_INFLIGHT.size();
    }

    // ---- 探针读视图（package-private；生产零影响）----

    /** 显式开关值（不含探针 before/after 覆盖）：探针断言"默认关、只 HIER 生效"。 */
    static boolean sharedDomainSwitch() { return SHARED_LAKE; }

    /** 域缓存容量上限（探针断言有界）。 */
    static int sharedDomainCacheCap() { return SHARED_LAKE_CACHE_CAP; }

    /** 每 chunk 候选缝域数的告警阈值（每轴 ≤2×2 ⇒ ≤8；仅计数，不是门）。 */
    static int sharedDomainChunkWarn() { return SHARED_LAKE_CHUNK_WARN; }

    /** 探针 before/after：只关【列改写】，不动缓存与域结果。 */
    static void setProbeSharedOff(boolean off) { sharedLakeProbeOff = off; }

    SharedLakeDomain.Domain preparedDomainForProbe(SharedLakeDomain.Key key) {
        return sharedActive() ? domainFor(key) : null;
    }

    /** 探针直取某缝域。 */
    SharedLakeDomain.Domain domainForProbe(SharedLakeDomain.Key key) {
        return sharedActive() ? resolveDomain(domainFor(key)) : null;
    }

    /**
     * ★ 2026-10-10【pass-1（双 pass 续流的"发送"阶段）】。
     *
     * <h3>为什么要分出 pass-1</h3>
     * <p>跨区续流必须让【接收方】用它自己的 {@code parent} 树追进来（方向场统一）。
     * 但接收方要知道"哪个邻区朝我吐了河"⇒ 得先有邻区的出口种子；而出口种子
     * 需要邻区跑完管线。若 {@code solve} 直接这么做，就会出现
     * <b>solve → 8 邻 solve → 64 邻 solve…</b> 的递归爆炸。</p>
     *
     * <p>⇒ 拆成两层：{@link #regionPass1} <b>只跑管线 + 无接力的骨架</b>
     * （产出 {@link DeterministicSkeleton.Result#exitSeeds}），<b>绝不触碰任何邻区</b>
     * ⇒ 扇出恒为 1；{@link #solve} 才取 8 邻的 pass-1 拼出 {@code RegionData}。</p>
     *
     * <p>⚠ 这也是 {@link #schedulePreheat} 改为预热 <b>pass-1</b> 而非 {@code solve}
     * 的原因 —— 否则 4 个预热各带 8 个邻区 ⇒ 32 次管线白跑。</p>
     */
    private static final LinkedHashMap<CacheKey, Pass1> P1_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    /**
     * 单飞在途表 —— 值是 {@link Pass1Task}（而非裸 {@code CompletableFuture}）：
     * 让"排队未开跑"的任务可被需求线程原子接管执行（见 {@link Pass1Task}）。
     */
    private static final ConcurrentHashMap<CacheKey, Pass1Task> P1_INFLIGHT =
            new ConcurrentHashMap<>();

    /**
     * pass-1 产物：管线结果 + 出口种子（无接力时的骨架产出）。
     *
     * @param pipe      管线结果（本区窗口的 level/parent/lake/sea/discharge/basinOutlet）
     * @param exitSeeds 走到本窗口边框的链（交给朝向的邻区接力）
     */
    private record Pass1(HydrologyPipeline.Result pipe,
                         List<DeterministicSkeleton.ExitSeed> exitSeeds,
                         int originX, int originZ) { }

    /**
     * pass-1 单飞任务。{@code QUEUED→RUNNING} 的 CAS 是唯一执行入口 —— 池线程与需求线程都可抢，
     * 抢到者在本线程跑、其余等待 {@link #future}。旧实现让"已入队未开跑"的预热任务也把需求线程
     * 挡在 {@code join} 后面，故改为可被接管。结果由执行者唯一发布（缓存 → complete → 撤在途表）；
     * 失败 {@code completeExceptionally} 且撤表 ⇒ 可重试、绝不缓存半成品。
     * 预热名额由 {@code counted} 任务恰好归还一次。
     */
    private final class Pass1Task {
        /** 0 = 已入队未开跑；1 = 正在执行；2 = 已落定（成功或失败）。 */
        private static final int QUEUED = 0, RUNNING = 1, DONE = 2;
        private final CacheKey key;
        private final int rx, rz;
        /** 是否占用预热队列名额 —— 只有 {@link #schedulePreheat} 入队的任务为 true。 */
        private final boolean counted;
        /** 名额是否仍被本任务持有（保证<b>恰好归还一次</b>）。 */
        private final AtomicBoolean slotHeld = new AtomicBoolean();
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final CompletableFuture<Pass1> future = new CompletableFuture<>();

        Pass1Task(CacheKey key, int rx, int rz, boolean counted) {
            this.key = key;
            this.rx = rx;
            this.rz = rz;
            this.counted = counted;
            this.slotHeld.set(counted);
        }

        /** 归还预热名额（幂等：CAS 保证至多一次）。 */
        private void releaseSlot() {
            if (counted && slotHeld.compareAndSet(true, false)) preheatQueue.decrementAndGet();
        }

        /**
         * 入池（只排队；执行权由 {@link #runIfClaimed} 的 CAS 决定）。
         * 池拒绝时归还名额并保持 {@code QUEUED}，任务留在在途表 ⇒ 由需求线程接管执行。
         */
        void enqueue() {
            try {
                preheat.execute(this::runIfClaimed);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                releaseSlot();
                LOGGER.warn("[HYDRO-MODEL] preheat rejected for ({},{}) — 留待需求线程执行: {}",
                        rx, rz, String.valueOf(e));
            }
        }

        /**
         * 原子接管并在本线程执行 —— 池线程与需求线程共用同一入口。
         *
         * @return true = 本线程抢到并已执行完（结果/异常已在 {@link #future} 上）；
         *         false = 别的线程正在执行或已落定 ⇒ 调用方应等待 {@link #future}
         */
        boolean runIfClaimed() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return false;
            releaseSlot();                                    // 已由本线程代跑 ⇒ 不再算池积压
            // 诊断：预热任务被【非池线程】抢走执行权 = 需求线程接管（下轮验收看这条计数）
            if (counted && com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()
                    && !Thread.currentThread().getName().contains("hydro-model-solve")) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("D",
                        "model-pass1-takeover", "region=(" + rx + "," + rz + ")"
                                + " thread=" + Thread.currentThread().getName());
            }
            Pass1 data = null;
            Throwable err = null;
            try {
                data = solvePass1(rx, rz);
            } catch (RuntimeException | Error e) {
                err = e;
            }
            try {
                if (err == null) {
                    cachePutPass1(key, data);                 // ★ 先发布缓存
                    future.complete(data);                    //   再完成（等待者立刻可取）
                } else {
                    future.completeExceptionally(err);
                }
            } finally {
                P1_INFLIGHT.remove(key, this);                // ★ 最后撤占位（失败同样撤 ⇒ 可重试）
                state.set(DONE);
            }
            return true;
        }
    }

    private final ExecutorService preheat;
    private final AtomicInteger preheatQueue = new AtomicInteger();

    // ---- 并发回归 hook（package-private；探针 ModelProviderConcurrencyProbe 消费）----
    // 默认零开销：计数/注入仅在 `-Dgeogenesis.hydrologyConcurrencyDiag=true` 时启用。
    // 只读视图（在途数/缓存命中/队列深度）探针用反射取字段，不在此加 getter。
    private static boolean concurrencyDiag() {
        return Boolean.getBoolean("geogenesis.hydrologyConcurrencyDiag");
    }

    /** region 键（{@code rx<<32 ^ rz}）。 */
    private static long regionKey(int rx, int rz) {
        return (((long) rx) << 32) ^ (rz & 0xffffffffL);
    }

    /** 每 region 的 pass-1 求解次数（诊断；证明"恰好一次"）。 */
    private static final ConcurrentHashMap<Long, AtomicInteger> PASS1_SOLVES =
            new ConcurrentHashMap<>();
    /** 首次求解某 region 的线程名（诊断；证明"需求线程接管"）。 */
    private static final ConcurrentHashMap<Long, String> PASS1_SOLVER_THREAD =
            new ConcurrentHashMap<>();

    private static void countPass1Solve(int rx, int rz) {
        long k = regionKey(rx, rz);
        PASS1_SOLVES.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
        PASS1_SOLVER_THREAD.putIfAbsent(k, Thread.currentThread().getName());
    }

    /** 诊断：某 region 的 pass-1 求解次数（0 = 未求解）。 */
    static int pass1SolveCount(int rx, int rz) {
        AtomicInteger c = PASS1_SOLVES.get(regionKey(rx, rz));
        return c == null ? 0 : c.get();
    }

    /** 诊断：首次求解某 region 的线程名（null = 未求解）。 */
    static String pass1SolverThread(int rx, int rz) {
        return PASS1_SOLVER_THREAD.get(regionKey(rx, rz));
    }

    /** 诊断：清空求解计数（探针分段隔离）。 */
    static void resetProbeCounters() {
        PASS1_SOLVES.clear();
        PASS1_SOLVER_THREAD.clear();
    }

    /**
     * 诊断：失败注入 —— 列出的 region 其下一次 pass-1 求解抛异常，之后恢复正常。
     * 仅 {@link #concurrencyDiag()} 开启时生效；默认空集 ⇒ 生产零开销、零行为变更。
     */
    private static final java.util.Set<Long> PASS1_FAIL_ONCE = ConcurrentHashMap.newKeySet();
    /** 已注入过失败的 region（保证"只失败一次"；探针按段清空）。 */
    static final java.util.Set<Long> PASS1_FAILED = ConcurrentHashMap.newKeySet();

    /** 探针武装失败注入（须先开 {@code hydrologyConcurrencyDiag}）。 */
    static void injectPass1Failure(int rx, int rz) {
        PASS1_FAIL_ONCE.add(regionKey(rx, rz));
    }

    /** 探针解除失败注入。 */
    static void clearPass1Failures() {
        PASS1_FAIL_ONCE.clear();
        PASS1_FAILED.clear();
    }

    /** 本实例的世界种子（缓存键的一部分；由 {@link #setSeed} 更新）。 */
    private volatile long seed;

    /**
     * 求解线程数 —— ★ 2026-10-08 实机驱动的修复。
     *
     * <h3>为什么不能是单线程（事故记录）</h3>
     * <p>原为 {@code newSingleThreadExecutor}。实机新建世界日志实测：
     * <pre>
     *   chunk(-3,-3) hydro=21303ms   ← 出生点预加载，单块水文 21.3 秒
     *   chunk(0,-3)  hydro=18140ms
     *   chunk(0,0)   hydro=9595ms
     *   之后 chunk(2,*) = 34~74ms    ← 缓存命中
     * </pre>
     * 机理：出生点周围有 <b>多个 region 首次求解</b>（每个 5~8 秒），
     * 而单线程池 + 每个 {@code regionFor} 都 {@code join()} 阻塞
     * ⇒ 4 个 region <b>串行</b> 5~8 秒 = 20+ 秒，用户体感"打开非常慢"。
     * （注意：骨架化把单次求解从 14~18s 降到 5~8s，但<b>串行叠加</b>吃掉了收益。）</p>
     *
     * <h3>为什么是 4 而不是核数</h3>
     * <p>本机 20 逻辑核（实测）。世界生成同期的 CPU 消耗方还有：
     * {@code Worker-Main}（地形 tile 采样）、{@code TileSampler}、
     * {@code commonPool}（侵蚀内部并行）、预览线程。
     * region 求解是<b>纯 CPU 密集</b>（单次 5~8s 打满一核），全给出去会饿死 tile 生成
     * ⇒ 反而拖长整体加载（本项目 2026-09 已有"线程超额订阅"事故）。
     * 4 是"并行吃掉串行叠加、又不抢光 tile 生成"的折中，可用
     * {@code -Dgeogenesis.modelSolveThreads} 覆盖。</p>
     */
    private static final int SOLVE_THREADS = Integer.getInteger("geogenesis.modelSolveThreads", 4);

    /**
     * @param seed 世界种子 —— <b>必需参数</b>（缓存键的一部分，见 {@link #SHARED_CACHE}）。
     *
     * <p>⚠ 刻意<b>不提供</b>无参重载：静默默认 {@code seed=0} 会让调用方
     * "忘了传种子"时以一个合法但错误的值缓存 region ⇒ 与传了真种子的实例
     * 各缓存一份、且两者产出不同（正是实机缺陷 B 的形态）。
     * 编译期强制显式传参，比运行期日志警告更可靠。</p>
     */
    public ModelHydrologyProvider(CellGenerator gen, long seed) {
        this.gen = gen;
        this.seed = seed;
        double hs = Math.max(0.01, gen.params().horizontalScale());
        this.invHs = 1.0 / hs;
        // ★ 实例指纹在此求值（不是 static）⇒ 多配置预览各持有自己的缓存身份。
        this.strategy = instanceStrategyFingerprint(gen);
        // ★ 多线程求解（见 SOLVE_THREADS javadoc）：region 之间互不依赖（各自纯函数），
        //   并行安全。solve() 路径内【零并行流】（已核查 HydrologyPipeline /
        //   EquilibriumWater / LakeWaterBalance / WindowOutlets 均无 parallel/commonPool）
        //   ⇒ 无嵌套并行死锁风险。
        this.preheat = Executors.newFixedThreadPool(SOLVE_THREADS, r -> {
            Thread t = new Thread(r, "geogenesis-hydro-model-solve");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 世界/种子切换：清空全部缓存（旧世界内容绝不复用）。
     * ★ static 缓存下，"清空"改为按 seed 隔离 —— 只丢弃本实例以外种子的条目，
     *   避免多实例并存时互相踩踏（实例 A 切种子不该毁掉实例 B 的有效缓存）。
     */
    public void setSeed(long newSeed) {
        if (this.seed == newSeed) return;
        this.seed = newSeed;
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        SHARED_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
        synchronized (P1_CACHE) {
            P1_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        P1_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
        // ★ 分层实验的粗层缓存：同样按 seed 隔离（否则换世界后命中旧世界的粗层权威）。
        synchronized (COARSE_CACHE) {
            COARSE_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        COARSE_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
        // ★ 跨 tract 缝共享湖域：同样按 seed 隔离（否则换世界后命中旧世界的湖平面）。
        synchronized (DOMAIN_CACHE) {
            DOMAIN_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        DOMAIN_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
        DOMAIN_FINE_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
    }

    /** 兼容旧语义：完全清空共享缓存（换世界时由 setSeed 按 seed 处理）。 */
    public void clear() {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.clear();
        }
        SHARED_INFLIGHT.clear();
        synchronized (P1_CACHE) {
            P1_CACHE.clear();
        }
        P1_INFLIGHT.clear();
        synchronized (COARSE_CACHE) {
            COARSE_CACHE.clear();
        }
        COARSE_INFLIGHT.clear();
        clearSharedCaches();
    }

    /**
     * 湖判定口径诊断（`-Dgeogenesis.lakeDiag=true`）——
     * 分离「粗格布尔命中」「邻域放宽命中」「逐块精算命中」三者，
     * 用于回答"游戏湖比正典少 95% 到底是哪一步丢的"。
     */
    static final boolean LAKE_DIAG = Boolean.getBoolean("geogenesis.lakeDiag");
    static final java.util.concurrent.atomic.AtomicLong lakeDiagTotal =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagCoarse =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagNear =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagBlock =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagCoarseButNotBlock =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagGainedByRefine =
            new java.util.concurrent.atomic.AtomicLong();

    /** 河道半宽直方图诊断（`-Dgeogenesis.riverWidthDiag=true`），桶 = floor(half) 0..15。 */
    static final boolean RIVER_WIDTH_DIAG = Boolean.getBoolean("geogenesis.riverWidthDiag");
    static final java.util.concurrent.atomic.AtomicLong[] riverHalfHist =
            new java.util.concurrent.atomic.AtomicLong[16];
    static {
        for (int i = 0; i < riverHalfHist.length; i++) {
            riverHalfHist[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    /** 河道半宽分布报告。 */
    public static String riverWidthReport() {
        long tot = 0;
        for (var a : riverHalfHist) tot += a.get();
        StringBuilder sb = new StringBuilder("【河道半宽分布】(floor(half) 桶)\n");
        for (int i = 0; i < riverHalfHist.length; i++) {
            long v = riverHalfHist[i].get();
            if (v == 0) continue;
            sb.append(String.format("  half∈[%d,%d) : %d (%.1f%%)%n",
                    i, i + 1, v, 100.0 * v / Math.max(1, tot)));
        }
        sb.append(String.format("  合计 %d%n", tot));
        return sb.toString();
    }

    // ==========================================================================
    // ★★★ 2026-10-09【河道断点归因诊断 `-Dgeogenesis.riverBreakDiag=true`】★★★
    //
    // 【用户判据】「是真的有断的地方，不是视觉问题」。
    //
    // 【为什么需要】适配层有**两道门**，此前只诊断了第一道：
    //   门 1 `p.channel()[k]`   —— 粗格布尔说"这里是不是河道"
    //   门 2 `surface > carved + 0.5` —— 水柱 ≥0.5 格才真的出水
    // ⇒ 两类"断"必须分开数，否则永远说不清断在哪一步：
    //   (a) `onChannel=true` 但 `fill=false`  ⇒ 【有河道没水】= 真断（用户看到的）
    //   (b) `onChannel=false` 但距离场命中  ⇒ 粗格布尔把它判掉了
    // ==========================================================================
    static final boolean RIVER_BREAK_DIAG = Boolean.getBoolean("geogenesis.riverBreakDiag");
    static final java.util.concurrent.atomic.AtomicLong brkOnChannel =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkNoWater =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkDistHitButOff =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkWaterOk =
            new java.util.concurrent.atomic.AtomicLong();
    /** 断点样本（block 坐标 + 差值），最多记 12 个。 */
    static final java.util.List<String> brkSamples =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static String riverBreakReport() {
        long on = brkOnChannel.get(), noW = brkNoWater.get();
        long off = brkDistHitButOff.get(), ok = brkWaterOk.get();
        StringBuilder sb = new StringBuilder("【河道断点归因】(方块级)\n");
        sb.append(String.format("  onChannel=true  总格 = %d%n", on));
        sb.append(String.format("   ├ 出水 √          = %d (%.1f%%)%n", ok,
                100.0 * ok / Math.max(1, on)));
        sb.append(String.format("   └ ★有河却没水 ✗    = %d (%.1f%%)  ← 真断（用户看到的）%n", noW,
                100.0 * noW / Math.max(1, on)));
        sb.append(String.format("  距离场命中但 onChannel=false = %d  ← 粗格布尔误杀%n", off));
        sb.append("  断点样本（blockX,blockZ | surface,carved,差）:\n");
        synchronized (brkSamples) {
            for (String s : brkSamples) sb.append("    ").append(s).append('\n');
        }
        return sb.toString();
    }

    // ==========================================================================
    // 2026-10-10 确定性骨架（配置项 `hydrologyDeterministicSkeleton`）
    //
    // 用户方向（HYDROLOGY.md 用户口径②③ + 2026-10-10 会话）：
    //   河流做成确定性骨架（PL-RGA 式沿 minimax 树追踪 + 湖溢口续流），
    //   model 链是 legacy 链的升级版 —— legacy 的既有机制必须继承。
    //
    // 开关 true 时用 DeterministicSkeleton 替代 RiverCenterlines（累积->阈值->细化）；
    // false = 旧路径（A/B 对照基线）。
    //
    // ★★★ 2026-10-10【重大发现：此开关过去只在探针里生效，实机从未生效】★★★
    // 【症状】用户判据「将测试窗口的水文转换适配生产环境」。
    // 【根因】原实现是 `Boolean.getBoolean("geogenesis.skeletonTrace")` ——
    //   **纯 `-D` 系统属性**：探针命令行总是带 `-Dgeogenesis.skeletonTrace=true`
    //   ⇒ 探针走骨架；而**玩家启动游戏时不存在该属性** ⇒ 实机走【旧路径 RiverCenterlines】
    //   ⇒ 本会话十几轮形态修复（孤立段 −53% / 交叉 −62% / 对角补点 / 末端伸入水体…）
    //     **在实机上完全没有生效**。
    // 【铁证】实机日志 `HYDRO-MODEL reaches=15~26`（旧路径：窄、少），
    //   而探针日志 `skeleton routing ACTIVE ... 7~25 rivers` ⇒ 两条不同代码线。
    // 【修法】改为**配置项优先**（`GeoGenesisConfig.hydrologyDeterministicSkeleton`，
    //   默认 true）+ 系统属性可覆盖（探针 A/B 仍用 `-D...=false`）。
    // ==========================================================================
    static final boolean SKELETON_TRACE = resolveSkeletonTrace();

    /**
     * 解析骨架开关：<b>配置项优先</b>，系统属性可覆盖（探针 A/B 用）。
     *
     * <p>无 Forge 环境（探针进程）时读配置会抛 ⇒ 回退系统属性（旧行为）。</p>
     */
    private static boolean resolveSkeletonTrace() {
        String prop = System.getProperty("geogenesis.skeletonTrace");
        if (prop != null) return Boolean.parseBoolean(prop);   // 探针显式指定优先
        try {
            return com.geogenesis.config.GeoGenesisConfig.INSTANCE
                    .hydrologyDeterministicSkeleton.get();
        } catch (RuntimeException e) {
            return false;   // 无配置环境（探针未指定）⇒ 保持旧的默认
        }
    }

    /**
     * 双 pass 跨区续流总开关（`-Dgeogenesis.skeletonCrossPass=false` 关闭）。
     *
     * <p>关 = 完全不取邻区出口种子（等价于"无跨区续流"），用于 A/B 归因；
     * 开（默认）= {@link #collectIncoming} 给 pass-2 注入接力种子。</p>
     */
    static final boolean CROSS_PASS =
            !"false".equals(System.getProperty("geogenesis.skeletonCrossPass"));

    /**
     * 断口现场 watch（`-Dgeogenesis.gapWatch=x1,z1;x2,z2`）：
     * 对指定世界坐标方块打印落块判据全链（isLake/half/dist/onChannel/fill/nearBorder）
     * ⇒ 一次跑定位"断口是几何未覆盖、湖掩码覆盖、还是落块门控"。
     */
    static final java.util.Set<Long> GAP_WATCH = parseGapWatch();

    private static java.util.Set<Long> parseGapWatch() {
        java.util.Set<Long> s = new java.util.HashSet<>();
        String v = System.getProperty("geogenesis.gapWatch", "");
        for (String part : v.split(";")) {
            String[] xy = part.trim().split(",");
            if (xy.length != 2) continue;
            try {
                long x = Long.parseLong(xy[0].trim());
                long z = Long.parseLong(xy[1].trim());
                s.add((x << 32) ^ (z & 0xffffffffL));
            } catch (NumberFormatException ignored) { }
        }
        return s;
    }

    /** 端点类型表收集（`-Dgeogenesis.skeletonEndDiag=true`，断口归因用）。 */
    static final boolean SKELETON_END_DIAG = Boolean.getBoolean("geogenesis.skeletonEndDiag");

    /** 源头门限诊断（`-Dgeogenesis.logSrc=true`）：打印 srcAccum / runoff / maxQ。 */
    static final boolean LOG_SRC = Boolean.getBoolean("geogenesis.logSrc");

    private static double maxDischarge(double[] d) {
        double m = 0;
        for (double v : d) if (Double.isFinite(v) && v > m) m = v;
        return m;
    }
    static final java.util.List<String> SKELETON_ENDS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 骨架折线 dump（`-Dgeogenesis.skelDump=x,z,r`）：
     * 打印所有经过 [x±r, z±r] 方块的折线点序列 ⇒ 断口现场的直接证据
     * （点序列有无洞、洞在哪两点之间，一次说清）。
     */
    static final String SKEL_DUMP = System.getProperty("geogenesis.skelDump", "");

    /** 骨架模式的 region 产物：日志用 cl（Reach 表）+ 落块用亚格段索引 + 出口种子（接力用）。 */
    private record SkeletonRegion(RiverCenterlines.Result cl, SegmentIndex seg,
                                  List<DeterministicSkeleton.ExitSeed> exitSeeds) { }

    /**
     * 从 {@link DeterministicSkeleton} 构建 region 骨架。
     *
     * <p><b>参数口径</b>（全部对齐 legacy，见 {@code DeterministicSkeleton.Params}）：</p>
     * <ul>
     *   <li>源头间距 = {@code -Dgeogenesis.sourceSpacing}（默认 24 块，折算 /STEP 格）；</li>
     *   <li>源头面积下限 = {@code n² × 0.003} 集水（legacy 骨架
     *       {@code thr = gridN²×0.003} 同值；{@code -Dgeogenesis.skeletonSourceFrac} 可 A/B）；</li>
     *   <li>就近汇入窗口 = 2 格（legacy {@code traceStep}）；</li>
     *   <li>meander 默认关（amp=0，用户 2026-10-10："弯曲先移除，我后面重新设计"）；
     *       重设计时用 {@code -Dgeogenesis.skeletonMeanderAmp}（legacy 默认 6.0 / 波长 24）开；</li>
     *   <li>分支上限 {@code -Dgeogenesis.skeletonMaxRivers}（legacy {@code riverCount} 同义；0=关）。</li>
     * </ul>
     *
     * <p><b>几何源</b>：形态收尾后是亚格 block 折线（Catmull-Rom + 格点吸附），
     * 与落块侧 {@link SegmentIndex} 同一坐标语义 ⇒ 逐块连续、无 STEP 方块化。
     * 宽度/深度恒定最小可见值（用户方针：宽度后续用流量平滑处理）。</p>
     *
     * @param incomingCells 接力种子（<b>本区</b>格索引；来自 8 邻 pass-1 的出口种子。
     *                      空 = pass-1（不接力，只产出出口种子））
     * @param primary       true = 主 pass（打日志 / 收集端点表）；
     *                      false = pass-1（静音 —— 否则每个 region 的日志翻倍）
     */
    private SkeletonRegion skeletonRegion(HydrologyPipeline.Result p,
                                          int originX, int originZ,
                                          List<Integer> incomingCells, boolean primary) {
        int n = p.nx();
        // 源头间距（block）：legacy `sourceSpacingCells=2` × SKELETON_CELL_BLOCKS=8
        //   ⇒ 16 block（其注释写"≈48wu"= 24wu ≈ 48 block，按 wu 口径；骨架路径按格 ⇒ 16 block）。
        //   我方沿用 block 语义 ⇒ 默认 24 block（略宽于 legacy 16，抑制过密）。
        int spacingBlocks = Integer.getInteger("geogenesis.sourceSpacing", 24);
        int spacingCells = Math.max(1, Math.round(spacingBlocks / (float) STEP));
        double runoff = p.lakeResult() == null ? 0.0 : p.lakeResult().runoffPerCell();
        double meanderAmp = Double.parseDouble(
                System.getProperty("geogenesis.skeletonMeanderAmp", "0.0"));
        // ★★★ 2026-10-10【默认 0 = 不限制 —— 实测归因后的决定，勿凭"legacy 有 80"就照抄】★★★
        //
        // 【我曾设 27】理由："按每 1000 block 边长折算 legacy riverCount=80"。
        // 【实测证否（同参 r=700 单变量 A/B）】：
        //   全开(maxRivers=27) ⇒ 河道 14000 格 / 分量 5297
        //   关掉(maxRivers=0)  ⇒ 河道 22475 格 / 分量 7995
        //   ⇒ 河道 −38% 几乎【全是这个上限砍的】，且【最大连通分量 4252 → 1823（−57%）】
        //      = 主干链被数量上限切断（用户判据"截断"反而恶化）。
        // 【为什么 legacy 能用 80 而我方不能用】legacy `riverCount` 计的是
        //   【commitRiver 的尝试数】，且它的 region 含 margin 后是 1536 block 边长
        //   （640wu × hs2.0 = 1280 block，再加 SKELETON_MARGIN 128×2）⇒ 面积是我方的 9 倍。
        //   且 legacy 的源头候选已被 `SOURCE_MIN_ACCUM_CELLS` 等判据大幅收窄，
        //   80 是"上限"而非常规命中数。直接照抄数字 = 刻舟求剑。
        // 【结论】不设上限，交给"源头判据 + minNodes + 自贴近"去控密度。
        int maxRivers = Integer.getInteger("geogenesis.skeletonMaxRivers", 0);
        // ★★★ 2026-10-10【成河门限改为【绝对集水面积】—— 这是"短碎河流"的真正根因】★★★
        //
        // 【旧口径（错）】门限 = n² × frac = 窗口格数的 0.3%。
        // 【为什么错】"成河"是物理判据（绝对集水面积），不是"占窗口的比例"。
        //     legacy 窗口 = 192 格 × 8 block ⇒ 0.3% = **110 格** ≈ 7078 block²
        //     我方窗口 = 177 格 × 4 block ⇒ 0.3% = **94 格** ≈ 1504 block²
        //   ⇒ 我方门限只有 legacy 的 1/4.7 ⇒ 遍地都是"河"。
        //
        // 【换算（★ 单位实测校准过，勿凭直觉改）】
        //   `runoffPerCell = precipitation × runoffCoeff × cellArea`（LakeWaterBalance:392），
        //   `RiverNetwork.build` 里 `discharge[k]` 初值就是它、沿 parent 累加
        //   ⇒ **discharge 单位 = 上游格数 × runoffPerCell**。
        //   实测（正典 r=300）：`runoff=0.01920`、`maxQ≈234`、`P99≈57`
        //   ⇒ legacy 等价门限 = 440 格 × 0.0192 = **8.45**（不是 7078！）。
        //   ⚠ 我一度把门限当成 block² 直接写 7078 ⇒ 高于 maxQ ⇒ `sources=0`，
        //     而残存的河【全部来自 basinOutlet 强制起链】（见 DeterministicSkeleton
        //     的溢口门限说明）⇒ 那才是"提高门限看似无效"的原因。
        //
        //   公式：srcAccum = 目标格数 × runoff，目标格数 = 110 × (8/STEP)² = 440
        double chanMinCells = Double.parseDouble(
                System.getProperty("geogenesis.channelMinCells", "150"));
        double srcAccum = runoff > 0 ? chanMinCells * runoff : 0.0;
        // ★★★ 2026-10-10【三项 legacy 机制补齐（用户判据驱动）】★★★
        //
        // 【用户报的三个问题】① 很多短碎河流 ② 一小部分截断 ③ 反复交叉/重叠。
        // 【共同根因】我只移植了 legacy 的"追踪 + 汇入"，漏了它【过滤】的一半：
        //   · minRiverNodes（长度下限）—— 短段不丢 ⇒ 短碎河流；
        //   · riverCount（数量上限）—— 源头不限 ⇒ 河网过密；
        //   · SELF_APPROACH（自贴近守卫）—— 只排自己节点 ⇒ 擦身绕行 = 交叉重叠。
        //
        // 【尺度换算（关键）】legacy `buildSkeletonRivers` 用 `SKELETON_CELL_BLOCKS=8`
        //   （block），我方 STEP=4 ⇒ **我方 1 格 = legacy 半格** ⇒ legacy 的格数阈值
        //   须**乘 2** 才等价：
        //     minRiverNodes  3 格(legacy) × 8 ÷ 4 = **6 格**（我方）
        //     SELF_APPROACH_RADIUS 2 格 × 8 ÷ 4 = **4 格**（我方）
        //     SELF_APPROACH_EXEMPT 6 步 × 2 = **12 步**（我方）
        //   ⚠ 若直接照抄 legacy 数字，判据会【过松】（半径 2 格在我方只覆盖 8 block）。
        int legacyToOurs = Math.max(1, 8 / STEP);                  // 8 block / STEP
        // ★ 单变量 A/B 开关（2026-10-10 归因用）—— 每项可独立关掉（0 = 关）：
        //   -Dgeogenesis.skeletonMinNodes=6 / skeletonSelfRadius=4 / skeletonMaxRivers=27
        // ★★★ 2026-10-10【selfRadius 默认 0 —— 它无贡献且制造孤立段】★★★
        // 【无贡献】单变量 A/B 实测：关它 14119 格 vs 全开 14000（差 0.8%）⇒
        //   防交叉的主力是 segmentCrossesAny（交叉守卫），不是它。
        // 【有害】用户圈出孤立段（既不连湖也不入海）⇒ skelDump 铁证：
        //   poly#18 end=DEAD_END mouth=(-416,468) 不在窗口边框 —— 是自贴近守卫
        //   误触发（大弯河曲相隔 >12 步 ⇒ 豁免放不过 ⇒ "贴着自己"就地终止）。
        //   终止的链既不接湖也不接海 ⇒ 凭空断流。legacy 同名机制由"洼地续流"兜底，
        //   我方无该兜底 ⇒ 直接关闭（候选：日后给自贴近终止加专用 End + 兜底）。
        int minNodes = Integer.getInteger("geogenesis.skeletonMinNodes", 3 * legacyToOurs);
        int selfRadius = Integer.getInteger("geogenesis.skeletonSelfRadius", 0);
        int selfExempt = Integer.getInteger("geogenesis.skeletonSelfExempt", 6 * legacyToOurs);
        var skParams = new DeterministicSkeleton.Params(
                spacingCells,
                srcAccum,                            // ★ 绝对集水面积门限（见上方 javadoc）
                2,                                   // 就近汇入窗口（legacy traceStep）
                meanderAmp, 24.0,
                maxRivers,
                minNodes,                            // legacy minRiverNodes=3
                selfRadius,                          // legacy SELF_APPROACH_RADIUS=2
                selfExempt);                         // legacy SELF_APPROACH_EXEMPT=6
        var sk = DeterministicSkeleton.build(
                p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.lakeResult() == null ? null : p.lakeResult().basinOutlet(),
                n, n, skParams, incomingCells, p.bed(),
                // ★★ 分层实验：**构建时显式关闭**网络过滤（Boolean.FALSE）。
                //    理由（失败规范）：{@code filterUnconnected} 会整簇删除"不接水体"的链
                //    ⇒ 拿它的产出当连通性证据 = 用删除掩盖"支流接不进下游接口"的失败。
                //    只记日志然后继续 ❌ ⇒ 这里真的不删；孤立由独立审计如实计入并判 FAIL。
                //    既有生产路径传 null ⇒ 逐位不变。
                HIER ? Boolean.FALSE : null);
        if (HIER) {
            // 回传事实（探针可断言"实验构建确实关了过滤且未删链"）。
            HierarchicalHydrology.Diag.skeletonBuild(sk.networkFilterEnabled,
                    sk.droppedByNetworkFilter);
            // #region agent log: R2 incoming 被吃掉的总量（不改几何）
            if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R2", "incoming-" + originX + "-" + originZ,
                        "requested=" + (incomingCells == null ? 0 : incomingCells.size())
                                + " accepted=" + sk.incomingStarts + " fineWindowExits=" + sk.exitSeeds.size());
                for (var d : sk.incomingDiagnostics) {
                    int bx = originX + (d.start() / n) * STEP;
                    int bz = originZ + (d.start() % n) * STEP;
                    com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R5",
                            "incoming-detail-" + originX + "-" + originZ + "-" + d.start(),
                            "point=" + bx + "," + bz + " status=" + d.status()
                                    + " nodes=" + d.nodes() + " end=" + d.end()
                                    + " mouth=" + (d.mouth() < 0 ? "-" :
                                    (originX + (d.mouth() / n) * STEP) + ","
                                            + (originZ + (d.mouth() % n) * STEP)));
                }
            }
            // #endregion
        }
        if (SKELETON_END_DIAG || LOG_SRC) {
            LOGGER.info("[HYDRO-SRC] srcAccum={} runoff={} traces={} sources={} maxQ={}",
                    String.format("%.1f", srcAccum), String.format("%.5f", runoff),
                    sk.traces.size(), sk.sourcesTotal, String.format("%.1f", maxDischarge(p.discharge())));
        }
        // 亚格几何（Catmull-Rom + 格点吸附）→ SegmentIndex（落块用）
        var shaped = DeterministicSkeleton.shape(sk.traces, p.level(),
                originX, originZ, STEP, n, n, skParams, p.bed());
        List<DeterministicSkeleton.Shaped> allPolys = shaped;
        // ★ 诊断（`-Dgeogenesis.joinChk=true`）：JOIN 点的像素是否重合
        //   —— 共享锚点表若生效，两条链在该格应产出【逐位相同】的像素。
        if (Boolean.getBoolean("geogenesis.joinChk")) {
            var cellOwner = new java.util.HashMap<Integer, Integer>();
            for (int ti = 0; ti < shaped.size() && ti < sk.traces.size(); ti++) {
                for (int c : sk.traces.get(ti).cell) cellOwner.putIfAbsent(c, ti);
            }
            int joins = 0, exact = 0, adjacent = 0, mid = 0, far = 0;
            var hist = new java.util.TreeMap<Integer, Integer>();
            for (int ti = 0; ti < shaped.size() && ti < sk.traces.size(); ti++) {
                var tr = sk.traces.get(ti);
                if (tr.end != DeterministicSkeleton.End.JOIN) continue;
                var sh = shaped.get(ti);
                if (sh.size() == 0) continue;
                Integer ow = cellOwner.get(tr.mouthCell());
                if (ow == null || ow == ti) continue;
                joins++;
                int px = (int) Math.rint(sh.x[sh.size() - 1]);
                int pz = (int) Math.rint(sh.z[sh.size() - 1]);
                var osh = shaped.get(ow);
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < osh.size(); i++) {
                    int d = Math.max(Math.abs((int) Math.rint(osh.x[i]) - px),
                                     Math.abs((int) Math.rint(osh.z[i]) - pz));
                    if (d < best) best = d;
                }
                hist.merge(Math.min(best, 9), 1, Integer::sum);
                if (best == 0) exact++;
                else if (best == 1) adjacent++;
                else if (best <= 3) mid++;
                else far++;
            }
            LOGGER.info("[JOINCHK] origin({},{}) joins={} exact={} adjacent={} 2-3={} far={} hist={}",
                    originX, originZ, joins, exact, adjacent, mid, far, hist);
        }
        // 断口现场：dump 折线点序列（`-Dgeogenesis.skelDump=x,z,r`，多框用 ';' 分隔）
        if (!SKEL_DUMP.isEmpty()) {
            for (String box : SKEL_DUMP.split(";")) {
                String[] pt = box.trim().split(",");
                if (pt.length < 2) continue;
                double qx = Double.parseDouble(pt[0].trim());
                double qz = Double.parseDouble(pt[1].trim());
                double qr = pt.length > 2 ? Double.parseDouble(pt[2].trim()) : 24;
                boolean anyHit = false;
                for (int pi = 0; pi < shaped.size(); pi++) {
                    var sh = shaped.get(pi);
                    boolean near = false;
                    for (int i = 0; i < sh.size(); i++) {
                        if (Math.abs(sh.x[i] - qx) <= qr && Math.abs(sh.z[i] - qz) <= qr) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) continue;
                    anyHit = true;
                    var tr = sk.traces.get(pi);
                    int sc0 = tr.sourceCell(), mc0 = tr.mouthCell();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < sh.size(); i++) {
                        if (Math.abs(sh.x[i] - qx) <= qr + 10
                                && Math.abs(sh.z[i] - qz) <= qr + 10) {
                            sb.append(String.format("(%.1f,%.1f)", sh.x[i], sh.z[i]));
                        }
                    }
                    LOGGER.info("[SKEL-DUMP] box({},{}) poly#{} n={} end={} fromOutlet={}"
                                    + " src=({},{}) mouth=({},{}) 近区点: {}",
                            qx, qz, pi, sh.size(), tr.end, tr.fromOutlet,
                            originX + (sc0 / n) * STEP, originZ + (sc0 % n) * STEP,
                            originX + (mc0 / n) * STEP, originZ + (mc0 % n) * STEP,
                            sb);
                }
                if (!anyHit && Math.abs(originX - (qx - 352)) < 300) {
                    LOGGER.info("[SKEL-DUMP] box({},{}) 本窗口无折线经过", qx, qz);
                }
            }
        }
        SegmentIndex seg = allPolys.isEmpty() ? SegmentIndex.EMPTY
                : SegmentIndex.buildFromNodes(allPolys);
        // 格路径 Reach（日志 reachCount 用；宽度恒最小可见值）
        List<RiverCenterlines.Reach> reaches = new ArrayList<>();
        for (var t : sk.traces) {
            int m = t.nodeCount();
            int[] cell = new int[m];
            double[] q = new double[m];
            double[] w = new double[m];
            double[] d = new double[m];
            for (int i = 0; i < m; i++) {
                cell[i] = t.cell[i];
                q[i] = p.discharge()[cell[i]];
                w[i] = 1.0;    // 全宽 1 块（半宽 0.5）—— 用户方针：宽度后续再说
                d[i] = 1.0;
            }
            reaches.add(new RiverCenterlines.Reach(cell, q, w, d, 0));
        }
        int mouthsLake = 0, mouthsSea = 0, joins = 0, unresolved = 0, deadEnd = 0;
        for (var t : sk.traces) {
            switch (t.end) {
                case MOUTH_LAKE -> mouthsLake++;
                case MOUTH_SEA -> mouthsSea++;
                case JOIN -> joins++;
                case UNRESOLVED -> unresolved++;
                case DEAD_END -> deadEnd++;
            }
        }
        // DEAD_END = parent=-1 且非海非湖（窗口排水根）—— 断口归因的头号嫌疑。
        if (primary) {
            LOGGER.info("[HYDRO-SKELETON] traces={} sources={} droppedBySpacing={} outletStarts={}"
                            + " incomingStarts={} ends: lake={} sea={} join={} unresolved={}"
                            + " DEAD_END={} exitSeeds={}",
                    sk.traces.size(), sk.sourcesTotal, sk.sourcesDroppedBySpacing,
                    sk.outletStarts, sk.incomingStarts, mouthsLake, mouthsSea, joins,
                    unresolved, deadEnd, sk.exitSeeds.size());
        }
        // 端点表收集（与图上断口坐标对表用）
        if (SKELETON_END_DIAG && primary && originX != Integer.MIN_VALUE) {
            for (var t : sk.traces) {
                int mc = t.mouthCell();
                int wx = originX + (mc / n) * STEP;
                int wz = originZ + (mc % n) * STEP;
                String extra = "";
                if (t.end == DeterministicSkeleton.End.DEAD_END) {
                    // DEAD_END 属性（判定它是否窗口边框种子格）：parent / frame / level
                    int mci = mc / n, mcj = mc % n;
                    boolean frame = mci == 0 || mcj == 0 || mci == n - 1 || mcj == n - 1;
                    extra = String.format(",p=%d,frame=%b,lvl=%.2f,sea=%b",
                            p.parent()[mc], frame, p.level()[mc], p.sea()[mc]);
                }
                SKELETON_ENDS.add(wx + "," + wz + "," + t.end + extra);
            }
        }
        return new SkeletonRegion(RiverCenterlines.fromReaches(reaches, n, n, STEP), seg,
                sk.exitSeeds);
    }

    /**
     * 已求解 region 的【网格级湖格总数】累计（诊断用）——
     * 用于判定"湖是窗口丢的还是适配层丢的"：
     * 网格湖格 × step² ≈ 应有的方块湖面积，与探针实测湖列数比较即得保留率。
     */
    static final java.util.concurrent.atomic.AtomicLong regionLakeCells =
            new java.util.concurrent.atomic.AtomicLong();

    /** 网格级湖格累计（各 region 累加，含重叠区 ⇒ 仅为量级对照）。 */
    public static long lastRegionLakeCells() {
        return regionLakeCells.get();
    }

    /** 打印湖判定诊断汇总（探针在结束时调用）。 */
    public static String lakeDiagReport() {        return String.format(
                "【湖判定诊断】总列=%d%n"
                + "  粗格布尔命中(coarseLake)=%d%n"
                + "  邻域放宽后(nearLake)=%d%n"
                + "  逐块精算命中(isLakeBlock)=%d%n"
                + "  粗格真但精算否=%d  ← 精算【杀掉】的量%n"
                + "  邻域放宽新增=%d%n",
                lakeDiagTotal.get(), lakeDiagCoarse.get(), lakeDiagNear.get(),
                lakeDiagBlock.get(), lakeDiagCoarseButNotBlock.get(),
                lakeDiagGainedByRefine.get());
    }

    /**
     * 求（或建）指定 chunk 的 256 列雕刻计划。
     *
     * @param chunkX,chunkZ chunk 坐标
     * @param originalCells 该 chunk 的【无侵蚀】基线 Cell（HydrologyChunkSampling 产物）
     *                       —— 只取 {@code height} 作 originalGroundY
     * @return 256 列（顺序与调用方惯例一致：{@code cells[lx*16+lz]}）
     */
    public List<HydrologyBlockCarvedColumn> columnsFor(int chunkX, int chunkZ, Cell[] originalCells) {
        if (originalCells == null || originalCells.length != 256) {
            throw new IllegalArgumentException("originalCells must be 256");
        }
        int blockX0 = chunkX * 16, blockZ0 = chunkZ * 16;
        // 中心点入区（floorDiv：负坐标不能用 / 截断）
        int centerBlockX = blockX0 + 8, centerBlockZ = blockZ0 + 8;
        RegionData rd = regionFor(centerBlockX, centerBlockZ);
        schedulePreheat(Math.floorDiv(centerBlockX, REGION), Math.floorDiv(centerBlockZ, REGION));

        // 区域窗口内子索引（整除性：origin ≡ 0 mod STEP 由布局保证）
        int gi0 = Math.floorDiv(blockX0 - rd.originX, STEP);
        int gj0 = Math.floorDiv(blockZ0 - rd.originZ, STEP);
        HydrologyPipeline.Result p = rd.pipe();
        int n = p.nx();

        // 段空间索引（每 region 建一次：tile → 段下标；64-block tile，容差 12 < 64）
        // 骨架模式直接用求解期建好的亚格段索引（Catmull-Rom + 格点吸附后的连续折线）；
        // 不能从 reaches 重建（那会退回网格锚点、丢掉曲线）。
        SegmentIndex segIndex = rd.skeletonSeg() != null ? rd.skeletonSeg()
                : (rd.centerline().reaches.isEmpty()
                ? SegmentIndex.EMPTY : SegmentIndex.build(rd.centerline(), rd.originX(), rd.originZ(), n, STEP));

        // ★★★ 2026-10-09【3×3 邻 region 合并 —— 修"region 缝上断半截"】★★★
        //
        // 【问题】原实现只用【本方块的 region】（`rd`）。相邻 region 各自求解
        //   （各自的窗口、各自的海/无海、各自的盆地）⇒ 边界两侧判定不一致
        //   ⇒ 视觉上"湖/河在 region 缝上断成半截"。
        //
        // 【实测】（1401² 出图，512 块 region 网格，逐缝统计）：
        //   河-竖缝：跨缝对 414，单侧断开 146（35.3%）
        //   河-横缝：跨缝对 383，单侧断开 223（58.2%）
        //   湖-竖缝：跨缝对 172，单侧断开  76（44.2%）
        //   ⇒ 横缝上 58% 的河道在边界处断开 —— 正是用户"放实机就断"的直接证据。
        //
        // 【正解 = legacy 链早已在做的做法】`RiverLineNetwork.sampleAll`
        //   （第 4421-4427 行）对 **3×3 邻 region** 求并集：本区没有的水，
        //   邻区若判定有则照常输出 ⇒ 缝两侧都能看到同一份水。
        //   本处照搬：遍历 3×3 邻区，取（湖优先，其次最近的河道命中）。
        //
        // ⚠ 邻区求解走 `regionFor`（同步、有缓存、有单飞）⇒ 不引入顺序依赖
        //   （region 内容是 (seed, 坐标) 纯函数，缓存只改时序）。
        // ⚠⚠⚠ 2026-10-09【性能修复：邻区改为【惰性】加载 —— 别再无条件取 9 个】⚠⚠⚠
        //
        // 【事故】原写法在**每个 chunk** 开头无条件遍历 9 个邻区并调 `regionFor`：
        //   ```
        //   for (int t = 0; t < 9; t++) {
        //       neigh[t] = regionFor(...);                     // ← 可能触发求解
        //       neighSeg[t] = SegmentIndex.build(...);          // ← 还重建段索引
        //   }
        //   ```
        //   ⇒ 每 chunk 的 region 工作量 **×9**。
        //   实测（正典规格 8281 chunk）：**580s → 791s（+36%）**；
        //   且 `-Dgeogenesis.modelStep=1` 下**直接跑不完**（用户："缩小都跑不完"）。
        //
        // 【正解】惰性：只在**本区判定为空**、且该方块**贴近 region 边界**时，
        //   才去取对应邻区。理由：
        //     · 本区窗口 = `[rx*REGION−HALO, rx*REGION+REGION+HALO]`
        //       ⇒ 已完整覆盖本 region 并外扩 HALO=96 块 ⇒ **内部方块无需邻区**；
        //     · 只有边界带（±HALO）才可能出现"本区说没有、邻区说有"。
        //   ⇒ 绝大多数方块**零邻区开销**（原来每个方块都要扫 9 个）。
        int rdRx = Math.floorDiv(centerBlockX, REGION), rdRz = Math.floorDiv(centerBlockZ, REGION);
        // 本 region 的块范围（用于判断"是否贴近边界"）
        int regLoX = rdRx * REGION, regHiX = regLoX + REGION;
        int regLoZ = rdRz * REGION, regHiZ = regLoZ + REGION;
        // 邻区缓存（惰性填充；下标 = t，与 neighDx/neighDz 对齐；未取 = null）
        RegionData[] neigh = new RegionData[9];
        SegmentIndex[] neighSeg = new SegmentIndex[9];
        int[] neighDx = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
        int[] neighDz = {-1, -1, -1, 0, 0, 0, 1, 1, 1};
        neigh[4] = rd;
        neighSeg[4] = segIndex;

        List<HydrologyBlockCarvedColumn> out = new ArrayList<>(256);
        // ★ 跨 tract 缝共享湖域：逐 chunk 【一次】预解析候选缝域（键 ≤8/chunk），逐列只读裁决。
        //   ⚠【不设缝带门】：控制体是整个跨缝盆地足迹（可达缝两侧 ±REGION/2）；按 ±HALO 门只查缝附近
        //     会把足迹在带外的部分留给本区细窗 ⇒ 只是把墙挪 ±HALO。故每 chunk 都解析（键由世界坐标唯一导出）。
        List<SharedLakeDomain.Domain> sharedDoms = sharedActive()
                ? resolveChunkDomains(blockX0, blockZ0) : List.of();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int blockX = blockX0 + lx, blockZ = blockZ0 + lz;
                double original = originalCells[lx * 16 + lz].height;
                // 网格索引（4-block 网格 → 最近格点）
                int gi = clamp((int) Math.round((blockX - rd.originX) / (double) STEP), n);
                int gj = clamp((int) Math.round((blockZ - rd.originZ) / (double) STEP), n);
                int k = gi * n + gj;

                // 侵蚀后地形（region 求解已把窗口内 tile 全部生成 ⇒ 此处命中缓存，廉价）
                // ★ block → wu：sampleWu 的坐标语义是 wu（1 wu = hs 块）
                // ★ 2026-10-12【height-only】只读 height ⇒ 改调 erodedHeight（逐位等价、跳过气候/分类）。
                double eroded = gen.erodedHeight(blockX * invHs, blockZ * invHs);
                // 连续水位（双线性：湖面/河面在 4-block 网格上平滑）
                double surface = bilinear(p.waterSurface(), n, gi, gj, rd.originX, rd.originZ, blockX, blockZ, STEP);

                // ★ 3×3 邻区求并集：湖优先（任一邻区判湖即湖），否则取"最近中心线命中"。
                //
                // ⚠⚠ 2026-10-09【严重 bug 修复：越界必须跳过，绝不能 clamp】⚠⚠
                //
                // 【伪影现象】实测出图后，沿 region 边界出现**矩形湖块**：
                //   col i=358 从 j=51 连续 418 像素全判湖，i=362 与之逐位相同
                //   ⇒ 完美的矩形，且 j=51 正是 region 边界（512×(-1)+563）。
                //
                // 【根因】原写法对**不在该邻区窗口内**的方块也去查询，并把索引
                //   `clamp` 到邻区边界 ⇒ 查到的是"邻区边缘格" ⇒ 若边缘格判湖，
                //   则在**整条 region 宽度**上都被误判成湖 ⇒ 矩形块。
                //
                // 【正解】越界 ⇒ `continue` 跳过该邻区（不查、不 clamp）。
                //   3×3 邻区的**有效窗口**本就有重叠（RADIUS=352 > REGION/2=256），
                //   所以"每个方块都能被至少一个邻区覆盖"—— 不需要靠 clamp 兜底。
                boolean isLakeBlock = p.lake()[k];
                double half = 0, depth = 0, bestDist = Double.POSITIVE_INFINITY;
                // ★ 本区先判（零邻区开销）
                if (!isLakeBlock && !segIndex.isEmpty()) {
                    SegmentIndex.Hit h0 = segIndex.nearest(blockX, blockZ);
                    if (h0 != null && h0.half > 0 && h0.dist <= h0.half) {
                        bestDist = h0.dist;
                        half = h0.half;
                        depth = Math.max(0, h0.depth);
                    }
                }
                // ★ 惰性邻区：仅当【本区无命中】且【远离本 region 中心、贴近边界带】时才查。
                //   本区窗口已外扩 HALO（RADIUS = REGION/2 + HALO）⇒ 内部方块无需邻区。
                //   （2026-10-10 双 pass 后无须再拓宽：接力折线由【接收方】在自己窗口内追出
                //    ⇒ 段落全在接收方窗口内 ⇒ 原 HALO 带即可命中。）
                boolean nearBorder = (blockX < regLoX + HALO) || (blockX >= regHiX - HALO)
                        || (blockZ < regLoZ + HALO) || (blockZ >= regHiZ - HALO);
                // ★ 分层实验：near-border 补缝【只借粗层做候选/校验】，**不覆盖输出**。
                //   禁止隐式重解邻区细层（既有路径的 `regionFor(邻居)` 会跑完整重管线 ×9）。
                //   owner 按【绝对坐标】判定（floorDiv ⇒ 与生成顺序、缓存状态无关）：
                //     · 本区拥有该点 ⇒ 本区细层说了算（无命中即"此处无水"，不借邻区）；
                //     · 邻区拥有该点 ⇒ 用【owner 粗 tract】做<b>校验</b>：粗层判湖而本块真实
                //       侵蚀地形不支持（`eroded ≥ 水面 − 0.5`）⇒ 记为【不一致】并如实计数
                //       （正是"侵蚀打开出口漏水"的现场）；<b>绝不</b>用粗层旧湖面覆盖输出。
                //     · 连粗域都不覆盖 ⇒ 契约缺口（清晰失败，不静默补缝）。
                //   ⚠ 湖一律来自【细层】：{@code isLakeBlock} 只由 {@code p.lake()[k]}（下方
                //     或既有 3×3 逻辑）决定；粗层无权改写落块。
                boolean outerOwner = Math.floorDiv(blockX, REGION) != rdRx
                        || Math.floorDiv(blockZ, REGION) != rdRz;
                // ★★ 2026-10-12【跨 tract 缝共享湖域】★★
                //   实测根因（FINDINGS.md）：缝两侧 bed 逐位相同、只有【湖布尔 + 水位】不同
                //   ⇒ 同一盆地被两个 tract 窗口各截断一次、各算一个水位。
                //   本段是【唯一改写点】：只在缝带内、且该点属于某个"跨缝 + 未截断 + 单平面"
                //   盆地的粗层足迹时，用【共享域】（关于缝对称的同一窗、真实侵蚀地形）裁决，
                //   并且【整个盆地足迹】一并采用（有则给同一水位、无则一并判干）。
                //   ⚠ 不做"见缓存就用"：域是 (seed, 策略, 缝键) 的纯函数，与生成顺序无关。
                //   ⚠ 仍是【缝带局部】：足迹外依旧由本区细窗裁决 ⇒ 缝可能只是移到足迹边缘
                //     （共享诊断如实报告改写/抑制列数，不声称整缝已消除）。
                boolean sharedLake = false;
                double sharedSurface = Double.NaN;
                if (!sharedDoms.isEmpty()) {
                    SharedLakeDomain.Decision sd = decideShared(sharedDoms, blockX, blockZ);
                    if (sd.controlled()) {
                        HierarchicalHydrology.Diag.SHARED_CONTROLLED_COLUMNS.incrementAndGet();
                        // ★ 只区分【真实湖面】：共享域只裁决湖。
                        //   · sd.lake() ⇒ 用共享【实测均值水位】（= 真实 wet plane 常数）；
                        //   · !sd.lake()（干环）⇒ 只抹掉本区【湖】判定，**绝不剔除河**（half>0 保留）。
                        if (sd.lake()) {
                            if (!isLakeBlock) {
                                HierarchicalHydrology.Diag.SHARED_LAKE_COLUMNS.incrementAndGet();
                            }
                            isLakeBlock = true;
                            half = 0;
                            depth = 0;
                            sharedLake = true;
                            sharedSurface = sd.surface();
                        } else if (isLakeBlock) {
                            HierarchicalHydrology.Diag.SHARED_SUPPRESSED_COLUMNS.incrementAndGet();
                            isLakeBlock = false;
                        }
                        if (!GAP_WATCH.isEmpty() && GAP_WATCH.contains(
                                (((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                            LOGGER.info("[GAP-WATCH] ({},{}) => SHARED_DOMAIN lake={} surf={} key={}",
                                    blockX, blockZ, sd.lake(), sd.surface(), sd.key().label());
                        }
                    }
                }
                if (HIER && !isLakeBlock && half <= 0 && nearBorder) {
                    if (!outerOwner) {
                        HierarchicalHydrology.Diag.inTractNoHit();     // 本区无命中（如实计数）
                    } else {
                        HierarchicalHydrology.Coarse ct = coarseFor(
                                Math.floorDiv(blockX, REGION), Math.floorDiv(blockZ, REGION));
                        if (!ct.contains(blockX, blockZ)) {
                            HierarchicalHydrology.Diag.contractGap(blockX, blockZ);
                        } else if (ct.lakeAt(blockX, blockZ)) {
                            double cs = ct.waterSurfaceAt(blockX, blockZ);
                            // 粗层判湖 + 本块真实地形确实低于水面 ⇒ 候选一致（仅计数）。
                            // 否则 ⇒ 侵蚀打开了出口（漏水）⇒ 如实计不一致，仍不覆盖输出。
                            boolean terrainSupports =
                                    Double.isFinite(cs) && eroded < cs - 0.5;
                            HierarchicalHydrology.Diag.coarseLakeCandidate(terrainSupports);
                        }
                    }
                }
                if (!HIER && !isLakeBlock && half <= 0 && nearBorder) {
                    for (int t = 0; t < 9; t++) {
                        if (t == 4) continue;
                        // 只取"该方块靠近的那一侧"的邻区，避免无谓求解
                        if (neighDx[t] < 0 && blockX >= regLoX + HALO) continue;
                        if (neighDx[t] > 0 && blockX < regHiX - HALO) continue;
                        if (neighDz[t] < 0 && blockZ >= regLoZ + HALO) continue;
                        if (neighDz[t] > 0 && blockZ < regHiZ - HALO) continue;
                        if (neigh[t] == null) {
                            neigh[t] = regionFor((rdRx + neighDx[t]) * REGION + REGION / 2,
                                    (rdRz + neighDz[t]) * REGION + REGION / 2);
                            // 骨架模式用邻区求解期建好的亚格索引（同本区，勿从 reaches 重建）
                            neighSeg[t] = neigh[t].skeletonSeg() != null ? neigh[t].skeletonSeg()
                                    : (neigh[t].centerline().reaches.isEmpty()
                                    ? SegmentIndex.EMPTY
                                    : SegmentIndex.build(neigh[t].centerline(),
                                            neigh[t].originX(), neigh[t].originZ(),
                                            neigh[t].pipe().nx(), STEP));
                        }
                        RegionData rr = neigh[t];
                        int nn = rr.pipe().nx();
                        int ti = (int) Math.round((blockX - rr.originX()) / (double) STEP);
                        int tj = (int) Math.round((blockZ - rr.originZ()) / (double) STEP);
                        if (ti < 0 || tj < 0 || ti >= nn || tj >= nn) continue;   // 越界跳过
                        if (rr.pipe().lake()[ti * nn + tj]) { isLakeBlock = true; break; }
                        SegmentIndex si = neighSeg[t];
                        if (si == null || si.isEmpty()) continue;
                        SegmentIndex.Hit h2 = si.nearest(blockX, blockZ);
                        if (h2 != null && h2.half > 0 && h2.dist <= h2.half && h2.dist < bestDist) {
                            bestDist = h2.dist;
                            half = h2.half;
                            depth = Math.max(0, h2.depth);
                        }
                    }
                    if (isLakeBlock) half = 0;
                }

                // ★★★ 2026-10-08【湖判据最终定案：用模型原生 `p.lake()`】★★★
                //
                // 【为什么不再用"逐块比较水位与地形"】
                //   我曾改成 `isLakeAt(eroded, surface)`（逐块判 `地形 < 水位 − 0.5`），
                //   理由是"湖岸落在等高线上、无方块台阶"。**实测证明这是错的**：
                //   非湖格的 `waterSurface` 是 EquilibriumWater 的【几何逃逸高程 level】，
                //   它的定义就是"水刚好能溢出的高度" ⇒ **恒 > 床面** ⇒
                //   该判据把【整个填洼谷地】都判成湖 ⇒ 湖 7.17% vs 正典 3.63%（**多一倍**）。
                //
                // （湖/河判定已在上方由 3×3 邻区并集完成，见那里的 javadoc）
                // ★ 诊断（-Dgeogenesis.lakeDiag=true）：对照"粗格布尔"与"邻区并集"两口径。
                if (LAKE_DIAG) {
                    boolean coarseLake = p.lake()[k];
                    lakeDiagTotal.incrementAndGet();
                    if (coarseLake) lakeDiagCoarse.incrementAndGet();
                    if (isLakeBlock) lakeDiagBlock.incrementAndGet();
                    if (coarseLake && !isLakeBlock) lakeDiagCoarseButNotBlock.incrementAndGet();
                    if (!coarseLake && isLakeBlock) lakeDiagGainedByRefine.incrementAndGet();
                }
                if (isLakeBlock) {
                    // ★ 断口现场 watch
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => LAKE_COL region=({},{}) shared={}",
                                blockX, blockZ, rdRx, rdRz, sharedLake);
                    }
                    // ★ 湖一律来自【细层】：水面取本区细层水位（`p.waterSurface()` 双线性）。
                    //   粗层只做候选/校验，**不改写**落块（旧实现会用粗层水位覆盖 ⇒ 掩盖漏水）。
                    //   ★ 2026-10-12 唯一例外：跨 tract 缝的【共享域】受控列 —— 它的水面就是
                    //     关于缝对称的同一个窗在真实侵蚀地形上算出的水位（同一平面），
                    //     两个邻 tract 对该列因此得到同一个值 ⇒ 缝上的湖墙消失。
                    //     非受控列（拒绝/竞争/足迹外）一概仍用本区细层水位。
                    double lakeSurface = sharedLake && Double.isFinite(sharedSurface)
                            ? sharedSurface : surface;
                    // 湖列：不雕刻，按水位洪泛；lakeNode=null ⇒ 写回跳过逃逸重算、按水位分组
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            lakeSurface, lakeSurface, 0.0, 1.0,
                            true, true, null, lakeSurface));
                    continue;
                }

                // ★ 诊断（-Dgeogenesis.riverWidthDiag=true）：河道半宽分布。
                //   目的：验证"河道精确率仅 19%"是否源于【宽度律饱和】——
                //   `fullWidth(q) = min(maxWidth, headWidth × (q/widthRef)^0.45)`
                //   在 widthRef=1.0（从 ContinuousRiverNetwork 继承的连续量口径）
                //   而 discharge 是【格数累积】（1~数千）时会极早饱和 ⇒ 全部 12 块宽。
                if (RIVER_WIDTH_DIAG && half > 0) {
                    int b = (int) Math.min(15, Math.floor(half));
                    riverHalfHist[b].incrementAndGet();
                }

                // 河道：`half`/`depth` 已由上方给出（本区 + 惰性邻区的最邻近中心线命中）。
                // ★★★ 2026-10-09【河道掩码必须用 `p.channel()` —— 与参照图同口径】★★★
                //
                // 【为什么（用户判据："参考图里面的湖泊河流都基本达到我要求"）】
                //   参照图 `CanonicalViewProbe:91` 的掩码来源是：
                //     `boolean[] chMask = useCenterline ? cl.channel : r.channel();`
                //   —— **默认就是 `r.channel()`（RiverNetwork 的【流量阈值掩码】）**。
                //   而生产原先只用【距离场命中】(`hit.dist <= hit.half`)——
                //   距离场 = `thin(channel)` 再按宽度扫掠 ⇒ **比原掩码宽 3.5 倍**
                //   （实测：生产河道 4.51% vs 参照 1.30%）。
                //
                // 【我曾误判 A/B 为"错误"】（务必勿重犯）：
                //   · "错误 A：加 `p.channel()` 门控 = 同条件过滤两次" —— **错**。
                //     参照图正是用 `r.channel()` 直出；距离场只是"给宽度"，
                //     不该拿它自己当"是不是河"的判据。
                //   · "错误 B：给最小可雕值" —— 那次碎片化的真因不是补宽度，
                //     而是【叠加了 `fillWater` 门槛】（见下方 fill 判定）。
                //
                // 【正解】`p.channel()[k]` 决定"是不是河"；距离场只提供宽度/深度；
                //   距离场未覆盖时给最小可雕值（保证刻出河床 ⇒ 有水面）。
                //
                // 骨架 A/B（2026-10-10）：B 侧（skeletonTrace=true）= 距离场命中即河
                //   （去粗格门控 —— 旧路径的粗格布尔导致 STEP=4 方块化，
                //    像素铁证：河段起点 x mod 4 = 2 占 97.7%）；
                //   A 侧保持旧口径，逐位不变。
                boolean onChannel;
                if (SKELETON_TRACE) {
                    onChannel = half > 0;      // 距离场命中 = 河（连续几何，无粗格门控）
                } else {
                    onChannel = p.channel() != null && p.channel()[k];
                }
                if (onChannel) {
                    // ★★★ 2026-10-09【宽度统一固定为最小可见值 —— 用户的既定方针】★★★
                    //
                    // 【用户原话（已两次强调）】「宽度目前不需要，因为如果有宽度会影响
                    //   骨架判断的，可能会看错图。」
                    //
                    // 【为什么必须固定（本轮实测）】宽度律
                    //   `W = min(12, 1.5 × (q/1.0)^0.45)` 的 `widthRef=1.0` 是从
                    //   【连续流量模型】继承的，而 D8 的 `q` 是【格数累积】（几百~几千）
                    //   ⇒ 量纲错配 ⇒ (833)^0.45 ≈ 21 ⇒ **所有河全部饱和到 maxWidth=12**
                    //   ⇒ 图上河道糊成 2~6 像素粗带 ⇒ 骨架走向完全被掩盖
                    //     （我因此把骨架形态看错了好几轮）。
                    //
                    // ⇒ 本阶段只验【骨架形态】：半宽统一 0.5 块（全宽 1 块 = 1 像素细线）。
                    //   宽度是【后续议题】（见 HYDROLOGY.md §〇②）：
                    //   修它需要先把 widthRef 按 D8 的格数累积重新标定，而不是现在。
                    half = RiverCenterlines.MIN_HALF_BLOCKS;
                    depth = 1.0;
                    // 断因「有河却没水」修复（仅骨架侧；2026-10-10 断点归因）：
                    //   实测 318/31336 格（1%）的 surface-carved 落 0.18~0.50
                    //   （刚好卡在 0.5 水柱门槛下）⇒ depth=1.0 写死 + 平坦坡水位落差小
                    //   ⇒ 河在缓坡上断成虚线。修法：河床下挖深度按水位差自适应
                    //   （上限 4 块，防悬崖式深槽）。
                    if (SKELETON_TRACE) {
                        depth = Math.min(4.0, Math.max(depth, eroded - surface + 0.6));
                    }
                } else {
                    half = 0;
                    depth = 0;
                }
                if (half > 0) {
                    double carved = eroded - depth;                 // 意图床面
                    // ★ 出水判定 = 至少 0.5 格水柱（**不得放宽**）
                    //   依据（项目既有结论）："深度 < 0.5 块 = 地面与水面同一格、
                    //   放不下任何一个水块 ⇒ 物理上本就该干"。
                    //   ⚠ 我曾改成 `+1e-6` 图省事 —— 那会让"半格浅水"也出水，
                    //     而这些格在 MC 里放不下水块 ⇒ 只是凭空加湿（伪影）。
                    boolean fill = surface > carved + 0.5;
                    // ★ 断点归因诊断（默认关，零开销）
                    if (RIVER_BREAK_DIAG) {
                        brkOnChannel.incrementAndGet();
                        if (fill) brkWaterOk.incrementAndGet();
                        else {
                            brkNoWater.incrementAndGet();
                            if (brkSamples.size() < 12) {
                                brkSamples.add(String.format(
                                        "(%d,%d) surface=%.2f carved=%.2f diff=%.3f depth=%.2f",
                                        blockX, blockZ, surface, carved, surface - carved, depth));
                            }
                        }
                    }
                    // ★ 断口现场 watch（`-Dgeogenesis.gapWatch=x,z;...`）
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => RIVER_COL fill={} half={} bestDist={}"
                                        + " depth={} surface={} carved={} region=({},{})",
                                blockX, blockZ, fill, half, bestDist,
                                depth, surface, carved, rdRx, rdRz);
                    }
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                            surface, surface, depth, 1.0,
                            fill, false, null, Double.NaN));
                } else {
                    // 普通列：不雕刻、不出水（海洋水体由 fillFromNoise 的高度判定负责）
                    // ★ 诊断：距离场命中却被粗格布尔判掉（"误杀"）
                    if (RIVER_BREAK_DIAG && bestDist < Double.POSITIVE_INFINITY) {
                        brkDistHitButOff.incrementAndGet();
                    }
                    // ★ 断口现场 watch —— 「无命中」就是断口的直接现场
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => PLAIN_COL(no hit) bestDist={}"
                                        + " nearBorder={} region=({},{}) lakeCoarse={}",
                                blockX, blockZ, bestDist,
                                (blockX < regLoX + HALO) || (blockX >= regHiX - HALO)
                                        || (blockZ < regLoZ + HALO) || (blockZ >= regHiZ - HALO),
                                rdRx, rdRz, p.lake()[k]);
                    }
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            surface, surface, 0.0, 1.0,
                            false, false, null, Double.NaN));
                }
            }
        }
        return out;
    }

    /**
     * 取 region 数据；缓存/单飞双层。失败的 future 必须移除（绝不缓存半成品）。
     *
     * <h3>★ 刚性求解在【调用线程】上跑，不进队列（2026-10-08 实机修复）</h3>
     * <p>原实现把本 region 的求解也 {@code supplyAsync} 进 preheat 线程池再
     * {@code join()} —— 当池子/队列被【邻区预热任务】占满时，刚性需求要排在
     * 预热<b>后面</b>干等。实机出生点实测正是这个形态：
     * {@code chunk(-3,-3) hydro=21303ms} ≈ 3 个预热任务（各 ~7s）排队 + 本解。
     * 而调用方本来就要阻塞等结果 ⇒ 在调用线程上直接解，<b>零排队</b>；
     * 线程池只服务"不阻塞任何人的"邻区预热。</p>
     */
    private RegionData regionFor(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, REGION), rz = Math.floorDiv(blockZ, REGION);
        CacheKey key = new CacheKey(seed, rx, rz, strategy);
        // ① 缓存命中
        synchronized (SHARED_CACHE) {
            RegionData hit = SHARED_CACHE.get(key);
            if (hit != null) return hit;
        }
        // ② 已有在途求解（它在别人的线程/队列上）→ 等它
        CompletableFuture<RegionData> existing = SHARED_INFLIGHT.get(key);
        if (existing != null) return await(existing, key, rx, rz);
        // ③ 抢占：赢家在【本线程】直接求解（不进池 ⇒ 不排队在预热后面）
        CompletableFuture<RegionData> mine = new CompletableFuture<>();
        CompletableFuture<RegionData> raced = SHARED_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) return await(raced, key, rx, rz);   // 输了 → 等赢家
        try {
            RegionData data = solve(rx, rz);
            mine.complete(data);
            cachePut(key, data);
            SHARED_INFLIGHT.remove(key, mine);
            return data;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            SHARED_INFLIGHT.remove(key, mine);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz, e);
            throw e;
        }
    }

    /** 等待在途求解并入缓存（两个入口共用：输掉竞争的调用方 / 预热方）。 */
    private RegionData await(CompletableFuture<RegionData> f, CacheKey key, int rx, int rz) {
        try {
            RegionData data = f.join();
            SHARED_INFLIGHT.remove(key, f);
            cachePut(key, data);
            return data;
        } catch (CompletionException e) {
            SHARED_INFLIGHT.remove(key, f);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz,
                    String.valueOf(e.getCause()));
            throw e;
        }
    }

    /** 入 LRU 缓存（容量满时淘汰最久未用）。 */
    private void cachePut(CacheKey key, RegionData data) {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.put(key, data);
            while (SHARED_CACHE.size() > CACHE_CAP) {
                SHARED_CACHE.remove(SHARED_CACHE.keySet().iterator().next());
            }
        }
    }

    /**
     * 跨区水量传递：窗外 margin 带的最陡下降追踪（2026-10-08）。
     *
     * <p><b>⚠ 默认关闭（MARGIN_BLOCKS=0）—— 实测有伪影，未采纳</b>：
     * margin 把窗外水量按【落点】注入窗框离散点（每 MARGIN_STEP 块一个），
     * 大量水从边框离散点涌入 ⇒ 平坦区沿填洼 parent 图汇流时流向退化为直线
     * ⇒ 图上出现【等长横条纹】（用户实测指出：平原上的密集等长横线）。
     * 这与 HANDOFF §17.6 记录的"平坦区流向欠定"同源 —— 水一多，缺陷就显形。</p>
     *
     * <p>⇒ 保留代码供后续改用"沿真实 D8 路径推进窗内再入账"的方案，
     * <b>默认不启用</b>。要复现历史实验：{@code -Dgeogenesis.modelMargin=384}。</p>
     *
     * @return 窗口网格上的逐格额外来水（格数当量），MARGIN_BLOCKS&lt;=0 时返回 null
     */
    private double[] computeMarginInflow(int centerX, int centerZ, int radius, int step, int n) {
        int marginBlocks = MARGIN_BLOCKS;
        if (marginBlocks <= 0) return null;
        double hs = Math.max(0.01, gen.params().horizontalScale());
        double sea = gen.seaLevel();
        double[] extra = new double[n * n];
        int ox = centerX - radius, oz = centerZ - radius;          // 窗口原点（块）
        int mSteps = marginBlocks / MARGIN_STEP;                    // margin 带内采样步数
        long t0 = System.nanoTime();
        // 遍历窗外 margin 带（四条边），步长 MARGIN_STEP
        for (int side = 0; side < 4; side++) {
            for (int t = -mSteps; t <= mSteps; t++) {
                for (int d = 1; d <= mSteps; d++) {
                    // 带内样点（块坐标）：沿边方向 t、垂直边方向 d（窗外为正）
                    int dx, dz;
                    switch (side) {
                        case 0 -> { dx = t * MARGIN_STEP; dz = -d * MARGIN_STEP; }   // 北
                        case 1 -> { dx = t * MARGIN_STEP; dz = d * MARGIN_STEP; }    // 南
                        case 2 -> { dx = -d * MARGIN_STEP; dz = t * MARGIN_STEP; }   // 西
                        default -> { dx = d * MARGIN_STEP; dz = t * MARGIN_STEP; }   // 东
                    }
                    int bx = centerX + dx, bz = centerZ + dz;
                    // 只处理窗外点（边框内一点由窗内自己算）
                    if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) continue;
                    // ★ 2026-10-12【height-only】：只读 height ⇒ 改调 erodedHeight（逐位等价、更轻）。
                    double h = gen.erodedHeight(bx / hs, bz / hs);
                    if (h <= sea) continue;                          // 海：无径流
                    // 最陡下降追踪到窗框（步长 MARGIN_STEP，上限 mSteps*2 步）
                    double cx2 = bx, cz2 = bz;
                    boolean landed = false;
                    for (int s = 0; s < mSteps * 2; s++) {
                        // 8 邻最陡下降（步长 MARGIN_STEP）
                        double best = Double.NaN; int bdx = 0, bdz = 0;
                        for (int di = -1; di <= 1; di++) {
                            for (int dj = -1; dj <= 1; dj++) {
                                if (di == 0 && dj == 0) continue;
                                double nx = cx2 + di * (double) MARGIN_STEP;
                                double nz = cz2 + dj * (double) MARGIN_STEP;
                                // ★ 2026-10-12【height-only】：只读 height ⇒ 改调 erodedHeight（逐位等价、更轻）。
                                double nh = gen.erodedHeight(nx / hs, nz / hs);
                                if (Double.isNaN(best) || nh < best) { best = nh; bdx = di; bdz = dj; }
                            }
                        }
                        if (Double.isNaN(best) || best >= h) break;  // 洼地/平地：丢弃（v1）
                        cx2 += bdx * (double) MARGIN_STEP;
                        cz2 += bdz * (double) MARGIN_STEP;
                        // 已入窗？
                        if (Math.abs(cx2 - centerX) <= radius && Math.abs(cz2 - centerZ) <= radius) {
                            landed = true;
                            break;
                        }
                        h = best;
                    }
                    if (!landed) continue;                           // 没到窗（洼地/到海/超步）
                    // 落点 → 窗口网格索引（最近格点）
                    int gi = (int) Math.round((cx2 - ox) / (double) step);
                    int gj = (int) Math.round((cz2 - oz) / (double) step);
                    if (gi < 0) gi = 0; else if (gi >= n) gi = n - 1;
                    if (gj < 0) gj = 0; else if (gj >= n) gj = n - 1;
                    extra[gi * n + gj] += 1;
                }
            }
        }
        marginMs = (System.nanoTime() - t0) / 1e6;
        return extra;
    }

    /** margin 追踪耗时（诊断）。 */
    private volatile double marginMs = 0;

    /**
     * 双 pass 的 pass-1：跑本区管线 + 无接力骨架，产出出口种子。
     * <b>绝不取任何邻区</b>（扇出恒为 1）；缓存/inflight 范式同 {@link #regionFor}。
     */
    private Pass1 regionPass1(int rx, int rz) {
        // ★ 分层实验：pass-1 内容 = 本 tract 的粗层固定域（零 sampleWu/零预热/零邻区）。
        //   直接短路到粗层缓存，避免与 P1_CACHE 重复计算同一份粗产物。
        if (HIER) {
            HierarchicalHydrology.Coarse ct = coarseFor(rx, rz);
            return new Pass1(ct.pipe(), coarseExitSeeds(ct), ct.originX(), ct.originZ());
        }
        CacheKey key = new CacheKey(seed, rx, rz, strategy);
        synchronized (P1_CACHE) {
            Pass1 hit = P1_CACHE.get(key);
            if (hit != null) return hit;
        }
        // ① 已有在途任务 → 先尝试【原子接管】（只有"排队未开跑"抢得到；RUNNING/DONE 抢不到）
        Pass1Task existing = P1_INFLIGHT.get(key);
        if (existing != null) {
            existing.runIfClaimed();
            return awaitPass1(existing);
        }
        // ② 抢占：赢家在本线程直接跑（counted=false ⇒ 不占预热名额、不进池、零排队）
        Pass1Task mine = new Pass1Task(key, rx, rz, false);
        Pass1Task raced = P1_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) {
            raced.runIfClaimed();
            return awaitPass1(raced);
        }
        mine.runIfClaimed();
        return awaitPass1(mine);
    }

    /**
     * 等 pass-1 落定。发布与撤表都由执行者唯一负责 ⇒ 此处只等待
     * （不重复发布、不撤表，避免"别人把我的占位撤了"的竞态）。
     */
    private Pass1 awaitPass1(Pass1Task t) {
        try {
            return t.future.join();
        } catch (CompletionException e) {
            LOGGER.warn("[HYDRO-MODEL] pass1 failed: {}", String.valueOf(e.getCause()));
            throw e;
        }
    }

    /** pass-1 入 LRU（与 {@link #cachePut} 同容量策略；执行者调用一次）。 */
    private static void cachePutPass1(CacheKey key, Pass1 data) {
        synchronized (P1_CACHE) {
            P1_CACHE.put(key, data);
            while (P1_CACHE.size() > CACHE_CAP) {
                P1_CACHE.remove(P1_CACHE.keySet().iterator().next());
            }
        }
    }

    /** pass-1 实体：跑管线，骨架【不接力】（只需出口种子；traces 丢弃）。 */
    private Pass1 solvePass1(int rx, int rz) {
        if (concurrencyDiag()) {
            countPass1Solve(rx, rz);      // 统计"求解尝试次数"（含被注入失败的那次）
            if (PASS1_FAIL_ONCE.contains(regionKey(rx, rz))
                    && PASS1_FAILED.add(regionKey(rx, rz))) {
                throw new IllegalStateException("injected pass1 failure for (" + rx + "," + rz + ")");
            }
        }
        // ★ 分层实验：pass-1 【只跑粗层】—— 零 sampleWu、零 tile 预热、零邻区，扇出恒为 1。
        if (HIER) {
            HierarchicalHydrology.Coarse ct = coarseFor(rx, rz);
            return new Pass1(ct.pipe(), coarseExitSeeds(ct), ct.originX(), ct.originZ());
        }
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        double[] extra = computeMarginInflow(centerX, centerZ, RADIUS, STEP, 2 * RADIUS / STEP + 1);
        var req = new HydrologyPipeline.Request(centerX, centerZ, RADIUS, STEP, CLIMATE, 0,
                riverModel(), SAMPLES, extra);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, gen.params(), req);
        List<DeterministicSkeleton.ExitSeed> seeds = List.of();
        if (SKELETON_TRACE) {
            // 不接力（incoming = 空）⇒ 只产出出口种子。整条骨架白跑一遍是刻意的：
            // 出口种子必须来自"本区独立追踪"的结果，否则会与接力链互相依赖。
            var sr = skeletonRegion(p, centerX - RADIUS, centerZ - RADIUS, List.of(), false);
            seeds = sr.exitSeeds();
        }
        return new Pass1(p, seeds, centerX - RADIUS, centerZ - RADIUS);
    }

    /**
     * 粗层出口端口 → 接力种子（分层实验）。语义与既有 pass-1 的 {@code ExitSeed} 相同
     * （"水从哪个格离开本 tract、指向哪个邻 tract"），但来源是<b>粗层水位接口</b>
     * 而不是细层骨架 —— 邻区因此只需付粗层代价。
     */
    private static List<DeterministicSkeleton.ExitSeed> coarseExitSeeds(
            HierarchicalHydrology.Coarse ct) {
        List<DeterministicSkeleton.ExitSeed> out = new ArrayList<>();
        for (HierarchicalHydrology.Port port : ct.ports()) {
            if (port.dRX() == 0 && port.dRZ() == 0) continue;
            int i = Math.round((port.blockX() - ct.originX()) / (float) ct.step());
            int j = Math.round((port.blockZ() - ct.originZ()) / (float) ct.step());
            if (i < 0 || j < 0 || i >= ct.n() || j >= ct.n()) continue;
            out.add(new DeterministicSkeleton.ExitSeed(i * ct.n() + j, port.dRX(), port.dRZ()));
        }
        return out;
    }

    /**
     * 收集接力种子：8 邻 pass-1 中【指向本区】的出口种子 → 换算成本区格索引。
     *
     * <p>换算按 legacy 的<b>方向匹配</b>（邻区出口指向本区 ⟺ dRX/dRZ 与本区
     * 相对邻区的偏移同号反向）：</p>
     * <pre>
     *   邻区在 (rx+dRX, rz+dRZ)，其出口朝 (dRX,dRZ) ⇒ 接收方就是本区
     * </pre>
     * <p>落在本区窗口内才收（窗口外 = 该种子朝更远的区，与本区无关）。</p>
     */
    private List<Integer> collectIncoming(int rx, int rz, int n) {
        List<Integer> out = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                Pass1 nb = regionPass1(rx + dRX, rz + dRZ);
                int dOriginX = (rx + dRX) * REGION + REGION / 2 - RADIUS;
                int dOriginZ = (rz + dRZ) * REGION + REGION / 2 - RADIUS;
                int myOriginX = rx * REGION + REGION / 2 - RADIUS;
                int myOriginZ = rz * REGION + REGION / 2 - RADIUS;
                for (DeterministicSkeleton.ExitSeed s : nb.exitSeeds()) {
                    // 只收"朝本区"的方向：邻区相对本区的偏移是 (-dRX,-dRZ)，
                    // 故其出口方向须为 (-dRX,-dRZ)。
                    if (s.dRX() != -dRX || s.dRZ() != -dRZ) continue;
                    int si = s.cell() / n, sj = s.cell() % n;
                    // 邻区格 → 世界 → 本区格
                    int wx = dOriginX + si * STEP, wz = dOriginZ + sj * STEP;
                    int mi = Math.floorDiv(wx - myOriginX, STEP);
                    int mj = Math.floorDiv(wz - myOriginZ, STEP);
                    if (mi < 0 || mj < 0 || mi >= n || mj >= n) continue;   // 越界（朝更远的区）
                    out.add(mi * n + mj);
                }
            }
        }
        return out;
    }

    /** 求解一个 region 窗口（纯函数；耗时日志只打【求解】不打缓存命中）。 */
    private RegionData solve(int rx, int rz) {
        return HIER ? solveHier(rx, rz) : solveProduction(rx, rz);
    }

    /**
     * ★ 分层实验的【细层】：本 region + 固定 halo 跑重管线（{@code sampleWu}），
     * 窗外一格外环取<b>坐标 owner 的粗层水位接口</b>（下游权威，不是本区近似）；
     * 接力种子 = <b>邻区粗层主干端口</b> + <b>本区粗层主干源头</b>（两侧都沿侵蚀 parent 细化）
     * —— <b>绝不</b>求解任何邻区的完整重管线。
     */
    private RegionData solveHier(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        int originX = centerX - RADIUS, originZ = centerZ - RADIUS;
        long t0 = System.nanoTime();
        HierarchicalHydrology.Coarse ct = coarseFor(rx, rz);
        // ★ 窗外一格外环的边界条件 = 【坐标 owner】的粗层水位接口。
        //   旧写法用本区 `ct.levelAt` ⇒ 对窗外点而言那不是 owner（可能是另一个 tract）
        //   ⇒ 违反"同坐标任何区拿同 authority"。改为按绝对坐标解析 owner。
        WindowOutlets.Terrain bnd = (bx, bz) -> {
            int qx = (int) Math.floor(bx), qz = (int) Math.floor(bz);
            return HierarchicalHydrology.owner(qx, qz, HIER_PARAMS, this::coarseFor)
                    .levelAt(qx, qz);
        };
        var req = new HydrologyPipeline.Request(centerX, centerZ, RADIUS, STEP, CLIMATE, 0,
                riverModel(), SAMPLES, null);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, gen.params(), req,
                new HydrologyPipeline.Layer(null, bnd, true, "fine"));
        HierarchicalHydrology.Diag.FINE_SOLVES.incrementAndGet();
        // ★ 盆地【形态对比】：粗层只是候选、细层真实侵蚀盆地为最终形态 ⇒
        //   候选消失/分裂/合并/出口移动/水位变动一律【不判失败】，只如实记录。
        //   真实冲突（非有限水位/越域索引）才抛带坐标异常（严格模式默认开）。
        HierarchicalHydrology.MorphologyComparison v =
                HierarchicalHydrology.validate(HIER_PARAMS, ct, p, originX, originZ, rx, rz);
        LAST_FINE_VALIDATION = v;
        LAST_FINE_CONNECTIVITY = HierarchicalHydrology.auditConnectivity(p);
        HierarchicalHydrology.Diag.connectivity(LAST_FINE_CONNECTIVITY);
        RiverCenterlines.Result cl;
        SegmentIndex skelSeg = null;
        if (SKELETON_TRACE) {
            // 接力：本区粗层主干源头（细化主干）+ 邻区粗层主干端口（续流）。
            // 两路都由【本区自己的侵蚀 parent】往里追 ⇒ 不是"只把边界换粗层"。
            List<Integer> incoming = CROSS_PASS
                    ? hierIncomingSeeds(rx, rz, p.nx(), originX, originZ) : List.of();
            var sr = skeletonRegion(p, originX, originZ, incoming, true);
            // #region agent log: R1 细层出口目前未消费，先量化再改边界几何
            if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R1", "hier-ports-" + rx + "-" + rz,
                        "incomingSeeds=" + incoming.size() + " fineWindowExitsUnused=" + sr.exitSeeds().size());
            }
            // #endregion
            cl = sr.cl();
            skelSeg = sr.seg();
        } else {
            cl = RiverCenterlines.build(p.bed(), p.discharge(), p.channel(), p.sea(),
                    p.nx(), p.nz(), RiverCenterlines.Params.defaults(), STEP);
        }
        logRegionSolved(rx, rz, p, cl, t0, originX, originZ);
        HierarchicalHydrology.Diag.fineTiming((System.nanoTime() - t0) / 1_000_000L, p.nx() * p.nx());
        lastFineMs = (System.nanoTime() - t0) / 1e6;
        return new RegionData(p, cl, skelSeg, originX, originZ);
    }

    /** 最近一次细层求解耗时（ms）——性能诊断（与 {@link HierarchicalHydrology.Diag#LAST_FINE_MS} 同源）。 */
    private volatile double lastFineMs = -1;

    /**
     * 分层接力种子（本区格索引）= 本区粗层主干源头 + 邻区粗层主干端口。
     *
     * <p>语义：粗层给出<b>主干候选</b>（源头与跨区接口），细层用<b>侵蚀后地形</b>把主干
     * 追踪出来并细化 —— 而不是"只把边界换成粗层、其余照旧"。</p>
     */
    private List<Integer> hierIncomingSeeds(int rx, int rz, int n,
                                            int myOriginX, int myOriginZ) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        // ① 本区粗层主干源头（主干候选，交细层沿侵蚀 parent 追）。
        //   ★ 只收【owner = 本 tract】的源头：粗域含 halo，会看到邻区主干，
        //     不筛就会把别区源头当本区源头重复起河。
        HierarchicalHydrology.Coarse mine = coarseFor(rx, rz);
        HierarchicalHydrology.Params hp = HIER_PARAMS;
        for (HierarchicalHydrology.TrunkSeed ts : mine.trunkSeeds()) {
            if (Math.floorDiv(ts.blockX(), hp.tract()) != rx
                    || Math.floorDiv(ts.blockZ(), hp.tract()) != rz) continue;   // 别区拥有
            int mi = Math.floorDiv(ts.blockX() - myOriginX, STEP);
            int mj = Math.floorDiv(ts.blockZ() - myOriginZ, STEP);
            if (mi < 0 || mj < 0 || mi >= n || mj >= n) continue;
            out.add(mi * n + mj);
            // #region agent log: R6 本地主干候选与跨缝端口分开留痕
            if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R6",
                        "local-trunk-" + rx + "-" + rz + "-" + (mi * n + mj),
                        "cell=" + (mi * n + mj) + " point=" + ts.blockX() + "," + ts.blockZ());
            }
            // #endregion
        }
        // ② 邻区粗层主干端口（朝本区的那些）。
        out.addAll(collectIncomingCoarse(rx, rz, n, myOriginX, myOriginZ));
        return new ArrayList<>(out);
    }

    /**
     * 接力种子（分层实验）：只取<b>邻区粗层端口</b>中指向本 tract 的那些。
     * 邻区因此只付粗层代价 —— 不触发任何邻区细层求解（铁律）。
     */
    private List<Integer> collectIncomingCoarse(int rx, int rz, int n,
                                                int myOriginX, int myOriginZ) {
        List<Integer> out = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                HierarchicalHydrology.Coarse nb = coarseFor(rx + dRX, rz + dRZ);
                for (HierarchicalHydrology.Port port : nb.ports()) {
                    // 邻区在 (rx+dRX)，其端口朝 (-dRX,-dRZ) ⇒ 接收方就是本 tract。
                    boolean directed = port.dRX() == -dRX && port.dRZ() == -dRZ;
                    int mi = Math.floorDiv(port.blockX() - myOriginX, STEP);
                    int mj = Math.floorDiv(port.blockZ() - myOriginZ, STEP);
                    boolean inside = mi >= 0 && mj >= 0 && mi < n && mj < n;
                    // #region agent log: R4 粗端口发射/拒绝/落点，仅诊断
                    if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
                        com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R4", "coarse-port-" + rx + "-" + rz
                                        + "-" + (rx + dRX) + "-" + (rz + dRZ)
                                        + "-" + port.blockX() + "-" + port.blockZ(),
                                "from=" + (rx + dRX) + "," + (rz + dRZ) + " cell=" + (mi * n + mj)
                                        + " point=" + port.blockX() + "," + port.blockZ()
                                        + " status=" + (!directed ? "DIR_MISMATCH" : inside ? "TAKEN" : "OUT_OF_WINDOW")
                                        + " levelUnused=" + port.level());
                    }
                    // #endregion
                    if (!directed || !inside) continue;
                    out.add(mi * n + mj);
                }
            }
        }
        return out;
    }

    /**
     * 【生产】region 求解（既有 model 链，非分层实验路径）。
     *
     * <p>⚠ 命名说明：原名 {@code solveLegacy} 易被误读为"legacy 链"——它实际是
     * <b>本 model 链的既有版本</b>（{@code ModelHydrologyProvider} 自己的 3×3 惰性邻区 +
     * 双 pass 续流），与 legacy（{@code HydrologyBlockCarver}）无关。本轮改名
     * {@code solveProduction} 以消除歧义。</p>
     */
    private RegionData solveProduction(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        long t0 = System.nanoTime();
        // ★ 双 pass：本区 pass-1（管线 + 出口种子）—— 复用缓存，绝不在 solve 里重跑管线。
        Pass1 base = regionPass1(rx, rz);
        long baseDone = System.nanoTime();
        long incomingNs = 0;
        HydrologyPipeline.Result p = base.pipe();
        // 骨架 A/B（2026-10-10）：true = DeterministicSkeleton（沿 minimax 树追踪 +
        //   湖溢口续流 + legacy 形态收尾）；false = RiverCenterlines（累积->阈值->细化的旧路径）。
        RiverCenterlines.Result cl;
        SegmentIndex skelSeg = null;
        if (SKELETON_TRACE) {
            // ★ 双 pass 续流：取 8 邻 pass-1 中【指向本区】的出口种子，换算成本区格索引，
            //   由本区自己的 parent 树追进来（方向场统一 ⇒ 缝上不再断）。
            long incomingStart = System.nanoTime();
            List<Integer> incoming = CROSS_PASS ? collectIncoming(rx, rz, p.nx()) : List.of();
            incomingNs = System.nanoTime() - incomingStart;
            var sr = skeletonRegion(p, base.originX(), base.originZ(), incoming, true);
            cl = sr.cl();
            skelSeg = sr.seg();
        } else {
            // 中心线（距离场几何源；cellSize=STEP 换算 block 单位）
            cl = RiverCenterlines.build(
                    p.bed(), p.discharge(), p.channel(), p.sea(), p.nx(), p.nz(),
                    RiverCenterlines.Params.defaults(), STEP);
        }
        RegionData rd = new RegionData(p, cl, skelSeg, base.originX(), base.originZ());
        // #region agent log: D 冷区依赖成本拆分，实机确认后清理
        if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
            com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("D", "model-region-" + rx + "-" + rz,
                    "seed=" + seed + " baseMs=" + (baseDone - t0) / 1e6
                            + " neighborsMs=" + incomingNs / 1e6
                            + " finalMs=" + (System.nanoTime() - baseDone - incomingNs) / 1e6);
        }
        // #endregion
        logRegionSolved(rx, rz, p, cl, t0, base.originX(), base.originZ());
        return rd;
    }

    /** region 求解日志（细层/既有路径共用；不改产出）。 */
    private void logRegionSolved(int rx, int rz, HydrologyPipeline.Result p,
                                 RiverCenterlines.Result cl, long t0, int originX, int originZ) {
        double ms = (System.nanoTime() - t0) / 1e6;
        int lakeCells = countTrue(p.lake());
        regionLakeCells.addAndGet(lakeCells);       // ★ 诊断累计（见 lastRegionLakeCells）
        // ★ 诊断（2026-10-09）：`resolved` 是 EquilibriumWater 的"与窗口切割无关"标志。
        //   小窗口下若 resolved 大面积失效 ⇒ 盆地无法建立 ⇒ 湖丢失。
        int resolvedN = countTrue(p.resolved());
        int basinsN = p.lakeResult() == null ? -1 : p.lakeResult().basins().size();
        int dryN = p.lakeResult() == null ? -1 : p.lakeResult().dryBasins();
        LOGGER.info("[HYDRO-MODEL] region ({},{}) solved in {} ms (model={}, hier={}, grid={}, reaches={}, lake={}, channel={}, margin={}ms, resolved={}/{} basins={} dry={})",
                rx, rz, String.format("%.0f", ms), riverModel(), HIER, p.nx() + "x" + p.nz(),
                cl.reachCount(), lakeCells, countTrue(p.channel()),
                String.format("%.0f", marginMs), resolvedN, p.nx() * p.nz(), basinsN, dryN);
    }

    /**
     * 证据场模型：默认 {@link HydrologyPipeline.RiverModel#D8}（骨架化决策 2026-10-07，
     * 用户选定）。D8 = priority-flood/D8 拓扑累积，<b>零随机数、O(n log n)</b>，
     * 实测同参比 CONTINUOUS 快 ~3×（正典 r=700: 53s vs 150s）且水量账本可收敛。
     * <p>{@code -Dgeogenesis.modelRiverModel=continuous} ⇒ 蒙特卡洛流线证据
     * （50k 随机起点，湖↔河耦合首轮实测 14~18 秒/region）。</p>
     */
    private static HydrologyPipeline.RiverModel riverModel() {
        String v = System.getProperty("geogenesis.modelRiverModel", "d8");
        return v.toLowerCase().startsWith("c")
                ? HydrologyPipeline.RiverModel.CONTINUOUS : HydrologyPipeline.RiverModel.D8;
    }

    /**
     * 4 邻区异步预热（队列超限则跳过 —— 只是缓存优化，绝不能反压世界生成）。
     * 名额先占、再建任务；建不成（输 putIfAbsent 竞争）立刻归还，否则计数会虚增并顶死上限。
     */
    private void schedulePreheat(int rx, int rz) {
        if (preheatQueue.get() >= PREHEAT_QUEUE_CAP) return;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dd : d) {
            int nx = rx + dd[0], nz = rz + dd[1];
            CacheKey key = new CacheKey(seed, nx, nz, strategy);
            // 预热 pass-1 而非 solve（solve 会取 8 邻 pass-1 ⇒ 4 个预热各带 8 次管线白跑）
            synchronized (P1_CACHE) {
                if (P1_CACHE.containsKey(key)) continue;
            }
            if (P1_INFLIGHT.containsKey(key)) continue;
            // 占名额（超限即归还并放弃本轮预热）
            if (preheatQueue.incrementAndGet() > PREHEAT_QUEUE_CAP) {
                preheatQueue.decrementAndGet();
                return;
            }
            // 建任务；输竞争 ⇒ 归还名额（★ 原实现的计数泄漏点）
            Pass1Task task = new Pass1Task(key, nx, nz, true);
            Pass1Task raced = P1_INFLIGHT.putIfAbsent(key, task);
            if (raced != null) {
                preheatQueue.decrementAndGet();
                continue;
            }
            task.enqueue();
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /**
     * ★ 湖泊判定的【连续口径】—— 与游戏落块层同一物理语义。
     *
     * <p>游戏在 {@code GeoGenesisTerrain} 用 {@code cell.height < spill − 0.5} 判湖，
     * 其中 {@code cell.height} 是<b>侵蚀后</b>地形、{@code spill} 是水位。
     * 本方法把同一判据用在【逐方块】上：{@code eroded} 是本块侵蚀后地形、
     * {@code surface} 是双线性插值的水位 ⇒ 湖岸落在地形与水位面的交线上，
     * 是连续曲线而非 4 块网格的直角台阶。</p>
     *
     * <p>{@code 0.5} 与游戏层一致（"至少半格水柱才算湖"）。</p>
     */
    private static boolean isLakeAt(double erodedGroundY, double waterSurfaceY) {
        return erodedGroundY < waterSurfaceY - 0.5;
    }

    /** 4-block 网格上的双线性采样（水面是连续量 ⇒ 取最近格点会有 4 块台阶）。 */
    private static double bilinear(double[] field, int n, int gi, int gj,
                                   int originX, int originZ, int blockX, int blockZ, int step) {
        double fx = ((blockX - originX) - gi * (double) step) / step;
        double fz = ((blockZ - originZ) - gj * (double) step) / step;
        // gi 是 round 后的格点 ⇒ fx ∈ [-0.5, 0.5] 附近；取两侧格点插值
        int g0x = fx >= 0 ? gi : Math.max(0, gi - 1);
        int g1x = fx >= 0 ? Math.min(n - 1, gi + 1) : gi;
        int g0z = fz >= 0 ? gj : Math.max(0, gj - 1);
        int g1z = fz >= 0 ? Math.min(n - 1, gj + 1) : gj;
        double tx = (blockX - originX - g0x * (double) step) / step;
        double tz = (blockZ - originZ - g0z * (double) step) / step;
        tx = Math.max(0, Math.min(1, tx));
        tz = Math.max(0, Math.min(1, tz));
        double v00 = field[g0x * n + g0z], v10 = field[g1x * n + g0z];
        double v01 = field[g0x * n + g1z], v11 = field[g1x * n + g1z];
        return v00 * (1 - tx) * (1 - tz) + v10 * tx * (1 - tz)
                + v01 * (1 - tx) * tz + v11 * tx * tz;
    }

    /**
     * 中心线索段的空间索引（64-block tile → 段下标）。
     *
     * <p>与 {@code RiverCenterlines.paintSegment} 共用同一几何源（节点 = 网格点
     * {@code origin + cell*step}）—— 适配器逐块求的距离与探针画的掩码是同一段线，
     * 不存在"预览画 A、游戏查 B"的第二套几何。</p>
     */
    static final class SegmentIndex {
        static final SegmentIndex EMPTY = new SegmentIndex(new double[0], new HashMap<>());

        /** 段参数平铺：x0,z0,x1,z1（block）, h0,h1（半宽 block）, d0,d1（深度 block）。 */
        private final double[] seg;
        private final Map<Long, List<Integer>> tiles;

        private record Hit(double dist, double half, double depth) { }

        private SegmentIndex(double[] seg, Map<Long, List<Integer>> tiles) {
            this.seg = seg;
            this.tiles = tiles;
        }

        boolean isEmpty() { return seg.length == 0; }

        static SegmentIndex build(RiverCenterlines.Result cl, int originX, int originZ,
                                  int n, int step) {
            int count = 0;
            for (RiverCenterlines.Reach r : cl.reaches) count += Math.max(0, r.nodeCount() - 1);
            double[] seg = new double[count * 8];
            Map<Long, List<Integer>> tiles = new HashMap<>();
            int idx = 0;
            for (RiverCenterlines.Reach r : cl.reaches) {
                for (int i = 1; i < r.nodeCount(); i++) {
                    int c0 = r.cell[i - 1], c1 = r.cell[i];
                    double x0 = originX + (c0 / n) * (double) step;
                    double z0 = originZ + (c0 % n) * (double) step;
                    double x1 = originX + (c1 / n) * (double) step;
                    double z1 = originZ + (c1 % n) * (double) step;
                    double h0 = r.width[i - 1] * 0.5, h1 = r.width[i] * 0.5;
                    double d0 = r.depth[i - 1], d1 = r.depth[i];
                    // ★ idx 是【段号】，每段占 8 个 double（原写 `idx++ / 8` 使前 8 段
                    //   全部写进 slot 0 互相覆盖、其余槽位残留全零段 ⇒ 段索引形同虚设
                    //   （适配器探针实测：region 有 7682 个证据河道格而列产出 河=0）。
                    int si = idx++;
                    int o = si * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    seg[o + 4] = h0; seg[o + 5] = h1; seg[o + 6] = d0; seg[o + 7] = d1;
                    registerTile(tiles, si, x0, z0, x1, z1);
                }
            }
            return new SegmentIndex(seg, tiles);
        }

        /**
         * 从亚格 block 折线建段索引（骨架模式；2026-10-10）。
         *
         * <p>{@link DeterministicSkeleton.shape} 输出 Catmull-Rom + 格点吸附后的
         * 连续折线 —— 节点不是网格点，故不能走 {@link #build}（它按
         * {@code origin + cell·step} 换算）。半宽/深度恒定最小可见值
         * （用户方针：宽度后续议题）。</p>
         */
        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys) {
            // ★★★ 2026-10-10【端点缝合 —— 治"拓扑已 JOIN、像素差 2~3 格"的孤立段】★★★
            //
            // 【实测依据（OrphanGap）】孤立段到最近河/湖/海的距离：**2 格 8 个、3 格 2 个**，
            //   其余 ≥7 格 ⇒ **10/16 是 2~3 格的像素缝**（复查：折线自身必 8 连通，
            //   所以缝只能出现在两条折线之间）。
            // 【做法】把每条折线的【首/尾点】连到【另一条】折线 3 格内最近的点
            //   （切比雪夫距离 2~3；=1 已 8 连通无需连、>3 属真正断裂不硬连）。
            //   ⇒ 缝处补一个 2~3 块的短段 ⇒ 落块后像素连通。
            // 【为什么不是"放宽半宽"】那会把所有河加粗（用户：宽度是后续议题）。
            List<double[]> bridges = sewEndpoints(polys,
                    Integer.getInteger("geogenesis.sewGap", 3));   // 0 = 关（A/B 归因）
            if (!polys.isEmpty()) {
                LOGGER.info("[SEW] polys={} bridges={} gap={}", polys.size(), bridges.size(),
                        Integer.getInteger("geogenesis.sewGap", 3));
            }
            int count = 0;
            for (var sh : polys) count += Math.max(0, sh.size() - 1);
            double[] seg = new double[(count + bridges.size()) * 8];
            Map<Long, List<Integer>> tiles = new HashMap<>();
            int idx = 0;
            for (var sh : polys) {
                for (int i = 1; i < sh.size(); i++) {
                    double x0 = sh.x[i - 1], z0 = sh.z[i - 1];
                    double x1 = sh.x[i], z1 = sh.z[i];
                    int si = idx++;
                    int o = si * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    seg[o + 4] = RiverCenterlines.MIN_HALF_BLOCKS;  // h0
                    seg[o + 5] = RiverCenterlines.MIN_HALF_BLOCKS;  // h1
                    seg[o + 6] = 1.0;                               // d0
                    seg[o + 7] = 1.0;                               // d1
                    registerTile(tiles, si, x0, z0, x1, z1);
                }
            }
            for (double[] br : bridges) {
                int si = idx++;
                int o = si * 8;
                seg[o] = br[0]; seg[o + 1] = br[1]; seg[o + 2] = br[2]; seg[o + 3] = br[3];
                seg[o + 4] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 5] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 6] = 1.0;
                seg[o + 7] = 1.0;
                registerTile(tiles, si, br[0], br[1], br[2], br[3]);
            }
            return new SegmentIndex(seg, tiles);
        }

        /**
         * 端点缝合（见 {@link #buildFromNodes} 的说明）：
         * 每条折线的首/尾点连到<b>另一条</b>折线在 {@code maxGap} 格内最近的点。
         *
         * @return 缝合段列表 {x0,z0,x1,z1}（双精度 block 坐标）
         */
        private static List<double[]> sewEndpoints(List<DeterministicSkeleton.Shaped> polys, int maxGap) {
            final int PT = 8;                       // 点网格边长（块）
            Map<Long, List<int[]>> grid = new HashMap<>();
            if (maxGap <= 0) return new ArrayList<>();   // 关闭（A/B）
            for (int pi = 0; pi < polys.size(); pi++) {
                var sh = polys.get(pi);
                for (int i = 0; i < sh.size(); i++) {
                    int px = (int) Math.rint(sh.x[i]), pz = (int) Math.rint(sh.z[i]);
                    long key = (((long) Math.floorDiv(px, PT)) << 32)
                            ^ (Math.floorDiv(pz, PT) & 0xFFFFFFFFL);
                    List<int[]> lst = grid.get(key);
                    if (lst == null) { lst = new ArrayList<>(); grid.put(key, lst); }
                    lst.add(new int[]{px, pz, pi});
                }
            }
            List<double[]> out = new ArrayList<>();
            int span = maxGap / PT + 2;
            for (int pi = 0; pi < polys.size(); pi++) {
                var sh = polys.get(pi);
                if (sh.size() < 2) continue;
                for (int k = 0; k < 2; k++) {
                    int i = (k == 0) ? 0 : sh.size() - 1;
                    int px = (int) Math.rint(sh.x[i]), pz = (int) Math.rint(sh.z[i]);
                    int gx = Math.floorDiv(px, PT), gz = Math.floorDiv(pz, PT);
                    int bx = 0, bz = 0, best = Integer.MAX_VALUE;
                    for (int ax = gx - span; ax <= gx + span; ax++) {
                        for (int az = gz - span; az <= gz + span; az++) {
                            List<int[]> lst = grid.get((((long) ax) << 32) ^ (az & 0xFFFFFFFFL));
                            if (lst == null) continue;
                            for (int[] pt : lst) {
                                if (pt[2] == pi) continue;                 // 不连自己
                                int d = Math.max(Math.abs(pt[0] - px), Math.abs(pt[1] - pz));
                                if (d > 1 && d <= maxGap && d < best) {
                                    best = d; bx = pt[0]; bz = pt[1];
                                }
                            }
                        }
                    }
                    if (best != Integer.MAX_VALUE) {
                        out.add(new double[]{px, pz, bx, bz});
                    }
                }
            }
            return out;
        }

        /** 段 bbox + 半宽余量（≤16 < TILE=64 ⇒ 至多 2×2 tile）登记。 */
        private static void registerTile(Map<Long, List<Integer>> tiles, int si,
                                         double x0, double z0, double x1, double z1) {
            final int TILE = 64;
            int tx0 = (int) Math.floor((Math.min(x0, x1) - 16) / TILE);
            int tx1 = (int) Math.floor((Math.max(x0, x1) + 16) / TILE);
            int tz0 = (int) Math.floor((Math.min(z0, z1) - 16) / TILE);
            int tz1 = (int) Math.floor((Math.max(z0, z1) + 16) / TILE);
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int tz = tz0; tz <= tz1; tz++) {
                    tiles.computeIfAbsent(((long) tx << 32) ^ (tz & 0xffffffffL),
                            x -> new ArrayList<>()).add(si);
                }
            }
        }

        /** 该 block 到最近段的距离/半宽/深度；无候选 ⇒ null。 */
        Hit nearest(int blockX, int blockZ) {
            final int TILE = 64;
            List<Integer> cand = tiles.get(((long) Math.floorDiv(blockX, TILE) << 32)
                    ^ (Math.floorDiv(blockZ, TILE) & 0xffffffffL));
            if (cand == null) return null;
            double best = Double.POSITIVE_INFINITY, half = 0, depth = 0;
            for (int si : cand) {
                int o = si * 8;
                double x0 = seg[o], z0 = seg[o + 1], x1 = seg[o + 2], z1 = seg[o + 3];
                double dx = x1 - x0, dz = z1 - z0;
                double len2 = dx * dx + dz * dz;
                double t = len2 > 0
                        ? Math.max(0, Math.min(1, ((blockX - x0) * dx + (blockZ - z0) * dz) / len2))
                        : 0;
                double px = x0 + t * dx, pz = z0 + t * dz;
                double dist = Math.hypot(blockX - px, blockZ - pz);
                if (dist < best) {
                    best = dist;
                    half = seg[o + 4] + t * (seg[o + 5] - seg[o + 4]);
                    depth = seg[o + 6] + t * (seg[o + 7] - seg[o + 6]);
                }
            }
            return best == Double.POSITIVE_INFINITY ? null : new Hit(best, half, depth);
        }
    }
}
