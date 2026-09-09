package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkEngine;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
import com.geogenesis.worldgen.noise.NoiseUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Map<Long, Cell[]> cache = new ConcurrentHashMap<>(CACHE_SIZE);

    private final boolean riversEnabled;
    /** 侵蚀向河道软让步（方案 A，config erosionYieldToRiver，默认 true）。 */
    private final boolean erosionYieldToRiver;
    private final HydrologyChunkEngine hydrologyExperiment;

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
        return generator.sample(toWu(wx), toWu(wz));
    }

    /**
     * 获取 chunk 内所有 Cell（用于 fillFromNoise 逐格遍历）。
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cache.get(key);
        if (cells == null) {
            cells = generateChunk(chunkX, chunkZ);
            Cell[] prev = cache.putIfAbsent(key, cells);
            if (prev != null) cells = prev;
        }
        pruneIfNeeded();
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
     * ★ 2026-08-09 优化：出生点周边异步预热（光追"先投浅路径"类比——把可能马上要用的
     *   tiles 提前在后台算好，玩家进入时缓存命中，冷启动观感丝滑）。
     *   只执行一次（AtomicBoolean），提交到 TILE_SAMPLER 后台池，不阻塞服务器线程。
     *   围绕 (0,0) 半径 3 → 7×7=49 chunk，覆盖 3×3 tiles 全量 + 1 圈边（含懒生成热点）。
     */
    public void preloadSpawnAsync() {
        if (!preloadSpawnScheduled.compareAndSet(false, true)) return;
        CellGenerator.TILE_SAMPLER.execute(() -> {
            try {
                preloadAround(0, 0, 3);
            } catch (Exception e) {
                LOGGER.warn("spawn preload failed", e);
            }
        });
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

        // 水文 + 侵蚀 tile 管线（wu 坐标定位 tile；extractFromTile 内部按块→wu 插值读取）
        generator.extractFromTile(cells, cx, cz);

        // ★ 水文河谷雕刻回写 cell.height：预览/群系采样与游戏落块看到同一条河
        //   （旧 RTF 河网已下线，雕刻改由水文模型统一提供）。
        if (riversEnabled) {
            applyHydrologyValley(cells, cx, cz);
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
        HydrologyChunkResult result = hydrologyExperiment.calculate(cx, cz);
        double seaLevel = generator.seaLevel();
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
                double spill = column.waterSurfaceY();
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
            cell.isLake = column.fillWater() && column.waterSurfaceY() >= seaLevel;
            cell.lakeMask = cell.isLake;
        }
    }

    /** 简单 LRU 淘汰 */
    private void pruneIfNeeded() {
        if (cache.size() > CACHE_SIZE) {
            var it = cache.keySet().iterator();
            int toRemove = Math.max(1, cache.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
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
