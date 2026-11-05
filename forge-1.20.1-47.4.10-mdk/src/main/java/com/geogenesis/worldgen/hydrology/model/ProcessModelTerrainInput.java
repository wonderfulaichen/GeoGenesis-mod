package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.process.ProcessContext;
import com.geogenesis.worldgen.process.ProcessRuntime;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Block/Y boundary for one frozen B/P runtime. No CellGenerator or global configuration. */
public final class ProcessModelTerrainInput implements ModelTerrainInput, AutoCloseable {
    private final ProcessRuntime runtime;
    private final ProcessContext context;
    private final double hs, sea;
    private final boolean ownsRuntime;
    private final Object gate = new Object();
    private final Set<ProcessRuntime.Subscription> subscriptions = new HashSet<>();
    private boolean closed;

    /** Borrows runtime; close cancels only this adapter's subscriptions, never other consumers. */
    public ProcessModelTerrainInput(ProcessRuntime runtime) { this(runtime, false); }

    /** Creates an owned runtime; closing this adapter also releases its tile executor/cache. */
    public ProcessModelTerrainInput(ProcessContext context, ProcessRuntime.Budget budget) {
        this(new ProcessRuntime(context, budget), true);
    }

    private ProcessModelTerrainInput(ProcessRuntime runtime, boolean ownsRuntime) {
        this.runtime = Objects.requireNonNull(runtime);
        this.context = runtime.context();
        this.ownsRuntime = ownsRuntime;
        var d = context.definition();
        hs = d.terrain().horizontalScale();
        sea = new HeightCurve(d.terrain(), d.minY(), d.maxY()).seaLevelY();
    }

    public ProcessContext context() { return context; }
    public ProcessRuntime.Metrics metrics() { return runtime.metrics(); }
    @Override public String cacheNamespace() { return context.identity(); }
    @Override public TerrainParams params() { return context.definition().terrain(); }
    @Override public double seaLevel() { return sea; }
    @Override public double mappedSeaLevel() { return sea; }
    private void checkOpen() {
        synchronized (gate) {
            if (closed) throw new CancellationException("closed model terrain input");
        }
    }
    @Override public double baseHeightAtBlock(double bx, double bz) {
        checkOpen();
        double value = context.sampleBase(bx / hs, bz / hs).yB();
        checkOpen();
        return value;
    }
    @Override public double coarseBaseHeightAtBlock(double bx, double bz) {
        return baseHeightAtBlock(bx, bz);
    }
    @Override public double processHeightAtBlock(double bx, double bz) {
        ProcessRuntime.Subscription subscription;
        synchronized (gate) {
            checkOpen();
            subscription = runtime.requestExact(bx / hs, bz / hs);
            subscriptions.add(subscription);
        }
        try {
            double value = subscription.await().yP();
            checkOpen();
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("interrupted exact P model query", e);
        } catch (ExecutionException e) {
            throw new CompletionException("exact P model query failed", e.getCause());
        } finally {
            synchronized (gate) { subscriptions.remove(subscription); }
            subscription.close();
        }
    }
    @Override public double pipelineHeightAtBlock(double bx, double bz, double ignoredHs, boolean eroded) {
        return eroded ? processHeightAtBlock(bx, bz) : baseHeightAtBlock(bx, bz);
    }
    @Override public double marginHeightAtBlock(double bx, double bz, double ignoredHs) {
        return processHeightAtBlock(bx, bz);
    }
    /**
     * ★ R2：<b>新 H 版本</b>（B/P → model 新会话）才启用规范塑形。
     *
     * <p>隔离点就在这里：旧 {@code CellModelTerrainInput} 不覆盖本方法 ⇒ 恒 LEGACY。
     * 规范塑形改变几何外观，若直接落到共享生产路径，会连带要求预览 schema 升版与
     * 标准图对拍（计划 §6）；本轮只给新版本。（{@code PreviewDisplay} 未改。）</p>
     */
    @Override public DeterministicSkeleton.Shaping shaping() {
        return DeterministicSkeleton.Shaping.CANONICAL;
    }
    /**
     * ★ R3：<b>新 H 版本</b>才启用整数水柱四邻接连接计划。
     *
     * <p>隔离点与塑形同法：旧 {@code CellModelTerrainInput} 不覆盖本方法 ⇒ 恒 {@code false}
     * ⇒ 列计划逐位不变（37 份 model 二进制 + 114 份地形对拍）。规则改变列床面，
     * 若落到共享生产路径会连带要求预览 schema 升版与标准图对拍（计划 §6）；
     * 本轮只给新版本。（{@code PreviewDisplay} 未改。）</p>
     */
    @Override public boolean integerWaterConnector() {
        return true;
    }
    @Override public long strategyContribution(long hash) { return hash; }
    /**
     * 批量预热：与生产 {@code CellGenerator.prewarmErosionTiles} 同语义 —— 只提前提交 tile，
     * 不阻塞、不改产出（tile 是冻结上下文的纯函数）。生产侧靠它避免"整窗采样时逐点同步建 tile
     * 串行几十秒"，缺了它 R4 实测单 region 150~180s vs 命中态 5~20s。
     *
     * <p>参数是 block 坐标；转换到 wu 只在这里做一次，与其它高度入口同一 HS。</p>
     */
    @Override public void prewarm(double x, double z, double span, double ignoredHs) {
        checkOpen();
        double x0 = x / hs, z0 = z / hs;
        double x1 = (x + span) / hs, z1 = (z + span) / hs;
        runtime.prewarmTiles(x0, z0, x1, z1);
    }
    @Override public int processCacheSize() { return runtime.metrics().completed(); }
    @Override public long processCacheEvictions() { return 0; } // Runtime does not expose this metric.
    @Override public void close() {
        synchronized (gate) {
            if (closed) return;
            closed = true;
            for (var s : subscriptions) s.cancel();
            subscriptions.clear();
        }
        if (ownsRuntime) runtime.close();
    }
}
