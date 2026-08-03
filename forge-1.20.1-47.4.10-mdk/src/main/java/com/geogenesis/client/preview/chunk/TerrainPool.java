package com.geogenesis.client.preview.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多线程 Chunk 采样池（对标参考项目 WorkManager 的 executorService）。
 * <p>
 * 固定 4 线程。提交的 WorkBatch 由线程池的线程顺序执行。
 * 支持批量取消。
 */
public class TerrainPool {

    private final ExecutorService executor;
    private final List<Future<?>> futures = new ArrayList<>();
    /** 保存 batch 引用：cancelAll 时必须调用 batch.cancel()（f.cancel(true) 对正在运行的 batch 无效，
     *  它只设置线程中断，若 batch 不响应中断就会跑完整个批次，快速滑动时任务无限堆积导致 OOM） */
    private final List<WorkBatch> batches = new ArrayList<>();
    /** 哨兵标志：submit 时追加一个哨兵任务（排在所有批次之后），哨兵执行完 = 本轮全部结束。
     *  供 TerrainQueue 判断"上一轮任务是否全部完成"——完成前不 cancel、不重提（排队串行化），
     *  让任务自然跑完写入缓存，避免快速拖动时永远在取消→重提的循环里（预览永远空白）。
     *  不用计数器：cancelAll 取消"未开始"任务时其 finally 不执行会导致计数泄漏。 */
    private final AtomicBoolean sentinelPending = new AtomicBoolean(false);
    private volatile boolean shutdown = false;

    public TerrainPool(int threadCount) {
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(threadCount, Runtime.getRuntime().availableProcessors())),
                r -> {
                    Thread t = new Thread(r, "GeoGenesis-TerrainPool");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 提交一批 WorkBatch 到线程池，并在末尾追加哨兵任务（哨兵执行完 = 本轮全部结束） */
    public synchronized void submit(List<WorkBatch> newBatches) {
        if (shutdown) return;
        for (WorkBatch batch : newBatches) {
            if (batch.isCanceled()) continue;
            batches.add(batch);
            futures.add(executor.submit(batch::process));
        }
        appendSentinel();
    }

    /** 提交单个 WorkBatch */
    public synchronized void submit(WorkBatch batch) {
        if (shutdown || batch.isCanceled()) return;
        batches.add(batch);
        futures.add(executor.submit(batch::process));
        appendSentinel();
    }

    private void appendSentinel() {
        sentinelPending.set(true);
        futures.add(executor.submit(() -> {
            try {
                sentinelPending.set(false);
            } finally {
                Thread.interrupted(); // 消费中断位，防线程罢工
            }
        }));
    }

    /** 上一轮任务是否还有未完成的（哨兵未执行完 = 队列里还有批次） */
    public boolean isBusy() {
        return sentinelPending.get();
    }

    /** 取消所有正在运行/等待的任务：先 batch.cancel() 置标志，再 f.cancel(true) 置线程中断 */
    public synchronized void cancelAll() {
        for (WorkBatch b : batches) {
            b.cancel();
        }
        for (Future<?> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
        futures.clear();
        batches.clear();
        // 旧哨兵作废：被取消的哨兵不会执行（置 false），这里显式重置，
        // 否则 isBusy() 恒 true → 之后队列永不提交新批次
        sentinelPending.set(false);
    }

    /** 等待所有任务完成 */
    public synchronized void awaitAll() {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {}
        }
        futures.clear();
        batches.clear();
    }

    /** 关闭线程池 */
    public void shutdown() {
        shutdown = true;
        cancelAll();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() { return shutdown; }
}
