package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 每帧扫描可见区域、对未采样的 chunk 提交 WorkBatch（对标参考项目 WorkManager.queueRange）。
 * <p>
 * 核心策略（对齐参考项目 WorkManager.queueRange）：
 * <ol>
 *   <li><b>视口未变 → 跳过整个流程</b>（lastQueuedTopLeft/BotRight 比较）。
 *       任务跑完不主动重扫：pending chunk 全部 needsResample 判定后已被处理，
 *       视口再次变化才会触发下一轮入队。这彻底杜绝"任务跑完 → 立即下一轮 → 雷达闪动"循环。</li>
 *   <li><b>isBusy 串行化</b>：上一轮任务未完成时直接返回，让任务自然跑完写入缓存（不取消、不重提）。</li>
 *   <li><b>随机洗牌 + 一次全提</b>（对齐参考模组 queueForLevel 的 shuffle + queueRangeReal 无单次上限）：
 *       画面随机小块渐进密实，无"环状"（距离优先）/无"条状"（扫描顺序）填充伪影；
 *       一轮全提后 isBusy 串行 + 视口未变跳过自然终止，无多轮刷新闪烁。</li>
 *   <li><b>needsResample 三态判断</b>：无数据 / 已有粗数据（stride 更大）→ 采；粒度够 → 跳过（历史保留）。</li>
 *   <li><b>矩形窗口淘汰</b>（CellCache.trimToRect）：视口矩形内永不淘汰，
 *       只回收拖拽后变为矩形外的旧视口 → 无"刷新圆圈"（旧距离淘汰保留圆形区域所致）。</li>
 * </ol>
 */
public class TerrainQueue {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    /** 每批最大 chunk 数（对齐参考模组 maxBatchSize，4 线程顺序消费）。 */
    private static final int MAX_BATCH_SIZE = 64;

    private final CellCache cellCache;
    private final TerrainPool pool;
    private final GeoGenesisTerrain terrain;
    /** 采样步长：1=全分辨率, 4=每 quart 一个, 16=单点 */
    private final int blockStride;

    /** 上次入队的视口（视口未变 → 跳过）。resetViewport/缩放重建时会设为 MAX_VALUE 强制下一帧入队。 */
    private int lastMinCX = Integer.MAX_VALUE, lastMinCZ = Integer.MAX_VALUE;
    private int lastMaxCX = Integer.MAX_VALUE, lastMaxCZ = Integer.MAX_VALUE;
    private boolean firstQueue = true;

    public TerrainQueue(CellCache cellCache, TerrainPool pool,
                        GeoGenesisTerrain terrain, int blockStride) {
        this.cellCache = cellCache;
        this.pool = pool;
        this.terrain = terrain;
        this.blockStride = blockStride;
    }

    /**
     * 每帧调用：视口未变直接返回；视口变了才扫描未采样 chunk 并提交到线程池。
     * @param centerX 视口中心世界 X
     * @param centerZ 视口中心世界 Z
     * @param blocksWide 视口世界宽度
     * @param blocksHigh 视口世界高度
     */
    public void queueGeneration(int centerX, int centerZ,
                                 int blocksWide, int blocksHigh) {
        int halfW = blocksWide / 2;
        int halfH = blocksHigh / 2;
        int minCX = (centerX - halfW) >> 4;
        int maxCX = (centerX + halfW) >> 4;
        int minCZ = (centerZ - halfH) >> 4;
        int maxCZ = (centerZ + halfH) >> 4;

        // ★ 对齐参考项目 WorkManager.queueRange：视口未变 → 整个流程跳过（firstQueue 除外）。
        //   这是"不雷达闪动"的关键——任务跑完后不会主动重扫，避免 4 线程批量完成一"环"
        //   立即被 paint 出来的视觉副作用。视口下次变化（或 resetViewport/缩放）才再次入队。
        if (!firstQueue
                && minCX == lastMinCX && maxCX == lastMaxCX
                && minCZ == lastMinCZ && maxCZ == lastMaxCZ) {
            return;
        }
        firstQueue = false;
        lastMinCX = minCX; lastMinCZ = minCZ;
        lastMaxCX = maxCX; lastMaxCZ = maxCZ;

        // ★ isBusy 串行化：上一轮任务未完成时直接返回（不 cancel、不重提）。
        //   让任务自然跑完写入缓存。任务完成后下帧再走此函数时视口若已变 → 入队；
        //   若视口未变 → 上面"视口未变跳过"接住，永不重复入队。
        if (pool.isBusy()) {
            return;
        }

        // 收集未采样 chunk（全视口扫描）
        List<ChunkPos> pending = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                // needsResample：无数据 → 采；已有数据但粒度更粗（stride 更大）→ 渐近细化；
                // 粒度够细 → 跳过（缩放回看秒显，拖拽历史保留）。
                if (cellCache.needsResample(cx, cz, blockStride)) {
                    pending.add(new ChunkPos(cx, cz));
                }
            }
        }

        if (pending.isEmpty()) return;

        // ★ 随机洗牌（对齐参考模组 queueForLevel 的 shuffle）：画面随机小块渐进密实，
        //   无"环状"（距离优先）/无"条状"（扫描顺序）填充伪影——参考模组就是这样做的。
        Collections.shuffle(pending);

        // ★ 一次提交全部 pending（对齐参考模组：queueRangeReal 无单次上限，全量分批提交）。
        //   多轮分批是"刷新圆圈/雷达闪动"感知的来源之一——一轮全提后 isBusy 串行 + 视口未变跳过
        //   自然终止，不再有"任务跑完→再扫→又刷新"的多轮闪烁。内存：stride=16 超大视口
        //   每 chunk 仅 1 Cell + 2KB 数组，21 万 chunks ≈ 1GB 峰值，6GB 堆安全；
        //   trimTo 矩形淘汰保证缓存只增到视口规模（拖拽时旧视口被淘汰）。
        int chunksWide = maxCX - minCX + 1;
        int chunksHigh = maxCZ - minCZ + 1;
        int viewportChunks = chunksWide * chunksHigh;
        int submitCount = pending.size();

        List<WorkBatch> batches = new ArrayList<>();
        for (int i = 0; i < submitCount; i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, submitCount);
            List<ChunkWorkUnit> units = new ArrayList<>();
            for (int j = i; j < end; j++) {
                units.add(new ChunkWorkUnit(pending.get(j), terrain, cellCache, blockStride));
            }
            batches.add(new WorkBatch(new ArrayList<>(units)));
        }

        pool.submit(batches);

        // ★ 容量兜底改为"矩形窗口淘汰"：视口矩形内数据永不淘汰（拖拽/缩放历史保留），
        //   只淘汰矩形外最远的 chunk（拖到新位置后旧视口自然回收）。
        //   原"按距中心点距离"淘汰保留的是圆形区域 → 渲染时形成"刷新圆圈"（用户截图实锤）。
        //   矩形淘汰 + 视口内全保留 → 无圆圈、无大半屏黑（视口全部可填充）。
        int trimCap = Math.max(CellCache.MAX_ENTRIES, viewportChunks);
        cellCache.trimToRect(trimCap, minCX, minCZ, maxCX, maxCZ);

        LOGGER.info("[DIAG Queue] queued {} chunks in {} batches (viewport {}x{} chunks, pending {})",
                submitCount, batches.size(), chunksWide, chunksHigh, pending.size());
    }

    /** 重置视口记录（缩放/setTerrain 时调用），使下帧强制入队。 */
    public void resetViewport() {
        firstQueue = true;
        lastMinCX = lastMinCZ = lastMaxCX = lastMaxCZ = Integer.MAX_VALUE;
    }
}