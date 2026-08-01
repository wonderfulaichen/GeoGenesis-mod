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

    /** 诊断日志记录器 */
    private static final Logger DLOG = LogManager.getLogger("geogenesis/diag");

    /** 诊断阈值：e 的相邻块断裂超过此值即打日志（0.01≈4 块合格线，2026-08-01 从 0.003 还原——低阈值刷屏 950+ 条/次运行且无实义） */
    private static final double DIAG_THRESHOLD = 0.01;
    /** 诊断阈值（跨 chunk）：与 DIAG_THRESHOLD 相同，只报超过合格线的真实断裂 */
    private static final double INTER_THRESHOLD = 0.01;

    public GeoGenesisTerrain(CellGenerator generator) {
        this.generator = generator;
        this.curve = generator.heightCurve();
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

        // 诊断：扫描 chunk 内相邻块断裂，超过阈值即记录到日志
        double maxEDeltaX = 0, maxEDeltaZ = 0;
        double maxEOceanDX = 0, maxContDX = 0;
        double maxELandDX = 0;
        int peX = 0, peZ = 0;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 15; lx++) {
                int i0 = lx * 16 + lz, i1 = (lx + 1) * 16 + lz;
                double dx = Math.abs(cells[i0].e - cells[i1].e);
                if (dx > maxEDeltaX) { maxEDeltaX = dx; peX = lx; peZ = lz; }
                double dl = Math.abs(cells[i0].eLand - cells[i1].eLand);
                if (dl > maxELandDX) maxELandDX = dl;
                double doc = Math.abs(cells[i0].eOcean - cells[i1].eOcean);
                if (doc > maxEOceanDX) maxEOceanDX = doc;
                double dc = Math.abs(cells[i0].blendCont - cells[i1].blendCont);
                if (dc > maxContDX) maxContDX = dc;
            }
        }
        for (int lz = 0; lz < 15; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i0 = lx * 16 + lz, i1 = lx * 16 + lz + 1;
                double dz = Math.abs(cells[i0].e - cells[i1].e);
                if (dz > maxEDeltaZ) maxEDeltaZ = dz;
            }
        }
        double maxEDelta = Math.max(maxEDeltaX, maxEDeltaZ);
        if (maxEDelta > DIAG_THRESHOLD) {
            Cell c = cells[peX * 16 + peZ];
            double blk = maxEDelta * 384;
            int wx = baseX + peX, wz = baseZ + peZ;
            DLOG.warn("[DIAG] chunk({},{}): maxFe={}({}blk) at(local={},{} world=({},{}))"
                + " type={}, eLand={}, eOcean={}, cont={},"
                + " eLandMaxF={}, eOceanMaxF={}, contMaxF={}",
                cx, cz, String.format("%.4f", maxEDelta), String.format("%.0f", blk),
                peX, peZ, wx, wz, c.terrainType,
                String.format("%.4f", c.eLand), String.format("%.4f", c.eOcean),
                String.format("%.4f", c.blendCont),
                String.format("%.4f", maxELandDX), String.format("%.4f", maxEOceanDX),
                String.format("%.4f", maxContDX));
        }

        // 跨区块接缝诊断：比较本 chunk 右/下边缘与相邻 chunk 左/上边缘。
        double maxInterX = 0, maxInterZ = 0;
        int tileCX = Math.floorDiv(cx, 3) * 3, tileCZ = Math.floorDiv(cz, 3) * 3;
        int nTileCX = Math.floorDiv(cx + 1, 3) * 3, nTileCZ = Math.floorDiv(cz, 3) * 3;
        Cell[] nbX = cache.get(pack(cx + 1, cz));
        if (nbX != null) {
            for (int lz = 0; lz < 16; lz++) {
                double d = Math.abs(cells[15 * 16 + lz].e - nbX[0 * 16 + lz].e);
                if (d > maxInterX) maxInterX = d;
            }
        }
        Cell[] nbZ = cache.get(pack(cx, cz + 1));
        if (nbZ != null) {
            for (int lx = 0; lx < 16; lx++) {
                double d = Math.abs(cells[lx * 16 + 15].e - nbZ[lx * 16 + 0].e);
                if (d > maxInterZ) maxInterZ = d;
            }
        }
        double maxInter = Math.max(maxInterX, maxInterZ);
        if (maxInter > INTER_THRESHOLD) {
            // 获取两边 tile 的版本号（诊断是否是 tile 边界断裂）
            int myTileRound = 0, nbrTileRoundX = 0, nbrTileRoundZ = 0;
            CellGenerator.ErosionTileResult myTile = generator.getTileResult(tileCX, tileCZ);
            if (myTile != null) myTileRound = myTile.erosionRound;
            CellGenerator.ErosionTileResult nbrX = generator.getTileResult(nTileCX, nTileCZ);
            if (nbrX != null) nbrTileRoundX = nbrX.erosionRound;
            CellGenerator.ErosionTileResult nbrZ = generator.getTileResult(tileCX, nTileCZ);
            if (nbrZ != null) nbrTileRoundZ = nbrZ.erosionRound;

            boolean sameTileX = (tileCX == nTileCX); // X 相邻 chunk 是否同 tile
            boolean sameTileZ = (tileCZ == nTileCZ); // Z 相邻 chunk 是否同 tile

            DLOG.warn("[DIAG-INTER] chunk({},{}): maxFe={}({}blk)"
                + " world=({},{})({},{})"
                + " interX={}({}blk,{},tCX={}/{},rd={}/{})"
                + " interZ={}({}blk,{},tCZ={}/{},rd={}/{})",
                cx, cz,
                String.format("%.4f", maxInter), String.format("%.0f", maxInter * 384),
                baseX + 15, baseZ, baseX + 16, baseZ,
                String.format("%.4f", maxInterX), String.format("%.0f", maxInterX * 384),
                sameTileX ? "same" : "DIFF",
                tileCX, nTileCX,
                myTileRound, nbrTileRoundX,
                String.format("%.4f", maxInterZ), String.format("%.0f", maxInterZ * 384),
                sameTileZ ? "same" : "DIFF",
                tileCZ, nTileCZ,
                myTileRound, nbrTileRoundZ);
        }

        return cells;
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
