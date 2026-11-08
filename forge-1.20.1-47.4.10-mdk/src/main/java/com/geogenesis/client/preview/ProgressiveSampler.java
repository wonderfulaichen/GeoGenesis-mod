package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>地形采样器</b>（★ 2026-11-08 重写，S2）。
 *
 * <h2>设计来源：完整移植 yardsite {@code SiteMapSampler} 的采样架构</h2>
 * <p>上一版是「渲染线程每帧补一块」，实测两轮都不对。读完参考项目后发现它根本不是分帧，
 * 而是<b>后台多线程一次跑完</b>，界面拿<b>活数据</b>边采边画。关键差别：</p>
 *
 * <table border="1">
 *   <caption>采样架构对照</caption>
 *   <tr><th></th><th>yardsite（{@code SiteMapSampler.build()} 第 421-457 行）</th><th>我上一版</th></tr>
 *   <tr><td><b>线程</b></td><td>固定线程池，<b>核数 ÷ 2</b>，优先级 -2
 *       （第 398-405 行 {@code SiteThreadBudget.resolve()}）</td>
 *       <td>渲染线程，每帧一步</td></tr>
 *   <tr><td><b>取活</b></td><td>{@code AtomicInteger cursor.getAndIncrement()} 从中心向外
 *       （第 432-440 行）</td><td>顺序扫块</td></tr>
 *   <tr><td><b>水域</b></td><td>先问群系，是水<b>直接记海平面、跳过高度采样</b>
 *       （第 481-490 行 {@code samplePoint}）</td><td>全采</td></tr>
 *   <tr><td><b>竖向比例</b></td><td>{@code easeReliefSpan}：涨 8% / 降 2%，
 *       差 0.6 内直接对齐（第 353-357 行）</td><td>每帧重算，无缓动</td></tr>
 * </table>
 *
 * <p>结论：<b>不要分帧</b>。分帧是错的抽象——一次采样本来只要几十毫秒，
 * 分到 64 帧反而每帧都在和渲染抢时间，用户看到的永远是粗档。
 * 正确做法是丢到后台线程池，界面用「当前已采到多少」的画面（yardsite 的
 * {@code blockState} PENDING/WORKING/READY 三态）。</p>
 *
 * <h2>本类的简化（相对 yardsite）</h2>
 * <ul>
 *   <li>不做 {@code SurfaceTrend} 竖直扫描优化（那是它为了 {@code getBaseHeight}
 *       逐格扫空气设计的；我们的 {@code sampleCellCoarse} 是纯噪声求值，没有"扫空气"成本）。</li>
 *   <li>不做水域跳过（同上：我们的采样成本与水深无关；但保留 {@code isWater} 判定供着色）。</li>
 *   <li>用现成的 {@link GeoGenesisTerrain#sampleCellCoarse}，不自己管 accessor 阶梯。</li>
 * </ul>
 * <p>保留的核心是<b>多线程 + 中心向外 + 活数据 + 竖向比例缓动</b>。</p>
 */
public final class ProgressiveSampler {

    /**
     * 采样网格（每边格数）。
     *
     * <p>★ 2026-11-08 从 128 提到 <b>256</b>，对齐参考项目档位：
     * yardsite {@code site.selectRadius=2000} + 128 柱 ⇒ 一根柱约 31 格方块；
     * 我们视口 3072 格，128 细格时一格 24 格方块、柱子（2×2 细格）**48 格方块**
     * —— 比参考粗 1.5 倍，细格数只有它的 1/4（16384 vs 65536），
     * 这就是"效果一般般、细节看不出来"的量化来源。
     * 提到 256 后：一格 12 格方块、柱子 24 格方块，细格 65536 个，与参考同档。</p>
     *
     * <p>成本：S0 实测全字段采样 128² 约 154 ms（单线程），后台多线程实测约 70 ms；
     * 256² 是 4 倍点数 ⇒ 约 280 ms（后台跑，不阻塞渲染线程）。</p>
     */
    public static final int GRID = 256;

    /** 竖向比例的当前显示值（缓动目标）。初值同 yardsite {@code shownSpan = 60}。 */
    private float shownSpan = 60.0f;

    private TerrainFieldData current;
    private long currentId = Long.MIN_VALUE;

    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicInteger progressTotal = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean();

    private ExecutorService pool;
    private boolean complete;

    public ProgressiveSampler(int gridW, int gridH) {
        // gridW/gridH 忽略：网格由 GRID 决定（保持构造签名兼容）
    }

    /** 活数据（可能还在采）。无数据返回 null。 */
    public TerrainFieldData data() { return current; }

    /** 是否采完。 */
    public boolean isComplete() { return complete; }

    /** 进度 0..1。 */
    public float progress() {
        int t = progressTotal.get();
        return t <= 0 ? 0f : Math.min(1f, progress.get() / (float) t);
    }

    public long lastCostMs() { return current == null ? 0 : current.costMs; }

    /** 竖向比例的当前值（渲染器每帧读它算柱高）。 */
    public float reliefSpan() { return shownSpan; }

    /**
     * 竖向比例缓动：<b>照抄</b> yardsite {@code easeReliefSpan}（第 353-357 行）。
     * 涨快（8%）、降慢（2%），差 0.6 内直接对齐 —— 免得新数据进来时柱子高度突变。
     */
    public void easeReliefSpan(float target) {
        shownSpan += (target - shownSpan) * (target > shownSpan ? 0.08f : 0.02f);
        if (Math.abs(target - shownSpan) < 0.6f) shownSpan = target;
    }

    /**
     * 启动后台采样。同一视口重复调用不做任何事。
     *
     * <p>返回时 {@link #data()} 可能仍是 null（后台还没填第一格）——
     * 调用方应按 {@code null} 处理（画个底或跳过），不要阻塞等待。</p>
     */
    public void reset(GeoGenesisTerrain terrain, int originX, int originZ,
                      int blocksWide, int blocksHigh, long viewportId) {
        if (viewportId == currentId) return;

        // 停掉上一轮
        stop();

        currentId = viewportId;
        complete = false;
        int n = GRID * GRID;
        progress.set(0);
        progressTotal.set(n);

        TerrainFieldData data = TerrainFieldData.blank(GRID, GRID, originX, originZ,
                blocksWide, blocksHigh, viewportId);
        current = data;

        // ★ 中心向外的取活顺序（yardsite centerOutOrder 第 187-206 行）
        int[] order = centerOutOrder(GRID);

        // ★ 线程数 = 核数 ÷ 2（yardsite SiteThreadBudget），留一半给渲染线程
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger cursor = new AtomicInteger();
        AtomicBoolean cancel = new AtomicBoolean(false);
        running.set(true);

        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "GeoGenesis-SandboxSample");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);   // 别把渲染线程饿到掉帧
            return t;
        });

        long t0 = System.nanoTime();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int k = 0; k < threads; k++) {
            futures.add(pool.submit(() -> {
                double stepX = blocksWide / (double) GRID;
                double stepZ = blocksHigh / (double) GRID;
                Cell reuse = new Cell();
                while (!cancel.get()) {
                    int c = cursor.getAndIncrement();
                    if (c >= order.length) return;
                    int idx = order[c];
                    int gx = idx / GRID;
                    int gz = idx % GRID;
                    int wx = originX + (int) Math.round(gx * stepX);
                    int wz = originZ + (int) Math.round(gz * stepZ);
                    Cell cell = terrain.sampleCellCoarse(wx, wz);
                    data.put(gx, gz, cell);
                    progress.incrementAndGet();
                }
            }));
        }
        // 收尾放在独立线程：不阻塞调用方（渲染线程）
        Thread joiner = new Thread(() -> {
            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) { }
            }
            data.costMs = (System.nanoTime() - t0) / 1_000_000L;
            if (!cancel.get()) {
                complete = true;
                System.out.printf("[Preview] 沙盘采样完成 %dx%d，%d 线程，%d ms%n",
                        GRID, GRID, threads, data.costMs);
                System.out.flush();
            }
            running.set(false);
        }, "GeoGenesis-SandboxSample-join");
        joiner.setDaemon(true);
        joiner.start();
    }

    /** 停止当前采样（视口变化 / 关屏时调用）。 */
    public void stop() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        running.set(false);
    }

    /**
     * 中心向外顺序（yardsite {@code centerOutOrder()} 第 187-206 行）：
     * 按到图中心的距离排序，<b>同距离按下标</b>保证顺序稳定。
     */
    private static int[] centerOutOrder(int n) {
        int points = n * n;
        long[] keyed = new long[points];
        double half = (n - 1) / 2.0;
        for (int cj = 0; cj < n; cj++) {
            for (int ci = 0; ci < n; ci++) {
                double dx = ci - half;
                double dz = cj - half;
                long dist = Math.round(Math.sqrt(dx * dx + dz * dz) * 1024.0);
                int index = cj * n + ci;
                keyed[index] = (dist << 32) | (index & 0xFFFFFFFFL);
            }
        }
        java.util.Arrays.sort(keyed);
        int[] order = new int[points];
        for (int k = 0; k < order.length; k++) {
            order[k] = (int) keyed[k];
        }
        return order;
    }

    public int targetGridW() { return GRID; }
    public int targetGridH() { return GRID; }
}
