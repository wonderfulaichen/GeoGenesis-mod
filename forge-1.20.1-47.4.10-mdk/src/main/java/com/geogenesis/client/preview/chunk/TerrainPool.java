package com.geogenesis.client.preview.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 多线程 Chunk 采样池（对标参考项目 WorkManager 的 executorService）。
 * <p>
 * 固定 4 线程。提交的 WorkBatch 由线程池的线程顺序执行。
 * 支持批量取消。
 */
public class TerrainPool {

    private final ExecutorService executor;
    private final List<Future<?>> futures = new ArrayList<>();
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

    /** 提交一批 WorkBatch 到线程池 */
    public synchronized void submit(List<WorkBatch> batches) {
        if (shutdown) return;
        for (WorkBatch batch : batches) {
            if (batch.isCanceled()) continue;
            futures.add(executor.submit(batch::process));
        }
    }

    /** 提交单个 WorkBatch */
    public synchronized void submit(WorkBatch batch) {
        if (shutdown || batch.isCanceled()) return;
        futures.add(executor.submit(batch::process));
    }

    /** 取消所有正在运行/等待的任务 */
    public synchronized void cancelAll() {
        for (Future<?> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
        futures.clear();
    }

    /** 等待所有任务完成 */
    public synchronized void awaitAll() {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {}
        }
        futures.clear();
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
