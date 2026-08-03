package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import net.minecraft.world.level.ChunkPos;

/**
 * 采样工作单元：用 GeoGenesisTerrain 采样一个 MC chunk（16×16 block）。
 * <p>
 * 写入 CellCache 供渲染层读取。Cell 已包含所有数据层（高度/温度/群系等），
 * 无需 ChunkStorage 的多 flag 存储。
 */
public class ChunkWorkUnit {

    private final ChunkPos chunkPos;
    private final GeoGenesisTerrain terrain;
    private final CellCache cellCache;
    /** 采样步长（block 数）：1=全分辨率, 4=每 quart 一个, 16=单点 */
    private final int blockStride;
    private volatile boolean canceled = false;

    public ChunkWorkUnit(ChunkPos chunkPos, GeoGenesisTerrain terrain,
                         CellCache cellCache, int blockStride) {
        this.chunkPos = chunkPos;
        this.terrain = terrain;
        this.cellCache = cellCache;
        this.blockStride = Math.max(1, Math.min(16, blockStride));
    }

    /** 执行采样。返回 true=成功, false=取消/失败 */
    public boolean work() {
        if (canceled) return false;
        try {
            doWork();
            return !canceled;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private void doWork() {
        Cell[] cells = new Cell[256];
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();

        for (int lx = 0; lx < 16 && !canceled; lx += blockStride) {
            for (int lz = 0; lz < 16 && !canceled; lz += blockStride) {
                Cell c = terrain.sampleCell(baseX + lx, baseZ + lz);
                // 展开到 16×16 网格
                int endX = Math.min(lx + blockStride, 16);
                int endZ = Math.min(lz + blockStride, 16);
                for (int ex = lx; ex < endX; ex++) {
                    for (int ez = lz; ez < endZ; ez++) {
                        cells[ez * 16 + ex] = c;
                    }
                }
            }
        }

        if (!canceled) {
            cellCache.put(chunkPos.x, chunkPos.z, cells, blockStride);
        }
    }

    public void cancel() { this.canceled = true; }
    public boolean isCanceled() { return canceled; }
    public ChunkPos chunkPos() { return chunkPos; }
}
