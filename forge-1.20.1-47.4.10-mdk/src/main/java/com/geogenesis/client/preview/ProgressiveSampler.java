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
     * <b>默认</b>采样网格（每边格数）—— 仅在调用方没有按视口指定时使用。
     *
     * <h2>★ 2026-11-08 重大修正：网格不再固定</h2>
     * <p>上一版把网格<b>固定为 256</b>，理由是"采样点数恒定 ⇒ 成本恒定"。
     * 但实机暴露出根本问题：<b>放大后精度没变化</b>。</p>
     *
     * <p>对比平面视图（我们的原有能力）：</p>
     * <table border="1">
     *   <caption>采样密度对照（视口 900×900）</caption>
     *   <tr><th></th><th>采样点数</th><th>每点代表</th></tr>
     *   <tr><td>平面视图 1:1</td><td><b>810,000</b></td><td><b>1 格</b></td></tr>
     *   <tr><td>沙盘旧（固定 256）</td><td>65,536</td><td>1~15.6 格</td></tr>
     * </table>
     * <p>⇒ 差 12.4 倍。平面视图能做到"每个屏幕像素一个方块"，
     * 因为它<b>采样点数 = 视口像素数</b>，不是固定值。</p>
     *
     * <h2>正解：网格 = 视口像素数 / 每柱像素</h2>
     * <p>与平面视图同一逻辑（像素采样），只是每格画成一根柱子：</p>
     * <pre>
     *   柱子屏幕尺寸恒定（约 13px） ⇒ 网格数 ≈ 视口像素 / 13
     *   视野 = 网格数 × 每格方块数
     *   推近 ⇒ 视野变小 ⇒ 每柱代表方块变少 ⇒ <b>精度自然提高</b>
     * </pre>
     * <p>这正是用户要的"视野缩小后每柱代表的方块变少"，
     * 也是参考项目三层视图（大沙盘 15.6 格/格 → 精读 16 格/格 → 落点 4 格/格）的同一原理：
     * <b>不堆采样点数，靠缩小窗口提精度</b>。</p>
     *
     * <p>成本：网格按视口算出（900px/13 ≈ 70 柱 ⇒ 140 细格 ⇒ 19,600 点），
     * 反而<b>比旧的 256² 少</b>（19.6k vs 65.5k），采样更快。</p>
     */
    public static final int DEFAULT_GRID = 256;

    /** 本实例实际使用的网格（每边细格数）。由调用方按视口算好后传入。 */
    private int grid = DEFAULT_GRID;

    /** 本实例的网格（每边细格数）。 */
    public int grid() { return grid; }

    /** 竖向比例的当前显示值（缓动目标）。初值同 yardsite {@code shownSpan = 60}。 */
    private float shownSpan = 60.0f;

    private TerrainFieldData current;
    private long currentId = Long.MIN_VALUE;

    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicInteger progressTotal = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean();

    private ExecutorService pool;
    private boolean complete;
    /** 本轮是否走完整水文管线（{@code sampleCell}）。 */
    private boolean hydro;
    /** 粗层水位查询器（null = 不查水位）。 */
    private SandboxWaterLookup water;
    /**
     * 本会话第一次采样的时刻（毫秒）。
     *
     * <p>生长动画的"出生时刻"基准：同一会话内所有 reset 都用它，
     * 这样滚动缩放不会重播"从中心长出来"（见 reset 里的说明）。
     * 0 = 尚未开始（新建实例时）。</p>
     */
    private long sessionStartMs;

    /** 本轮是否走水文管线（诊断/状态行用）。 */
    public boolean isHydroPath() { return hydro; }

    public ProgressiveSampler(int gridW, int gridH) {
        // ★ 2026-11-08：现在真的用传入的网格尺寸（以前忽略、写死 256）。
        //   调用方按视口像素算：grid ≈ 视口像素 / 每柱像素 × PER_COLUMN。
        this.grid = Math.max(16, Math.max(gridW, gridH));
    }

    /** 换网格尺寸（视口变化时调用；下一次 reset 生效）。 */
    public void setGrid(int gridSize) {
        this.grid = Math.max(16, gridSize);
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
     * 兼容重载：<b>不走</b>水文管线（等价 {@code hydroPath=false}）。
     *
     * <p>平面大范围与探针沿用此签名。要看河谷/湖河必须显式传 {@code hydroPath=true}。</p>
     */
    public void reset(GeoGenesisTerrain terrain, int originX, int originZ,
                      int blocksWide, int blocksHigh, long viewportId) {
        reset(terrain, originX, originZ, blocksWide, blocksHigh, viewportId, false);
    }

    /**
     * 启动后台采样。同一视口重复调用不做任何事。
     *
     * <p>返回时 {@link #data()} 可能仍是 null（后台还没填第一格）——
     * 调用方应按 {@code null} 处理（画个底或跳过），不要阻塞等待。</p>
     *
     * @param hydroPath true = 走 {@code sampleCell}（带河谷雕刻与湖河数据，
     *                  但每个新水文 region 需秒级）；false = {@code sampleCellCoarse}（廉价、无水文）。
     *                  由 {@link SandboxLod#usesHydroPath} 按 LOD 档位决定。
     */
    public void reset(GeoGenesisTerrain terrain, int originX, int originZ,
                      int blocksWide, int blocksHigh, long viewportId, boolean hydroPath) {
        reset(terrain, originX, originZ, blocksWide, blocksHigh, viewportId, hydroPath, null);
    }

    /**
     * 启动后台采样（带可选的<b>粗层水位</b>查询）。
     *
     * @param water 非 null 时，每个采样点额外查一次粗层水面（画河道/湖面）；
     *              由 {@link SandboxLod#usesCoarseWater} 按档位决定是否为 null
     */
    public void reset(GeoGenesisTerrain terrain, int originX, int originZ,
                      int blocksWide, int blocksHigh, long viewportId, boolean hydroPath,
                      SandboxWaterLookup water) {
        if (viewportId == currentId && (water == null) == (this.water == null)) return;

        // 停掉上一轮
        stop();

        currentId = viewportId;
        complete = false;
        hydro = hydroPath;
        this.water = water;
        int n = grid * grid;
        progress.set(0);
        progressTotal.set(n);

        TerrainFieldData data = TerrainFieldData.blank(grid, grid, originX, originZ,
                blocksWide, blocksHigh, viewportId);
        current = data;

        // ★★ 2026-11-08【修"滚轮重播动画"】：
        //
        //   生长动画的出生时刻存在 data.bornAt[]，而每次 reset 都 new 一份
        //   TerrainFieldData ⇒ bornAt 全 0 ⇒ 每滚一格滚轮都重放一次"从中心长出来"。
        //   参考项目不会（它的 drawnAt 由字段自己记账，切视口不重播）。
        //
        //   修法：把"本会话第一次采样时刻"作为采样器状态，
        //   同一会话内的后续 reset <b>全都用它</b> ⇒ 柱子出生时刻远早于现在
        //   ⇒ 生长动画早已结束，不会重播。
        //   仅当真正换世界/换种子（新实例）时才重新录制 —— 那才该播一次。
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis();
        java.util.Arrays.fill(data.bornAt, sessionStartMs);

        // ★ 中心向外的取活顺序（yardsite centerOutOrder 第 187-206 行）
        int[] order = centerOutOrder(grid);

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
                double stepX = blocksWide / (double) grid;
                double stepZ = blocksHigh / (double) grid;
                Cell reuse = new Cell();
                while (!cancel.get()) {
                    int c = cursor.getAndIncrement();
                    if (c >= order.length) return;
                    int idx = order[c];
                    int gx = idx / grid;
                    int gz = idx % grid;
                    int wx = originX + (int) Math.round(gx * stepX);
                    int wz = originZ + (int) Math.round(gz * stepZ);
                    // ★ 按 LOD 档选路径：细档走完整管线才能拿到河谷/湖河
                    Cell cell = hydro
                            ? terrain.sampleCell(wx, wz)
                            : terrain.sampleCellCoarse(wx, wz);
                    data.put(gx, gz, cell);
                    // ★ 粗层水位（河道/湖面）：细档才查，见 SandboxLod.usesCoarseWater
                    if (water != null) {
                        data.putWater(gx, gz, water.waterSurfaceAt(wx, wz));
                    }
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
                        grid, grid, threads, data.costMs);
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

    public int targetGridW() { return grid; }
    public int targetGridH() { return grid; }
}
