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
        if (canceled) return;
        for (ChunkWorkUnit unit : units) {
            if (canceled) return;
            unit.work();
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
