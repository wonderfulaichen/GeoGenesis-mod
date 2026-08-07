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
    /** 结构扫描器（可 null，创建世界界面才有 registry；null = 跳过结构检测） */
    private final StructureScanner structureScanner;
    private volatile boolean canceled = false;

    public ChunkWorkUnit(ChunkPos chunkPos, GeoGenesisTerrain terrain,
                         CellCache cellCache, int blockStride) {
        this(chunkPos, terrain, cellCache, blockStride, null);
    }

    public ChunkWorkUnit(ChunkPos chunkPos, GeoGenesisTerrain terrain,
                         CellCache cellCache, int blockStride,
                         StructureScanner structureScanner) {
        this.chunkPos = chunkPos;
        this.terrain = terrain;
        this.cellCache = cellCache;
        this.blockStride = Math.max(1, Math.min(16, blockStride));
        this.structureScanner = structureScanner;
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
        // ★ stride 感知：按 blockStride 一次生成整个 chunk Cell[256]。
        //   stride=16 → 1 次 sampleCore + 展开（旧代码 256 次 → 现在 1 次）。
        //   stride=1 → 全分辨率含侵蚀（等价于旧 generateChunk，行为不变）。
        Cell[] cells = terrain.getChunkCells(chunkPos.x, chunkPos.z, blockStride);

        // ★ 顺带检测结构（Worker 线程，不卡主线程）：placement 哈希判定 + 会话内缓存
        if (!canceled && structureScanner != null && !structureScanner.isScanned(chunkPos.x, chunkPos.z)) {
            structureScanner.scan(chunkPos.x, chunkPos.z);
        }

        if (!canceled) {
            cellCache.put(chunkPos.x, chunkPos.z, cells, blockStride);
        }
    }

    public void cancel() { this.canceled = true; }
    public boolean isCanceled() { return canceled; }
    public ChunkPos chunkPos() { return chunkPos; }
}
