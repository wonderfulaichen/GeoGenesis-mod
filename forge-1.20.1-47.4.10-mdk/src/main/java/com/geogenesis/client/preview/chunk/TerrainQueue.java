package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每帧扫描可见区域、创建未采样 chunk 的 WorkBatch（对标参考项目 WorkManager.queueGeneration）。
 * <p>
 * 1. 计算视口覆盖的 chunk 范围
 * 2. 扫描未采样线程
 * 3. 随机洗牌后打包成 WorkBatch
 * 4. 提交到 TerrainPool
 */
public class TerrainQueue {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    /** 单帧最大扫描 chunk 数。设为 Integer.MAX_VALUE 实际禁用（让全视口都能扫到） */
    private static final int MAX_CHUNKS_PER_SCAN = Integer.MAX_VALUE;

    private final CellCache cellCache;
    private final TerrainPool pool;
    private final GeoGenesisTerrain terrain;
    /** 采样步长：4=默认（1采样覆盖4×4块） */
    private final int blockStride;
    /** 每批最大 chunk 数 */
    private static final int MAX_BATCH_SIZE = 64;

    private final AtomicBoolean shouldEarlyAbort = new AtomicBoolean(false);
    private int lastMinCX = Integer.MAX_VALUE, lastMinCZ = Integer.MAX_VALUE;
    private int lastMaxCX = Integer.MAX_VALUE, lastMaxCZ = Integer.MAX_VALUE;
    private boolean firstQueue = true;

    // ===== 防抖：避免拖动/缩放时每帧都 cancel+submit（无声崩溃根因） =====
    /** 两次入队之间最小间隔（毫秒）。无条件防抖——快速滑动时每帧视口变 ≥16 块，条件跳过会失效。 */
    private static final long DEBOUNCE_MS = 200;
    private long lastQueueMs = 0;

    public TerrainQueue(CellCache cellCache, TerrainPool pool,
                        GeoGenesisTerrain terrain, int blockStride) {
        this.cellCache = cellCache;
        this.pool = pool;
        this.terrain = terrain;
        this.blockStride = blockStride;
    }

    /**
     * 每帧调用：扫描未采样 chunk 并提交到线程池
     * @param centerX 视口中心世界 X
     * @param centerZ 视口中心世界 Z
     * @param blocksWide 视口世界宽度
     * @param blocksHigh 视口世界高度
     */
    public void queueGeneration(int centerX, int centerZ,
                                 int blocksWide, int blocksHigh) {
        // 防抖：无论视口怎么变，200ms 内最多入队一次
        // 避免拖动/缩放时每帧都 cancel+submit worker 线程（无声崩溃根因）
        // 条件防抖（viewChanged 绕过）在快速拖动时会失效——center 每帧移动 ≥16 块即每帧都触发
        long now = System.currentTimeMillis();
        if (!firstQueue && (now - lastQueueMs) < DEBOUNCE_MS) {
            return;
        }
        lastQueueMs = now;

        int halfW = blocksWide / 2;
        int halfH = blocksHigh / 2;
        int minCX = (centerX - halfW) >> 4;
        int maxCX = (centerX + halfW) >> 4;
        int minCZ = (centerZ - halfH) >> 4;
        int maxCZ = (centerZ + halfH) >> 4;

        // 视口未变 → 跳过
        if (!firstQueue && minCX == lastMinCX && minCZ == lastMinCZ
                && maxCX == lastMaxCX && maxCZ == lastMaxCZ) {
            return;
        }
        firstQueue = false;
        lastMinCX = minCX; lastMinCZ = minCZ;
        lastMaxCX = maxCX; lastMaxCZ = maxCZ;

        shouldEarlyAbort.set(true);
        pool.cancelAll();
        shouldEarlyAbort.set(false);

        // 限制扫描范围：以视口中心为原点，扫描 MAX_CHUNKS_PER_SCAN chunks 的方形区域
        int chunksWide = maxCX - minCX + 1;
        int chunksHigh = maxCZ - minCZ + 1;
        int totalChunks = chunksWide * chunksHigh;
        int actualMinCX = minCX, actualMaxCX = maxCX;
        int actualMinCZ = minCZ, actualMaxCZ = maxCZ;

        if (totalChunks > MAX_CHUNKS_PER_SCAN) {
            // 缩到 MAX_CHUNKS_PER_SCAN 的方形
            int side = (int) Math.sqrt(MAX_CHUNKS_PER_SCAN);
            int centerCX = (minCX + maxCX) / 2;
            int centerCZ = (minCZ + maxCZ) / 2;
            actualMinCX = centerCX - side / 2;
            actualMaxCX = centerCX + side / 2;
            actualMinCZ = centerCZ - side / 2;
            actualMaxCZ = centerCZ + side / 2;
        }

        // 收集未采样 chunk
        List<ChunkPos> pending = new ArrayList<>();
        for (int cx = actualMinCX; cx <= actualMaxCX; cx++) {
            for (int cz = actualMinCZ; cz <= actualMaxCZ; cz++) {
                if (!cellCache.contains(cx, cz)) {
                    pending.add(new ChunkPos(cx, cz));
                }
            }
        }

        if (pending.isEmpty()) return;

        Collections.shuffle(pending);

        List<WorkBatch> batches = new ArrayList<>();
        for (int i = 0; i < pending.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, pending.size());
            List<ChunkWorkUnit> units = new ArrayList<>();
            for (int j = i; j < end; j++) {
                units.add(new ChunkWorkUnit(pending.get(j), terrain, cellCache, blockStride));
            }
            batches.add(new WorkBatch(new ArrayList<>(units)));
        }

        pool.submit(batches);

        LOGGER.info("[DIAG Queue] queued {} chunks in {} batches (viewport {}x{} chunks, scanned {}x{})",
                pending.size(), batches.size(), chunksWide, chunksHigh,
                actualMaxCX - actualMinCX + 1, actualMaxCZ - actualMinCZ + 1);
    }

    public void setShouldEarlyAbort(boolean v) { shouldEarlyAbort.set(v); }
    public void resetViewport() {
        firstQueue = true;
        lastMinCX = lastMinCZ = lastMaxCX = lastMaxCZ = Integer.MAX_VALUE;
        lastQueueMs = 0;
    }
}
