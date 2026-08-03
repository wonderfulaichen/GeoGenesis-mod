package com.geogenesis.client.preview.chunk;

import java.util.List;

/**
 * 一批 ChunkWorkUnit（对标参考项目 WorkBatch）。
 * <p>
 * 由线程池的一个线程按序执行，减少调度开销。
 */
public class WorkBatch {

    private final List<ChunkWorkUnit> units;
    private volatile boolean canceled = false;

    public WorkBatch(List<ChunkWorkUnit> units) {
        this.units = units;
    }

    public void process() {
        try {
            // 同时检查自身标志和线程中断：pool.cancelAll() 的 f.cancel(true) 会设置中断，
            // 若不响应中断，正在运行的 batch 会跑完整个批次，快速滑动时任务无限堆积（OOM 根因）
            if (canceled || Thread.currentThread().isInterrupted()) return;
            for (ChunkWorkUnit unit : units) {
                if (canceled || Thread.currentThread().isInterrupted()) return;
                unit.work();
            }
        } finally {
            // ★ 消费线程中断标志：f.cancel(true) 设置的中断位若不消费（isInterrupted 只读不清除），
            //   线程池线程后续所有任务第一行检查都会直接 return → 4 线程永久罢工，预览永远空白
            Thread.interrupted();
        }
    }

    public void cancel() {
        this.canceled = true;
        for (ChunkWorkUnit unit : units) {
            unit.cancel();
        }
    }

    public boolean isCanceled() { return canceled; }
    public int size() { return units.size(); }
}
