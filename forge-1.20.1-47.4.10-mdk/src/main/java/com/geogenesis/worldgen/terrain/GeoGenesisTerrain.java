package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarver;
import com.geogenesis.worldgen.hydrology.HydrologyChunkEngine;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
import com.geogenesis.worldgen.hydrology.HydrologyExperimentEngine;
import com.geogenesis.worldgen.noise.NoiseUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地形引擎对外接口：带缓存的 Cell 网格采样。
 *
 * 跨 chunk 共享，无 tile 边界断裂。
 * 缓存策略：按 chunk 网格（16×16）缓存 Cell 数组，LRU-like（ConcurrentHashMap + 简单上限）。
 */
public final class GeoGenesisTerrain {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final int CACHE_SIZE = 4096;
    private static final int CHUNK_SHIFT = 4; // 16 blocks per chunk

    private final CellGenerator generator;
    private final HeightCurve curve;

    /** 缓存命中统计（P0-3）；须在 cache 之前初始化（LruMap 构造引用它）。 */
    private final CacheStats chunkCacheStats = new CacheStats("chunkCell");

    /**
     * chunk Cell 缓存 —— 访问序（<b>真 LRU</b>）有界 Map。
     *
     * <p>★ 2026-09-11 P0-3：取代原「ConcurrentHashMap + 超限任意删 1/8」——
     * 原实现会把刚生成的热点随机驱逐，玩家回头又得重建（~700ms/chunk）。</p>
     */
    private final Map<Long, Cell[]> cache =
        Collections.synchronizedMap(new LruMap<>(CACHE_SIZE, chunkCacheStats));

    private final boolean riversEnabled;

    /**
     * ★ 2026-09-17【M2，默认 false】湖水位是否改用"雕刻后最终地形上的逃逸高度"。
     *
     * <p>修的是<b>阶段错位</b>：旧水位在【侵蚀后、雕刻前】地形上求，而雕刻会把山脊挖穿、
     * 开出新排水口 ⇒ 水悬在"现已能排干"的河谷上方（实测 21.6% 的水格紧邻 1~2 块内
     * 有低 8.75 块的旱地却无水）。详见 {@code applyHydrologyValley} 湖分支的注释。</p>
     *
     * <h4>★ 2026-09-17 A/B 实测：本实现【尚不完整】（故默认关闭）</h4>
     * <p>启用后窗口水格由 362 → 136（确有变化），但<b>目标最坏点未改善</b>（仍 166.63 / +8.747 块）。
     * 原因：本实现用 {@code generator.sampleWu} 作为地形——那是<b>侵蚀后、但仍未雕刻</b>的地形，
     * 与旧实现<b>同一份输入</b> ⇒ 逃逸高度（167.539）反而高于旧水位（166.627）⇒ {@code min} 后不变。</p>
     *
     * <h4>★★ 2026-09-17 二次 A/B（地形口径已修正为点态最终地形）：仍无改善</h4>
     * <p>修正后再测：窗口水格仍 136、最坏点仍 166.63 / +8.747 块。
     * ⇒ <b>水位不是主因</b>。结合决策链 [10]（边界 44/44 来自 {@code inFlood} 连通区）
     * 与块级地图（水边界为一条跨约 24 块的斜线）⇒ <b>真正的主因是
     * {@code computeFlood} 的 BFS 网格粒度：{@code claimGrid/2 = 12wu（= 24 块）}</b>，
     * 与 {@code floodHalf = 6wu}。水边界因此被<b>量化到 12wu 网格</b>，
     * 而不是贴合"侵蚀后 height &lt; spill"的等高线 ⇒
     * 紧邻水边、低于水位却是干的格（实测 263 格）正是"量化边界之外"的格。</p>
     * <p>⇒ <b>下一步应改 {@code inFlood} 的边界精度</b>（加密网格或落块侧按等高线精修），
     * 而不是继续调水位。水位重算能力本身（{@code escapeWaterLevel} + 点态雕刻）仍然有效，
     * 可在边界修好后作为"统一水位"接入。</p>
     *
     * <h4>★ 先前的结构结判断（已由点态雕刻【推翻】）</h4>
     * <p>水位必须在<b>雕刻后的地形</b>上求解；但<b>雕刻是逐 chunk 的</b>，而逃逸路径要跨几十个 chunk ⇒
     * 在 {@code applyHydrologyValley} 内部读"其它 chunk 的已雕刻地形"会递归触发
     * {@code getChunkCells → generateChunk → applyHydrologyValley → 逃逸计算 → getChunkCells …}。</p>
     * <p>⇒ <b>水位与雕刻必须处于同一层级</b>：先把 region 级地形"侵蚀 + 雕刻"定型，
     * 再在其上求水位 —— 即本例程所属的「世界水文模型」重构核心（见
     * {@code .codebuddy/plans/世界水文模型-重构设计.md} §2.3）。
     * <b>在该结构调整完成前，本开关保持 false。</b></p>
     */
    static final boolean LAKE_ESCAPE_LEVEL = true;    // ★ 与"湖域判定修复"配套启用（见类注释）

    /**
     * ★ 2026-09-17【湖岸 1 块精度精修】开关。<b>当前 = true（A/B 验证中）</b>。
     *
     * <p>修的是<b>覆盖缺陷</b>（不是水位）：水体物理审计实测（runWaterPhysicsProbe）
     * 显示「水位 166.627 vs 真盆沿 167.706 ⇒ 水位正确（短板生效）；但干墙 451 格」——
     * 水边界被 12wu 粗格洪泛区切死 ⇒ 垂直水墙 + 水不贴岸（用户截图）。
     * 详见 {@link #lakeFineFlood}。</p>
     *
     * <h4>★ 2026-09-17：v1（失败）→ v2（有效，本版）</h4>
     * <p><b>v1 = 只重判 {@code lakePlan=true} 的列</b>：干墙 451 → 452、面积 +2
     * ⇒ <b>纯开销零收益</b>。原因：那些干墙格全是被 {@code inFlood} <b>拒绝</b>过的列
     * （carver 走"非湖列"早退分支，v1 的登记根本看不到它们）⇒ <b>够不着目标</b>。</p>
     * <p><b>v2（本版）</b>：carver 对<b>被拒列也回传"湖节点 + 水位"</b>
     * （{@code lakeNode} + {@code lakeLevelY}，{@code lakePlan} 仍为 false），
     * 本方法再对<b>所有受该湖影响的列</b>做块级洪泛、<b>只增不减</b>（避免空洞）。
     * 实测（{@code runWaterPhysicsProbe}，seed 5436529513624899584 @ wu(12,316)±96wu）：</p>
     * <pre>
     *   水位 166.627 → 166.627   （**一字未动** ⇒ 只修覆盖、不动水位）
     *   面积 26739   → 28586     （+6.9%，补上该淹而没淹的格）
     *   干墙 451     → 158       （−65%）
     * </pre>
     * <p>水体视图 {@code build/seam/water_view.png} 已确认岸线基本贴合地形（不再是直角水板）。
     * <b>残余 158 格待查</b>（疑为：弃湖列 / 本 chunk 无粗格种子的岸线格 ——
     * 后者可考虑用 {@code LakeNode.inFlood} 作 pad 侧种子，但它是线性扫描，需先评估开销）。</p>
     * <p>⚠ 本实现自身<b>顺序无关</b>（只读本 chunk 最终高度 + 纯采样）；
     * 门禁 {@code runHydrologyDeterminismProbe} 的 FAIL 经隔离实验确认是<b>既有问题</b>
     * （本开关关掉仍 FAIL；max|Δheight| = 0.0515，与本改动无关）。</p>
     */
    static final boolean LAKE_FINE_FLOOD = true;

    /** 逃逸水位专用的水文引擎（懒建一次，供点态最终地形采样复用）。 */
    private HydrologyExperimentEngine escapeEngine;

    /** ★ 2026-09-17【临时诊断，取到结论后删】。 */
    private static final boolean LAKE_ESCAPE_DIAG = false;
    private static final java.util.concurrent.atomic.AtomicInteger escapeDiagCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 侵蚀向河道软让步（方案 A，config erosionYieldToRiver，默认 true）。 */
    private final boolean erosionYieldToRiver;
    private final HydrologyChunkEngine hydrologyExperiment;

    /** 非阻塞高度查询降级计数（P0-3 埋点）：① 已生成 chunk 精确命中 / ② 廉价重算。 */
    private final AtomicLong baseHeightTier1 = new AtomicLong();
    private final AtomicLong baseHeightTier2 = new AtomicLong();

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
        // ★ 2026-08-16 预览进程兜底：Swing 预览（TerrainPreview）无 Forge 配置
        //   环境，GeoGenesisConfig.INSTANCE 的 spec 未加载 → 任何 .get() 抛
        //   IllegalStateException（实锤：runPreview 崩溃堆栈 at :42）。整体
        //   try-catch 兜底 → 默认值（对齐 CellGenerator 的 cfg 空保护惯例）。
        double hs = generator.params().horizontalScale();
        boolean riversEnabled = true;
        boolean erosionYieldToRiver = true;
        try {
            riversEnabled = GeoGenesisConfig.INSTANCE.riverEnabled.get();
            erosionYieldToRiver = GeoGenesisConfig.INSTANCE.erosionYieldToRiver.get();
        } catch (IllegalStateException e) {
            // 预览进程：配置未加载，保持默认值
        }
        this.riversEnabled = riversEnabled;
        this.erosionYieldToRiver = erosionYieldToRiver;
        this.hydrologyExperiment = new HydrologyChunkEngine(generator, 0L);
        // ★ 2026-08-14 启动诊断：确认游戏内河网 + discharge 是否启用（用户"跑新版没变化"排查）
        LOGGER.info("[RIVER] terrain init: riversEnabled={} hs={}",
            riversEnabled, hs);
    }

    /** 播种所有噪声节点（每个世界种子调用一次） */
    public void seed(long worldSeed) {
        generator.seed(worldSeed);
        cache.clear();
        baseHeightTier1.set(0);   // 埋点按世界重置
        baseHeightTier2.set(0);
        chunkCacheStats.reset();
        hydrologyExperiment.setSeed(worldSeed);
    }

    /** 实验专用水文 chunk 结果；默认游戏路径不调用。 */
    public HydrologyChunkResult calculateHydrologyChunk(int chunkX, int chunkZ) {
        return hydrologyExperiment.calculate(chunkX, chunkZ);
    }

    /** 海平面 Y */
    public double seaLevel() { return generator.seaLevel(); }

    /** HeightCurve 暴露（给 Generator 做 eFromHeightF） */
    public HeightCurve heightCurve() { return generator.heightCurve(); }

    /** 河流开关（配置 riverEnabled） */
    public boolean riversEnabled() { return riversEnabled; }

    /**
     * 采样世界高度。
     */
    public double sampleHeight(double wx, double wz) {
        Cell cell = sampleCell(wx, wz);
        return cell != null ? cell.height : generator.seaLevel();
    }

    /**
     * 采样完整 Cell 数据（带缓存）。
     */
    public Cell sampleCell(double wx, double wz) {
        int cx = chunkCoord(wx), cz = chunkCoord(wz);
        long key = pack(cx, cz);
        Cell[] cells = getChunkCells(cx, cz);
        int lx = localCoord(wx), lz = localCoord(wz);
        return cells[lx * 16 + lz];
    }

    /**
     * 轻量采样（★ 2026-08-09 无伤优化）：直接调 generator.sample() 纯 e 场 + 气候 + 分类，
     * 不触发 getChunkCells/侵蚀 tile。BiomeSource 群系分类只用 terrainType/climate/e（均在
     * sample() 内设置）→ 出生点搜索/BIOMES stage 零 tile 生成（世界创建 9 分钟 → 秒级）。
     * 地形高度仍由 fillFromNoise 走完整管线（sampleCell），不受影响。
     * 入参 = MC 块坐标 → wu 换算。
     */
    public Cell sampleCellLight(double wx, double wz) {
        double wux = toWu(wx), wuz = toWu(wz);
        Cell cell = generator.sample(wux, wuz);
        // ★ 2026-09-11 B2：把【已缓存】的侵蚀增量并入快速路径 → 已探索区域与完整管线收敛
        //   （terrainType 重分类 + height 含侵蚀）。tile 未生成时静默跳过 →
        //   出生点搜索等冷启动场景仍【零 tile 生成】，保持秒级（与 B1 同一"不阻塞"语义）。
        //   残余有界近似：① 不含水文河谷雕刻（河道处 height 可略高）；
        //   ② 结果依赖 tile 是否已缓存（冷启动 = 无侵蚀分类，tile 就绪后 = 有）；
        //      实测侵蚀 delta 在多数 tile 为小量或 0，故影响有界。
        generator.applyCachedTileDelta(cell, wux, wuz);
        fillRiverDistance(cell, wux, wuz);
        return cell;
    }

    /**
     * 【非阻塞】结构/特征放置专用高度查询（★ 2026-09-11 P0-1 止血）。
     *
     * <p><b>为什么需要</b>：MC 的 {@code getBaseHeight}/{@code getBaseColumn} 在
     * STRUCTURE_STARTS 阶段被高频调用，而该阶段<b>早于</b> NOISE —— 目标 chunk 必然尚未生成。
     * 原实现走 {@link #sampleCell} → {@link #getChunkCells} → 冷侵蚀 tile
     * （实测 400~719 ms/次），等于把全管线最贵的操作接到最热的调用点 → 世界生成卡死。</p>
     *
     * <p>两级降级（参考 FreeTerraForged {@code WorldLookup} 的 accurate / cached / cheap）：</p>
     * <ol>
     *   <li><b>① 已生成 chunk</b>：直接取缓存 Cell 的 height → <b>与落块完全一致</b>，零额外成本。</li>
     *   <li><b>② 未生成 chunk</b>：走 {@link CellGenerator#sampleHeightNonBlocking} —— 基础场
     *       + <b>仅当侵蚀 tile 已缓存时</b>叠加增量；tile 未生成则退化为无侵蚀基础高度。
     *       <b>保证绝不触发侵蚀 tile 生成。</b></li>
     * </ol>
     *
     * <p><b>权衡（已知近似）</b>：② 在完全未探索区域不含<b>侵蚀增量</b>，也不含
     * <b>水文河谷雕刻</b>（河道下切），故结构（村庄等）放置高度可能与最终地形相差一个
     * 侵蚀/下切量级，河道处可能偏高。这是"绝不阻塞"的必然代价，与 RTF 的 cheap 降级同级。
     * 而相邻 chunk 已生成时（常见情形）tile 通常已缓存 → ② 同样能拿到侵蚀，误差极小。</p>
     */
    public double sampleHeightNonBlocking(double wx, double wz) {
        // ① 已生成 chunk → 精确（与落块完全一致）
        Cell[] cells = cachedChunk(chunkCoord(wx), chunkCoord(wz));
        if (cells != null) {
            Cell c = cells[localCoord(wx) * 16 + localCoord(wz)];
            if (c != null) {
                baseHeightTier1.incrementAndGet();
                return c.height;
            }
        }
        // ② 未生成 → 廉价重算：基础场 + 已缓存的侵蚀增量，绝不触发侵蚀 tile
        baseHeightTier2.incrementAndGet();
        return generator.sampleHeightNonBlocking(toWu(wx), toWu(wz));
    }

    /** 非阻塞高度查询降级统计（P0-3 埋点）：① 已生成 chunk 精确命中次数。 */
    public long baseHeightTier1Count() { return baseHeightTier1.get(); }

    /** 非阻塞高度查询降级统计（P0-3 埋点）：② 廉价重算次数。 */
    public long baseHeightTier2Count() { return baseHeightTier2.get(); }

    /** chunk Cell 缓存命中统计（P0-3 埋点）：hit / miss / evict。 */
    public CacheStats chunkCacheStats() { return chunkCacheStats; }

    /**
     * 【大范围预览专用】最廉价的采样（★ 2026-09-11）。
     *
     * <p>只跑 {@code generator.sample()}（高度场 + 气候 + 降水），与 {@link #sampleCellLight} 相比
     * <b>额外省掉两件事</b>：
     * <ol>
     *   <li><b>不查河流距离</b>：{@link #fillRiverDistance} 对干旱格会调
     *       {@code riverNetwork().distanceToWater(...)}，在【大范围】下会触发大量河网 region 构建
     *       （实测可达 ~117 ms/region），代价不可接受；</li>
     *   <li><b>不叠加已缓存的侵蚀增量</b>：大范围看的是气候格局与地形骨架，侵蚀量级差异不影响判读。</li>
     * </ol>
     *
     * <p>因此本方法<b>不保证与最终落块地形一致</b>（相差侵蚀增量 + 河谷雕刻），仅用于
     * {@code LargeAreaSampler} 的大范围视图；需要"预览 = 游戏"请用 {@link #sampleCell}。</p>
     */
    public Cell sampleCellCoarse(double wx, double wz) {
        return generator.sample(toWu(wx), toWu(wz));
    }

    /**
     * 填充「到最近河线的距离」（wu）—— 河流绿洲判定的输入。
     *
     * <p><b>为什么需要</b>：本类有两条采样路径 —— 完整管线（{@link #getChunkCells}，
     * 含侵蚀与水文雕刻）与快速路径（{@link #sampleCellLight}，群系分类专用）。
     * 绿洲依赖"离水多远"，此前只有完整管线拿得到，导致规则只在预览生效。
     * 两条路径在此用<b>同一个方法、同一个条件</b>填充 → 预览 = 游戏。
     *
     * <p><b>只在干旱群区计算</b>：快速路径在出生点搜索等场景被高频调用，
     * 全域查询会无谓地实例化大量河网 region（region 是懒加载的）。
     */
    private void fillRiverDistance(Cell cell, double wuX, double wuZ) {
        if (!riversEnabled || hydrologyExperiment == null || cell == null) return;
        if (cell.biomeType != com.geogenesis.worldgen.climate.WhittakerType.DESERT) return;
        cell.riverDistance = hydrologyExperiment.riverNetwork().distanceToWater(wuX, wuZ);
    }

    /**
     * 获取 chunk 内所有 Cell（用于 fillFromNoise 逐格遍历）。
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cachedChunk(chunkX, chunkZ);
        if (cells == null) {
            cells = generateChunk(chunkX, chunkZ);
            Cell[] prev = cache.putIfAbsent(key, cells);
            if (prev != null) cells = prev;
        }
        return cells;
    }

    /**
     * ★ 2026-09-15：<b>非生成</b>的 chunk Cell 查询（只 peek 缓存，miss 返回 null）。
     *
     * <h3>为何需要（性能实测驱动的修复）</h3>
     * <p>{@link #getChunkCells} 在 miss 时会<b>主动触发</b> {@code generateChunk}
     * —— 实测（{@code CavePerfProbe}）冷取 <b>avg 24.7ms、max 594ms</b>，
     * 而热取仅 <b>0.6μs</b>（相差可达数万倍）。</p>
     *
     * <p>{@code applyCarvers} 雕洞穴时需要本 chunk 的地表高度与岩性，但它位于
     * 管道<b>下游</b>（{@code fillFromNoise} → {@code applyCarvers}），按下游调用者
     * 的正确姿势<b>不该</b>在这里触发地形生成 —— 与 {@code getBaseHeight} 的
     * 止血（P0-1）和 {@link #sampleHeightNonBlocking} 是同一原则：
     * <b>下游只读已就绪的数据，绝不反向触发昂贵的上游生成</b>。</p>
     *
     * <h3>语义（与 getChunkCells 的差别）</h3>
     * <p>返回 {@code null} 表示"本 chunk 尚未生成"。调用方应<b>跳过</b>该 chunk
     * （而非退回 getChunkCells）：跳过只损失洞穴（下一轮生成/相邻 chunk 仍会雕），
     * 而触发生成会付出最高 ~600ms 的代价并可能形成"生成链"。</p>
     */
    public Cell[] peekChunk(int chunkX, int chunkZ) {
        return cachedChunk(chunkX, chunkZ);
    }

    /** 带埋点的 chunk 缓存查询（hit/miss 统计，P0-3）。 */
    private Cell[] cachedChunk(int chunkX, int chunkZ) {
        Cell[] cells = cache.get(pack(chunkX, chunkZ));
        if (cells != null) chunkCacheStats.hit(); else chunkCacheStats.miss();
        return cells;
    }

    /** 预载周边 chunk */
    public void preloadAround(int centerCX, int centerCZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                getChunkCells(centerCX + dx, centerCZ + dz);
            }
        }
    }

    private final AtomicBoolean preloadSpawnScheduled = new AtomicBoolean(false);

    /**
     * ★ 首个 chunk 完成信号（2026-09-14）：预热等它之后再启动，避免与主线程抢 CPU。
     *
     * <p><b>为何需要</b>：同进程对照实测 —— 单独生成首 chunk 893ms，
     * <b>并发预热时 1086ms</b>（+193ms）。因为预热构建的 region/tile 与首 chunk
     * 需要的高度重叠，两者争抢 CPU 且竞争 {@code computeIfAbsent} ⇒ 主线程反而更慢，
     * 而这段正是用户看到的"进度条不动"。</p>
     */
    private final java.util.concurrent.CountDownLatch firstChunkLatch =
            new java.util.concurrent.CountDownLatch(1);

    /** 由 {@code fillFromNoise} 在 chunk 生成本身完成后调用（释放预热等待）。 */
    public void noteChunkGenerated() {
        firstChunkLatch.countDown();
    }

    /**
     * ★ 2026-08-09 优化：出生点周边异步预热（光追"先投浅路径"类比——把可能马上要用的
     *   tiles 提前在后台算好，玩家进入时缓存命中，冷启动观感丝滑）。
     *   只执行一次（AtomicBoolean），后台线程执行，不阻塞服务器线程。
     *   围绕 (0,0) 半径 3 → 7×7=49 chunk，覆盖 3×3 tiles 全量 + 1 圈边（含懒生成热点）。
     *
     * <p>★ 2026-09-14 两处修正（用户："刚创建加载有一段无动静的空闲期"）：</p>
     * <ol>
     *   <li><b>推迟到首个 chunk 完成后</b>：实测并发预热使首 chunk 从 893ms 涨到 1086ms
     *       —— 预热与首 chunk 抢 CPU，正好加长了"进度条不动"的窗口。先让首 chunk 跑完，
     *       再把预热放出去（此时玩家已看到进度条在动）。</li>
     *   <li><b>改用专用守护线程</b>（原先提交到 {@code TILE_SAMPLER}）：预热现在要
     *       "等待"，若提交到 TILE_SAMPLER，在其队列满（64）且线程全忙时
     *       {@code CallerRunsPolicy} 会在<b>调用线程（主线程）</b>上执行该任务
     *       ⇒ 那个等待就会把主线程卡住（与优化目标相反）。专用线程彻底规避该风险。</li>
     * </ol>
     */
    public void preloadSpawnAsync() {
        if (!preloadSpawnScheduled.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                // 等首个 chunk 生成完毕（上限 30s，超时也继续 —— 预热只是优化）
                firstChunkLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                preloadAround(0, 0, 3);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("spawn preload failed", e);
            }
        }, "GeoGenesis-SpawnPreload");
        t.setDaemon(true);
        t.start();
    }

    /** 旧 API 兼容：按 block 网格返回 Cell 二维数组。支持中断。 */
    public Cell[][] getRegionCells(int originBlockX, int originBlockZ, int cellCountX, int cellCountZ) {
        Cell[][] region = new Cell[cellCountX][cellCountZ];
        for (int bx = 0; bx < cellCountX; bx++) {
            for (int bz = 0; bz < cellCountZ; bz++) {
                if (Thread.interrupted()) return null;
                region[bx][bz] = sampleCell(originBlockX + bx, originBlockZ + bz);
            }
        }
        return region;
    }

    // === wu 化映射层 ===

    /** 块坐标 → wu（水平映射层，对齐 NovoAtlas horizontalScale 语义；HS=1 恒等）。 */
    private double toWu(double block) {
        double hs = generator.params().horizontalScale();
        return (hs > 0.01 && hs != 1.0) ? block / hs : block;
    }

    // === 内部 ===

    private Cell[] generateChunk(int cx, int cz) {
        long ts0 = System.nanoTime();
        Cell[] cells = new Cell[16 * 16];
        int baseX = cx << CHUNK_SHIFT;
        int baseZ = cz << CHUNK_SHIFT;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                // 2026-08-10 wu 化：块坐标 → wu（÷horizontalScale）交给引擎
                cells[lx * 16 + lz] = generator.sample(
                    toWu(baseX + lx), toWu(baseZ + lz));
            }
        }
        long ts1 = System.nanoTime();

        // 水文 + 侵蚀 tile 管线（wu 坐标定位 tile；extractFromTile 内部按块→wu 插值读取）
        generator.extractFromTile(cells, cx, cz);
        long ts2 = System.nanoTime();

        // ★ 水文河谷雕刻回写 cell.height：预览/群系采样与游戏落块看到同一条河
        //   （旧 RTF 河网已下线，雕刻改由水文模型统一提供）。
        if (riversEnabled) {
            applyHydrologyValley(cells, cx, cz);
        }
        long ts3 = System.nanoTime();

        // ★ 2026-09-14 性能诊断（用户"比几小时前慢"）：分段定位
        //   sample=地形采样 / extract=侵蚀tile提取(可能触发冷生成) / hydro=水文雕刻
        if ((ts3 - ts0) > 50_000_000L) {
            // ★ 2026-09-16：由 System.out.printf 改为 LOGGER —— 原先只打到 stdout，
            //   进不了 latest.log（dev 环境下 stdout 不落盘）⇒ 实机性能问题无法定位。
            //   改为日志后与既有的 [PERF] fillFromNoise 同源，可直接从 latest.log 读分段。
            //   （纯日志改动，零行为变更。）
            LOGGER.info("[PERF-TERRAIN] chunk({},{}): sample={}ms extract={}ms hydro={}ms total={}ms",
                    cx, cz, (ts1 - ts0) / 1000000, (ts2 - ts1) / 1000000,
                    (ts3 - ts2) / 1000000, (ts3 - ts0) / 1000000);
        }
        return cells;
    }

    /**
     * 水文雕刻回写：把 {@link HydrologyChunkEngine} 的雕刻计划写回 cell（高度/河型/
     * 水面/唇口/湖泊标记）。预览与游戏共用本实现（fillFromNoise 的 hydrology 分支
     * 也走 getChunkCells → 这里），保证预览 = 游戏。
     *
     * <p>高度采用<b>施加雕刻量</b>而非直接取 carvedGroundY：本方法的 cell 已过侵蚀 tile
     * （extractFromTile），而雕刻计划基于无侵蚀原始地形计算（管线顺序：河流在前、
     * 侵蚀在后），直接覆盖会丢失侵蚀细节；减去雕刻量（original−carved，恒 ≥0）
     * 可在保留侵蚀的同时刻出同一条河谷。</p>
     *
     * <p>★ 侵蚀交互原则（2026-09-09 方案 A 终版，取代 2026-09-08 的"河床全量跟随侵蚀"）：</p>
     * <ol>
     * <li><b>河床让位于河道（RTF 式软让步）</b>：河床 = carved + delta × mask，
     *     mask = {@link com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn#erosionMask()}
     *     ——河心（dist≤width）→ 0，谷外（dist≥valley）→ 1，[width, valley] 间 smoothstep。
     *     <b>根治图1 阶梯 / 图2 干滩</b>：旧版让河床全量跟随侵蚀 delta，但水面是"无侵蚀
     *     地形"上算出的计划单调剖面——二者恰好相差一个高频 delta 场，于是出现侵蚀刻画的
     *     非单调阶梯（图1）与侵蚀沉积顶穿水面的干沙洲（图2，还复活了 2026-09-06 已修的
     *     "floor 追平 → 零高水柱"）。河心 mask→0 后河床严格 = 计划 carved，与计划水面
     *     <b>同源自洽</b>，carver 的纵剖面单调与"至少 1 整块水柱"保证全部重新成立。
     *     用平滑过渡而非二值屏蔽，避免重蹈"均匀钳幅 ±0.5 → 岸坡 9.5 格垂直断面墙"
     *     的历史旧坑（这正是 FreeTerraForged 用软 riverMask 而非硬屏蔽的原因）。</li>
     * <li><b>水面保持雕刻计划水位</b>（不加 delta）：计划水面已过单调化/岸线 cap，
     *     保持它 → 河面平滑；且现在河床已回归计划 carved，二者不再脱节。</li>
     * <li><b>无钳幅</b>（2026-09-09 终修）：delta = rawDelta·mask，谷外缘（mask→1）恒等于
     *     隔壁原侵蚀地形，垂直墙在构造上不可能出现（旧 0.5 钳幅为历史残留，已删）。</li>
     * </ol>
     */
    private void applyHydrologyValley(Cell[] cells, int cx, int cz) {        HydrologyChunkResult result = hydrologyExperiment.calculate(cx, cz);
        double seaLevel = generator.seaLevel();

        // 河流绿洲输入：到最近河线的距离（与快速路径 fillRiverDistance 同一条件 → 预览 = 游戏）
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                fillRiverDistance(cells[lx * 16 + lz], toWu(cx * 16 + lx), toWu(cz * 16 + lz));
            }
        }
        // ★ 2026-09-17：湖【影响】登记（供 chunk 级【块精度】洪泛重判湖岸，见 LAKE_FINE_FLOOD）
        //   ⚠ 必须包含【被拒列】（carver 的 inFlood=false 早退：lakePlan=false 但带湖节点）
        //     —— 水体物理审计实测：452 个"干墙"格 100% 来自这一类列；
        //     只登记 lakePlan 列则本法【够不着】它们（首版 A/B 无效的直接原因）。
        java.util.List<LakeGroup> lakeGroups = new java.util.ArrayList<>();
        boolean[] lakeAny = new boolean[256];    // 受某湖影响的列（含被拒列）
        boolean[] lakeSeed = new boolean[256];   // 粗格已判出水 ⇒ 块级洪泛的种子
        int[] lakeOf = new int[256];             // 列 → lakeGroups 下标
        java.util.Arrays.fill(lakeOf, -1);
        int lakeCount = 0;
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            int lx = Math.floorMod(column.blockX(), 16);
            int lz = Math.floorMod(column.blockZ(), 16);
            Cell cell = cells[lx * 16 + lz];
            // ★ 湖列（2026-09-09 B1）：本 cell.height 已是【侵蚀后】地面（extractFromTile
            //   先跑），湖列不雕刻（carved=original）也不做河床侵蚀减法 —— 只判水：
            //   cell.height < spill − 0.5 → 出水。湖岸 = 侵蚀后地形与 spill 的等高线。
            //   侵蚀切深盆底 → 淹更多；侵蚀淤积垫高 → 湖岸内缩/该格变滩（物理正确）。
            //   这是湖"吃侵蚀后地形"的真正落点（carver 的 original 是无侵蚀基线，判不得）。
            if (column.lakePlan()) {
                // ★★★ 2026-09-17【M2】湖水位改用【雕刻后最终地形】上的逃逸高度 ★★★
                //
                // 【被修的缺陷（实机 + 路径剖面实证）】
                //   旧：水位 = erodedWaterLevel（在【侵蚀后、雕刻前】地形上求，且只取 rim 圈）。
                //   实测（块(-15,661)，湖心块(17,752)）：
                //     · 水位求解=166.627 —— 对"侵蚀后雕刻前"地形【正确】
                //       （两法互证：priority-flood filledAt 167.117、逃逸高度 167.539）
                //     · 但【雕刻】随后把山脊挖穿、开出新排水口：
                //       已雕刻地形上，湖心→违反点路径最高仅 162.150
                //     · 水位没有重算 ⇒ 水悬在"现已能排干"的河谷上方
                //       ⇒ 紧邻 1~2 块内有低 8.75 块的旱地却无水（21.6% 的水格如此）
                //
                // 【修法】在【已雕刻的最终地形】上重算逃逸高度（minimax escape height），
                //   并只降不升（不高于旧水位，避免引入新变形）。
                //   湖列【不雕刻】⇒ 湖水位不影响雕刻 ⇒ 无循环、可后算（单向化）。
                //   回退：LAKE_ESCAPE_LEVEL = false。
                double spill = column.waterSurfaceY();
                if (LAKE_ESCAPE_LEVEL && column.lakeNode() != null) {
                    // ★ 地形采样 = **雕刻后的点态最终地形**（carved + rawDelta×mask），
                    //   与真实放置口径一致（实测差 0.005 块）。这是修"水位求解早于雕刻"的关键：
                    //   旧实现用的是侵蚀后但仍未雕刻的地形（同一份输入 ⇒ 逃逸高度反而更高、min 后不变）。
                    // ⚠ 引擎必须复用（懒建一次）；此前写在逐列循环内 ⇒ 每列都新建，极浪费。
                    if (escapeEngine == null) {
                        escapeEngine = new HydrologyExperimentEngine(generator, 0L);
                    }
                    final HydrologyExperimentEngine engEsc = escapeEngine;
                    // ⚠ 入参 (a,b) 是 **wu**（escapeWaterLevel 用 wu）；块坐标 = wu × hs
                    final double hsEscape = generator.params().horizontalScale();
                    java.util.function.ToDoubleBiFunction<Double, Double> finalGroundFn = (a, b) -> {
                        int bxx = (int) Math.floor(a * hsEscape);
                        int bzz = (int) Math.floor(b * hsEscape);
                        double o = generator.sample(a, b).height;
                        HydrologyBlockCarvedColumn c =
                                HydrologyBlockCarver.carveColumnAt(engEsc, bxx, bzz, o, hsEscape);
                        if (c == null) return generator.sampleWu(a, b).height;
                        double raw = generator.sampleWu(a, b).height - c.originalGroundY();
                        return c.carvedGroundY() + raw * c.erosionMask();
                    };
                    double esc = column.lakeNode().escapeWaterLevel(finalGroundFn, 24.0, 6.0);
                    // ★ 临时诊断（取到结论后删）：确认逃逸高度是否真的被算出来、以及值是多少
                    if (LAKE_ESCAPE_DIAG && escapeDiagCount.getAndIncrement() < 10) {
                        LOGGER.info("[LAKE-ESC] block=({},{}) 旧spill={} 逃逸高度={} ⇒ 采用={}",
                                column.blockX(), column.blockZ(),
                                String.format("%.3f", column.waterSurfaceY()),
                                String.format("%.3f", esc),
                                String.format("%.3f", Double.isNaN(esc) ? column.waterSurfaceY()
                                        : Math.min(column.waterSurfaceY(), esc)));
                    }
                    if (!Double.isNaN(esc)) {
                        spill = Math.min(spill, esc);      // 只降不升
                    }
                }
                boolean flooded = cell.height < spill - 0.5;
                // 湖不挖地；水柱保护：落块水放 (floor(height), floor(spill)]，若
                // floor 相同则无水块 → 只在必需时把地面压到 floor(spill)-1 以下（<1 格）
                if (flooded && cell.height >= Math.floor(spill) - 1e-9) {
                    cell.height = Math.min(spill - 0.75, Math.floor(spill) - 1e-9);
                }
                cell.riverType = (byte) (flooded ? 1 : 0);
                cell.riverSurfaceY = spill;
                cell.riverLipY = spill;
                cell.isLake = flooded && spill >= seaLevel;
                cell.lakeMask = cell.isLake;
                // ★ 登记本列（湖列：粗格已判出水 ⇒ 块级洪泛的种子；水位优先采用它）
                int lgi = lakeGroupOf(lakeGroups, column.lakeNode(), spill);
                LakeGroup lg = lakeGroups.get(lgi);
                lg.level = spill;        // 湖列水位（可能已被 escape 压低）优先级最高
                lg.accepted = true;
                lakeAny[lx * 16 + lz] = true;
                lakeSeed[lx * 16 + lz] = flooded;
                lakeOf[lx * 16 + lz] = lgi;
                lakeCount++;
                continue;
            }
            double rawDelta = cell.height - column.originalGroundY(); // 本列侵蚀增量（全量）
            // ★ 方案 A：河心 mask→0（河床不吃侵蚀，回归计划 carved），谷外→1（全量侵蚀）
            double mask = erosionYieldToRiver ? column.erosionMask() : 1.0;
            // ★ 2026-09-09 终修：去掉 0.5 沉积钳幅（历史残留，用户实测截图"岸坡过渡
            //   结束边缘的垂直断面"）：min(rawDelta·mask, 0.5) 会在谷外缘（mask→1）把
            //   本应全额补回的侵蚀截成 +0.5，而隔壁未命中列吃全额 rawDelta →
            //   两者差 (rawDelta−0.5)，山地沉积 rawDelta 可达 +5 格 = 一堵贯穿山体的墙。
            //   河心 mask→0 已天然保护水面（不存在"顶破计划水面"），钳幅纯属副作用。
            //   去掉后：谷外缘 delta=rawDelta ⇒ 高度 = carved + rawDelta = original + rawDelta
            //   （carved→original）⇒ 数学恒等于隔壁"原侵蚀地形"，墙在构造上不可能出现。
            double delta = rawDelta * mask;
            cell.height -= column.erosion() + (rawDelta - delta);     // = carved + delta
            cell.riverType = (byte) (column.fillWater() ? 1 : 0);
            cell.riverSurfaceY = column.waterSurfaceY();               // 计划水位（不跟 delta）
            cell.riverLipY = column.lipSurfaceY();
            cell.isLake = column.fillWater() && column.waterSurfaceY() >= seaLevel;
            cell.lakeMask = cell.isLake;
            // ★ 2026-09-17：湖域内【被拒列】登记（carver 的 inFlood=false 早退）。
            //   本列行为与改前【完全一致】（仍不出水、riverSurfaceY 不变）：
            //   只是把"湖 + 水位"登记下来，交给 lakeFineFlood 做 1 块精度重判。
            if (!column.lakePlan() && column.lakeNode() != null) {
                int lgi = lakeGroupOf(lakeGroups, column.lakeNode(), column.lakeLevelY());
                LakeGroup lg = lakeGroups.get(lgi);
                if (!lg.accepted && !Double.isNaN(column.lakeLevelY())) {
                    lg.level = column.lakeLevelY();   // 湖列水位优先（它已含 escape 压低）
                }
                lakeAny[lx * 16 + lz] = true;
                lakeOf[lx * 16 + lz] = lgi;
                lakeCount++;
            }
        }
        // ★ 2026-09-17：湖岸 1 块精度精修（见 LAKE_FINE_FLOOD）
        if (LAKE_FINE_FLOOD && lakeCount > 0) {
            lakeFineFlood(cells, cx, cz, lakeAny, lakeSeed, lakeOf, lakeGroups);
        }
    }

    /**
     * ★ 2026-09-17：<b>湖岸 1 块精度精修</b>（开关 {@link #LAKE_FINE_FLOOD}）。
     *
     * <h4>被修的缺陷（水体物理审计实测）</h4>
     * <p>{@code runWaterPhysicsProbe} 在用户报告点实测（seed 5436529513624899584）：</p>
     * <pre>
     *   水体：水位 166.627  面积 26739 格  盆底 153.145
     *   【应有水位】159.540（±96wu 窗口）/ 165.498（稳定值，96~288 块）/ 167.706（真盆沿，384 块）
     *   ⇒ 水位 166.627 < 真盆沿 167.706 ⇒ **水位本身是对的**（短板生效）
     *   但【干墙 = 451 格】—— 与水相邻、低于水位、却是干的
     * </pre>
     * <p>⇒ 水边界既不是水位等高线、也不是地形，而是 <b>12wu 粗格洪泛区的边界</b>
     * （{@code computeFlood} 的 BFS 网格）⇒ 视觉上就是<b>垂直水墙 + 水不贴岸</b>。</p>
     *
     * <h4>修法</h4>
     * <p>以【本 chunk 内的湖列】（= 粗格洪泛内）为<b>种子</b>，在
     * {@code chunk + 32 块 pad} 的窗口内做 <b>1 块精度 4 邻洪泛</b>
     * （只通过 {@code 高度 < 水位 − 0.5} 的格）⇒ 水边界回归水位等高线。
     * ★ 覆盖范围 = <b>所有受该湖影响的列</b>（含 carver 因 {@code inFlood=false} 早退的
     * "被拒列"）—— 实测干墙格 100% 来自被拒列，只重判湖列是够不着的（首版 A/B 无效的教训）。
     * ★ 写入策略 = <b>只增不减</b>：粗格已判出水的列一律保留（避免出现空洞），
     * 只把"1 块精度洪泛连通得到"的列补成水；河列出水列（非湖种子）不抢。</p>
     *
     * <h4>为何是局部（而非全局）</h4>
     * <p>种子已由粗格洪泛给出（保证大范围正确），本步只做<b>边界精修</b>
     * ⇒ 窗口只需覆盖岸边，成本 O((16+32)²) ≈ 2.3k 格/chunk，可忽略。
     * pad 内的高度用<b>不带侵蚀</b>的廉价采样（{@code sample}）：它只参与连通性判断，
     * 而侵蚀 delta 为亚块级；<b>刻意不用 {@code sampleWu}</b>——那会为 pad 触发侵蚀 tile 生成。</p>
     */
    private void lakeFineFlood(Cell[] cells, int cx, int cz, boolean[] lakeAny,
                               boolean[] lakeSeed, int[] lakeOf, java.util.List<LakeGroup> groups) {
        final int pad = 16;          // 只做【岸边】精修 ⇒ 一个粗格（6wu/12wu）足够
        final int w = 16 + 2 * pad;
        double seaLevel = generator.seaLevel();
        boolean[] seen = new boolean[w * w];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        final int[] dxx = {1, -1, 0, 0};
        final int[] dzz = {0, 0, 1, -1};
        for (int gi = 0; gi < groups.size(); gi++) {
            LakeGroup g = groups.get(gi);
            double level = g.level;
            if (Double.isNaN(level)) continue;
            java.util.Arrays.fill(seen, false);
            q.clear();
            // ① 种子 = 本 chunk 内【粗格已判出水】的湖列（大范围正确性由粗格保证）
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = lx * 16 + lz;
                    if (!lakeSeed[i] || lakeOf[i] != gi) continue;
                    int k = (lz + pad) * w + (lx + pad);
                    if (!seen[k] && fineFloodWet(cells, cx, cz, pad, k % w, k / w, level)) {
                        seen[k] = true;
                        q.add(k);
                    }
                }
            }
            // ② 1 块精度 4 邻洪泛
            //    ★ 可通行性【惰性求值】：只对洪泛真正访问到的格算高度 ——
            //    若预生成全窗口掩码，每 chunk 要多算 (48²−16²)=2048 次 generator.sample()
            //    （实测 sample ≈ 10~15µs/格 ⇒ +20~30ms/chunk，且绝大多数是白算的）。
            while (!q.isEmpty()) {
                int cur = q.poll();
                int gx = cur % w, gz = cur / w;
                for (int d = 0; d < 4; d++) {
                    int nx = gx + dxx[d], nz = gz + dzz[d];
                    if (nx < 0 || nx >= w || nz < 0 || nz >= w) continue;
                    int ni = nz * w + nx;
                    if (seen[ni]) continue;
                    if (!fineFloodWet(cells, cx, cz, pad, nx, nz, level)) continue;
                    seen[ni] = true;
                    q.add(ni);
                }
            }
            // ④ 回写：本 chunk 内【受该湖影响】的列（含被拒列）—— 只增不减
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = lx * 16 + lz;
                    if (!lakeAny[i] || lakeOf[i] != gi) continue;
                    if (cells[i].riverType != 0 && !lakeSeed[i]) continue;   // 河列出水：不抢
                    if (!seen[(lz + pad) * w + (lx + pad)]) continue;        // 未连通 ⇒ 不动
                    Cell cell = cells[i];
                    if (cell.height >= Math.floor(level) - 1e-9) {
                        cell.height = Math.min(level - 0.75, Math.floor(level) - 1e-9);
                    }
                    cell.riverType = 1;
                    cell.riverSurfaceY = level;
                    cell.riverLipY = level;
                    cell.isLake = level >= seaLevel;
                    cell.lakeMask = cell.isLake;
                }
            }
        }
    }

    /**
     * 块级洪泛的【可通行】判据：该格是否低于水位 − 0.5。
     *
     * <p>本 chunk 用 {@code cell.height}（最终高度，含雕刻）；pad 用
     * {@code generator.sample()} 的廉价采样 —— 只参与<b>连通性</b>判断，
     * 而侵蚀 delta 为亚块级；<b>刻意不用 sampleWu</b>：那会为 pad 触发侵蚀 tile 生成。</p>
     */
    private boolean fineFloodWet(Cell[] cells, int cx, int cz, int pad,
                                 int gx, int gz, double level) {
        int lx = gx - pad, lz = gz - pad;
        double h;
        if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
            h = cells[lx * 16 + lz].height;
        } else {
            h = generator.sample(toWu(cx * 16 + lx), toWu(cz * 16 + lz)).height;
        }
        return h < level - 0.5;
    }

    /** 一个 chunk 内的湖分组（块级洪泛按【湖身份】分组，避免不同湖共用同一水位判据）。 */
    private static final class LakeGroup {
        final Object node;
        double level = Double.NaN;
        /** 是否已有"湖列水位"：湖列水位（已含 escape 压低）优先于被拒列的湖水位。 */
        boolean accepted;
        LakeGroup(Object node) { this.node = node; }
    }

    /**
     * 取（或新建）某湖在分组列表中的下标。
     * <p>节点非 null 时按【身份】匹配；节点为 null（理论上罕见）时按【水位】匹配。</p>
     */
    private static int lakeGroupOf(java.util.List<LakeGroup> groups, Object node, double level) {
        for (int i = 0; i < groups.size(); i++) {
            LakeGroup g = groups.get(i);
            if (node != null ? g.node == node
                    : (g.node == null && Math.abs(g.level - level) < 1e-6)) {
                return i;
            }
        }
        LakeGroup g = new LakeGroup(node);
        if (node == null) g.level = level;
        groups.add(g);
        return groups.size() - 1;
    }

    /**
     * 访问序（<b>真 LRU</b>）有界 Map —— 超容量即淘汰【最久未访问】条目（O(1)）。
     *
     * <p>★ 2026-09-11 P0-3：取代原「超限任意删 1/8」。原实现的问题不是"删太多"，
     * 而是<b>删错对象</b>——ConcurrentHashMap 无访问序，删掉的可能是刚生成的热点。</p>
     */
    private static final class LruMap<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final int maxSize;
        private final CacheStats stats;

        LruMap(int maxSize, CacheStats stats) {
            super(Math.max(16, maxSize + 1), 0.75f, true);   // accessOrder = true
            this.maxSize = maxSize;
            this.stats = stats;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            if (size() <= maxSize) return false;
            stats.evicted();
            return true;
        }
    }

    private static int chunkCoord(double world) {
        return (int) Math.floor(world / 16.0);
    }

    private static int localCoord(double world) {
        int v = (int) Math.floor(world) & 15;
        return v < 0 ? v + 16 : v;
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
