package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.river.RiverNetwork;
import com.geogenesis.worldgen.river.RiverSample;
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

    // ===== 确定性河网（Phase 2：sampleRiver 纯函数雕刻采样） =====
    private final RiverNetwork rivers;
    private final boolean riversEnabled;

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
        // ★ 2026-08-16 预览进程兜底：Swing 预览（TerrainPreview）无 Forge 配置
        //   环境，GeoGenesisConfig.INSTANCE 的 spec 未加载 → 任何 .get() 抛
        //   IllegalStateException（实锤：runPreview 崩溃堆栈 at :42）。整体
        //   try-catch 兜底 → 默认值（对齐 CellGenerator 的 cfg 空保护惯例）。
        double hs = generator.params().horizontalScale();
        int gridSpacing = 4;
        double riverWidthWu = 8.0 / (hs > 0.01 ? hs : 1.0);
        double riverDepth = 6.0;
        double riverMinFall = 3.0;
        boolean riversEnabled = true;
        try {
            GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
            riversEnabled = cfg.riverEnabled.get();
            gridSpacing = (int) Math.max(2, cfg.riverGridSpacing.get());
            riverWidthWu = cfg.riverWidth.get() / (hs > 0.01 ? hs : 1.0);
            riverDepth = cfg.riverDepth.get();
            riverMinFall = cfg.riverMinFall.get();
        } catch (IllegalStateException e) {
            // 预览进程：配置未加载，保持默认值
        }
        this.riversEnabled = riversEnabled;
        // 配置语义 = 块 → ÷HS 转 wu（宽度）；深度/落差为 Y 块（无水平缩放）；间距 = wu
        // ★ discharge 场采样传入：液滴流量累积 → 真物理水流方向（寻路用）
        this.rivers = new RiverNetwork(
            generator::terrainEQuick, curve,
            gridSpacing, riverWidthWu, riverDepth,
            0.6, 0.5, riverMinFall,
            hs,
            generator::sampleDischargeCached);
        // 回注 CellGenerator：RIVER_NETWORK 流量图层数据源（河网需要 terrainEQuick → 延迟注入）
        generator.setRivers(rivers);
        // ★ 2026-08-14 启动诊断：确认游戏内河网 + discharge 是否启用（用户"跑新版没变化"排查）
        LOGGER.info("[RIVER] terrain init: riversEnabled={} hs={}",
            riversEnabled, hs);
    }

    /** 播种所有噪声节点（每个世界种子调用一次） */
    public void seed(long worldSeed) {
        generator.seed(worldSeed);
        cache.clear();
        // ★ R13c：河网每世界不同（DW WORLD_SEED 语义——否则所有世界同一河网）
        rivers.setWorldSeed(worldSeed);
    }

    /** 海平面 Y */
    public double seaLevel() { return generator.seaLevel(); }

    /** HeightCurve 暴露（给 Generator 做 eFromHeightF） */
    public HeightCurve heightCurve() { return generator.heightCurve(); }

    /** 河流开关（配置 riverEnabled） */
    public boolean riversEnabled() { return riversEnabled; }

    /** 纯函数河流采样（入参 = wu 坐标；块坐标需先 ÷horizontalScale） */
    public RiverSample sampleRiver(double wx, double wz) {
        return rivers.sampleRiver(wx, wz);
    }

    /** 纯函数河流采样（MC 块坐标 → 内部 wu 换算，generateChunk 用） */
    public RiverSample sampleRiverAtBlock(double blockX, double blockZ) {
        return rivers.sampleRiver(toWu(blockX), toWu(blockZ));
    }

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
        // ★ 2026-08-17 卡死修复：后台预热周边 REGION 段（玩家移动方向提前生成，
        //   Server thread 首次 buildPlate 命中缓存 → 保存/退出不冻结）。幂等，
        //   每次 chunk 生成调用开销 O(1)（warming set 去重）。
        if (riversEnabled) {
            rivers.warmRegionsAround(toWu(chunkX << 4), toWu(chunkZ << 4), 2);
        }
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
        // ★ 2026-08-17 卡死修复：出生点周边 REGION 段后台预热（首 chunk 不冻结）
        if (riversEnabled) rivers.warmRegionsAround(0, 0, 2);
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

        return cells;
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
