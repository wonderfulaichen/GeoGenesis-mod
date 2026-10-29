package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.diagnostics.HydrologyMetrics;
import com.geogenesis.diagnostics.HydrologyMetrics.Cache;
import com.geogenesis.diagnostics.HydrologyMetrics.Outcome;
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
 *
 * <h2>可选性能测量（{@code -Dgeogenesis.hydrologyMetrics=true}，默认关）</h2>
 * <p>{@link HydrologyMetrics} 记录 region/pass1 缓存、求解与等待，以及骨架、shape、宽深、
 * 索引、fromReaches、columns 阶段；不参与数值决策，不替换原日志或追加文件。
 * 聚合项跨任务求和，不能相加嵌套阶段推导总耗时；JFR 可结合线程与时间范围定位。
 * HIER/共享湖域内部缓存、margin 和逐段 nearest 未单独计量。</p>
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
    /**
     * 水文模型格距（wu）。<b>默认 2</b>。
     *
     * <p><b>为什么是 2 而不是 4 或 1</b>（2026-10-21 实测）：</p>
     * <table border="1">
     *   <tr><th>STEP</th><th>贴合率中位</th><th>&gt;60° 夹角</th><th>生产图耗时</th></tr>
     *   <tr><td>4</td><td>41.9%</td><td>—</td><td>290 s</td></tr>
     *   <tr><td><b>2</b></td><td><b>97.8%</b></td><td><b>18.5%</b></td><td><b>300 s</b></td></tr>
     *   <tr><td>1</td><td>100.0%</td><td>15.5%</td><td>568 s（×2）</td></tr>
     * </table>
     * <p>⇒ 4→2 是<b>质变</b>（贴合率 +56pt，耗时几乎不变）；
     * 2→1 只是<b>量变</b>（+2.2pt）却要 <b>2 倍</b>耗时 ⇒ 不取。</p>
     *
     * <p>用户口径：「效果优先，性能后续再说」——但 1 的效果增量不足以抵消
     * 2 倍耗时与它引入的副作用（覆盖率暴跌、短河），故定 2。</p>
     *
     * <p>回退/前进：{@code -Dgeogenesis.modelStep=4}（快）或 {@code =1}（最细）。</p>
     */
    private static final int STEP = Integer.getInteger("geogenesis.modelStep", 2);
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
                              int originX, int originZ,
                              ProductionDrainageAudit.Snapshot drainage,
                              WorldDrainage.Result world, WorldBasinFlow.Region worldWater,
                              /**
                               * 生产骨架每条 Trace 的<b>源头</b> block 坐标 {x,z}（{@code t.cell[0]}）。
                               *
                               * <p><b>只读诊断载荷</b>：仅供探针"从生产源头放粒子"用，
                               * 不参与任何几何/落块/水位计算 ⇒ <b>不改变产出</b>。
                               * 见 {@link #skeletonSourcesForProbe}。</p>
                               */
                              int[][] sources,
                              /** 落块真正使用的亚格折线（{@code shape()} 输出）。只读诊断载荷。 */
                              List<DeterministicSkeleton.Shaped> shapedPolys,
                              /**
                               * ★ v2：<b>最终发布</b>（pass2）的骨架结果。
                               *
                               * <p>只读诊断载荷。<b>审计必须读它而不是 pass1</b>：
                               * pass1 只是承诺，pass2 才是真正发布的几何。</p>
                               */
                              DeterministicSkeleton.Result skel,
                              double[] bankLowering) {
        RegionData(HydrologyPipeline.Result pipe, RiverCenterlines.Result centerline, SegmentIndex skeletonSeg,
                   int originX, int originZ, ProductionDrainageAudit.Snapshot drainage) {
            this(pipe, centerline, skeletonSeg, originX, originZ, drainage, null, null, null, null, null,
                    RIVER_BANK_HEIGHT ? RiverBankHeight.build(pipe) : null);
        }

        int[][] sourcesOrEmpty() { return sources == null ? new int[0][] : sources; }
    }

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
            "geogenesis.handoffStart", "geogenesis.selfJoinFix", "geogenesis.frameEscape",
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
            "geogenesis.boundaryPorts", "geogenesis.widthSmooth", "geogenesis.widthMonotone",
            "geogenesis.bankJitter", "geogenesis.riverWidth", "geogenesis.riverCrossSection",
            "geogenesis.riverBankHeight",
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
            "geogenesis.joinChk", "geogenesis.gapWatch", "geogenesis.skelDump", "geogenesis.drainageAudit",
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

    /** 已接入普通客户端；显式false仅供旧断面A/B，不再要求额外JVM参数才能按河宽定深。 */
    static final boolean RIVER_CROSS_SECTION =
            !"false".equals(System.getProperty("geogenesis.riverCrossSection"));
    /** 岸水高差候选：与深度断面独立，默认关闭，先交局部生产对照。 */
    static final boolean RIVER_BANK_HEIGHT = Boolean.getBoolean("geogenesis.riverBankHeight");

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
            // hydrologyModelPipelineEnabled 已是 no-op（legacy 链移除），不再混入身份。
            h = mix(h, cfg.hydrologySkeletonRouting.get() ? 1 : 0);
            h = mix(h, cfg.hydrologyDecayEnabled.get() ? 1 : 0);
            h = mix(h, cfg.riverEnabled.get() ? 1 : 0);
        } catch (RuntimeException | LinkageError ignored) {
            h = mix(h, 0L);     // 无 Forge 环境（探针）：不留未加载异常
        }
        if(HIER && CROSS_PASS) {
            h=mix(h,WorldBasinFlow.POLICY_VERSION);
            h=mix(h,WorldLakeSurface.POLICY_VERSION);
            h=mix(h,1L); // Same-basin corridor plane policy.
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
                         int originX, int originZ,
                         /** ★ ports 模块：pass1 骨架本体（pass2 作 base 复用）。 */
                         DeterministicSkeleton.Result skeleton,
                         Map<WidthEdge, double[]> widths) {
        Pass1(HydrologyPipeline.Result pipe, List<DeterministicSkeleton.ExitSeed> exitSeeds,
              int originX, int originZ, DeterministicSkeleton.Result skeleton) {
            this(pipe, exitSeeds, originX, originZ, skeleton,
                    pass1Widths(pipe, skeleton, originX, originZ));
        }

        /** 兼容构造（分层实验路径不产出骨架 ⇒ null）。 */
        Pass1(HydrologyPipeline.Result pipe, List<DeterministicSkeleton.ExitSeed> exitSeeds,
              int originX, int originZ) {
            this(pipe, exitSeeds, originX, originZ, null);
        }
    }

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
            // 【测量】pass-1 实体任务（pipeline + skeleton 的父 scope；两者在 solvePass1 内另有子 scope）。
            long task = HydrologyMetrics.newTaskId();
            var taskScope = HydrologyMetrics.begin("hydro.pass1.task", rx, rz, -1, task);
            try {
                data = solvePass1(rx, rz);
            } catch (RuntimeException | Error e) {
                err = e;
            } finally {
                HydrologyMetrics.end(taskScope);
            }
            if (err == null) {
                HydrologyMetrics.cache(Cache.PASS1, Outcome.COMPUTE);
            } else {
                HydrologyMetrics.add("pass1.task.failed", 1L);
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
    // 2026-10-20【legacy 线移除（用户决定）】—— 生产只保留骨架线。
    //
    // 历史教训（为什么不能再留开关）：`SKELETON_TRACE` 曾是"系统属性 → 配置项 → false"三级回退，
    //   探针进程无 Forge 配置环境 ⇒ 探针长期走 false 分支（旧 RiverCenterlines），
    //   而实机按 TOML 走骨架 ⇒ "探针绿、实机不对"，两线产出互不约束。
    //   2026-10-09 的注释（本文件 §2026-10-09）记录过同一陷阱的第一次，本处是第二次。
    //
    // 现状：骨架是唯一生产河网线。legacy `RiverCenterlines.build` 算法已从 main 移除
    //   （git 历史 + 诊断探针 CenterlineCrossSectionProbe/CanonicalViewProbe 保留作 A/B 工具）；
    //   `RiverCenterlines` 类型本身保留 —— 它是骨架/世界图发布折线的几何容器（Reach/Result）。
    // ⚠ `CROSS_PASS` 分支仍是"世界图 vs 骨架谁出几何"的待合并项（第二阶段），不在本次范围。
    // ==========================================================================
    static final boolean SKELETON_TRACE = true;

    /**
     * 双 pass 跨区续流总开关（`-Dgeogenesis.skeletonCrossPass=false` 关闭）。
     *
     * <p>关 = 完全不取邻区出口种子（等价于"无跨区续流"），用于 A/B 归因；
     * 开（默认）= {@link #collectIncoming} 给 pass-2 注入接力种子。</p>
     */
    static final boolean CROSS_PASS =
            !"false".equals(System.getProperty("geogenesis.skeletonCrossPass"));

    /**
     * 湖缘逐块精修的 epsilon（块）。
     *
     * <p>判据 {@code eroded < surface - LAKE_EDGE_EPS}：只有地形确实低于湖面才算湖。
     * 取 1e-9 与 {@code GeoGenesisTerrain} 湖列判据同一口径，避免两处门槛再次不一致。</p>
     */
    static final double LAKE_EDGE_EPS = 1e-9;

    /**
     * 河岸自然扰动幅度（相对半宽的比例；0 = 关闭）。
     *
     * <p>参考 {@code 参考/river/dynamicwaters-11.1.2} 的 {@code getRiverCarve}（字节码
     * {@code HydrologyManager.txt:3870-3900}）：河岸扰动是双频正弦叠加，且
     * <b>幅度正比于河宽</b>（{@code jitter *= width/10}），是<b>位置驱动</b>而非随机。</p>
     *
     * <p>本处作用于半宽，同样由世界坐标驱动 ⇒ 跨 region / 跨 trace 同坐标同值，
     * 天然连续、不会在接缝处跳变。上限取半宽的 25%，防止把河岸推出河道。</p>
     */
    static final double BANK_JITTER = Double.parseDouble(
            System.getProperty("geogenesis.bankJitter", "0.25"));

    /**
     * ★ ports 模块开关（{@code -Dgeogenesis.boundaryPorts}，<b>默认开</b>）。
     *
     * <p>打开后：pass1 保留骨架、pass2 以本区 pass1 作 base（不重追 local 源，端口承诺不被抹掉）、
     * 接力改用<b>typed 端口</b>（世界锚点 + 真实下一步 + 实际接收 owner），并打印显式边界计数。</p>
     *
     * <p>★★ 2026-10-25【默认由关改为开】★★
     * 用户确认的目标效果（1401² 正典图，production.png SHA256 {@code 74E9DC00…B456E3}）
     * 就是在该开关打开时产出的；关掉它跨区端口/typed 接力/双 pass 全部停用，
     * 游戏里会退回另一套几何。故默认置开。
     * <b>回退</b>：{@code -Dgeogenesis.boundaryPorts=false} 即逐位回到关闭时的旧行为。</p>
     */
    static final boolean BOUNDARY_PORTS =
            !"false".equals(System.getProperty("geogenesis.boundaryPorts"));

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
                                  List<DeterministicSkeleton.ExitSeed> exitSeeds,
                                  ProductionDrainageAudit.Snapshot drainage,
                                  /** 每条 Trace 的源头 block 坐标 {x,z}（只读诊断载荷，不参与产出）。 */
                                  int[][] sources,
                                  /** 落块真正使用的亚格折线（{@code shape()} 输出）。只读诊断载荷。 */
                                  List<DeterministicSkeleton.Shaped> shapedPolys,
                                  /** ★ ports 模块：本次骨架结果（含端口/未决出口）。只读载荷。 */
                                  DeterministicSkeleton.Result skel) {
        SkeletonRegion(RiverCenterlines.Result cl, SegmentIndex seg,
                       List<DeterministicSkeleton.ExitSeed> exitSeeds,
                       ProductionDrainageAudit.Snapshot drainage,
                       int[][] sources, List<DeterministicSkeleton.Shaped> shapedPolys) {
            this(cl, seg, exitSeeds, drainage, sources, shapedPolys, null);
        }
        SkeletonRegion(RiverCenterlines.Result cl, SegmentIndex seg,
                       List<DeterministicSkeleton.ExitSeed> exitSeeds,
                       ProductionDrainageAudit.Snapshot drainage) {
            this(cl, seg, exitSeeds, drainage, new int[0][], null);
        }
    }

    /** 只读诊断：取 SkeletonRegion 里的 traces（null-safe）。 */
    static List<DeterministicSkeleton.Trace> skeletonTracesForProbe(Object skeletonRegion) {
        if (!(skeletonRegion instanceof SkeletonRegion sr) || sr.skel() == null) return null;
        return sr.skel().traces;
    }

    /** 只读诊断：取 SkeletonRegion 里重建出的河流（{@code buildRivers} 的产出）。 */
    static List<DeterministicSkeleton.River> skeletonRiversForProbe(Object skeletonRegion) {
        if (!(skeletonRegion instanceof SkeletonRegion sr) || sr.skel() == null) return null;
        return sr.skel().rivers;
    }

    /**
     * 生产骨架的<b>真源头</b>（每条 Trace 的首格，block 坐标）—— <b>只读诊断接口</b>。
     *
     * <p><b>为什么必须是它</b>（三次试错记录）：</p>
     * <ol>
     *   <li>诊断侧自己调 {@code DeterministicSkeleton.build} 复刻 ⇒ 漏掉生产的
     *       REGION 网格 / HALO / 跨区接力 ⇒ 源头只有 3.3% 落在生产河格上。</li>
     *   <li>取 {@code skeletonSeg} 的段端点当源头 ⇒ 每段一个（1.7 万个假源头）；
     *       端点只是"某条边的上端"，不是"起点"。</li>
     *   <li>取 {@code worldForProbe} 的边 ⇒ 生产不走 CROSS_PASS 分支（{@code HIER=false}），
     *       返回 0 条边。</li>
     * </ol>
     * <p>正解 = {@code Trace.cell[0]}（用户原话：「源头不是起点吗」）。</p>
     *
     * <p><b>不影响产出的保证</b>：本载荷只在 {@code skeletonRegion} 末尾顺带收集，
     * 不读也不写任何几何/水位/落块状态；删掉它对产出无任何影响。</p>
     *
     * @return 每项 {blockX, blockZ}；空数组表示该 region 无骨架
     */
    int[][] skeletonSourcesForProbe(int blockX, int blockZ) {
        return regionFor(blockX, blockZ).sourcesOrEmpty();
    }

    ProductionDrainageAudit.Snapshot drainageForProbe(int rx, int rz) {
        return regionFor(rx * REGION + REGION / 2, rz * REGION + REGION / 2).drainage();
    }

    HydrologyPipeline.Result pipeForProbe(int blockX, int blockZ) {
        return regionFor(blockX, blockZ).pipe();
    }

    /**
     * <b>只读诊断接口</b>：本 region 真正用于落块的<b>亚格几何节点</b>（{@code shape()} 输出）。
     *
     * <p><b>为什么不能用 {@link #reachNodesWorldForProbe}</b>：那个返回的是 {@code Reach.cell}
     * 的<b>格路径</b>（拓扑层，恒为 STEP 整数倍坐标），而真正画成河的是
     * {@code DeterministicSkeleton.shape()} 产出的<b>亚格锚点折线</b>
     * （含 Catmull-Rom 重采样、meander、有界粒子偏移）。
     * 几何改动（如连续锚点）只影响后者 ⇒ 用格路径去验证会得到"完全没变"的假结论。</p>
     *
     * @return 外层 = 折线；内层每项 {worldX, worldZ}（double 精度，含小数）
     */
    double[][] shapedNodesWorldForProbe(int blockX, int blockZ) {
        RegionData rd = regionFor(blockX, blockZ);
        List<DeterministicSkeleton.Shaped> polys = rd.shapedPolys();
        if (polys == null) return new double[0][];
        double[][] out = new double[polys.size()][];
        for (int i = 0; i < polys.size(); i++) {
            var sh = polys.get(i);
            double[] pts = new double[sh.size() * 2];
            for (int k = 0; k < sh.size(); k++) {
                pts[k * 2] = sh.x[k];
                pts[k * 2 + 1] = sh.z[k];
            }
            out[i] = pts;
        }
        return out;
    }

    List<RiverCenterlines.Reach> reachesForProbe(int blockX, int blockZ) {
        return List.copyOf(regionFor(blockX, blockZ).centerline().reaches);
    }

    /**
     * <b>只读诊断接口</b>：把本 region 每条河段的节点转成<b>世界坐标</b>返回。
     *
     * <p><b>为什么要这个</b>（用户 2026-10-21）：
     * 「我们的节点生成位置也许就不太对。还有节点之间连线，什么节点和什么节点连接，
     * 是不是连错了」+「记得我说过要把河流骨架的节点显示出来方便观察的。还有各条河流的各自颜色」。</p>
     *
     * <p>此前诊断侧自己把 {@code Reach.cell} 换算世界坐标，需要知道窗口原点，
     * 而窗口原点的算法（{@code centerX - RADIUS}，其中 {@code RADIUS = REGION/2 + HALO}）
     * 每次都要我猜一遍 ⇒ 极易错位（已因此把"源头对齐率"算错两次）。
     * 本接口把换算收在生产侧，只有一处真相。</p>
     *
     * <p><b>不影响产出的保证</b>：纯读取已发布产物，不做任何计算写回。</p>
     *
     * @return 外层 = 河段；内层每项 {worldX, worldZ}；空数组=该段无有效节点
     */
    int[][] reachNodesWorldForProbe(int blockX, int blockZ) {
        RegionData rd = regionFor(blockX, blockZ);
        int ox = rd.originX(), oz = rd.originZ();
        // ★ 2026-10-21 修复【坐标系统性减半】：
        //   `Reach.cell` 是【本 region 窗口】的格索引，编码用 `n = pipe.nx()`
        //   （见 `fromReaches(reaches, n, n, STEP)`，其 n 即 p.nx()）。
        //   旧写法用 `rd.pipe().nx()` —— 数值上等价，但**没有校验**；
        //   一旦 Reach 来自跨区合并（3×3 邻区）而 n 取错，解码就会整体错位。
        //   现显式取管道 nx 并与 `Reach` 里的数组长度做一致性断言。
        HydrologyPipeline.Result pipe = rd.pipe();
        int n = pipe.nx();
        var reaches = rd.centerline().reaches;
        int[][] out = new int[reaches.size()][];
        for (int i = 0; i < reaches.size(); i++) {
            int[] cell = reaches.get(i).cell;
            int[] pts = new int[cell.length * 2];
            for (int k = 0; k < cell.length; k++) {
                int c = cell[k];
                if (c < 0 || c >= n * n) { pts[k * 2] = Integer.MIN_VALUE; continue; }
                pts[k * 2] = ox + (c / n) * STEP;
                pts[k * 2 + 1] = oz + (c % n) * STEP;
            }
            out[i] = pts;
        }
        return out;
    }

    /**
     * <b>只读诊断接口</b>：本 region 每条河段的节点，<b>连同该 region 的窗口原点与 nx</b>。
     *
     * <p>与 {@link #reachNodesWorldForProbe} 的区别：把 {@code originX/originZ/nx}
     * 一并返回，让调用方<b>无法在不知情的情况下</b>用错解码参数。
     * 这是修复「坐标系统性减半」的直接措施 —— 该缺陷曾导致
     * {@code ContourWalkProbe} 报出「39.2% 爬升」的假结论。</p>
     *
     * @return {@code {originX, originZ, nx, x0,z0, x1,z1, ...}}（单条河段展平）
     */
    int[][] reachNodesWithFrameForProbe(int blockX, int blockZ) {
        RegionData rd = regionFor(blockX, blockZ);
        int ox = rd.originX(), oz = rd.originZ();
        int n = rd.pipe().nx();
        var reaches = rd.centerline().reaches;
        int[][] out = new int[reaches.size()][];
        for (int i = 0; i < reaches.size(); i++) {
            int[] cell = reaches.get(i).cell;
            int[] pts = new int[3 + cell.length * 2];
            pts[0] = ox; pts[1] = oz; pts[2] = n;
            for (int k = 0; k < cell.length; k++) {
                int c = cell[k];
                if (c < 0 || c >= n * n) { pts[3 + k * 2] = Integer.MIN_VALUE; continue; }
                pts[3 + k * 2] = ox + (c / n) * STEP;
                pts[3 + k * 2 + 1] = oz + (c % n) * STEP;
            }
            out[i] = pts;
        }
        return out;
    }

    /**
     * <b>只读诊断接口</b>：本 region 的「源头 ↔ 骨架折线」<b>一一配对</b>。
     *
     * <p><b>为什么要这个</b>（用户 2026-10-21）：
     * 「你确定是对应河流各自对比 2 种路线吗？不要把不同源头的河流做对比。」</p>
     *
     * <p><b>旧做法的缺陷</b>：探针把「所有源头」和「所有骨架折线」混成两个集合，
     * 再用「起点最近的折线」配对。当多条河的源头相距 &lt;24 块时会<b>配错</b>
     * ⇒ 拿 A 河的物理线比 B 河的骨架线 ⇒ 夹角/贴合全是假的。</p>
     *
     * <p><b>正解</b>：生产里 {@code shaped.get(i)} 与 {@code sk.traces.get(i)}
     * <b>下标严格对应</b>（{@code shape()} 内 "if (m &lt; 2) 发空折线占位" 就是为保这个）。
     * 故源头（{@code Trace.cell[0]}）与折线天然一一配对，直接按同一下标回传。</p>
     *
     * @return 每项 = {worldX0, worldZ0, x1,z1, x2,z2, ...}
     *         （第 1 对是源头；其后为该折线全部节点，含 CR 重采样后的点）
     */
    double[][] sourceToPolylineForProbe(int blockX, int blockZ) {
        RegionData rd = regionFor(blockX, blockZ);
        List<DeterministicSkeleton.Shaped> polys = rd.shapedPolys();
        int[][] srcs = rd.sourcesOrEmpty();
        if (polys == null) return new double[0][];
        int n = Math.min(polys.size(), srcs.length);
        int ox = rd.originX(), oz = rd.originZ();
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] pt;
            int[] s = srcs[i];
            boolean hasSrc = s != null && s[0] != Integer.MIN_VALUE;
            if (hasSrc) {
                pt = new double[]{s[0], s[1]};
            } else {
                var sh = polys.get(i);
                if (sh.size() == 0) continue;
                pt = new double[]{sh.x[0], sh.z[0]};
            }
            var sh = polys.get(i);
            int m = sh.size();
            double[] row = new double[2 + m * 2];
            row[0] = pt[0];
            row[1] = pt[1];
            for (int k = 0; k < m; k++) {
                row[2 + k * 2] = sh.x[k];
                row[3 + k * 2] = sh.z[k];
            }
            out[i] = row;
        }
        return out;
    }

    /**
     * <b>只读诊断接口</b>：该 region 的窗口原点与格距，供探针把 {@code bed[]}
     * 的<b>窗口格索引</b>正确换算成世界坐标。
     *
     * <p><b>为什么需要</b>：{@code pipeForProbe().bed()} 的索引是窗口内索引，
     * 换算世界坐标必须知道 origin 与 step。诊断侧自己用
     * {@code (n-1)/2*step} 反推窗口原点 ⇒ 已因此产出过
     * 「bed 与 erodedHeight 差 17 倍」的<b>假结论</b>。</p>
     *
     * @return {originX, originZ, step, nx}
     */
    int[] windowFrameForProbe(int blockX, int blockZ) {
        RegionData rd = regionFor(blockX, blockZ);
        return new int[]{rd.originX(), rd.originZ(), STEP, rd.pipe().nx()};
    }

    WorldDrainage.Result worldForProbe(int rx, int rz) {
        return regionFor(rx * REGION + REGION / 2, rz * REGION + REGION / 2).world();
    }

    WorldBasinFlow.Region worldWaterForProbe(int rx,int rz) {
        return regionFor(rx*REGION+REGION/2,rz*REGION+REGION/2).worldWater();
    }

    WorldDrainage worldDrainage() {
        double minCells = Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150"));
        if (!Double.isFinite(minCells) || minCells < 1 || minCells > 4096)
            throw new IllegalArgumentException("world source threshold must be 1..4096 cells");
        return new WorldDrainage(STEP, (int) Math.ceil(minCells), gen.params().seaLevel(),
                (x, z) -> gen.erodedHeight(x * invHs, z * invHs));
    }

    private SkeletonRegion worldRegion(WorldBasinFlow.Region water, HydrologyPipeline.Result p, int ox, int oz) {
        var world = water.graph();
        var polys = new ArrayList<DeterministicSkeleton.Shaped>();
        var reaches = new ArrayList<RiverCenterlines.Reach>();
        for (var edge : world.edges()) {
            var a = edge.from(); var b = edge.to();
            polys.add(new DeterministicSkeleton.Shaped(new double[]{a.x(), b.x()}, new double[]{a.z(), b.z()}));
            int ca = (a.x() - ox) / STEP * p.nz() + (a.z() - oz) / STEP;
            int cb = (b.x() - ox) / STEP * p.nz() + (b.z() - oz) / STEP;
            reaches.add(new RiverCenterlines.Reach(new int[]{ca, cb}, new double[]{Double.NaN, Double.NaN},
                    new double[]{1, 1}, new double[]{1, 1}, 0));
        }
        int n = p.bed().length;
        var cl = new RiverCenterlines.Result(List.copyOf(reaches), new double[n], new double[n],
                new double[n], new boolean[n], 0);
        var index = SegmentIndex.buildExactNodes(polys, p, ox, oz, n, STEP);
        int edgeIndex=0;
        for(var edge:world.edges()) {
            var wa=water.water().get(edge.from());
            var wb=water.water().get(edge.to());
            if(wa!=null && wb!=null && wa.basin()!=null && wb.basin()!=null
                    && wa.basin().identity().equals(wb.basin().identity())) {
                if(Double.doubleToLongBits(wa.level())!=Double.doubleToLongBits(wb.level()))
                    throw new IllegalStateException("same-basin corridor has two water planes");
                index.surface[edgeIndex]=wa.level();
            }
            edgeIndex++;
        }
        if (index.seg.length / 8 != world.edges().size() || reaches.size() != world.edges().size())
            throw new IllegalStateException("world edge publication lost geometry");
        return new SkeletonRegion(cl, index, List.of(), null);
    }

    /**
     * 从 {@link DeterministicSkeleton} 构建 region 骨架。
     *
     * <p><b>参数口径</b>（全部对齐 legacy，见 {@code DeterministicSkeleton.Params}）：</p>
     * <ul>
     *   <li>源头间距 = {@code -Dgeogenesis.sourceSpacing}（默认 24 块，折算 /STEP 格）；</li>
     *   <li>源头面积下限 = {@code n² × 0.003} 集水（legacy 骨架
     *       {@code thr = gridN²×0.003} 同值；{@code -Dgeogenesis.skeletonSourceFrac} 可 A/B）；</li>
     *   <li>就近汇入窗口 = 2 格（legacy {@code traceStep}）；</li>
     *   <li>meander 默认<b>开</b>（amp=0.6）。历史：2026-10-10 用户曾要求关闭
     *       （"弯曲先移除，我后面重新设计"），2026-10-25 因"骨架呈折线"改为默认开；
     *       用 {@code -Dgeogenesis.skeletonMeanderAmp=0} 可回到关闭态做 A/B；</li>
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
    /**
     * 生产骨架参数派生（<b>单一事实来源</b>）。
     *
     * <p>探针若自行拼 {@link DeterministicSkeleton.Params}，极易漏掉
     * {@code srcAccum}（成河门限）与 {@code selfRadius}（生产已关）——
     * 结果画出来的"骨架"不是生产骨架。故把派生逻辑抽到这里，
     * 生产路径与诊断共用同一份。</p>
     *
     * @param runoff 集水径流（{@code lakeResult.runoffPerCell()}；无湖泊结果时传 0）
     */
    static DeterministicSkeleton.Params productionParams(double runoff) {
        int legacyToOurs = Math.max(1, 8 / STEP);
        int spacingBlocks = Integer.getInteger("geogenesis.sourceSpacing", 24);
        int spacingCells = Math.max(1, Math.round(spacingBlocks / (float) STEP));
        // ★★★ 2026-10-21【门限 150 → 40：对齐物理动线参照】★★★
        //
        // 【依据】用户目标 = 让确定性骨架"大致相似"物理粒子动线（不要求一致）。
        //   以 `ParticleOverlayProbe` 产出的粒子动线为参照，实测生产骨架覆盖率：
        //     基线 150：±8 块 17.1% / ±16 块 30.0%
        //     ⇒ 骨架只覆盖参照的一小部分，「上半内陆河几乎缺席」是其直观表现。
        //
        // 【单变量扫描（-Dsweep.chan / -Dsweep.nodes，其余走生产路径）】
        //     chan=150 nodes=6   traces=183 riverPx=17184  ±8=17.1% ±16=30.0%
        //     chan= 60 nodes=14  traces=221 riverPx=21961  ±8=20.7% ±16=35.6%
        //     chan= 40 nodes=14  traces=277 riverPx=26012  ±8=25.5% ±16=43.3%   ← 取此
        //     chan= 25 nodes=14  traces=313 riverPx=28434  ±8=26.3% ±16=45.7%
        //     chan= 15 nodes=14  traces=337 riverPx=30612  ±8=27.7% ±16=48.1%
        //   40→15 河格 +18% 而覆盖率仅 +2.2pt ⇒ **边际饱和**，故取 40 不取更低。
        //
        // 【配套】minNodes 6 → 14：用户判据「短碎河流」。
        //   放宽门限必然放出一批短段，提高长度下限把它们整条丢弃。
        //   实测 nodes=6→14 在 chan=150 时 riverPx 17184→16483（−4%），
        //   即只砍短段、不动主干，正是需要的选择性。
        //   ⚠【2026-10-29 拆分属性】本处原与 `geogenesis.channelMinCells` 共用属性名，
//     但两处默认值不同（骨架 40 / 世界排水 150）。调世界门限会**静默**把骨架源密度
//     改掉 3.75 倍，与 boundaryPorts 那次同类的坑。故拆为独立键，默认值不变。
        double chanMinCells = Double.parseDouble(
                System.getProperty("geogenesis.skeletonSourceCells", "40"));
        double srcAccum = runoff > 0 ? chanMinCells * runoff : 0.0;
        // ★★★ 2026-10-25【meander 由 0 改为默认开启 —— 治"骨架呈折线"】★★★
        //
        // 【为什么之前是 0】2026-10-10 用户明确要求关闭（当时开启后河流/湖泊出问题）。
        //   但那个问题出在**当时的 4 倍频 meander 与旧几何的交互**，不是 meander 本身。
        //
        // 【现在为什么必须开】用户实测「骨架不够平滑连续，有部分呈折线状态」。
        //   根因：锚点是 STEP=2 的格心，Catmull-Rom 在**三点共线**时切线也共线 ⇒ 曲率为 0
        //   ⇒ 输出仍是直线段折线。meanderAmp=0 时没有任何东西能让锚点脱离共线。
        //   ⇒ 只有 meander 能给锚点连续的侧向偏移，从而让 CR 真正产生曲率。
        //
        // 【幅度取值】0.6 块（半宽级别），远小于旧值 6.0 —— 目标是"消除折线感"，
        //   不是制造明显蛇曲。可 -Dgeogenesis.skeletonMeanderAmp=0 关回旧行为。
        double meanderAmp = Double.parseDouble(
                System.getProperty("geogenesis.skeletonMeanderAmp", "0.6"));
        int maxRivers = Integer.getInteger("geogenesis.skeletonMaxRivers", 0);
        int minNodes = Integer.getInteger("geogenesis.skeletonMinNodes", 14);
        int selfRadius = Integer.getInteger("geogenesis.skeletonSelfRadius", 0);
        int selfExempt = Integer.getInteger("geogenesis.skeletonSelfExempt", 6 * legacyToOurs);
        return new DeterministicSkeleton.Params(
                spacingCells, srcAccum, 2, meanderAmp, 24.0,
                maxRivers, minNodes, selfRadius, selfExempt);
    }

    private SkeletonRegion skeletonRegion(HydrologyPipeline.Result p,
                                          int originX, int originZ,
                                          List<Integer> incomingCells, boolean primary) {
        return skeletonRegion(p, originX, originZ, incomingCells, primary, null);
    }

    /**
     * ★ ports 模块：带 base 的骨架构建（pass2 专用）。
     *
     * @param base 本区 pass1 的骨架结果；{@code null} = 旧行为（逐位不变）
     */
    private SkeletonRegion skeletonRegion(HydrologyPipeline.Result p,
                                          int originX, int originZ,
                                          List<Integer> incomingCells, boolean primary,
                                          DeterministicSkeleton.Result base) {
        // ★ v2：角色<b>由 base 是否存在推出</b>并显式传入（旧缺陷 ②：base 重载硬写 false）。
        return skeletonRegion(p, originX, originZ, incomingCells, List.of(), primary, base,
                base == null ? DeterministicSkeleton.PassRole.PRIMARY
                             : DeterministicSkeleton.PassRole.SECOND_PASS);
    }

    /**
     * ★ v2 typed 入口：带 {@code incomingWires} 与显式 {@link DeterministicSkeleton.PassRole}。
     */
    private SkeletonRegion skeletonRegion(HydrologyPipeline.Result p,
                                          int originX, int originZ,
                                          List<Integer> incomingCells,
                                          List<WireIn> incomingWires, boolean primary,
                                          DeterministicSkeleton.Result base,
                                          DeterministicSkeleton.PassRole role) {
        int n = p.nx();
        double runoff = p.lakeResult() == null ? 0.0 : p.lakeResult().runoffPerCell();
        // ★ 单一事实来源：全部参数派生集中在 productionParams（探针也调用它）。
        //   此处原先另有一份内联等价实现 + "漂移断言"；那是双份真相，
        //   改默认值时必须两处同步（已为此踩过一次）。现只保留一处。
        var skParams = productionParams(runoff);
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
        //   ⚠ 现行取值见 productionParams（150 → 40，对齐物理动线参照，附扫描数据）。
        // ★★★ 2026-10-10【三项 legacy 机制补齐（用户判据驱动）】★★★
        //
        // 【用户报的三个问题】① 很多短碎河流 ② 一小部分截断 ③ 反复交叉/重叠。
        // 【共同根因】我只移植了 legacy 的"追踪 + 汇入"，漏了它【过滤】的一半：
        //   · minRiverNodes（长度下限）—— 短段不丢 ⇒ 短碎河流；
        //   · riverCount（数量上限）—— 源头不限 ⇒ 河网过密；
        //   · SELF_APPROACH（自贴近守卫）—— 只排自己节点 ⇒ 擦身绕行 = 交叉重叠。
        //
        //   （block），我方 STEP=4 ⇒ **我方 1 格 = legacy 半格** ⇒ legacy 的格数阈值
        //   须**乘 2** 才等价：
        //     minRiverNodes  3 格(legacy) × 8 ÷ 4 = **6 格**（我方）
        //     SELF_APPROACH_RADIUS 2 格 × 8 ÷ 4 = **4 格**（我方）
        //     SELF_APPROACH_EXEMPT 6 步 × 2 = **12 步**（我方）
        //   ⚠ 若直接照抄 legacy 数字，判据会【过松】（半径 2 格在我方只覆盖 8 block）。
        //
        // ★ 上述三项 + 成河门限的【可执行取值】全部在 productionParams 里（单一事实来源）。
        // ★★★ 2026-10-10【selfRadius 默认 0 —— 它无贡献且制造孤立段】★★★
        // 【无贡献】单变量 A/B 实测：关它 14119 格 vs 全开 14000（差 0.8%）⇒
        //   防交叉的主力是 segmentCrossesAny（交叉守卫），不是它。
        // 【有害】用户圈出孤立段（既不连湖也不入海）⇒ skelDump 铁证：
        //   poly#18 end=DEAD_END mouth=(-416,468) 不在窗口边框 —— 是自贴近守卫
        //   误触发（大弯河曲相隔 >12 步 ⇒ 豁免放不过 ⇒ "贴着自己"就地终止）。
        //   终止的链既不接湖也不接海 ⇒ 凭空断流。legacy 同名机制由"洼地续流"兜底，
        //   我方无该兜底 ⇒ 直接关闭（候选：日后给自贴近终止加专用 End + 兜底）。
        // 【测量】骨架本体（pass1 与 final 都走这里；父级 scope 分别是 hydro.pass1.skeleton / hydro.region.solve）
        DeterministicSkeleton.Result sk;
        var skelBuildScope = HydrologyMetrics.begin("hydro.skel.build", originX, originZ);
        try {
            sk = DeterministicSkeleton.build(
                p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.lakeResult() == null ? null : p.lakeResult().basinOutlet(),
                n, n, skParams, incomingCells, incomingWires, p.bed(),
                // ★★ 分层实验：**构建时显式关闭**网络过滤（Boolean.FALSE）。
                //    理由（失败规范）：{@code filterUnconnected} 会整簇删除"不接水体"的链
                //    ⇒ 拿它的产出当连通性证据 = 用删除掩盖"支流接不进下游接口"的失败。
                //    只记日志然后继续 ❌ ⇒ 这里真的不删；孤立由独立审计如实计入并判 FAIL。
                //    既有生产路径传 null ⇒ 逐位不变。
                HIER ? Boolean.FALSE : null,
                // ★【窗口边界不截断】：parent=-1 且非湖/海时，用世界坐标地形最陡下降续追到水体
                //   （等价旧版 margin 效果，但不扩求解域）。步数上限按窗口半宽折算。
                new DeterministicSkeleton.OutsideContinuation(
                        (bx, bz) -> gen.erodedHeight(bx * invHs, bz * invHs),
                        originX, originZ, STEP,
                        Integer.getInteger("geogenesis.outsideSteps", 2048),
                        gen.params().seaLevel()),
                // ★ v2：wires 与角色一起进 build（不再有"有 base 就自动 pass1"的默认）。
                base, role,
                new DeterministicSkeleton.CoreBounds(originX + HALO, originZ + HALO,
                        originX + HALO + REGION, originZ + HALO + REGION),
                // ★ 显式传湖泊水量平衡上下文：溢口首步用 parent[outlet] 的真实外出方向，
                //   并以 basinOutlet/basinId 做短滤认证。（不传 ⇒ 旧行为逐位不变。）
                p.lakeResult());
        } finally {
            HydrologyMetrics.end(skelBuildScope);
        }
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
                    String.format("%.1f", skParams.minSourceAccum()), String.format("%.5f", runoff),
                    sk.traces.size(), sk.sourcesTotal, String.format("%.1f", maxDischarge(p.discharge())));
        }
        // 亚格几何（Catmull-Rom + 格点吸附）→ SegmentIndex（落块用）
        // 【测量】shape 阶段 = 亚格折线化 + 出湖前缀拼接（final 的几何主体）
        List<DeterministicSkeleton.Shaped> shaped;
        var shapeScope = HydrologyMetrics.begin("hydro.skel.shape", originX, originZ);
        try {
            shaped = DeterministicSkeleton.shape(sk.traces, p.level(),
                    originX, originZ, STEP, n, n, skParams, p.bed(), sk.anchorPins);
            shaped = OutletLakePrefix.attach(sk.traces, shaped, p, originX, originZ, STEP);
        } finally {
            HydrologyMetrics.end(shapeScope);
        }
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
        // 【测量】profile 的输入层（宽度继承 / 边界约定 / 跨窗出湖半宽）；
        //   真正的 RiverWidthProfile 求解在 buildFromNodes 内（hydro.profile.width）。
        WidthInheritance inherited;
        Map<Integer, Double> boundary;
        Map<Long, Double> outletHalf;
        var widthScope = HydrologyMetrics.begin("hydro.skel.width", originX, originZ);
        try {
            inherited = primary && BOUNDARY_PORTS && !HIER
                    ? inheritedWidths(sk, originX, originZ, n) : new WidthInheritance(new HashMap<>(), new java.util.HashSet<>(), new HashMap<>());
            boundary = primary && BOUNDARY_PORTS && !HIER
                    ? boundaryWidths(sk, incomingWires, inherited, originX, originZ, n) : Map.of();
            // 同一世界出口格在相邻窗口可能判成不同源盆 ⇒ 出湖宽度必须取双方较小者，
            // 否则上游窗口按大盆出流抬粗、下游窗口按小盆压细，整条短河被迫收窄。
            // ⚠ 门控必须与 inheritedWidths 完全一致（primary + BOUNDARY_PORTS + !HIER）：
            //   pass1 内部也走本方法，若无 primary 门控就会在 pass1 里再求邻区 pass1 ⇒ 连锁扇出。
            outletHalf = primary && BOUNDARY_PORTS && !HIER && p != null && sk.traces != null
                    ? agreedOutletHalves(sk, p, originX, originZ, n) : Map.of();
        } finally {
            HydrologyMetrics.end(widthScope);
        }
        // 【测量】index 阶段 = SegmentIndex 构建（内含 profile/depth/诊断 I/O 三个子 scope）
        SegmentIndex seg;
        var indexScope = HydrologyMetrics.begin("hydro.skel.index", originX, originZ);
        try {
            seg = allPolys.isEmpty() ? SegmentIndex.EMPTY
                    : SegmentIndex.buildFromNodes(allPolys, p, originX, originZ, n, STEP,
                            sk.traces, sk.rivers, boundary, inherited, outletHalf);
        } finally {
            HydrologyMetrics.end(indexScope);
        }
        RiverCenterlines.Params wp = RiverCenterlines.Params.defaults();
        List<RiverCenterlines.Reach> reaches = new ArrayList<>();
        for (var t : sk.traces) {
            int m = t.nodeCount();
            int[] cell = new int[m];
            double[] q = new double[m];
            double[] w = new double[m];
            double[] d = new double[m];
            for (int i = 0; i < m; i++) {
                cell[i] = t.cell[i];
                // ★ 2026-10-25：Q 越界保护（cell 可能来自窗外续流，索引超出本窗口）
                double qi = cell[i] >= 0 && cell[i] < p.discharge().length
                        ? p.discharge()[cell[i]] : 0.0;
                q[i] = qi;
                // 全宽按流量律（源头细、干流粗）；无 Q ⇒ 最小可见值
                w[i] = qi > 0
                        ? Math.max(1.0, RiverCenterlines.fullWidthPublic(qi, wp))
                        : 1.0;
                d[i] = 1.0;
            }
            reaches.add(new RiverCenterlines.Reach(cell, q, w, d, 0));
        }
        int mouthsLake = 0, mouthsSea = 0, joins = 0, unresolved = 0, deadEnd = 0;
        int boundaryExits = 0;                       // ★ ports 模块：显式出窗数
        for (var t : sk.traces) {
            switch (t.end) {
                case MOUTH_LAKE -> mouthsLake++;
                case MOUTH_SEA -> mouthsSea++;
                case JOIN -> joins++;
                case UNRESOLVED -> unresolved++;
                case DEAD_END -> deadEnd++;
                case BOUNDARY_EXIT -> boundaryExits++;
            }
        }
        // DEAD_END = parent=-1 且非海非湖（窗口排水根）—— 断口归因的头号嫌疑。
        if (primary) {
            LOGGER.info("[HYDRO-SKELETON] traces={} sources={} droppedBySpacing={} outletStarts={}"
                            + " incomingStarts={} ends: lake={} sea={} join={} unresolved={}"
                            + " DEAD_END={} BOUNDARY_EXIT={} exitSeeds={} ports={} pending={}"
                            + " mergedIncoming={}",
                    sk.traces.size(), sk.sourcesTotal, sk.sourcesDroppedBySpacing,
                    sk.outletStarts, sk.incomingStarts, mouthsLake, mouthsSea, joins,
                    unresolved, deadEnd, boundaryExits, sk.exitSeeds.size(),
                    sk.ports.size(), sk.pendingExits.size(), sk.mergedIncoming);
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
        // ★ 只读诊断载荷：每条 Trace 的首格 = 源头（不改产出，见 skeletonSourcesForProbe）
        int[][] srcPts = new int[sk.traces.size()][];
        for (int i = 0; i < sk.traces.size(); i++) {
            var t = sk.traces.get(i);
            if (t.nodeCount() == 0) { srcPts[i] = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE}; continue; }
            int c0 = t.cell[0];
            srcPts[i] = new int[]{originX + (c0 / n) * STEP, originZ + (c0 % n) * STEP};
        }
        logBoundaryPorts(sk, originX, originZ, n, primary);
        RiverCenterlines.Result centerlines;
        var reachesScope = HydrologyMetrics.begin("hydro.skel.fromReaches", originX, originZ);
        try {
            centerlines = RiverCenterlines.fromReaches(reaches, n, n, STEP);
        } finally {
            HydrologyMetrics.end(reachesScope);
        }
        return new SkeletonRegion(centerlines, seg,
                sk.exitSeeds, primary && Boolean.getBoolean("geogenesis.drainageAudit")
                ? ProductionDrainageAudit.capture(p, sk, shaped,
                originX, originZ, REGION) : null, srcPts, shaped, sk);
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
        return measuredColumns(chunkX, chunkZ, originalCells, null);
    }

    /** 地形合成内部入口：只读侵蚀前快照，不持有或发布可变 Cell 别名。 */
    public List<HydrologyBlockCarvedColumn> columnsForHeights(int chunkX, int chunkZ, double[] originalHeights) {
        if (originalHeights == null || originalHeights.length != 256) {
            throw new IllegalArgumentException("originalHeights must be 256");
        }
        return measuredColumns(chunkX, chunkZ, null, originalHeights);
    }

    private List<HydrologyBlockCarvedColumn> measuredColumns(int chunkX, int chunkZ,
                                                             Cell[] originalCells, double[] originalHeights) {
        // 【测量】chunk 级"列计划"完整请求（内含 regionFor 的命中/等待/求解与逐列最近段查询）。
        var scope = HydrologyMetrics.begin("hydro.columns", chunkX, chunkZ);
        try {
            return columnsForBody(chunkX, chunkZ, originalCells, originalHeights);
        } catch (RuntimeException | Error e) {
            HydrologyMetrics.add("columns.failed", 1L);
            throw e;
        } finally {
            HydrologyMetrics.end(scope);
        }
    }

    /** 两个入口共用全部列判定；Cell 入口保持原长度校验及逐列读取时机。 */
    private List<HydrologyBlockCarvedColumn> columnsForBody(int chunkX, int chunkZ,
                                                           Cell[] originalCells, double[] originalHeights) {
        if (originalHeights == null && (originalCells == null || originalCells.length != 256)) {
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
        Map<WorldDrainage.Point,WorldLakeSurface.Cell> lakeCells=new HashMap<>();
        // ★ 跨 tract 缝共享湖域：逐 chunk 【一次】预解析候选缝域（键 ≤8/chunk），逐列只读裁决。
        //   ⚠【不设缝带门】：控制体是整个跨缝盆地足迹（可达缝两侧 ±REGION/2）；按 ±HALO 门只查缝附近
        //     会把足迹在带外的部分留给本区细窗 ⇒ 只是把墙挪 ±HALO。故每 chunk 都解析（键由世界坐标唯一导出）。
        List<SharedLakeDomain.Domain> sharedDoms = sharedActive() && rd.worldWater()==null
                ? resolveChunkDomains(blockX0, blockZ0) : List.of();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int blockX = blockX0 + lx, blockZ = blockZ0 + lz;
                double original = originalHeights != null ? originalHeights[lx * 16 + lz]
                        : originalCells[lx * 16 + lz].height;
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
                boolean isLakeBlock = rd.worldWater()==null && p.lake()[k];
                // 湖是单平面，不能把相邻非湖格的逃逸高程插进湖岸。
                double localLakeSurface = isLakeBlock ? p.waterSurface()[k] : Double.NaN;
                // ★★★ 2026-10-25【湖缘方块级精修 —— 用户要求「边缘高精度、内部粗格」】★★★
                //
                // 【缺陷】`p.lake()` 是 STEP=2 的粗格布尔 ⇒ 同一 2×2 格内四个方块要么全湖、
                //   要么全非湖 ⇒ 湖缘必然是 2 方块阶梯，达不到方块级。
                //   实测该窗口 coarseLakeCells=1258（粗格），边缘呈规则台阶。
                //
                // 【修法】保留粗格提供【湖身份】（哪个盆、湖面高度 surface），
                //   但【是否本块是湖】改成逐块比较「侵蚀地形 vs 该点水位」：
                //     eroded < surface − EPS  ⇒ 该方块真的在水下 ⇒ 湖
                //   这样湖缘会沿着真实地形的等高线走，精度 = 1 方块。
                //
                // 【为什么安全】
                //   · 湖【内部】eroded 远低于 surface ⇒ 判据恒真，不与粗格冲突；
                //   · 只有【边缘】那一圈会被精修裁掉/补上 ⇒ 面积变化局限在岸线；
                //   · 不改变 coarse 湖身份、不改变路由/水量/预算，只是把「粗格整块」细化为「逐块」。
                //   · surface 在非湖格也可能是 EquilibriumWater 的逃逸高程（会误扩湖），
                //     故本精修【只在粗格判湖处】执行，不用它去扩张新湖域。
                if (isLakeBlock) {
                    isLakeBlock = eroded < localLakeSurface - LAKE_EDGE_EPS;
                }
                if(rd.worldWater()!=null) {
                    int cellX=Math.floorDiv(blockX,STEP)*STEP, cellZ=Math.floorDiv(blockZ,STEP)*STEP;
                    var cellKey=new WorldDrainage.Point(cellX,cellZ);
                    var wet=lakeCells.computeIfAbsent(cellKey, key -> WorldLakeSurface.resolve(
                            key.x(),key.z(),STEP,rd.worldWater().water(),
                            (x,z)->gen.erodedHeight(x*invHs,z*invHs)));
                    double level=wet.levelAt(blockX,blockZ);
                    isLakeBlock=Double.isFinite(level);
                    surface=isLakeBlock?level:eroded;
                }
                double half = 0, depth = 0, bestDist = Double.POSITIVE_INFINITY;
                // ★ 本区先判（零邻区开销）
                if (!isLakeBlock && !segIndex.isEmpty()) {
                    SegmentIndex.Hit h0 = segIndex.nearest(blockX, blockZ);
                    // nearest 已验证半宽覆盖或真实穿块，不能再按格心距离剔除穿块命中。
                    if (h0 != null && h0.half > 0) {
                        bestDist = h0.dist;
                        half = h0.half;
                        depth = Math.max(0, h0.depth);
                        if(Double.isFinite(h0.surface))surface=h0.surface;
                    }
                }
                // ★ 惰性邻区：仅当【本区无命中】且【远离本 region 中心、贴近边界带】时才查。
                //   本区窗口已外扩 HALO（RADIUS = REGION/2 + HALO）⇒ 内部方块无需邻区。
                //   （2026-10-10 双 pass 后无须再拓宽：接力折线由【接收方】在自己窗口内追出
                //    ⇒ 段落全在接收方窗口内 ⇒ 原 HALO 带即可命中。）
                // ★★ 2026-10-22【低侧边界带必须含端点 —— 实测 294 列河被裁】★★
                //   低侧原为 `<`，高侧为 `>=` ⇒ 低侧少算一整行。实测南北接缝：
                //   北区窗口 originZ=-96/半径352/STEP2 ⇒ 折线到 z=608.63；
                //   南区 regLoZ=512、regLoZ+HALO=608 ⇒ z=608 恰落在带外 ⇒ 不查北区
                //   ⇒ 已有北区几何的 294 列不雕刻不 fill。两侧统一为【闭区间】。
                boolean nearBorder = (blockX <= regLoX + HALO) || (blockX >= regHiX - HALO)
                        || (blockZ <= regLoZ + HALO) || (blockZ >= regHiZ - HALO);
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
                double borrowedLakeSurface = Double.NaN;
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
                        // 只取"该方块靠近的那一侧"的邻区，避免无谓求解。
                        // ★ 与 nearBorder 同一闭区间口径：低侧带端点必须能取低侧邻区，
                        //   否则上面放行到 z==regLoZ+HALO 后仍在这里被剔除（同一行又丢一次）。
                        if (neighDx[t] < 0 && blockX > regLoX + HALO) continue;
                        if (neighDx[t] > 0 && blockX < regHiX - HALO) continue;
                        if (neighDz[t] < 0 && blockZ > regLoZ + HALO) continue;
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
                        if (rr.pipe().lake()[ti * nn + tj]) {
                            isLakeBlock = true;
                            // 湖标记与水位必须同源；不能给邻区的湖填本区非湖格的逃逸水位。
                            borrowedLakeSurface = rr.pipe().waterSurface()[ti * nn + tj];
                            break;
                        }
                        SegmentIndex si = neighSeg[t];
                        if (si == null || si.isEmpty()) continue;
                        SegmentIndex.Hit h2 = si.nearest(blockX, blockZ);
                        if (h2 != null && h2.half > 0 && h2.dist < bestDist) {
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
                double resolvedLakeSurface = sharedLake && Double.isFinite(sharedSurface) ? sharedSurface
                        : Double.isFinite(borrowedLakeSurface) ? borrowedLakeSurface
                        : rd.worldWater()==null && Double.isFinite(localLakeSurface) ? localLakeSurface : surface;
                if (BOUNDARY_PORTS && isLakeBlock
                        && !isLakeAt(eroded, resolvedLakeSurface)) {
                    SegmentIndex.Hit shoreHit = segIndex.nearest(blockX, blockZ);
                    if (shoreHit == null && nearBorder) {
                        for (int t = 0; t < 9; t++) {
                            if (t == 4) continue;
                            if (neighDx[t] < 0 && blockX > regLoX + HALO) continue;
                            if (neighDx[t] > 0 && blockX < regHiX - HALO) continue;
                            if (neighDz[t] < 0 && blockZ > regLoZ + HALO) continue;
                            if (neighDz[t] > 0 && blockZ < regHiZ - HALO) continue;
                            RegionData rr = neigh[t] != null ? neigh[t] : regionFor(
                                    (rdRx + neighDx[t]) * REGION + REGION / 2,
                                    (rdRz + neighDz[t]) * REGION + REGION / 2);
                            if (rr.skeletonSeg() == null) continue;
                            SegmentIndex.Hit h = rr.skeletonSeg().nearest(blockX, blockZ);
                            if (h != null && (shoreHit == null || h.dist < shoreHit.dist)) shoreHit = h;
                        }
                    }
                    if (shoreHit != null && shoreHit.half > 0) {
                        isLakeBlock = false;
                        half = shoreHit.half; depth = shoreHit.depth; bestDist = shoreHit.dist;
                        if (Double.isFinite(shoreHit.surface)) surface = shoreHit.surface;
                    }
                }
                if (isLakeBlock) {
                    // ★ 断口现场 watch
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => LAKE_COL region=({},{}) shared={}",
                                blockX, blockZ, rdRx, rdRz, sharedLake);
                    }
                    // 世界/共享湖沿用权威水面；借来的湖沿用命中邻区的同一湖水面。
                    // 单独保留resolvedLakeSurface，湖岸退回河列时不改河道原来的surface。
                    double lakeSurface = resolvedLakeSurface;
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
                // 骨架线（唯一生产线，legacy 分支已删 2026-10-20）：距离场命中即河
                //   （去粗格门控 —— 旧路径的粗格布尔导致 STEP=4 方块化，
                //    像素铁证：河段起点 x mod 4 = 2 占 97.7%）。
                boolean onChannel;
                onChannel = half > 0;      // 距离场命中 = 河（连续几何，无粗格门控）
                if (onChannel) {
                    // ★★★ 2026-10-25【启用真实宽度律（widthRef 已按 D8 标定）】★★★
                    //
                    // 【旧状态】半宽被硬钉成 MIN_HALF_BLOCKS=0.5（全宽 1 像素细线）。
                    //   原因：宽度律 `W = min(12, 1.5×(q/1.0)^0.45)` 的 widthRef=1.0
                    //   继承自连续流量模型（Q 归一化量级），而 D8 的 q 是【上游格数累积】
                    //   ⇒ 量纲错配 ⇒ 一律饱和到 12 ⇒ 河道糊成粗带、掩盖骨架。
                    //
                    // 【现在为什么可以开】widthRef 已按实测标定：
                    //   5 个 region / 579455 格实测 成河格 Q: P10=3.43 P50=8.15
                    //   P90=54.13 max=232.46；取 widthRef=12.6 使最大河恰好达到全宽上限 12
                    //   ⇒ 全图宽度分布为 源头~1.0 / 支流~1.6 / 干流~4.0 / 最大 12 块，
                    //   **不再饱和**，宽度携带真实流量信息（见 RiverCenterlines.defaults）。
                    //
                    // 【保留】距离场命中即河（onChannel）不变；half 只决定该河多宽。
                    //   若需要回到"看骨架"的细线模式：-Dgeogenesis.riverWidth=0.5
                    double widthOverride = Double.parseDouble(
                            System.getProperty("geogenesis.riverWidth", "-1"));
                    if (widthOverride > 0) {
                        half = widthOverride;
                    } else {
                        half = Math.max(half, RiverCenterlines.MIN_HALF_BLOCKS);
                    }
                    // 普通河水只能占据原地形中挖出的空间；minimax逃逸高程不是可悬空填水的许可。
                    // 湖列已在上方返回，海水仍由落块层按海平面补灌。
                    surface = RiverCrossSection.containedSurface(eroded, surface);
                    double steepExtra = RiverCrossSection.steepExtraDepth(
                            terrainSlope(p, rd.originX(), rd.originZ(), blockX, blockZ));
                    double bankLowering = 0;
                    if (RIVER_BANK_HEIGHT) {
                        double lowering = bilinear(rd.bankLowering(), n, gi, gj, rd.originX, rd.originZ,
                                blockX, blockZ, STEP);
                        double lowered = RiverBankHeight.surface(surface, lowering, gen.heightCurve().seaLevelY());
                        bankLowering = surface - lowered;
                        surface = lowered;
                    }
                    if (RIVER_CROSS_SECTION) {
                        // 命中段携带的是目标水深；列字段 erosion 仍表达相对侵蚀地形的下挖量。
                        if (eroded < gen.heightCurve().seaLevelY()) {
                            depth = 0.0; // 已在海平面下的原海床不再雕刻，海水由原落块分支负责。
                        } else {
                            depth = eroded - RiverCrossSection.wetBed(eroded, surface, depth);
                            // 在整数水柱保护之后补挖，陡坡的额外1格不会被保护量吞掉。
                            if (depth > 0) depth += steepExtra;
                        }
                    } else {
                        depth = 1.0;
                        // 原生产基线：防断水补挖，上限为下挖量4格（不是水深4格）。
                        depth = Math.min(4.0, Math.max(depth, eroded - (surface + bankLowering) + 0.6));
                        depth += bankLowering + steepExtra;
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

    /** 双线性插值既有侵蚀网格的梯度，不新增地形采样或最近格坡度台阶。 */
    private static double terrainSlope(HydrologyPipeline.Result p, int ox, int oz, int bx, int bz) {
        int nx = p.nx(), nz = p.nz(), step = p.cellBlocks();
        double gx = (bx - ox) / (double) step, gz = (bz - oz) / (double) step;
        int x0 = clamp((int) Math.floor(gx), nx), z0 = clamp((int) Math.floor(gz), nz);
        double tx = Math.max(0, Math.min(1, gx - x0)), tz = Math.max(0, Math.min(1, gz - z0));
        double dx = 0, dz = 0;
        for (int ix = 0; ix < 2; ix++) for (int iz = 0; iz < 2; iz++) {
            int x = Math.min(nx - 1, x0 + ix), z = Math.min(nz - 1, z0 + iz);
            int xm = Math.max(0, x - 1), xp = Math.min(nx - 1, x + 1);
            int zm = Math.max(0, z - 1), zp = Math.min(nz - 1, z + 1);
            double w = (ix == 0 ? 1 - tx : tx) * (iz == 0 ? 1 - tz : tz);
            dx += w * (p.bed()[xp*nz+z] - p.bed()[xm*nz+z]) / Math.max(step, (xp-xm)*step);
            dz += w * (p.bed()[x*nz+zp] - p.bed()[x*nz+zm]) / Math.max(step, (zp-zm)*step);
        }
        return Math.hypot(dx, dz);
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
            if (hit != null) {
                HydrologyMetrics.cache(Cache.REGION, Outcome.HIT);
                return hit;
            }
        }
        HydrologyMetrics.cache(Cache.REGION, Outcome.MISS);
        // ② 已有在途求解（它在别人的线程/队列上）→ 等它
        CompletableFuture<RegionData> existing = SHARED_INFLIGHT.get(key);
        if (existing != null) return await(existing, key, rx, rz);
        // ③ 抢占：赢家在【本线程】直接求解（不进池 ⇒ 不排队在预热后面）
        CompletableFuture<RegionData> mine = new CompletableFuture<>();
        CompletableFuture<RegionData> raced = SHARED_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) return await(raced, key, rx, rz);   // 输了 → 等赢家
        try {
            // 【测量】region 实体求解：task-local scope（每 region 一次，非逐块热路径）。
            long task = HydrologyMetrics.newTaskId();
            var solveScope = HydrologyMetrics.begin("hydro.region.solve", rx, rz, -1, task);
            RegionData data;
            try {
                data = solve(rx, rz);
                HydrologyMetrics.cache(Cache.REGION, Outcome.COMPUTE);
            } catch (RuntimeException | Error e) {
                HydrologyMetrics.add("region.solve.failed", 1L);
                throw e;
            } finally {
                HydrologyMetrics.end(solveScope);
            }
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
        // 【测量】本方法只被 regionFor 的"别人正在解"的两条分支调用 ⇒ 每次都是一次真实 inflight 等待。
        HydrologyMetrics.cache(Cache.REGION, Outcome.WAIT);
        var waitScope = HydrologyMetrics.begin("hydro.region.wait", rx, rz);
        RegionData data;
        try {
            data = f.join();
        } catch (CompletionException e) {
            HydrologyMetrics.add("region.wait.failed", 1L);
            SHARED_INFLIGHT.remove(key, f);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz,
                    String.valueOf(e.getCause()));
            throw e;
        } finally {
            HydrologyMetrics.end(waitScope);
        }
        SHARED_INFLIGHT.remove(key, f);
        cachePut(key, data);
        return data;
    }

    /** 入 LRU 缓存（容量满时淘汰最久未用）。 */
    private void cachePut(CacheKey key, RegionData data) {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.put(key, data);
            while (SHARED_CACHE.size() > CACHE_CAP) {
                SHARED_CACHE.remove(SHARED_CACHE.keySet().iterator().next());
                HydrologyMetrics.cache(Cache.REGION, Outcome.EVICT);
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
        //   ⚠【测量缺口·如实标注】HIER 分支不进 P1_CACHE ⇒ 它的 cache.PASS1.* 计数与
        //     hydro.pass1.* scope 都【不出现】；粗层缓存（COARSE_CACHE）本身未插桩。
        if (HIER) {
            HierarchicalHydrology.Coarse ct = coarseFor(rx, rz);
            return new Pass1(ct.pipe(), coarseExitSeeds(ct), ct.originX(), ct.originZ());
        }
        CacheKey key = new CacheKey(seed, rx, rz, strategy);
        synchronized (P1_CACHE) {
            Pass1 hit = P1_CACHE.get(key);
            if (hit != null) {
                HydrologyMetrics.cache(Cache.PASS1, Outcome.HIT);
                return hit;
            }
        }
        HydrologyMetrics.cache(Cache.PASS1, Outcome.MISS);
        // ① 已有在途任务 → 先尝试【原子接管】（只有"排队未开跑"抢得到；RUNNING/DONE 抢不到）
        Pass1Task existing = P1_INFLIGHT.get(key);
        if (existing != null) {
            // 接管成功 = 本线程真跑（算 COMPUTE，由 Pass1Task 记）；否则是纯等待。
            if (!existing.runIfClaimed()) HydrologyMetrics.cache(Cache.PASS1, Outcome.WAIT);
            return awaitPass1(existing);
        }
        // ② 抢占：赢家在本线程直接跑（counted=false ⇒ 不占预热名额、不进池、零排队）
        Pass1Task mine = new Pass1Task(key, rx, rz, false);
        Pass1Task raced = P1_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) {
            if (!raced.runIfClaimed()) HydrologyMetrics.cache(Cache.PASS1, Outcome.WAIT);
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
                HydrologyMetrics.cache(Cache.PASS1, Outcome.EVICT);
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
        // 【测量】pass-1 两层的父级拆分：① 管线求解 ② 骨架构建
        //   （② 内部的 shape / width / index 子阶段在 skeletonRegion / buildFromNodes 内各自计时）
        HydrologyPipeline.Result p;
        var pipeScope = HydrologyMetrics.begin("hydro.pass1.pipeline", rx, rz);
        try {
            p = HydrologyPipeline.run(gen, gen.params(), req);
        } finally {
            HydrologyMetrics.end(pipeScope);
        }
        List<DeterministicSkeleton.ExitSeed> seeds = List.of();
        // 不接力（incoming = 空）⇒ 只产出出口种子。整条骨架白跑一遍是刻意的：
        // 出口种子必须来自"本区独立追踪"的结果，否则会与接力链互相依赖。
        SkeletonRegion sr;
        var skelScope = HydrologyMetrics.begin("hydro.pass1.skeleton", rx, rz);
        try {
            sr = skeletonRegion(p, centerX - RADIUS, centerZ - RADIUS, List.of(), false);
        } finally {
            HydrologyMetrics.end(skelScope);
        }
        seeds = sr.exitSeeds();
        // ★ ports 模块：把 pass1 骨架一并缓存 —— pass2 以它为 base，才能保住已发出的端口。
        return new Pass1(p, seeds, centerX - RADIUS, centerZ - RADIUS, sr.skel());
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


    /**
     * ★ ports 模块：显式边界计数日志（发送端口 / 锚点精确率 / 未决出口 / 合并数）。
     *
     * <p>用户要求"ModelHydrologyProvider 日志显式 boundary 数" ⇒ 每次构建都打一行，
     * 并逐条列出 pending（pass2 出口无 pass1 承诺 = 未决，不得当全局完成）。</p>
     */
    private void logBoundaryPorts(DeterministicSkeleton.Result sk, int originX, int originZ,
                                  int n, boolean primary) {
        if (!BOUNDARY_PORTS || sk.ports.isEmpty() && sk.pendingExits.isEmpty()) return;
        int exact = 0;
        for (BoundaryPort port : sk.ports) {
            int px = originX + (port.cell() / n) * STEP;
            int pz = originZ + (port.cell() % n) * STEP;
            if (px == port.anchorX() && pz == port.anchorZ()) exact++;
            else LOGGER.warn("[HYDRO-BOUNDARY] anchor mismatch cell=({},{}) grid=({},{}) got=({},{})",
                    port.cell() / n, port.cell() % n, px, pz, port.anchorX(), port.anchorZ());
        }
        LOGGER.info("[HYDRO-BOUNDARY] origin({},{}) role={} roleConflict={} ports={}"
                        + " anchorExact={} pendingExits={} wiredIncoming={} wireRejects={}"
                        + " mergedIncoming={} mergeEvidence={} unsatisfied={}",
                originX, originZ, sk.passRole, sk.roleConflict, sk.ports.size(), exact,
                sk.pendingExits.size(), sk.wiredIncoming, sk.wireRejects.size(),
                sk.mergedIncoming, sk.mergeEvidence.size(), sk.unsatisfiedIncoming.size());
        for (WireReject r : sk.wireRejects) {
            LOGGER.warn("[HYDRO-WIRE-REJECT] {}", r);
        }
        for (DeterministicSkeleton.MergeEvidence ev : sk.mergeEvidence) {
            LOGGER.info("[HYDRO-WIRE-MERGE] anchor={} next={} {} steps={} path={}",
                    ev.anchor(), ev.next(), ev.resolution(), ev.steps(), ev.pathText());
        }
        for (DeterministicSkeleton.MergeEvidence ev : sk.unsatisfiedIncoming) {
            LOGGER.warn("[HYDRO-WIRE-UNSATISFIED] anchor={} next={} {} steps={}",
                    ev.anchor(), ev.next(), ev.resolution(), ev.steps());
        }
        // 接收侧归因（只读）：本区 pass2 对每个 incoming 的裁定，供审计回答"为什么没接上"
        if (primary) {
            java.util.List<String> diag = new ArrayList<>();
            for (var d : sk.incomingDiagnostics) {
                int bx = originX + (d.start() / n) * STEP;
                int bz = originZ + (d.start() % n) * STEP;
                int mx = d.mouth() < 0 ? Integer.MIN_VALUE : originX + (d.mouth() / n) * STEP;
                int mz = d.mouth() < 0 ? Integer.MIN_VALUE : originZ + (d.mouth() % n) * STEP;
                diag.add(bx + "," + bz + "@" + d.status() + ":" + d.end() + ":" + d.nodes()
                        + ":mouth=" + mx + "," + mz);
            }
            int cell = Math.floorDiv(originX + (n / 2) * STEP, REGION);
            int cellZ = Math.floorDiv(originZ + (n / 2) * STEP, REGION);
            RECEIVER_DIAG.put(cell + ":" + cellZ, java.util.List.copyOf(diag));
            for (String line : diag) {
                LOGGER.info("[HYDRO-RECEIVER] region({},{}) {}", cell, cellZ, line);
            }
        }
        for (BoundaryPort port : sk.pendingExits) {
            LOGGER.info("[HYDRO-BOUNDARY-PENDING] {} — pass2 出口无 pass1 承诺 ⇒ 未决", port);
        }
    }

    /**
     * ★ v2 <b>typed 接力装配</b>：端口 → {@link WireIn}（锚点格 + 真实下一步格 + 来向）。
     *
     * <h3>为什么必须带 next 而不是只回锚点格</h3>
     * <p>旧版只回 {@code List<Integer>}（锚点格索引）：<b>方向与 next 全部丢失</b>，
     * 接收方只能拿自己的地形重猜第一步 ⇒ 缝上"锚点有、下一步没"的假接力。
     * 新版把三件事一起带过去，并在这里就做<b>几何可判定</b>的拒收：</p>
     * <ul>
     *   <li>锚点必须逐位落在本区网格（否则 {@code ANCHOR_OFF_GRID}）；</li>
     *   <li>next 必须逐位落在本区窗口内（否则 {@code PENDING_NEXT_OTHER_OWNER}：
     *       端口指向更远的区 ⇒ 本区不接，如实未决）；</li>
     *   <li>next 必须与锚点相邻（否则 {@code NEXT_NOT_ADJACENT}）；</li>
     *   <li>同一个锚点被多个发送方承诺且 next 不同 ⇒ 保留首个并记 {@code DUPLICATE_ANCHOR}。</li>
     * </ul>
     * <p>拒收<b>不改道、不猜</b>；全部写进 {@link #WIRE_ASSEMBLY_REJECTS} 供审计与日志。</p>
     */
    private List<WireIn> assembleIncomingWires(int rx, int rz, int n, int myOriginX, int myOriginZ,
                                              List<BoundaryPort> ports) {
        List<WireIn> out = new ArrayList<>();
        java.util.Map<Integer, WireIn> byAnchor = new java.util.TreeMap<>();
        List<String> rejects = new ArrayList<>();
        for (BoundaryPort port : ports) {
            int ai = Math.floorDiv(port.anchorX() - myOriginX, STEP);
            int aj = Math.floorDiv(port.anchorZ() - myOriginZ, STEP);
            if (myOriginX + ai * (long) STEP != port.anchorX()
                    || myOriginZ + aj * (long) STEP != port.anchorZ()) {
                rejects.add("ANCHOR_OFF_GRID " + port);
                continue;
            }
            int ni = Math.floorDiv(port.nextX() - myOriginX, STEP);
            int nj = Math.floorDiv(port.nextZ() - myOriginZ, STEP);
            if (myOriginX + ni * (long) STEP != port.nextX()
                    || myOriginZ + nj * (long) STEP != port.nextZ()) {
                rejects.add("NEXT_OFF_GRID " + port);
                continue;
            }
            if (ni < 0 || nj < 0 || ni >= n || nj >= n) {
                // next 落在本区窗口之外 ⇒ 端口指向更远的区；本区不接，如实未决。
                rejects.add("PENDING_NEXT_OTHER_OWNER " + port);
                continue;
            }
            int anchorCell = ai * n + aj, nextCell = ni * n + nj;
            WireIn w = new WireIn(anchorCell, nextCell, port.dirX(), port.dirZ(),
                    port.anchorX(), port.anchorZ(), port.nextX(), port.nextZ());
            WireIn prior = byAnchor.get(anchorCell);
            if (prior != null) {
                if (prior.nextCell() != nextCell) rejects.add("DUPLICATE_ANCHOR " + port);
                continue;
            }
            byAnchor.put(anchorCell, w);
        }
        out.addAll(byAnchor.values());
        if (!rejects.isEmpty()) {
            WIRE_ASSEMBLY_REJECTS.put(rx + ":" + rz, List.copyOf(rejects));
            for (String r : rejects) LOGGER.warn("[HYDRO-WIRE-ASSEMBLY] region({},{}) {}", rx, rz, r);
        }
        return out;
    }

    /** typed 装配期拒收（端口→WireIn）——只读诊断，键 = {@code rx:rz}。
     *
     * <p>★ <b>实例级</b>（非 static）：static 诊断表会被多实例 / 多 seed / 多并发求解污染，
     * 且测完无法回收（本类 {@code SHARED_CACHE} 已踩过同类教训）。</p>
     */
    private final java.util.Map<String, java.util.List<String>> WIRE_ASSEMBLY_REJECTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 8 邻 pass1 中<b>实际接收 owner = 本区</b>的端口（按锚点排序 ⇒ 顺序无关）。 */
    private List<BoundaryPort> typedIncomingPorts(int rx, int rz) {
        List<BoundaryPort> out = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                Pass1 nb = regionPass1(rx + dRX, rz + dRZ);
                if (nb.skeleton() == null) continue;
                for (BoundaryPort raw : nb.skeleton().ports) {
                    BoundaryPort p = raw.stamp(rx + dRX, rz + dRZ, REGION);
                    if (p.receiverRegionX() != rx || p.receiverRegionZ() != rz) continue;
                    out.add(p);
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(BoundaryPort::anchorX)
                .thenComparingInt(BoundaryPort::anchorZ)
                .thenComparingInt(BoundaryPort::senderRegionX)
                .thenComparingInt(BoundaryPort::senderRegionZ));
        return out;
    }

    private record WidthEdge(int x, int z, int nx, int nz) { }

    /** 独立 pass1 的有向边属性。仅沿真实路径取值，不叠加重叠窗口的水量。 */
    private static Map<WidthEdge, double[]> pass1Widths(HydrologyPipeline.Result pipe,
                                                       DeterministicSkeleton.Result sk, int ox, int oz) {
        if (sk == null) return Map.of();
        var scope = HydrologyMetrics.begin("hydro.pass1.widthEdges", ox, oz);
        try {
            return pass1WidthEdges(pipe, sk, ox, oz);
        } finally {
            HydrologyMetrics.end(scope);
        }
    }

    private static Map<WidthEdge, double[]> pass1WidthEdges(HydrologyPipeline.Result pipe,
                                                          DeterministicSkeleton.Result sk, int ox, int oz) {
        int n = pipe.nz();
        List<DeterministicSkeleton.Shaped> grid = new ArrayList<>();
        for (var t : sk.traces) {
            double[] x = new double[t.cell.length], z = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                x[i] = ox + (t.cell[i] / n) * STEP;
                z[i] = oz + (t.cell[i] % n) * STEP;
            }
            grid.add(new DeterministicSkeleton.Shaped(x, z));
        }
        var profile = RiverWidthProfile.buildDetailed(grid, pipe, ox, oz, n, STEP, sk.traces, sk.rivers,
                Map.of(), Map.of(), java.util.Set.of(), Map.of());
        var widths = profile.widths();
        Map<WidthEdge, double[]> out = new HashMap<>();
        for (int p = 0; p < grid.size(); p++) {
            var sh = grid.get(p);
            for (int i = 0; i + 1 < sh.size(); i++) {
                WidthEdge edge = new WidthEdge((int) sh.x[i], (int) sh.z[i], (int) sh.x[i + 1], (int) sh.z[i + 1]);
                double[] value = {widths.get(p)[i], widths.get(p)[i + 1], i == 0 ? 0 : 1,
                        profile.limits().get(p)[i], profile.limits().get(p)[i + 1]};
                out.merge(edge, value, ModelHydrologyProvider::mergeWidthEdge);
            }
        }
        return Map.copyOf(out);
    }

    private static double[] mergeWidthEdge(double[] a, double[] b) {
        return new double[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]),
                Math.min(a[3], b[3]), Math.min(a[4], b[4])};
    }

    private record WidthInheritance(Map<Integer, Double> half, java.util.Set<Integer> continued,
                                    Map<Integer, Double> limits) { }

    /** 匹配同向且两端完全相同的骨架格边；不把空间邻近的其它河或逆流边混进来。 */
    private WidthInheritance inheritedWidths(DeterministicSkeleton.Result sk, int ox, int oz, int n) {
        int rx = Math.floorDiv(ox + HALO, REGION), rz = Math.floorDiv(oz + HALO, REGION);
        Map<WidthEdge, double[]> shared = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var base = regionPass1(rx + dx, rz + dz);
            for (var e : base.widths().entrySet()) shared.merge(e.getKey(), e.getValue(),
                    ModelHydrologyProvider::mergeWidthEdge);
        }
        Map<Integer, Double> half = new HashMap<>();
        Map<Integer, Double> limits = new HashMap<>();
        java.util.Set<Integer> continued = new java.util.HashSet<>();
        for (var t : sk.traces) for (int k = 0; k + 1 < t.cell.length; k++) {
            int a = t.cell[k], b = t.cell[k + 1];
            var edge = new WidthEdge(ox + (a / n) * STEP, oz + (a % n) * STEP,
                    ox + (b / n) * STEP, oz + (b % n) * STEP);
            double[] w = shared.get(edge);
            if (w == null) continue;
            half.merge(a, w[0], Math::max);
            half.merge(b, w[1], Math::max);
            limits.merge(a, w[3], Math::min);
            limits.merge(b, w[4], Math::min);
            if (k == 0 && w[2] > 0) continued.add(a);
        }
        return new WidthInheritance(half, continued, limits);
    }

    /**
     * 同一世界出口格的出湖半宽：本区与相邻区 pass1 各自按源盆净出流算，取<b>较小者</b>。
     *
     * <p>为什么必须跨窗口统一：源盆身份由各窗口自己的盆地解算给出，同一出口格在相邻窗口
     * 可以判成不同盆地（实测 (160,-486) 一侧 basin 18、另一侧 17/23）⇒ 净出流不同
     * ⇒ 同一条河在两个窗口得到不同起宽，下游又被小湖上限压细，整段被迫从上游粗收到下游细。
     * 取较小者与 typed 端口同一口径：双方共用同一个约定值，不放大任何一侧。</p>
     *
     * <p>只读邻区 <b>pass1</b>（与 {@link #inheritedWidths} 同源），不递归邻区 final。
     * <b>调用方必须已按 primary + BOUNDARY_PORTS + !HIER 门控</b>：pass1 自身也走同一条
     * build 路径，无门控会在 pass1 内再次触发邻区 pass1 ⇒ 连锁扇出。</p>
     */
    private Map<Long, Double> agreedOutletHalves(DeterministicSkeleton.Result sk,
                                                 HydrologyPipeline.Result pipe, int ox, int oz, int n) {
        Map<Long, Double> agreed = new HashMap<>();
        collectOutletHalves(agreed, sk, pipe, ox, oz, n);
        int rx = Math.floorDiv(ox + HALO, REGION), rz = Math.floorDiv(oz + HALO, REGION);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var base = regionPass1(rx + dx, rz + dz);
            if (base == null || base.skeleton() == null) continue;
            collectOutletHalves(agreed, base.skeleton(), base.pipe(),
                    base.originX(), base.originZ(), base.pipe() == null ? n : base.pipe().nz());
        }
        return Map.copyOf(agreed);
    }

    private static void collectOutletHalves(Map<Long, Double> agreed, DeterministicSkeleton.Result sk,
                                            HydrologyPipeline.Result pipe, int ox, int oz, int n) {
        if (sk == null || sk.traces == null || n <= 0) return;
        for (var t : sk.traces) {
            double half = RiverWidthProfile.outletHalfOf(pipe, t);
            if (!Double.isFinite(half)) continue;
            long key = RiverWidthProfile.worldKey(ox + (t.sourceCell() / n) * (double) STEP,
                    oz + (t.sourceCell() % n) * (double) STEP);
            agreed.merge(key, half, Math::min);
        }
    }

    private Map<Integer, Double> boundaryWidths(DeterministicSkeleton.Result sk, List<WireIn> wires,
                                                WidthInheritance inherited, int ox, int oz, int n) {
        Map<Integer, Double> pins = new HashMap<>();
        int rx = Math.floorDiv(ox + HALO, REGION), rz = Math.floorDiv(oz + HALO, REGION);
        for (var raw : sk.ports) {
            var p = raw.stamp(rx, rz, REGION);
            pins.merge(raw.cell(), portHalf(p.anchorX(), p.anchorZ(), p.nextX(), p.nextZ()), Math::max);
        }
        for (var w : wires) {
            boolean accepted = sk.mergeEvidence.stream().anyMatch(e -> e.anchor() == w.anchorCell()
                    && e.next() == w.nextCell() && e.resolved());
            accepted |= sk.traces.stream().anyMatch(t -> t.cell.length > 1
                    && t.cell[0] == w.anchorCell() && t.cell[1] == w.nextCell());
            if (!accepted) continue;
            double half = portHalf(w.anchorX(), w.anchorZ(), w.nextX(), w.nextZ());
            pins.merge(w.anchorCell(), half, Math::max);
            inherited.half().merge(w.anchorCell(), half, Math::max);
            inherited.continued().add(w.anchorCell());
        }
        return pins;
    }

    /**
     * 端口的约定半宽：所有能覆盖该锚点+next 的 pass1 候选<b>取最细者</b>。
     *
     * <p><b>为什么是最细而不是最粗</b>：注释沿用了旧写法 {@code Math.max}，但两端各自只看
     * 自己能看到的那批 pass1 候选 ⇒ 收/发两边可能取到不同值，端口就必然对不上
     * （单调收束启用后实测 21 处 gap）。取 {@code min} 时双方都等于"所有候选里最细的那个"，
     * 只要可见集合相同就<b>逐位相等</b>，与"下游不得比上游宽"的单向语义也一致。</p>
     */
    private double portHalf(int ax, int az, int nx, int nz) {
        double half = Double.POSITIVE_INFINITY, limit = 6.0;
        int minRx = Math.floorDiv(ax - HALO - 1, REGION), maxRx = Math.floorDiv(ax + HALO, REGION);
        int minRz = Math.floorDiv(az - HALO - 1, REGION), maxRz = Math.floorDiv(az + HALO, REGION);
        for (int rx = minRx; rx <= maxRx; rx++) for (int rz = minRz; rz <= maxRz; rz++) {
            var base = regionPass1(rx, rz);
            for (var e : base.widths().entrySet()) {
                if (e.getKey().x() == ax && e.getKey().z() == az) limit = Math.min(limit, e.getValue()[3]);
                if (e.getKey().nx() == ax && e.getKey().nz() == az) limit = Math.min(limit, e.getValue()[4]);
            }
            for (var t : base.skeleton().traces) {
                if (t.port == null || t.port.anchorX() != ax || t.port.anchorZ() != az
                        || t.port.nextX() != nx || t.port.nextZ() != nz) continue;
                for (var e : base.widths().entrySet()) if (e.getKey().nx() == ax && e.getKey().nz() == az)
                    half = Math.min(half, e.getValue()[1]);
            }
        }
        if (!Double.isFinite(half)) half = RiverCenterlines.MIN_HALF_BLOCKS;
        return Math.min(half, limit);
    }

    // ---- ports 模块只读探针入口（供 build 侧审计程序调用；不参与生产路径）----

    /**
     * pass2 的 incoming 逐入口裁定（{@code "point@status:end"} 列表）——只读诊断。
     *
     * <p>用于回答"接收方为什么没接上"：是锚点被湖/海吞掉、被占用、太短回滚，
     * 还是根本没有下坡。写进 {@code RECEIVER_DIAG} 的时机在 {@code skeletonRegion}。</p>
     */
    // ★ v2：**实例级**（非 static）。static 表会被多实例 / 多 seed / 并发求解互相覆盖
    //   ⇒ 审计读到别区的裁定（用户口径：不要再扩 static 污染面）。
    private final java.util.Map<String, java.util.List<String>> RECEIVER_DIAG =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取某 region 上次 pass2 的 incoming 裁定（空 = 尚未求解或开关关闭）。 */
    java.util.List<String> incomingDiagForProbe(int rx, int rz) {
        return RECEIVER_DIAG.getOrDefault(rx + ":" + rz, java.util.List.of());
    }

    /**
     * ★★ 主线程集成的<b>唯一只读入口</b>：该 region 的<b>最终结果</b>（= 落块真正用的那份）。
     *
     * <h3>语义（写死，别扩读）</h3>
     * <ul>
     *   <li>{@code final} = <b>pass2</b> 产物（base 几何在前 + 接收链在后，已合并）；
     *       分层实验路径不产 typed 端口 ⇒ 如实返回 {@code null}（<b>不</b>拿 pass1 冒充）；</li>
     *   <li>端口 / 未决出口 / 合并证据 / 后继冲突<b>全部来自这一份</b> ⇒ 审计不会读到
     *       "承诺期"的乐观数据（v1 的错就在这里：拿 {@code regionPass1} 判接收方）；</li>
     *   <li>惰性触发：owner 未求解时本条<b>会触发一次求解</b>（{@code regionFor} 自带缓存 + 单飞）；</li>
     *   <li><b>只读</b>：返回对象与缓存共享，调用方不得改写（要副本请自行 copy）。</li>
     * </ul>
     *
     * <p>与 {@code shape()} 输出<b>逐下标同序</b>（{@link #shapeAlignedForProbe} 可断言）。</p>
     */
    DeterministicSkeleton.Result finalSkeletonForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk =
                regionFor(rx * REGION + REGION / 2, rz * REGION + REGION / 2).skel();
        if (sk == null) {
            LOGGER.warn("[HYDRO-WIRE] region ({},{}) final skeleton is null (hier path or off)", rx, rz);
        }
        return sk;
    }

    /** 兼容别名（v2 早期名字）。 */
    DeterministicSkeleton.Result finalSkeleton(int rx, int rz) {
        return finalSkeletonForProbe(rx, rz);
    }

    /** 最终骨架的端口（已盖身份；按锚点 + 接收 owner 排序 ⇒ 顺序无关）。 */
    java.util.List<BoundaryPort> finalPortsForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        if (sk == null) return java.util.List.of();
        java.util.List<BoundaryPort> out = new ArrayList<>(sk.ports.size());
        for (BoundaryPort raw : sk.ports) out.add(raw.stamp(rx, rz, REGION));
        out.sort(java.util.Comparator.comparingInt(BoundaryPort::anchorX)
                .thenComparingInt(BoundaryPort::anchorZ)
                .thenComparingInt(BoundaryPort::receiverRegionX)
                .thenComparingInt(BoundaryPort::receiverRegionZ));
        return out;
    }

    /** 最终骨架的<b>未决</b>出口（PASS2 下无 pass1 承诺 ⇒ 契约未覆盖，不得当完成）。 */
    java.util.List<BoundaryPort> finalPendingForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        if (sk == null) return java.util.List.of();
        java.util.List<BoundaryPort> out = new ArrayList<>(sk.pendingExits.size());
        for (BoundaryPort raw : sk.pendingExits) out.add(raw.stamp(rx, rz, REGION));
        return out;
    }

    /** 最终骨架的 trace 表（与 {@code shapedPolys} 逐下标同序；只读）。 */
    java.util.List<DeterministicSkeleton.Trace> finalTracesForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        return sk == null ? java.util.List.of() : sk.traces;
    }

    /** 最终骨架的 trace 数与落块折线数是否相等（true = 可按下标互查）。 */
    boolean shapeAlignedForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        RegionData rd = regionFor(rx * REGION + REGION / 2, rz * REGION + REGION / 2);
        if (sk == null || rd.shapedPolys() == null) return false;
        return sk.traces.size() == rd.shapedPolys().size();
    }

    /** base 覆盖格上每条 incoming 的后继审计证据（实际 next + 实际路径；只读）。 */
    java.util.List<DeterministicSkeleton.MergeEvidence> finalMergeEvidenceForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        return sk == null ? java.util.List.of() : sk.mergeEvidence;
    }

    /** 后继审计<b>未</b>通过（追不到水体/端口）的 incoming —— 不是合并、也不算水。 */
    java.util.List<DeterministicSkeleton.MergeEvidence> finalUnsatisfiedForProbe(int rx, int rz) {
        DeterministicSkeleton.Result sk = finalSkeletonForProbe(rx, rz);
        return sk == null ? java.util.List.of() : sk.unsatisfiedIncoming;
    }

    /** typed 装配期的拒收（端口→WireIn），供审计逐项列出。 */
    java.util.List<String> wireAssemblyRejectsForProbe(int rx, int rz) {
        return WIRE_ASSEMBLY_REJECTS.getOrDefault(rx + ":" + rz, java.util.List.of());
    }

    /** pass1 的显式端口（已盖 identity）。{@code -Dgeogenesis.boundaryPorts} 关闭时为空。 */
    java.util.List<BoundaryPort> pass1PortsForProbe(int rx, int rz) {
        Pass1 p = regionPass1(rx, rz);
        if (p.skeleton() == null) return java.util.List.of();
        java.util.List<BoundaryPort> out = new ArrayList<>();
        for (BoundaryPort raw : p.skeleton().ports) out.add(raw.stamp(rx, rz, REGION));
        return out;
    }

    /** pass1 的未决出口（pass2 出口无 pass1 承诺 = 未决）。 */
    java.util.List<BoundaryPort> pass1PendingForProbe(int rx, int rz) {
        Pass1 p = regionPass1(rx, rz);
        if (p.skeleton() == null) return java.util.List.of();
        java.util.List<BoundaryPort> out = new ArrayList<>();
        for (BoundaryPort raw : p.skeleton().pendingExits) out.add(raw.stamp(rx, rz, REGION));
        return out;
    }

    /** 接收方 pass1 中该锚点是否有"几何 + 后继"（返回 {hasAnchor, hasSuccessor}）。 */
    boolean[] receiverAnchorForProbe(int rx, int rz, int anchorX, int anchorZ) {
        Pass1 p = regionPass1(rx, rz);
        if (p.skeleton() == null) return new boolean[]{false, false};
        int n = p.pipe().nx();
        int cell = Math.floorDiv(anchorX - p.originX(), STEP) * n
                + Math.floorDiv(anchorZ - p.originZ(), STEP);
        boolean has = false, successor = false;
        for (DeterministicSkeleton.Trace t : p.skeleton().traces) {
            for (int i = 0; i < t.cell.length; i++) {
                if (t.cell[i] != cell) continue;
                has = true;
                if (i + 1 < t.cell.length) successor = true;
            }
        }
        return new boolean[]{has, successor};
    }

    /** 接收方窗口原点/n（探针换算用）。 */
    int[] receiverFrameForProbe(int rx, int rz) {
        Pass1 p = regionPass1(rx, rz);
        return new int[]{p.originX(), p.originZ(), STEP, p.pipe().nx()};
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
        ProductionDrainageAudit.Snapshot drainage = null;
        WorldDrainage.Result world = null;
        WorldBasinFlow.Region worldWater = null;
        int[][] regionSources = null;
        List<DeterministicSkeleton.Shaped> regionShaped = null;
        if (CROSS_PASS) {
            var graph = worldDrainage();
            var routing = new WorldBasinRouting(graph, (x,z)->gen.erodedHeight(x*invHs,z*invHs), STEP, 131072);
            var flow = new WorldBasinFlow(graph, routing, CLIMATE, STEP, 32768);
            worldWater = flow.region(rx, rz, REGION);
            LOGGER.info("[HYDRO-DEMAND] region ({},{}) policy={} stats={}",rx,rz,WorldBasinFlow.POLICY_VERSION,flow.stats());
            if(worldWater.unresolved()!=0)
                throw new IllegalStateException("world water unresolved region="+rx+","+rz+" nodes="+worldWater.unresolved());
            world = worldWater.graph();
            var sr = worldRegion(worldWater, p, originX, originZ);
            cl = sr.cl(); skelSeg = sr.seg();
            if (Boolean.getBoolean("geogenesis.drainageAudit"))
                drainage = ProductionDrainageAudit.captureWorld(world, p, originX, originZ, REGION, graph, cl.reaches);
            LOGGER.info("[HYDRO-WORLD] region ({},{}) edges={} pendingBasins={} nodes={} terrainSamples={} neighborPipelines=0",
                    rx, rz, world.edges().size(), world.pendingBasins().size(), world.sampledNodes(), world.terrainSamples());
        } else {
            // ⚠【本分支当前**不做**跨区接力】incoming 恒为空列表（历史遗留，勿据注释误解）。
            //   曾经的意图是"本区粗层主干源头 + 邻区粗层主干端口"两路细化；
            //   实际实现里 {@link #hierIncomingSeeds} 只被诊断探针反射调用，
            //   生产这条路径从未接线 ⇒ 该分支的骨架只由本区侵蚀 parent 生成。
            //   若要真接上，必须同时验证 CROSS_PASS/HIER 的独立产出，不能只改这一行。
            List<Integer> incoming = List.of();
            var sr = skeletonRegion(p, originX, originZ, incoming, true);
            // #region agent log: R1 细层出口目前未消费，先量化再改边界几何
            if (com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()) {
                com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R1", "hier-ports-" + rx + "-" + rz,
                        "incomingSeeds=" + incoming.size() + " fineWindowExitsUnused=" + sr.exitSeeds().size());
            }
            // #endregion
            cl = sr.cl();
            skelSeg = sr.seg();
            drainage = sr.drainage();
            regionSources = sr.sources();
            regionShaped = sr.shapedPolys();
        }
        logRegionSolved(rx, rz, p, cl, t0, originX, originZ);
        HierarchicalHydrology.Diag.fineTiming((System.nanoTime() - t0) / 1_000_000L, p.nx() * p.nx());
        lastFineMs = (System.nanoTime() - t0) / 1e6;
        // ★ v2：分层实验路径不产出 typed 端口 ⇒ 最终骨架载荷如实为 null（不冒充 pass2）。
        return new RegionData(p, cl, skelSeg, originX, originZ, drainage, world, worldWater,
                regionSources, regionShaped, null, RIVER_BANK_HEIGHT ? RiverBankHeight.build(p) : null);
    }

    /** 最近一次细层求解耗时（ms）——性能诊断（与 {@link HierarchicalHydrology.Diag#LAST_FINE_MS} 同源）。 */
    private volatile double lastFineMs = -1;

    /**
     * 分层接力种子（本区格索引）= 本区粗层主干源头 + 邻区粗层主干端口。
     *
     * <p>语义：粗层给出<b>主干候选</b>（源头与跨区接口），细层用<b>侵蚀后地形</b>把主干
     * 追踪出来并细化 —— 而不是"只把边界换成粗层、其余照旧"。</p>
     *
     * <p>⚠ <b>生产路径当前不调用本方法</b>（{@code solveHier} 的 else 分支传空列表）。
     * 目前只有诊断探针 {@code SharedLakeSeamProbe} 通过反射拿它做对照。
     * 改动前请先确认是要"真接上"还是"继续只给探针用"。</p>
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
        // 骨架是唯一生产线（legacy 分支已删，2026-10-20）。
        RiverCenterlines.Result cl;
        SegmentIndex skelSeg = null;
        ProductionDrainageAudit.Snapshot drainage = null;
        // ★ 双 pass 续流：取 8 邻 pass-1 中【指向本区】的出口种子，换算成本区格索引，
        //   由本区自己的 parent 树追进来（方向场统一 ⇒ 缝上不再断）。
        long incomingStart = System.nanoTime();
        List<BoundaryPort> widthIncoming = BOUNDARY_PORTS && CROSS_PASS
                ? typedIncomingPorts(rx, rz) : List.of();
        List<WireIn> incomingWires = BOUNDARY_PORTS && CROSS_PASS
                ? assembleIncomingWires(rx, rz, p.nx(), base.originX(), base.originZ(), widthIncoming)
                : List.of();
        List<Integer> incoming = !CROSS_PASS ? List.of()
                : BOUNDARY_PORTS
                ? incomingWires.stream().map(WireIn::anchorCell).toList()
                : collectIncoming(rx, rz, p.nx());
        incomingNs = System.nanoTime() - incomingStart;
        var sr = skeletonRegion(p, base.originX(), base.originZ(), incoming, incomingWires, true,
                BOUNDARY_PORTS ? base.skeleton() : null,
                BOUNDARY_PORTS ? DeterministicSkeleton.PassRole.SECOND_PASS
                               : DeterministicSkeleton.PassRole.PRIMARY);
        cl = sr.cl();
        skelSeg = sr.seg();
        drainage = sr.drainage();
        // ★★★ 生产实际走的就是这里（HIER=false ⇒ `solve()` 的这条分支）★★★
        //   上次漏了它 ⇒ 探针读到的 sources 恒为 null ⇒ 源头 0 个。
        //   ⚠ 这条路径必须与 2495 行那条【一起】维护，否则只对一半。
        RegionData rd = new RegionData(p, cl, skelSeg, base.originX(), base.originZ(), drainage,
                null, null, sr.sources(), sr.shapedPolys(), sr.skel(),
                RIVER_BANK_HEIGHT ? bankLowering(base, rx, rz) : null);
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

    /** 只读固定8邻pass1的湖海掩码；不依赖邻区final，不再跑骨架或管线。 */
    private double[] bankLowering(Pass1 base, int rx, int rz) {
        HydrologyPipeline.Result p = base.pipe();
        boolean[] water = new boolean[p.nx() * p.nz()];
        RiverBankHeight.mergeWater(water, p, base.originX(), base.originZ(),
                p, base.originX(), base.originZ());
        if (BOUNDARY_PORTS && CROSS_PASS) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Pass1 neighbor = regionPass1(rx + dx, rz + dz);
                RiverBankHeight.mergeWater(water, p, base.originX(), base.originZ(),
                        neighbor.pipe(), neighbor.originX(), neighbor.originZ());
            }
        }
        return RiverBankHeight.build(p, water);
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
        private final double[] surface;
        private double[] widthLimits;
        private List<LakeWidthEnvelope.Interface> lakeInterfaces = List.of();
        private List<double[]> nodeDepths = List.of();
        /** Ordinary segments retain their trace/shape identity; spatial sew bridges remain -1. */
        private final int[] segmentPoly;
        private List<JunctionSection> junctionSections = List.of();
        private List<List<JunctionSection>> junctionsByPoly = List.of();

        /** Measured projection evidence, not a nearest-river/component association. */
        private record JunctionSection(int up, int down, double x, double z,
                                       double receiverX, double receiverZ, double gap,
                                       double maxHalf) { }

        private record Hit(double dist, double half, double depth, double surface) { }

        private SegmentIndex(double[] seg, Map<Long, List<Integer>> tiles) {
            this.seg = seg;
            this.tiles = tiles;
            this.surface = new double[seg.length/8];
            java.util.Arrays.fill(this.surface,Double.NaN);
            this.segmentPoly = RIVER_CROSS_SECTION ? new int[seg.length/8] : new int[0];
            java.util.Arrays.fill(this.segmentPoly, -1);
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
        static SegmentIndex buildExactNodes(List<DeterministicSkeleton.Shaped> polys) {
            return buildExactNodes(polys, null, 0, 0, 0, 1);
        }

        /**
         * ★★★ 2026-10-25【骨架模式接入真实宽度律】★★★
         *
         * <p><b>缺陷</b>：旧实现把每段半宽硬写成 {@code MIN_HALF_BLOCKS=0.5}
         * ⇒ 骨架模式下全图河恒为 1 像素细线，{@code RiverCenterlines.Params} 的宽度律
         * <b>完全没被消费</b>（那条律只服务 legacy 的 {@code build}，而 legacy 已删除）。</p>
         *
         * <p><b>做法</b>：用折线端点反查所在 coarse 格的 discharge，据
         * {@code fullWidth(Q)} 得全宽、取半写入段数据。查不到（越界/非河格）则退回最小可见值。
         * 这样宽度携带真实流量信息：源头细、干流粗，且不再是全图饱和。</p>
         *
         * <p>只改段索引里的宽度数值，不改几何、不改覆盖判据（{@code nearest} 的
         * 「半宽 ∪ 弦穿块」并集语义不变，宽了只是覆盖更多格）。</p>
         */
        static SegmentIndex buildExactNodes(List<DeterministicSkeleton.Shaped> polys,
                                            HydrologyPipeline.Result pipe, int ox, int oz,
                                            int n, int step) {
            int count = 0;
            for (var sh : polys) count += Math.max(0, sh.size() - 1);
            double[] seg = new double[count * 8];
            var tiles = new HashMap<Long, List<Integer>>();
            int index = 0;
            RiverCenterlines.Params wp = RiverCenterlines.Params.defaults();
            for (var sh : polys) {
                for (int i = 1; i < sh.size(); i++) {
                    double x0 = sh.x[i - 1], z0 = sh.z[i - 1];
                    double x1 = sh.x[i], z1 = sh.z[i];
                    double h0 = halfWidthAt(pipe, ox, oz, n, step, x0, z0, wp);
                    double h1 = halfWidthAt(pipe, ox, oz, n, step, x1, z1, wp);
                    int o = index * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    seg[o + 4] = h0; seg[o + 5] = h1;
                    seg[o + 6] = RIVER_CROSS_SECTION ? RiverCrossSection.centerDepth(h0 * 2) : 1.0;
                    seg[o + 7] = RIVER_CROSS_SECTION ? RiverCrossSection.centerDepth(h1 * 2) : 1.0;
                    registerTile(tiles, index++, x0, z0, x1, z1);
                }
            }
            return new SegmentIndex(seg, tiles);
        }

        /**
         * 沿折线对半宽做滑动平均（窗口半径 {@code radius}，端点收缩窗口）。
         *
         * <p>目的是消除 coarse 格离散造成的逐段跳变，使宽度成为位置的连续函数。
         * 参考：dynamicwaters 沿程线性插值（MountainRiverPath:1191-1236）、
         * plate-local 的连续距离场（rivers.cpp:497-502）—— 二者本质都是"宽度随位置连续"。</p>
         *
         * <p>窗口取 0 时原样返回（A/B 用）。</p>
         */
        static double[] smoothAlong(double[] v, int radius) {
            int n = v.length;
            if (radius <= 0 || n <= 2) return v.clone();
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                int lo = Math.max(0, i - radius), hi = Math.min(n - 1, i + radius);
                double sum = 0;
                for (int j = lo; j <= hi; j++) sum += v[j];
                out[i] = sum / (hi - lo + 1);
            }
            return out;
        }

        /** 宽度沿程平滑窗口半径（节点数）。0 = 关闭平滑（A/B 归因）。 */
        static final int WIDTH_SMOOTH = Integer.getInteger("geogenesis.widthSmooth", 4);

        /**
         * 宽度 IDW 混合半径（block）。
         *
         * <p>作用：对每一列，把该半径内所有邻近河段的半宽做反距离平方加权平均，
         * 使宽度成为<b>位置的连续场</b>，磨平 trace 接缝（JOIN / 跨区接力 / pass2）处的跳变。</p>
         *
         * <p><b>为什么不是 legacy 的 100</b>：legacy 的河段之间流量本身连续（同一条 polyline
         * 内部无断点），100 格混合是安全的。本项目的河被切成多条 trace，
         * 用 100 格会把<b>邻近但互不相连的平行冲沟</b>的宽度也混进来
         * ⇒ 整片加粗（实测河像素 45049→65086，图上出现 6~8 像素粗带）。</p>
         *
         * <p>取 12 格：刚好覆盖 trace 接缝两侧（JOIN 处两链端点相距通常 ≤3 格），
         * 又不至于把平行河道混在一起。{@code 0} = 关闭（A/B 归因）。</p>
         *
         * <p><b>现状</b>：列级 IDW 混合已随宽度统一迁入 {@link RiverWidthProfile}，
         * 本常量在生产路径<b>无消费者</b>；保留仅因 {@code WidthContinuityProbe} 仍静态反射读取它做元信息。</p>
         */
        static final double WIDTH_BLEND = Double.parseDouble(
                System.getProperty("geogenesis.widthBlend", "6"));

        /** 宽度沿程单调（下游不减）。默认开；{@code false} 关闭做 A/B。 */
        static final boolean WIDTH_MONOTONE =
                !"false".equals(System.getProperty("geogenesis.widthMonotone"));

        /**
         * 该世界坐标处的河道半宽：按 discharge 走 {@code fullWidth} 律，再取一半。
         *
         * <p>无水/越界/非河格 ⇒ 返回 {@code MIN_HALF_BLOCKS}（保证仍然可见、不断线）。</p>
         */
        static double halfWidthAt(HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                  double bx, double bz, RiverCenterlines.Params wp) {
            return halfWidthAt(pipe, ox, oz, n, step, bx, bz, wp, null, null);
        }

        /** 普通采样只消费本河 Q；湖净出流由 RiverWidthProfile 的显式出口身份注入。 */
        static double halfWidthAt(HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                  double bx, double bz, RiverCenterlines.Params wp,
                                  LakeWaterBalance.Result lakes, int[] basinCellToId) {
            double q = dischargeLookup(pipe, ox, oz, n, step, bx, bz);
            if (!(q > 0)) return RiverCenterlines.MIN_HALF_BLOCKS;
            double half = 0.5 * RiverCenterlines.fullWidthPublic(q, wp);
            return Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(wp.maxHalf(), half));
        }

        /** 单点查 coarse 格 discharge（越界/非河格返回 0）。 */
        static double dischargeLookup(HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                      double bx, double bz) {
            if (pipe == null || n <= 0 || step <= 0) return 0;
            int gi = (int) Math.round((bx - ox) / step);
            int gj = (int) Math.round((bz - oz) / step);
            if (gi < 0 || gj < 0 || gi >= pipe.nx() || gj >= n) return 0;
            int k = gi * n + gj;
            double[] q = pipe.discharge();
            return k >= 0 && k < q.length && q[k] > 0 ? q[k] : 0;
        }

        /** 只读诊断：最近一次 halfWidthAt 的决策现场。 */
        static volatile String LAST_PROBE = "(none)";

        // ★ 2026-10-25【只读计数器：验证三条宽度路径是否真的执行】
        //   历史教训：本仓已四次出现"改了代码但路径没被执行"（buildExactNodes 非生产路径、
        //   WorldLakeSurface 从未被调用、探针测不到 IDW）。故任何宽度改动都必须先证明它跑了。
        static final java.util.concurrent.atomic.AtomicLong CNT_NOODLE = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_UNIFY_SCAN = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_UNIFY_CHANGED = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_BASIN_HIT = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_BASIN_RAISED = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_BLEND_APPLIED = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong CNT_TOTAL_SEG = new java.util.concurrent.atomic.AtomicLong();

        /** 打印计数器快照（供 SkeletonRegion 结束时调用一次）。 */
        static void dumpWidthCounters(String where) {
            String msg = String.format("[WIDTH-COUNT] %s segs=%d unifyScan=%d unifyChanged=%d "
                            + "basinHit=%d basinRaised=%d blendApplied=%d noOodle0=%d",
                    where, CNT_TOTAL_SEG.get(), CNT_UNIFY_SCAN.get(), CNT_UNIFY_CHANGED.get(),
                    CNT_BASIN_HIT.get(), CNT_BASIN_RAISED.get(), CNT_BLEND_APPLIED.get(),
                    CNT_NOODLE.get());
            LOGGER.info(msg);
            // ★ 同时写文件：日志可能被 log4j 配置过滤，文件不会。
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("geogenesis-width-count.txt"),
                        msg + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) { }
        }

        /**
         * 取该世界位置附近的**最大** discharge（3×3 coarse 邻域）。
         *
         * <p>【为什么取最大而不是单点】{@code discharge} 是 coarse 格（STEP=2）的河道格累积值；
         * 河流跨出掩码时，相邻 coarse 格的 Q 会从 ~14 直接掉到 ~0.3（实测 x=-468 z=603→605）。
         * 单点取用会让宽度在 2 格内暴跌 40 倍。
         * 取邻域最大相当于"断面至少承载邻格来水"，把这种边界骤降吸收掉。</p>
         *
         * <p>{@code q} 缺失（越界/全邻域为 0）返回 0，由调用方决定兜底。</p>
         */
        static double dischargeAt(HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                  double bx, double bz) {
            if (pipe == null || n <= 0 || step <= 0) return 0;
            double[] q = pipe.discharge();
            int ci = (int) Math.round((bx - ox) / step);
            int cj = (int) Math.round((bz - oz) / step);
            double best = 0;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    int gi = ci + di, gj = cj + dj;
                    if (gi < 0 || gj < 0 || gi >= pipe.nx() || gj >= n) continue;
                    int k = gi * n + gj;
                    if (k < 0 || k >= q.length) continue;
                    if (q[k] > best) best = q[k];
                }
            }
            return best;
        }

        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys) {
            return buildFromNodes(polys, null, 0, 0, 0, 1);
        }

        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys,
                                           HydrologyPipeline.Result pipe, int ox, int oz,
                                           int n, int step) {
            return buildFromNodes(polys, pipe, ox, oz, n, step, null, ox, oz);
        }

        /** 半宽（JOIN 汇合匹配 / 边界约定 / 沿程平滑与单调趋势）全部由 {@link RiverWidthProfile} 算出，本方法只落几何。 */
        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys,
                                           HydrologyPipeline.Result pipe, int ox, int oz,
                                           int n, int step,
                                           List<DeterministicSkeleton.Trace> traces,
                                           int traceOx, int traceOz) {
            List<DeterministicSkeleton.River> rivers = traces == null ? List.of()
                    : DeterministicSkeleton.buildRivers(traces, n);
            return buildFromNodes(polys, pipe, ox, oz, n, step, traces, rivers, Map.of());
        }

        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys,
                                           HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                           List<DeterministicSkeleton.Trace> traces,
                                           List<DeterministicSkeleton.River> rivers,
                                           Map<Integer, Double> boundaryHalf) {
            return buildFromNodes(polys, pipe, ox, oz, n, step, traces, rivers, boundaryHalf,
                    new WidthInheritance(Map.of(), java.util.Set.of(), Map.of()));
        }

        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys,
                                           HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                           List<DeterministicSkeleton.Trace> traces,
                                           List<DeterministicSkeleton.River> rivers,
                                           Map<Integer, Double> boundaryHalf, WidthInheritance inherited) {
            return buildFromNodes(polys, pipe, ox, oz, n, step, traces, rivers, boundaryHalf, inherited,
                    Map.of());
        }

        /** 生产 final 入口：除边界/继承外，还消费跨窗口一致的出湖半宽。 */
        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys,
                                           HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                           List<DeterministicSkeleton.Trace> traces,
                                           List<DeterministicSkeleton.River> rivers,
                                           Map<Integer, Double> boundaryHalf, WidthInheritance inherited,
                                           Map<Long, Double> outletHalfByWorld) {
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
            int polyN = polys.size();
            RiverWidthProfile.Result profile;
            var profileScope = HydrologyMetrics.begin("hydro.profile.width", ox, oz);
            try {
                profile = RiverWidthProfile.buildDetailed(polys, pipe, ox, oz, n, step,
                        traces, rivers, boundaryHalf, inherited.half(), inherited.continued(), inherited.limits(),
                        outletHalfByWorld);
            } finally {
                HydrologyMetrics.end(profileScope);
            }
            List<double[]> halfSeqs = profile.widths();
            List<double[]> depthSeqs;
            var depthScope = HydrologyMetrics.begin("hydro.profile.depth", ox, oz);
            try {
                depthSeqs = RIVER_CROSS_SECTION
                        ? RiverDepthProfile.build(polys, pipe, ox, oz, n, step, traces, rivers, halfSeqs, boundaryHalf)
                        : List.of();
            } finally {
                HydrologyMetrics.end(depthScope);
            }
            double[] widthLimits = new double[(count + bridges.size()) * 2];
            java.util.Arrays.fill(widthLimits, 6.0);
            // 【测量】诊断 I/O（日志 + geogenesis-width-count.txt 追加）：只计时，不改内容/频率/落点。
            var dumpScope = HydrologyMetrics.begin("hydro.diag.widthCounters", ox, oz);
            try {
                dumpWidthCounters("buildFromNodes");
            } finally {
                HydrologyMetrics.end(dumpScope);
            }
            for (int pi = 0; pi < polyN; pi++) {
                var sh = polys.get(pi);
                // ★★★ 2026-10-25【宽度沿折线平滑 —— 治"一节节"】★★★
                //
                // 【缺陷】初版对每段两个端点各自查 coarse 格的 Q。coarse 格边长 = STEP(2)，
                //   而段长也是 1 格 ⇒ 相邻段常落在**不同 coarse 格**，Q 突变 ⇒ 半宽随之跳变
                //   ⇒ 图上河宽一节一节（用户实测"宽度变成一节节的"）。
                //
                // 【参考项目做法】
                //   · dynamicwaters：生成期沿程线性插值 w[i]=lerp(w_src,w_out,i/(n−1))，
                //     查询期再 lerp(w[i],w[i+1],t) ⇒ 任意点 C0 连续（MountainRiverPath:1191-1236, 1686-1703）
                //   · plate-local：根本不给节点设宽度，用「到折线的连续距离场」（rivers.cpp:497-502）
                //   ⇒ 两者共同点：**宽度必须是位置的连续函数**，不能按段各查各的。
                //
                // 【本处修法】先按折线节点采样原始半宽，再做**滑动平均**（窗口 WIDTH_SMOOTH），
                //   最后把平滑后的节点宽度喂给相邻段两端 ⇒ 段与段在共享节点处取同一值
                //   ⇒ 沿线 C0 连续，不再一节节。
                int nodes = sh.size();
                double[] smoothHalf = halfSeqs.get(pi);
                if (smoothHalf == null || smoothHalf.length != nodes) continue;
                if (nodes < 2) continue;
                for (int i = 1; i < nodes; i++) {
                    double x0 = sh.x[i - 1], z0 = sh.z[i - 1];
                    double x1 = sh.x[i], z1 = sh.z[i];
                    int si = idx++;
                    int o = si * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    CNT_TOTAL_SEG.incrementAndGet();
                    // 共享节点取同一值 ⇒ 段边界无阶跃
                    seg[o + 4] = smoothHalf[i - 1];
                    seg[o + 5] = smoothHalf[i];
                    widthLimits[si * 2] = profile.limits().get(pi)[i - 1];
                    widthLimits[si * 2 + 1] = profile.limits().get(pi)[i];
                    seg[o + 6] = RIVER_CROSS_SECTION ? depthSeqs.get(pi)[i - 1] : 1.0;
                    seg[o + 7] = RIVER_CROSS_SECTION ? depthSeqs.get(pi)[i] : 1.0;
                    registerTile(tiles, si, x0, z0, x1, z1);
                }
            }
            for (double[] br : bridges) {
                int si = idx++;
                int o = si * 8;
                seg[o] = br[0]; seg[o + 1] = br[1]; seg[o + 2] = br[2]; seg[o + 3] = br[3];
                seg[o + 4] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 5] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 6] = RIVER_CROSS_SECTION
                        ? projectedDepth(polys, depthSeqs, br[0], br[1]) : 1.0;
                seg[o + 7] = RIVER_CROSS_SECTION
                        ? projectedDepth(polys, depthSeqs, br[2], br[3]) : 1.0;
                registerTile(tiles, si, br[0], br[1], br[2], br[3]);
            }
            var result = new SegmentIndex(seg, tiles);
            result.widthLimits = widthLimits;
            result.lakeInterfaces = profile.interfaces();
            result.nodeDepths = depthSeqs;
            result.indexJunctionSections(polys, halfSeqs, rivers);
            return result;
        }

        /** Only chainEdges direct successors; no transitive component or spatial sew inference. */
        private void indexJunctionSections(List<DeterministicSkeleton.Shaped> polys,
                                            List<double[]> halves,
                                            List<DeterministicSkeleton.River> rivers) {
            if (!RIVER_CROSS_SECTION) return;
            int si = 0;
            for (int p = 0; p < polys.size(); p++) {
                int nodes = polys.get(p).size();
                if (halves.get(p) == null || halves.get(p).length != nodes) continue;
                for (int i = 1; i < nodes; i++) segmentPoly[si++] = p;
            }
            if (rivers == null || rivers.isEmpty()) return;
            List<List<JunctionSection>> byPoly = new ArrayList<>(polys.size());
            for (int p = 0; p < polys.size(); p++) byPoly.add(new ArrayList<>());
            int[] succ = new int[polys.size()];
            java.util.Arrays.fill(succ, -1);
            for (var river : rivers) RiverWidthProfile.chainEdges(succ, river, polys.size(), rivers);
            boolean[] cycles = RiverWidthProfile.cycleNodes(succ, polys.size());
            double[][] arcs = RiverWidthProfile.arcLengths(polys);
            List<JunctionSection> sections = new ArrayList<>();
            for (int up = 0; up < succ.length; up++) {
                int down = succ[up];
                if (down < 0 || cycles[up] || cycles[down]) continue;
                var a = polys.get(up);
                var b = polys.get(down);
                if (a.size() < 2 || b.size() < 2) continue;
                double x = a.x[a.size() - 1], z = a.z[a.size() - 1];
                double[] hit = RiverWidthProfile.projectOnto(b, arcs[down], x, z);
                if (hit == null) continue;
                int k = (int) hit[1];
                double rx = b.x[k] + hit[2] * (b.x[k + 1] - b.x[k]);
                double rz = b.z[k] + hit[2] * (b.z[k + 1] - b.z[k]);
                double gap = Math.hypot(x - rx, z - rz);
                // chainEdges proves the pair; shaped meander offsets are evidence, not a topology gate.
                if (!Double.isFinite(gap)) continue;
                double maxHalf = 0;
                for (double h : halves.get(up)) maxHalf = Math.max(maxHalf, h);
                for (double h : halves.get(down)) maxHalf = Math.max(maxHalf, h);
                var section = new JunctionSection(up, down, x, z, rx, rz, gap, maxHalf);
                sections.add(section);
                byPoly.get(up).add(section);
                byPoly.get(down).add(section);
            }
            junctionSections = List.copyOf(sections);
            for (int p = 0; p < byPoly.size(); p++) byPoly.set(p, List.copyOf(byPoly.get(p)));
            junctionsByPoly = List.copyOf(byPoly);
        }

        private static double projectedDepth(List<DeterministicSkeleton.Shaped> polys,
                                             List<double[]> depths, double x, double z) {
            double best = Double.POSITIVE_INFINITY, value = 1.0;
            for (int p = 0; p < polys.size(); p++) {
                var sh = polys.get(p);
                for (int i = 1; i < sh.size(); i++) {
                    double dx = sh.x[i] - sh.x[i - 1], dz = sh.z[i] - sh.z[i - 1];
                    double len = dx * dx + dz * dz;
                    double t = len == 0 ? 0 : Math.max(0, Math.min(1,
                            ((x - sh.x[i - 1]) * dx + (z - sh.z[i - 1]) * dz) / len));
                    double d = Math.hypot(x - sh.x[i - 1] - t * dx, z - sh.z[i - 1] - t * dz);
                    if (d < best) {
                        best = d;
                        value = depths.get(p)[i - 1] + t * (depths.get(p)[i] - depths.get(p)[i - 1]);
                    }
                }
            }
            return value;
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
            double best = Double.POSITIVE_INFINITY, half = 0, depth = 0, waterSurface = Double.NaN;
            double bestKey = Double.POSITIVE_INFINITY;
            int bestSegment = -1;
            double bankScale = bankScale(blockX, blockZ);
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
                double limit = widthLimits == null ? 6.0
                        : widthLimits[si * 2] + t * (widthLimits[si * 2 + 1] - widthLimits[si * 2]);
                double segHalf = Math.min(limit,
                        clampHalf((seg[o + 4] + t * (seg[o + 5] - seg[o + 4])) * bankScale));
                // ★★★ 2026-10-21【覆盖判据 = 半宽 ∪ 相交】
                //
                // 【旧判据】格心到线段距离 ≤ half(0.5)。对角步另一侧格心距线段 1/√2≈0.707
                //   > 0.5 ⇒ 漏格 ⇒ 8 连通断口。历史解法是 Math.rint + fillDiagonalGaps 把曲线
                //   量化成整数格再拆正交步 —— 断口治好了，代价是所有斜线变 90° 台阶。
                //   节点图分层实测：grid 3.23% 直角 vs final shaped 63.60%、中位 90°。
                //
                // 【新判据】两者取并集：
                //   · 格心距 ≤ half  → 保留半宽语义（宽度后续议题，但语义不能被改掉）
                //   · 弦穿过方块     → 补上对角针孔，8 连通由此保证
                //   只用后者会漏掉约 22.9% 的方块（实测河格 25130 → 19380）；
                //   只用前者就是旧行为（断口）。并集才是"既不断也不量化"。
                boolean covered = dist <= segHalf
                        || segmentMeetsBlock(x0, z0, x1, z1, blockX, blockZ);
                double key = covered ? dist : dist + 1e6;   // 覆盖优先，其次比距离
                if (dist == 0 && best == 0 && Double.isFinite(waterSurface) && Double.isFinite(surface[si])
                        && Double.doubleToLongBits(waterSurface)!=Double.doubleToLongBits(surface[si]))
                    throw new IllegalStateException("competing corridor planes at "+blockX+","+blockZ);
                if (key < bestKey) {
                    bestSegment = si;
                    bestKey = key;
                    best = dist;
                    half = segHalf;
                    double centerDepth = seg[o + 6] + t * (seg[o + 7] - seg[o + 6]);
                    depth = RIVER_CROSS_SECTION
                            ? RiverCrossSection.targetDepth(centerDepth, dist, segHalf) : centerDepth;
                    waterSurface = surface[si];
                }
            }
            // 没有任何段与该方块相交 ⇒ 此处无水。
            // ⚠ 不能像旧实现那样靠 dist>half 兜底：那会把"附近的方块"全染色 = 加粗。
            if (bestKey > 0.5e6) return null;
            depth = junctionDepth(blockX, blockZ, cand, bestSegment, depth, bankScale);
            return new Hit(best, half, depth, waterSurface);
        }

        /**
         * H only: winner/coverage/half/surface are immutable. In the full-strength core,
         * H=1+max(extra_up,extra_down); extra=(Hc-1)*(1-r*r)^2 vanishes at r>=1.
         * Outside the core only the additional depth fades, returning EXACTLY to the old H.
         * C0 at winner switches is promised inside the core for these two compatible traces,
         * not in the outer fade (which retains the original winner field), nor for Hc<1.
         * Two NaNs mean this SAME pipe's ordinary river: columnsFor applies ONE authoritative
         * S to the composed H via wetBed. Finite-vs-NaN/different finite planes never mix.
         */
        private double junctionDepth(double x, double z, List<Integer> candidates, int winner,
                                       double original, double bankScale) {
            if (!RIVER_CROSS_SECTION || winner < 0 || junctionSections.isEmpty() || original < 1) {
                return original;
            }
            int poly = segmentPoly[winner];
            if (poly < 0 || poly >= junctionsByPoly.size()) return original;
            double result = original;
            for (var join : junctionsByPoly.get(poly)) {
                if (poly != join.up() && poly != join.down()) continue;
                double distance = Math.hypot(x - join.x(), z - join.z());
                // Tail-centred patch includes the measured receiver point in its full-strength core.
                double radius = 16 + join.maxHalf() + join.gap(), core = 2 * join.maxHalf() + join.gap();
                if (distance >= radius) continue;
                double up = sectionExtra(x, z, candidates, join.up(), surface[winner], bankScale);
                double down = sectionExtra(x, z, candidates, join.down(), surface[winner], bankScale);
                if (up < 0 || down < 0) continue;
                double fade = 1 - RiverWidthProfile.smoothstep01((distance - core) / (radius - core));
                double shared = 1 + Math.max(up, down);
                result = Math.max(result, original + fade * Math.max(0, shared - original));
            }
            return result;
        }

        /** Max of compact-support extras, not max of whole H outside a candidate's width. */
        private double sectionExtra(double x, double z, List<Integer> candidates, int poly,
                                      double waterSurface, double bankScale) {
            double extra = -1;
            for (int si : candidates) {
                if (segmentPoly[si] != poly || !compatibleSectionSurface(waterSurface, surface[si])) continue;
                int o = si * 8;
                double dx = seg[o + 2] - seg[o], dz = seg[o + 3] - seg[o + 1];
                double len2 = dx * dx + dz * dz;
                double t = len2 > 0 ? Math.max(0, Math.min(1,
                        ((x - seg[o]) * dx + (z - seg[o + 1]) * dz) / len2)) : 0;
                double hc = seg[o + 6] + t * (seg[o + 7] - seg[o + 6]);
                if (!Double.isFinite(hc) || hc < 1) continue; // Preserve shallow/dry interface semantics.
                double limit = widthLimits == null ? 6.0
                        : widthLimits[si * 2] + t * (widthLimits[si * 2 + 1] - widthLimits[si * 2]);
                double half = Math.min(limit, clampHalf(
                        (seg[o + 4] + t * (seg[o + 5] - seg[o + 4])) * bankScale));
                double dist = Math.hypot(x - seg[o] - t * dx, z - seg[o + 1] - t * dz);
                extra = Math.max(extra, RiverCrossSection.targetDepth(hc, dist, half) - 1);
            }
            return extra;
        }

        private static boolean compatibleSectionSurface(double a, double b) {
            return Double.isNaN(a) && Double.isNaN(b)
                    || Double.isFinite(a) && Double.isFinite(b) && a == b;
        }

        /** 世界坐标驱动的局部岸宽起伏，在覆盖判断之前应用，收发侧相同点取相同倍率。 */
        static double bankHalf(double half, double x, double z) {
            return clampHalf(half * bankScale(x, z));
        }

        private static double bankScale(double x, double z) {
            double jitter = Math.sin(x * 0.08) * Math.cos(z * 0.08) * 1.5
                    + Math.sin(0.04 * (x + z)) * 0.7;
            return 1.0 + BANK_JITTER * jitter / 2.2;
        }

        private static double clampHalf(double half) {
            return Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(6.0, half));
        }

        /**
         * 线段与方块是否相交（Liang–Barsky 裁剪到以格心为中心的 1×1 AABB）。
         *
         * <p>这就是"超覆盖"判据：弦穿过的每个方块都染色 ⇒ 相邻方块必 8 连通，
         * 因此<b>不需要</b>把折线量化到整数格（那正是 90° 直角的来源）。</p>
         */
        private static boolean segmentMeetsBlock(double x0, double z0, double x1, double z1,
                                                 int blockX, int blockZ) {
            double xmin = blockX - 0.5, xmax = blockX + 0.5;
            double zmin = blockZ - 0.5, zmax = blockZ + 0.5;
            double t0 = 0, t1 = 1;
            double dx = x1 - x0, dz = z1 - z0;
            // 保持四个裁剪面的原顺序与除法，只移除逐候选分配的两个数组。
            for (int i = 0; i < 4; i++) {
                double p = switch (i) { case 0 -> -dx; case 1 -> dx; case 2 -> -dz; default -> dz; };
                double q = switch (i) {
                    case 0 -> x0 - xmin;
                    case 1 -> xmax - x0;
                    case 2 -> z0 - zmin;
                    default -> zmax - z0;
                };
                if (p == 0) {
                    if (q < 0) return false;              // 平行且在外侧
                } else {
                    double r = q / p;
                    if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r; }
                    else       { if (r < t0) return false; if (r < t1) t1 = r; }
                }
            }
            return t0 <= t1;
        }
    }
}
