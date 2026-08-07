package com.geogenesis.worldgen.terrain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地形引擎对外接口：带缓存的 Cell 网格采样。
 *
 * 跨 chunk 共享，无 tile 边界断裂。
 * 缓存策略：按 chunk 网格（16×16）缓存 Cell 数组，LRU-like（ConcurrentHashMap + 简单上限）。
 */
public final class GeoGenesisTerrain {

    private static final int CACHE_SIZE = 4096;
    private static final int CHUNK_SHIFT = 4; // 16 blocks per chunk

    private final CellGenerator generator;
    private final HeightCurve curve;
    /** 渲染层 cache：chunk key → Cell[256]。游戏内全分辨率（stride=1）写入此层。 */
    private final Map<Long, Cell[]> cache = new ConcurrentHashMap<>(CACHE_SIZE);
    /** 渲染层每 chunk 的采样 stride（越小越精细；预览用）。 */
    private final Map<Long, Integer> cacheStrides = new ConcurrentHashMap<>(CACHE_SIZE);

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
    }

    /** 播种所有噪声节点（每个世界种子调用一次） */
    public void seed(long worldSeed) {
        generator.seed(worldSeed);
        cache.clear();
        cacheStrides.clear();
    }

    /** 海平面 Y */
    public double seaLevel() { return generator.seaLevel(); }

    /** HeightCurve 暴露（给 Generator 做 eFromHeightF） */
    public HeightCurve heightCurve() { return generator.heightCurve(); }

    /**
     * 采样世界高度。
     * @param wx 世界 X 坐标
     * @param wz 世界 Z 坐标
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
     * 获取 chunk 内所有 Cell（用于 fillFromNoise 逐格遍历）。
     *
     * <p>2026-08-03 死锁修复（回退版本重放）：原 computeIfAbsent 的 mapping（generateChunk）
     * 内部会嵌套 CellGenerator.getErosionTile 的 computeIfAbsent（另一个 ConcurrentHashMap）——
     * 26 个 Worker 线程并发时偶发死锁（spawn 准备阶段卡死实锤）。改 get + putIfAbsent：
     * 并发时可能重复生成同 chunk（确定性结果相同），putIfAbsent 只留一个。</p>
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cache.get(key);
        if (cells == null) {
            cells = generateChunk(chunkX, chunkZ);
            Cell[] prev = cache.putIfAbsent(key, cells);
            if (prev != null) cells = prev; // 并发者胜出，丢弃自己的
            cacheStrides.put(key, 1); // 全分辨率
        }
        pruneIfNeeded();
        return cells;
    }

    /**
     * 按指定 stride 获取 chunk 内 Cell 数据（预览优化入口）。
     * <ul>
     *   <li>stride=1：等价于 getChunkCells(cx, cz)，全分辨率含侵蚀。</li>
     *   <li>stride≥2：按 (16/stride)² 采样点展开填充 Cell[256]，跳过侵蚀 tile（太贵）。</li>
     *   <li>缓存命中条件：已有 stride ≤ 请求 stride（已有数据够细）。</li>
     *   <li>更细请求（stride 更小）→ 覆盖写入。</li>
     * </ul>
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ, int stride) {
        if (stride <= 1) return getChunkCells(chunkX, chunkZ);
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cache.get(key);
        Integer haveStride = cacheStrides.get(key);
        if (cells != null && haveStride != null && haveStride <= stride) {
            return cells; // 已有够细的数据
        }
        // 按 stride 生成
        cells = generateChunk(chunkX, chunkZ, stride);
        cache.put(key, cells);
        cacheStrides.put(key, stride);
        pruneIfNeeded();
        return cells;
    }

    /** 预载周边 chunk（可选，减少首次采样冻帧） */
    public void preloadAround(int centerCX, int centerCZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                getChunkCells(centerCX + dx, centerCZ + dz);
            }
        }
    }

    /** 旧 API 兼容：按 block 网格返回 Cell 二维数组（cx/cz 为 block 索引）。
     * 支持中断：线程被 interrupt() 时返回 null（后台计算可被新请求快速取代，避免卡死）。 */
    public Cell[][] getRegionCells(int originBlockX, int originBlockZ, int cellCountX, int cellCountZ) {
        Cell[][] region = new Cell[cellCountX][cellCountZ];
        for (int bx = 0; bx < cellCountX; bx++) {
            for (int bz = 0; bz < cellCountZ; bz++) {
                if (Thread.interrupted()) return null; // 线程被中断（新请求已到达），放弃剩余计算
                region[bx][bz] = sampleCell(originBlockX + bx, originBlockZ + bz);
            }
        }
        return region;
    }

    // === 内部 ===

    private Cell[] generateChunk(int cx, int cz) {
        Cell[] cells = new Cell[16 * 16];
        int baseX = cx << CHUNK_SHIFT;
        int baseZ = cz << CHUNK_SHIFT;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = generator.sample(
                    baseX + lx, baseZ + lz);
            }
        }

        // 水文 + 侵蚀 tile 管线：80×80 共享 cache（含 border 重叠）→ 提取 16×16 填 cell。
        // 仅做地形场改写，不进入 sample 纯函数（保持每格确定性）。
        float[][] tile = generator.getErosionTile(cx, cz);
        generator.extractFromTile(tile, cells, cx, cz);

        return cells;
    }

    /**
     * 按 stride 生成 chunk Cell[256]（预览低分辨率路径）。
     * 只采样 (16/stride)² 个点，每个展开到 stride×stride 区域。
     * 跳过侵蚀 tile 管线（低分辨率下视觉差异可忽略，成本过高）。
     */
    private Cell[] generateChunk(int cx, int cz, int stride) {
        Cell[] cells = new Cell[16 * 16];
        int baseX = cx << CHUNK_SHIFT;
        int baseZ = cz << CHUNK_SHIFT;
        stride = Math.max(1, Math.min(16, stride));
        int step = stride;
        for (int lz = 0; lz < 16; lz += step) {
            for (int lx = 0; lx < 16; lx += step) {
                Cell c = generator.sample(baseX + lx, baseZ + lz);
                int endX = Math.min(lx + step, 16);
                int endZ = Math.min(lz + step, 16);
                for (int ex = lx; ex < endX; ex++) {
                    for (int ez = lz; ez < endZ; ez++) {
                        cells[ez * 16 + ex] = copyCell(c);
                    }
                }
            }
        }
        return cells;
    }

    /** 轻量 Cell 副本（防止下游代码修改共享引用） */
    private static Cell copyCell(Cell src) {
        Cell c = new Cell();
        c.height = src.height;
        c.continent = src.continent;
        c.e = src.e;
        c.eOcean = src.eOcean;
        c.blendCont = src.blendCont;
        c.eLand = src.eLand;
        c.oceanFeat = src.oceanFeat;
        c.landFeat = src.landFeat;
        c.terrainType = src.terrainType;
        c.typeWeights = src.typeWeights;  // 数组只读共享（下游不修改）
        c.coastCoord = src.coastCoord;
        c.climate = src.climate;
        c.temperature = src.temperature;
        c.humidity = src.humidity;
        c.continentNoise = src.continentNoise;
        c.isRiver = src.isRiver;
        c.riverWetness = src.riverWetness;
        c.isLake = src.isLake;
        c.riverMask = src.riverMask;
        c.lakeMask = src.lakeMask;
        c.riverDistance = src.riverDistance;
        c.riverIsWaterfall = src.riverIsWaterfall;
        c.riverSourceType = src.riverSourceType;
        c.riverFloorY = src.riverFloorY;
        c.riverSurfaceY = src.riverSurfaceY;
        c.erosionMask = src.erosionMask;
        c.riverNetDist = src.riverNetDist;
        c.shape = src.shape;
        c.isSnow = src.isSnow;
        return c;
    }

    /** 简单 LRU 淘汰（在 computeIfAbsent 外部调用，避免 ConcurrentHashMap 死锁）。
     *  淘汰 1/8（而非 1/2）以保留最近生成的大区域 chunk，让 QUARTER→FULL 跨相位共享 chunk。 */
    private void pruneIfNeeded() {
        if (cache.size() > CACHE_SIZE) {
            var it = cache.keySet().iterator();
            int toRemove = Math.max(1, cache.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                long k = it.next();
                it.remove();
                cacheStrides.remove(k);
            }
        }
    }

    private static int chunkCoord(double world) {
        return (int) Math.floor(world / 16.0);
    }

    private static int localCoord(double world) {
        int v = (int) Math.floor(world) & 15;
        return v < 0 ? v + 16 : v; // 处理负坐标
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
