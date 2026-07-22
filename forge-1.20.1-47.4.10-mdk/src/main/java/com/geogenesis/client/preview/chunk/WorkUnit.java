package com.geogenesis.client.preview.chunk;

import net.minecraft.world.level.ChunkPos;

/**
 * 单个 chunk 的采样工作单元（对标参考项目 WorkUnit）。
 * <p>
 * 每个 WorkUnit 负责采样一个 MC chunk（16×16 块）并写入 ChunkStorage。
 */
public abstract class WorkUnit {

    protected final ChunkPos chunkPos;
    protected final int y;
    protected final ChunkStorage storage;
    protected final int flag;
    private volatile boolean canceled = false;

    protected WorkUnit(ChunkPos chunkPos, int y, ChunkStorage storage, int flag) {
        this.chunkPos = chunkPos;
        this.y = y;
        this.storage = storage;
        this.flag = flag;
    }

    /** 执行采样。返回 true=成功, false=被取消 */
    public final boolean work() {
        if (canceled) return false;
        try {
            doWork();
            return !canceled;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    /** 子类实现实际采样逻辑 */
    protected abstract void doWork();

    public void cancel() { this.canceled = true; }
    public boolean isCanceled() { return canceled; }
    public ChunkPos chunkPos() { return chunkPos; }
    public int flag() { return flag; }
}
