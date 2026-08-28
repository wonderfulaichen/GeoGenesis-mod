package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkEngine;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
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
        try {
            riversEnabled = GeoGenesisConfig.INSTANCE.riverEnabled.get();
        } catch (IllegalStateException e) {
            // 预览进程：配置未加载，保持默认值
        }
        this.riversEnabled = riversEnabled;
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
     * 水面/湖泊标记），使预览与游戏的河流完全一致。
     *
     * <p>高度采用<b>施加雕刻量</b>而非直接取 carvedGroundY：本方法的 cell 已过侵蚀 tile
     * （extractFromTile），而雕刻计划基于原始地形计算，直接覆盖会丢失侵蚀细节；
     * 减去雕刻量（original−carved，恒 ≥0）可在保留侵蚀的同时刻出同一条河谷。</p>
     */
    private void applyHydrologyValley(Cell[] cells, int cx, int cz) {
        HydrologyChunkResult result = hydrologyExperiment.calculate(cx, cz);
        double seaLevel = generator.seaLevel();
        for (HydrologyBlockCarvedColumn column : result.carvedColumns()) {
            int lx = Math.floorMod(column.blockX(), 16);
            int lz = Math.floorMod(column.blockZ(), 16);
            Cell cell = cells[lx * 16 + lz];
            cell.height -= column.erosion();
            cell.riverType = (byte) (column.fillWater() ? 1 : 0);
            cell.riverSurfaceY = column.waterSurfaceY();
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
