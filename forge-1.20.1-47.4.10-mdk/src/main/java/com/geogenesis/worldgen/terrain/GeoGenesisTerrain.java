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
    private final Map<Long, Cell[]> cache = new ConcurrentHashMap<>(CACHE_SIZE);
    /** 每 chunk 的采样 stride（1=全分辨率；4=每 4 块 1 采样；16=单点）。预览低分辨率请求用。 */
    private final Map<Long, Integer> strides = new ConcurrentHashMap<>(CACHE_SIZE);

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
    }

    /** 播种所有噪声节点（每个世界种子调用一次） */
    public void seed(long worldSeed) {
        generator.seed(worldSeed);
        cache.clear();
        strides.clear();
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
     * 获取 chunk 内所有 Cell（全分辨率，用于 fillFromNoise 逐格遍历）。
     * 等价于 getChunkCells(chunkX, chunkZ, 1)。
     *
     * <p>2026-08-03 死锁修复（回退版本重放）：原 computeIfAbsent 的 mapping（generateChunk）
     * 内部会嵌套 CellGenerator.getErosionTile 的 computeIfAbsent（另一个 ConcurrentHashMap）——
     * 26 个 Worker 线程并发时偶发死锁（spawn 准备阶段卡死实锤）。改 get + putIfAbsent：
     * 并发时可能重复生成同 chunk（确定性结果相同），putIfAbsent 只留一个。</p>
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        return getChunkCells(chunkX, chunkZ, 1);
    }

    /**
     * 获取 chunk 内 Cell（按所需 stride 采样）。
     * <ul>
     *   <li>缓存已有更细数据（stride ≤ required）→ 直接复用（更细可当粗用）</li>
     *   <li>缓存无 / 更粗 → 按 required stride 重新生成覆盖（确定性结果相同，put 覆盖无妨）</li>
     * </ul>
     * 预览低分辨率（stride=4/8/16）只采样 (16/stride)² 个点，非采样格浅拷贝最近采样点 →
     * 单 chunk 成本从 256 次 sample 降到 (16/stride)² 次（1:16 视图 = 1 次，256 倍加速）。
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ, int stride) {
        int s = Math.max(1, Math.min(16, stride));
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cache.get(key);
        Integer have = strides.get(key);
        if (cells == null || have == null || have > s) {
            cells = generateChunk(chunkX, chunkZ, s);
            cache.put(key, cells);
            strides.put(key, s);
        }
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

    /**
     * 生成 chunk 的 256 格 Cell 数组（含侵蚀/河流改写）。
     * stride=1：每格独立采样（游戏全分辨率）。stride>1：仅采样点处独立采样，
     * 采样点块内其余格浅拷贝（预览低分辨率 → 256 次 sample 降至 (16/stride)² 次）。
     */
    private Cell[] generateChunk(int cx, int cz, int stride) {
        Cell[] cells = new Cell[16 * 16];
        int baseX = cx << CHUNK_SHIFT;
        int baseZ = cz << CHUNK_SHIFT;
        int step = Math.max(1, Math.min(16, stride));
        for (int lz = 0; lz < 16; lz += step) {
            for (int lx = 0; lx < 16; lx += step) {
                Cell sampled = generator.sample(baseX + lx, baseZ + lz);
                int endX = Math.min(lx + step, 16);
                int endZ = Math.min(lz + step, 16);
                for (int ex = lx; ex < endX; ex++) {
                    for (int ez = lz; ez < endZ; ez++) {
                        cells[ex * 16 + ez] = sampled;
                    }
                }
            }
        }
        // 采样点共享同一 Cell 引用，extractFromTile 原地修改会互相污染 → 先展开浅拷贝
        if (step > 1) expandCopies(cells, step);

        // 水文 + 侵蚀 tile 管线：共享 cache（含 border 重叠）→ 提取 16×16 填 cell。
        // 仅做地形场改写，不进入 sample 纯函数（保持每格确定性）。
        float[][] tile = generator.getErosionTile(cx, cz);
        generator.extractFromTile(tile, cells, cx, cz);

        return cells;
    }

    /** 把采样点块的浅拷贝展开到块内所有格（extractFromTile 每格独立应用 delta，禁止共享引用）。 */
    private static void expandCopies(Cell[] cells, int step) {
        for (int lz = 0; lz < 16; lz += step) {
            for (int lx = 0; lx < 16; lx += step) {
                Cell src = cells[lx * 16 + lz];
                int endX = Math.min(lx + step, 16);
                int endZ = Math.min(lz + step, 16);
                for (int ex = lx; ex < endX; ex++) {
                    for (int ez = lz; ez < endZ; ez++) {
                        if (ex != lx || ez != lz) {
                            cells[ex * 16 + ez] = copyCell(src);
                        }
                    }
                }
            }
        }
    }

    /** Cell 浅拷贝（Cell 无嵌套可变结构，字段直接复制即可）。 */
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
        c.typeWeights = src.typeWeights;
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
        c.riverNetDischarge = src.riverNetDischarge;
        c.riverNetOverflow = src.riverNetOverflow;
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
                it.next();
                it.remove();
            }
            // strides 同步裁剪（只保留仍在 cache 的 key）
            strides.keySet().removeIf(k -> !cache.containsKey(k));
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
