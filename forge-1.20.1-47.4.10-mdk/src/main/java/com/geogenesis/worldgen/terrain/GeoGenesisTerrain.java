package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.river.RiverField;
import com.geogenesis.worldgen.river.RiverSettings;
import com.geogenesis.worldgen.erosion.ErosionSystem;
import com.geogenesis.worldgen.erosion.ErosionSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地形引擎对外接口：带缓存的 Cell 网格采样。
 *
 * 跨 chunk 共享，无 tile 边界断裂。
 * 缓存策略：按 chunk 网格（16×16）缓存 Cell 数组，LRU-like（ConcurrentHashMap + 简单上限）。
 */
public final class GeoGenesisTerrain {

    private static final int CACHE_SIZE = 256;
    private static final int CHUNK_SHIFT = 4; // 16 blocks per chunk

    private final CellGenerator generator;
    private final HeightCurve curve;
    private final RiverField riverField;
    private final ErosionSystem erosion;
    private final Map<Long, Cell[]> cache = new ConcurrentHashMap<>(CACHE_SIZE);

    /** chunk 周边 pad（供侵蚀局部算子读取邻域，不写回，无接缝） */
    private static final int ERODE_PAD = 2;

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
        this.riverField = new RiverField(generator, curve, RiverSettings.defaults());
        this.erosion = new ErosionSystem(ErosionSettings.defaults());
    }

    /** 播种所有噪声节点（每个世界种子调用一次） */
    public void seed(long worldSeed) {
        generator.seed(worldSeed);
        cache.clear();
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
     * computeIfAbsent 返回后必要时做 LRU 淘汰，避免在 compute 函数内修改 map 造成死锁。
     */
    public Cell[] getChunkCells(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Cell[] cells = cache.computeIfAbsent(key, k -> generateChunk(chunkX, chunkZ));
        // LRU 淘汰放在 computeIfAbsent 外部（内部修改 map 会死锁）
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

    /** 旧 API 兼容：按 block 网格返回 Cell 二维数组（cx/cz 为 block 索引） */
    public Cell[][] getRegionCells(int originBlockX, int originBlockZ, int cellCountX, int cellCountZ) {
        Cell[][] region = new Cell[cellCountX][cellCountZ];
        for (int bx = 0; bx < cellCountX; bx++) {
            for (int bz = 0; bz < cellCountZ; bz++) {
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

        // ===== 临时关闭：侵蚀 + 河流刻蚀 =====
        // 仅跑 generator.sample 的原始地形，排查"地形断裂"根因。
        // 恢复时取消下方注释即可。
        /*
        int pad = ERODE_PAD;
        int size = 16 + 2 * pad;
        double[][] e = new double[size][size];
        for (int lj = 0; lj < size; lj++) {
            for (int li = 0; li < size; li++) {
                int wx = baseX + li - pad;
                int wz = baseZ + lj - pad;
                if (li >= pad && li < size - pad && lj >= pad && lj < size - pad) {
                    e[li][lj] = cells[(li - pad) * 16 + (lj - pad)].e;
                } else {
                    e[li][lj] = generator.sample(wx, wz).e;
                }
            }
        }
        erosion.apply(e, size, pad);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell c = cells[lx * 16 + lz];
                double ne = e[lx + pad][lz + pad];
                c.e = ne;
                c.height = curve.heightFromE(ne);
                if (c.terrainType != TerrainClass.RIVER) {
                    c.terrainType = generator.classify(
                        c.continent, ne, c.eLand, c.provinceWeights);
                }
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                riverField.apply(cells[lx * 16 + lz], baseX + lx, baseZ + lz);
            }
        }
        */
        return cells;
    }

    /** 简单 LRU 淘汰（在 computeIfAbsent 外部调用，避免 ConcurrentHashMap 死锁）。 */
    private void pruneIfNeeded() {
        if (cache.size() > CACHE_SIZE) {
            var it = cache.keySet().iterator();
            int toRemove = cache.size() / 2;
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
        return v < 0 ? v + 16 : v; // 处理负坐标
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
