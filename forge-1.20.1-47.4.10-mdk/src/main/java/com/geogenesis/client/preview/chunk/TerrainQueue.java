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

    // 2026-08-07 OOM 修复（用户预览崩溃：363×318 视口排队 10 万 chunk → 堆爆炸无 crash report）：
    // 1) 单次提交硬顶——记忆铁律"队列型架构必须设单次提交硬顶"。旧实现"一次全提无上限"
    //    （注释估 21 万 chunks≈1GB 安全）在低 stride 大视口下不成立；洗牌后取前 1024，
    //    超出的 chunk 由 isBusy 串行 + 视口未变再扫自然渐进补采（随机渐进，无圆圈/条状）。
    private static final int MAX_CHUNKS_PER_SCAN = 1024;
    /** 2) CellCache 容量兜底封顶：trimToRect 的 trimCap 不能再"随视口无上限增长"
     *    （363×318=11.5 万条目 OOM）；超出部分边缘 chunk 会被淘汰，缩放回看时
     *    needsResample 自动重采补上（渐进填充语义不变）。 */
    private static final int MAX_CACHE_ENTRIES_CAP = 32000;

    private final CellCache cellCache;
    private final TerrainPool pool;
    private final GeoGenesisTerrain terrain;
    /** 采样步长：1=全分辨率, 4=每 quart 一个, 16=单点 */
    private final int blockStride;
    /** 结构扫描器（可 null） */
    private final StructureScanner structureScanner;

    /** 上次入队的视口（视口未变 → 跳过）。resetViewport/缩放重建时会设为 MAX_VALUE 强制下一帧入队。 */
    private int lastMinCX = Integer.MAX_VALUE, lastMinCZ = Integer.MAX_VALUE;
    private int lastMaxCX = Integer.MAX_VALUE, lastMaxCZ = Integer.MAX_VALUE;
    private boolean firstQueue = true;

    /** pool 上一帧是否 busy：busy→idle 转换时强制重扫（修复：大视口首次提交 1024 后剩余 chunks
     *  永远不被重扫，需移动窗口才继续加载的 bug）。 */
    private boolean poolWasBusy = false;

    public TerrainQueue(CellCache cellCache, TerrainPool pool,
                        GeoGenesisTerrain terrain, int blockStride) {
        this(cellCache, pool, terrain, blockStride, null);
    }

    public TerrainQueue(CellCache cellCache, TerrainPool pool,
                        GeoGenesisTerrain terrain, int blockStride,
                        StructureScanner structureScanner) {
        this.cellCache = cellCache;
        this.pool = pool;
        this.terrain = terrain;
        this.blockStride = blockStride;
        this.structureScanner = structureScanner;
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
        //
        //   例外：pool 从 busy→idle（上一轮任务刚完成）时强制重扫——修复大视口首次提交
        //   MAX_CHUNKS_PER_SCAN 后剩余 chunks 永远不被重扫、需移动窗口才继续加载的 bug。
        boolean poolJustFinished = poolWasBusy && !pool.isBusy();
        poolWasBusy = pool.isBusy();
        if (!firstQueue && !poolJustFinished
                && minCX == lastMinCX && maxCX == lastMaxCX
                && minCZ == lastMinCZ && maxCZ == lastMaxCZ) {
            return;
        }
        firstQueue = false;
        lastMinCX = minCX; lastMinCZ = minCZ;
        lastMaxCX = maxCX; lastMaxCZ = maxCZ;

        // ★ isBusy 串行化：上一轮任务未完成时直接返回（不 cancel、不重提）。
        //   让任务自然跑完写入缓存。任务完成后下帧 poolJustFinished=true 绕过视口比较，
        //   重新扫描并提交剩余 pending chunks。
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

        // ★ 单次提交硬顶（2026-08-07 OOM 修复）：洗牌后取前 MAX_CHUNKS_PER_SCAN 个。
        //   超出的 pending 由"isBusy 串行 + 视口未变再扫"自然渐进补采（每轮 1024，4 线程
        //   处理完 → 哨兵 → 下轮再扫剩余）→ 随机渐进填充语义不变，但内存峰值可控
        //   （旧实现 363×318 视口一次提交 10 万 chunk → 堆爆炸 OOM 无 crash report）。
        int chunksWide = maxCX - minCX + 1;
        int chunksHigh = maxCZ - minCZ + 1;
        int viewportChunks = chunksWide * chunksHigh;
        int submitCount = Math.min(pending.size(), MAX_CHUNKS_PER_SCAN);

        List<WorkBatch> batches = new ArrayList<>();
        for (int i = 0; i < submitCount; i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, submitCount);
            List<ChunkWorkUnit> units = new ArrayList<>();
            for (int j = i; j < end; j++) {
                units.add(new ChunkWorkUnit(pending.get(j), terrain, cellCache, blockStride, structureScanner));
            }
            batches.add(new WorkBatch(new ArrayList<>(units)));
        }

        pool.submit(batches);

        // ★ 容量兜底：视口矩形内数据优先保留（拖拽/缩放历史），只淘汰矩形外最远的 chunk，
        //   但 trimCap 封顶（2026-08-07：旧 max() 随视口无上限 → 11.5 万条目 OOM）。
        //   超出封顶的视口边缘 chunk 被淘汰 → 缩放回看 needsResample 自动重采补上。
        int trimCap = Math.max(CellCache.MAX_ENTRIES,
                Math.min(viewportChunks, MAX_CACHE_ENTRIES_CAP));
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