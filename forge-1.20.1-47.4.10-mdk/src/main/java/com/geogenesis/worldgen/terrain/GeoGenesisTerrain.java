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

    // ★ 2026-09-30【LAKE_ESCAPE_LEVEL / LAKE_FINE_FLOOD 两个开关已随旧链删除】
    //   LAKE_ESCAPE_LEVEL："在雕刻后最终地形上重算湖水位"的逐列逃逸求解。
    //     其唯一使用点依赖 `column.lakeNode()`（唯一管线化后恒为 null）⇒ 恒不执行。
    //     历史结论（当时有效）：水位本身是对的（短板生效）；真正的主因是 computeFlood
    //     的 BFS 网格粒度（12wu = 24 块）把水边界量化成直角，且"水位与雕刻必须同层级"
    //     的结构约束意味着修法必须换架构 —— 这正是本轮重构的动机。
    //   LAKE_FINE_FLOOD：湖岸 1 块精度精修（lakeFineFlood 一族，约 238 行）。
    //     历史使命 = 补 12wu 粗格的 451 个"干墙"格；2026-09-23 掩码升到 1 块后，
    //     其"局部连通即出水"变成掩码外灌水（用户圈出的"坡地小水斑"）⇒ 置 false 后删除。
    //   两者在 `sim/` 新核心（HydroTileTopology 直接按 block 求解盆地与最低溢口）下均无必要。


    // ===== ★★★ 2026-09-19（P2 判别测量）湖面不平的【分层归因】埋点 ★★★
    //
    //  【要回答的问题】同一个湖的各列，最终水位为什么不同（J1 实测纯湖 σ>0）？只可能是三层之一：
    //    ① `column.waterSurfaceY()`（carver 回传的 spill）本就逐列不同
    //       ⇒ 根因在 RiverLineNetwork 的湖命中水位；
    //    ② spill 相同，但本类的逐列 `escapeWaterLevel` 给出不同结果
    //       ⇒ 根因在【逐列求解】本身（正解：每湖只求一次并缓存）；
    //    ③ 两者都相同却仍不平 ⇒ 根因在落块或精修洪泛的某一层。
    //
    //  ⚠ 为什么必须先测再改：P2 已两次盲改"选湖规则"（RiverLineNetwork 内 / HydrologyBlockCarver 内），
    //    且两次都与其它改动叠加 ⇒ 结论被污染、不成立。**未拿到本埋点结果前不得再改。**
    //
    //  ★★★ 已测出结论（2026-09-19，seed 9139912035078620160 @ 块(-377,-335)±128）★★★
    //   实测输出：
    //     [LAKE-SPLIT] 已处理 25000 列，共 1 个湖：
    //       湖1423768154 列数=5000  spill原值∈[169.331,169.331] 跨度=0.000
    //                                | 最终水位∈[169.331,169.331] 跨度=0.000
    //   ⇒ ①真湖的 spill 与最终水位【都完全恒定】⇒ **湖面是平的**；
    //   ⇒ ②25000 列里【只有 1 个湖】⇒ 那个"σ=0.912 的 573 格纯湖"**根本没走湖分支**。
    //
    //   ★ 结论：**"湖面不平"是【假缺陷】** —— 由 runLakeLevelFlatnessProbe 的 J1 判据错误造成。
    //     该判据用 `cell.isLake` 筛"纯湖"，但 `isLake` 在【三处】被赋值：
    //       :685 湖分支 / :713 【河分支也设】/ :857 【精修洪泛也设】
    //     ⇒ `isLake=true` 只表示"水位 ≥ 海平面"，**不表示这是湖**；河列同样为 true。
    //     ⇒ 那个 σ>0 的"纯湖"极可能是一条【按流向正常下降的河】（σ>0 对河物理正确）。
    //   ⇒ 连带影响：P2（水位收敛）与 P3（湖语义）的**前提被推翻**，不必再为它动刀。
    //   （对应埋点代码已于 2026-09-30 清理：LAKE_LEVEL_SPLIT_DIAG + lakeLevelSplitDiag）

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

    /**
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
        // ⚠ 2026-09-29【此处曾有 fillRiverDistance，已移除 —— 别加回来】：
        //   快速路径的红线是"零 tile 生成"（本方法 javadoc：世界创建 9 分钟 → 秒级）。
        //   绿洲距离改走新核心后，这里现场计算会 resolve 新核心 tile ⇒
        //   erodedHeightForRouting 同步生成侵蚀 tile ⇒ ① 结构 ring 扫描（BiomeSource
        //   多线程）触发它 = P0-1"结构阶段高频调用→卡死"重演；② 与 chunk 生成线程
        //   并发写 solver 缓存 ⇒ 实测 CME 崩溃（crash-2026-09-29_23.52.55，栈顶
        //   HydroWorldSolver.field）。绿洲只在【落块完整管线】(applyHydrologyValley)
        //   判定；快速路径样例 cell 的 riverDistance 保持 ∞（无绿洲）——
        //   出生点搜索/结构扫描在零星绿洲斑块上看到的 biome 与落块差一个
        //   DESERT→SAVANNA 斑块（可接受近似，与"不含水文雕刻"同级，见上方残余注释）。
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
        // ★ 2026-09-29【创建世界卡死修复】：原实现 hydrologyExperiment.riverNetwork()
        //   .distanceToWater(...) 会在每个沙漠格触发【旧链 region 构建】（唯一管线化后
        //   单 region 0.9~16.7s）+ 湖水位 sampleWu 同步生成远端侵蚀 tile
        //   （日志实测 605 个、0.6~1.1s/个、坐标随 region 铺到 ±10224wu）⇒ 创建卡死十分钟级。
        //   改走新核心：命中与雕刻同一个 solver/tile 缓存（本 chunk 的 calculate 必先跑）⇒
        //   边际成本≈0，且绿洲看到的水 = 雕刻刻出来的水（单一事实来源）。
        cell.riverDistance = hydrologyExperiment.distanceToWaterWu(wuX, wuZ);
    }

    /**
     * 获取 chunk 内所有 Cell（用于 fillFromNoise 逐格遍历）。
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cachedChunk(chunkX, chunkZ);
        if (cells == null) {
            // ★★ 2026-09-19 修复（缓存污染）：<b>中断中产出的块【绝不入缓存】</b>。
            //
            //   为什么必须拦：线程中断位已置时，CellGenerator 的取 tile 入口会直接
            //   返回 null（见中断重试风暴修复）⇒ 本块拿不到任何侵蚀增量 ⇒
            //   它是一个【降级结果】（delta=0，看起来是"未被侵蚀"的平地形）。
            //   若写进共享 chunk 缓存，后续正常请求会一直拿到这个降级块
            //   ⇒ 预览出现"拖动后该区域变成平原"的伪影，且不会自愈。
            //
            //   为什么现在才成为问题：修复前该路径要卡 14~100 秒（难得一见），
            //   修复后是【瞬时】完成 ⇒ 发生频率高得多。
            //
            //   ⚠ 只影响"线程已被中断"这一种情况；生产 Worker-Main 线程不会被
            //     这样中断 ⇒ 对游戏地形零影响（仅多算一次）。
            //   ⚠ 返回值照常给出（调用方仍可显示），只是【不再落缓存】。
            if (Thread.currentThread().isInterrupted()) {
                return generateChunk(chunkX, chunkZ);
            }
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
        // ★ 2026-09-18 诊断：本线程 CPU 起点 —— 与末尾配合算"停顿"（墙钟 − 本线程CPU），
        //   用于区分「真在算 108 秒」与「根本没被调度 108 秒」（见 Stage.STALL）。
        //   诊断关闭时 threadCpuNow() 返回 0 ⇒ recordStall 静默跳过 ⇒ 零开销。
        long cpu0 = com.geogenesis.diagnostics.WorldGenProfiler.threadCpuNow();
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

        // ★ 2026-09-18 诊断：停顿 = 墙钟 − 本线程 CPU（≥1ms 才记）。
        //   结果≈0 ⇒ 线程全程占 CPU（在算）；结果≈墙钟 ⇒ 基本没被调度（GC/换页/调度）。
        long cpu1 = com.geogenesis.diagnostics.WorldGenProfiler.threadCpuNow();
        if (cpu0 != 0L && cpu1 != 0L) {
            com.geogenesis.diagnostics.WorldGenProfiler.recordStall(ts3 - ts0, cpu1 - cpu0);
        }

        // ★ 2026-09-18 全流程诊断：无条件记账（不再受 50ms 阈值门控 ⇒ 可统计分布）
        //   关闭时零开销（WorldGenProfiler.begin/record 内部只做一次 volatile 读）
        com.geogenesis.diagnostics.WorldGenProfiler.record(
                com.geogenesis.diagnostics.WorldGenProfiler.Stage.SAMPLE, ts1 - ts0);
        com.geogenesis.diagnostics.WorldGenProfiler.record(
                com.geogenesis.diagnostics.WorldGenProfiler.Stage.EXTRACT, ts2 - ts1);
        com.geogenesis.diagnostics.WorldGenProfiler.record(
                com.geogenesis.diagnostics.WorldGenProfiler.Stage.HYDRO, ts3 - ts2);
        // ★ 说明：此处【不再】重复记 SAMPLE/EXTRACT/HYDRO 之外的阶段——
        //   PLACE / DECORATE / CARVE / ENSURE 由 GeoGenesisGenerator 侧记账
        //   （它们发生在 getChunkCells 之外）。

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
    private void applyHydrologyValley(Cell[] cells, int cx, int cz) {
        // ★ 2026-09-19 诊断：水文雕刻子项 ① —— 计算（河网 region 懒建 / FlowField / 雕刻计划）
        long hy0 = com.geogenesis.diagnostics.WorldGenProfiler.begin();
        HydrologyChunkResult result = hydrologyExperiment.calculate(cx, cz);
        com.geogenesis.diagnostics.WorldGenProfiler.end(
                com.geogenesis.diagnostics.WorldGenProfiler.Stage.HYCALC, hy0);
        double seaLevel = generator.seaLevel();

        // 河流绿洲输入：到最近河线的距离（与快速路径 fillRiverDistance 同一条件 → 预览 = 游戏）
        // ★ 2026-09-19 诊断：水文雕刻子项 ② —— 逐格河距（仅沙漠格；可触发河网 region 构建）
        long hy1 = com.geogenesis.diagnostics.WorldGenProfiler.begin();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                fillRiverDistance(cells[lx * 16 + lz], toWu(cx * 16 + lx), toWu(cz * 16 + lz));
            }
        }
        com.geogenesis.diagnostics.WorldGenProfiler.end(
                com.geogenesis.diagnostics.WorldGenProfiler.Stage.HYDIST, hy1);
        // ★ 2026-09-30【已删除"湖岸 1 块精度精修"机制 —— 结论留痕，勿再重加】
        //   原机制（LAKE_FINE_FLOOD + lakeFineFlood / fineFloodWet / lakeGroupOf / LakeGroup，
        //   约 350 行）：用块级 4 邻 BFS 在粗格判水列上重判湖岸，修 6wu 粗格的 452 个干墙格。
        //   停用原因（2026-09-23）：P2-2 把连通性换成 1 块分辨率掩码后，"被拒列" = 掩码外
        //   = 本就不该出水的列 ⇒ 本机制的"局部连通即出水"反而变成【掩码外灌水】
        //   （实测：坡地孤斑簇距母湖 88~102wu、带母湖水位、全部不在掩码内 ⇒ 正是用户
        //   圈出的"坡地小水斑"）。开关置 false 后本机制整体不可达，故随旧链一并删除。
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
                // ★ 2026-09-30【已删除的 LAKE_ESCAPE_LEVEL 逐列逃逸重算 —— 结论留痕，勿再重加】
                //   原逻辑：在【已雕刻最终地形】上重算 minimax 逃逸高度、只降不升。
                //   它依赖 `column.lakeNode()`，而唯一管线化后该字段恒为 null
                //   ⇒ 整块（原 L651~L680）从未执行。
                //   历史结论（当时有效，现由新核心承担）：水位求解早于雕刻会留下"悬空水板"；
                //   正解 = 在雕刻后地形上重算逃逸高度，且必须用【侵蚀后、雕刻前】的采样器地形
                //   （点态雕刻后地形会让湖内出现环状缺格：534 → 38571）。
                //   现在 `sim/HydroTileBalance` 在核心内一次性求解盆地水位，无此阶段错位。
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
            // ★★★ 2026-09-30【isLake 语义修正 —— 修"整片水面都被当湖"】★★★
            //   旧式：`fillWater && waterSurfaceY >= seaLevel` ⇒ 任何水面高于海平面的
            //   【河】列都被标成湖（实测：某窗口 riverType≠0 共 7739 格，河只有 4 格，
            //   其余全被算作"湖"）⇒ 预览/落块把整条河渲染成湖面，用户观感"到处是河/湖"。
            //   正解：河与湖由【车床分支】区分（column.lakePlan()：湖分支 true，河分支 false），
            //   而不是靠"水位是否高于海平面"。
            cell.isLake = column.fillWater() && column.lakePlan();
            cell.lakeMask = cell.isLake;
        }
    }

    // ★ 2026-09-30【湖岸 1 块精度精修（lakeFineFlood 一族）已随旧链删除】
    //   原实现约 238 行：以粗格湖列为种子，在 chunk+32 块 pad 内做 1 块精度 4 邻洪泛，
    //   把水边界从 12wu 粗格边界拉回水位等高线（修 451 个"干墙"格）。
    //   删除理由：2026-09-23 把连通性换成 1 块分辨率掩码后，"被拒列"= 掩码外 =
    //   本就不该出水的列 ⇒ 本机制的"局部连通即出水"变成掩码外灌水（实测坡地孤斑簇
    //   距母湖 88~102wu、带母湖水位、全部不在掩码内）。开关 LAKE_FINE_FLOOD 早已置 false。
    //   替代：`sim/HydroTileTopology` 的盆地在 block 分辨率上直接求解，无需二次精修。

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
