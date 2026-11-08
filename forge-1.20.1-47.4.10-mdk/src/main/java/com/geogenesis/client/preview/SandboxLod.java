package com.geogenesis.client.preview;

/**
 * <b>沙盘比例尺（像素驱动 LOD）</b>—— ★ 2026-11-08 第二次重做。
 *
 * <h2>为什么又要重做（上一版的错）</h2>
 * <p>上一版思路是"采样点数恒定 = 成本恒定"：网格固定 256²，
 * 窗口随缩放变。实机暴露出根本问题：<b>放大后精度没变化</b>。</p>
 *
 * <p>对比我们的<b>平面视图</b>（原有能力，用户问"为什么它精度这么高"）：</p>
 * <table border="1">
 *   <caption>采样密度（视口 900×900）</caption>
 *   <tr><th></th><th>采样点数</th><th>每点代表</th></tr>
 *   <tr><td>平面视图 1:1</td><td><b>810,000</b></td><td><b>1 格方块</b></td></tr>
 *   <tr><td>沙盘旧（固定 256²）</td><td>65,536</td><td>1~15.6 格</td></tr>
 * </table>
 * <p>差 <b>12.4 倍</b>。平面视图能做到"每屏幕像素 = 一个方块"，
 * 因为它的采样点数<b>等于视口像素数</b>，不是固定值。</p>
 *
 * <h2>正解：柱子屏幕尺寸恒定，网格 = 视口像素 / 柱像素</h2>
 * <pre>
 *   每柱像素  = 13px             （恒定 ⇒ 视觉密度稳定，不随缩放变化）
 *   柱子数    = 视口像素 / 13
 *   每柱方块  = BASE_BLOCKS_PER_PILLAR / zoomRatio
 *   窗口      = 柱子数 × 每柱方块
 *   网格(细格) = 柱子数 × 2      （1 柱 = 2×2 细格）
 * </pre>
 *
 * <p>⇒ 推近时：柱子数不变（屏幕密度恒定）、每柱方块变少 ⇒ <b>视野缩小、精度提高</b>，
 * 这就是用户要的"视野缩小后每柱代表的方块变少"。</p>
 *
 * <p>这也是参考项目三层视图的同一原理（大沙盘 15.6 格/格 → 区块精读 16 格/格 →
 * 落点精读 4 格/格）：<b>不堆采样点数，靠缩小窗口提精度</b>。</p>
 *
 * <h2>成本</h2>
 * <p>900px 视口 / 13 ≈ 70 柱 ⇒ 140 细格 ⇒ <b>19,600 采样点</b>，
 * 比旧的 256²（65,536）还少 3.3 倍 ⇒ 采样更快。</p>
 */
public final class SandboxLod {

    /**
     * 每个<b>细格</b>的目标屏幕尺寸（像素）。
     *
     * <h2>★ 为什么按细格而不是按柱子定（2026-11-08 第三次修正）</h2>
     * <p>先前按「每柱 13px」算 ⇒ 900px 只有 69 根柱 = 139 细格 ⇒ <b>19,044 采样点</b>。
     * 而我们的<b>平面视图</b> 1:1 是 900² = <b>810,000</b> 点 —— 差 42 倍，
     * 精度自然比不上（探针直接报 FAIL）。</p>
     *
     * <p>⇒ 细格才是"采样粒度"，应该和屏幕像素对齐：</p>
     * <pre>
     *   细格数 = 视口像素 / 2      （1 细格 ≈ 2px，肉眼已看不出格子）
     *   柱子数 = 细格数 / 2        （1 柱 = 2×2 细格）
     * </pre>
     * <p>900px ⇒ 450 细格 ⇒ 225 柱 ⇒ <b>202,500 采样点</b>，
     * 与平面视图同量级（81 万的 1/4，而每格还聚合了 2×2 细格的信息）。</p>
     *
     * <p>柱子屏幕尺寸 = 视口/柱数 = 4px。看似比 13px 细，但这是<b>对的</b>：
     * 平面视图也是 1px 一格；柱子只要能看出立体感即可，
     * 颜色精度靠细格 —— 这正是参考项目 `PER_COLUMN=2`（柱=2×2 细格）的设计意图。</p>
     */
    public static final double CELL_PX = 2.0;

    /**
     * 每根柱子在 zoom = {@link #BASE_ZOOM} 时代表的方块数。
     *
     * <p>对齐参考项目大沙盘：±2000 格 / 128 柱 ⇒ 一根柱约 31 格方块。</p>
     */
    public static final double BASE_BLOCKS_PER_PILLAR = 31.0;

    /** 与 {@link #BASE_BLOCKS_PER_PILLAR} 对应的 zoom（沙盘默认缩放）。 */
    public static final float BASE_ZOOM = 2.0f;

    /** 缩放下限。 */
    public static final float ZOOM_MIN = 0.6f;

    /**
     * 缩放上限。
     *
     * <p>硬约束：<b>每细格 ≥ 1 格方块</b>。
     * 每细格 = 每柱方块 / perColumn，而窗口 = 柱数 × 每柱方块 ≥ 网格数
     * ⇒ 每柱方块 ≥ perColumn ⇒ {@code 31/zoomRatio ≥ perColumn}
     * ⇒ {@code zoomRatio ≤ 31/perColumn}。</p>
     *
     * <p>900px 视口 perColumn≈6 ⇒ zoomRatio ≤ 5.2 ⇒ zoom ≤ 10.3。
     * 更高倍数的"推近"已经没有精度收益（细格已到 1 格/细格的地板），
     * 只会把柱子拉大 ⇒ 所以在 {@link #windowFor} 里夹住即可。</p>
     */
    public static final float ZOOM_MAX = 20.0f;

    /** 网格下限（避免窗口极小时采样过疏）。 */
    private static final int MIN_GRID = 64;
    /**
     * 网格上限。
     *
     * <p>512 细格 = 262,144 采样点。实测 256² 采样约 143ms（12 线程），
     * 512² 是 4 倍 ⇒ 约 570ms，后台跑可接受（一次推近只采一次）。
     * 再高就会显著拖慢拖动/缩放响应。</p>
     */
    private static final int MAX_GRID = 512;

    private SandboxLod() { }

    /** 相对基准的缩放倍率（&gt;1 = 更细）。 */
    public static double zoomRatio(float zoom) {
        double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        return z / (double) BASE_ZOOM;
    }

    /**
     * 该视口尺寸下的网格（每边<b>细格</b>数）= 视口像素 / {@link #CELL_PX}。
     *
     * @param viewportPx 视口边长（像素）
     */
    public static int gridFor(int viewportPx) {
        int g = (int) Math.round(viewportPx / CELL_PX);
        return Math.max(MIN_GRID, Math.min(MAX_GRID, g));
    }

    /** 该视口尺寸下的柱子数（每边）= 细格数 / {@link #perColumnFor}。 */
    public static int pillarsFor(int viewportPx) {
        return Math.max(1, gridFor(viewportPx) / perColumnFor(viewportPx));
    }

    /**
     * 一根柱子聚合几个细格（每边，<b>随视口自适应</b>）。
     *
     * <h2>★ 为什么不能固定 2（2026-11-08 实测）</h2>
     * <p>细格提到 450（按像素）后，若仍按 {@code PER_COLUMN=2} 聚合，
     * 柱子会变成 <b>225 根、每根只有 4px</b> ⇒ 每根柱子各带一圈侧墙台阶，
     * 整幅叠成<b>密集涟漪/同心环纹理</b>（离线出图实测），地形形状被完全淹没。</p>
     *
     * <p>参考项目用 2 是因为它细格只有 256；我们细格 450，
     * 聚合系数必须同步变大，才能让柱子保持<b>可辨识的视觉尺寸</b>。</p>
     *
     * <h2>取值</h2>
     * <pre>
     *   perColumn = round(细格数 / 目标柱数)，目标柱数 = 视口 / PILLAR_PX
     * </pre>
     * <p>900px ⇒ 目标 69 柱 ⇒ 450/69 ≈ 6.5 ⇒ 取 <b>6</b> ⇒ 实际 75 柱、每柱 12px。
     * 与参考项目"一根柱约 31 格方块、屏幕上十几像素"的观感一致。</p>
     */
    public static int perColumnFor(int viewportPx) {
        int grid = gridFor(viewportPx);
        double targetPillars = Math.max(8, viewportPx / PILLAR_PX);
        int per = (int) Math.round(grid / targetPillars);
        return Math.max(2, Math.min(16, per));
    }

    /** 柱子的目标屏幕尺寸（像素）—— 只用于反推聚合系数。 */
    private static final double PILLAR_PX = 13.0;

    /**
     * 该缩放 + 视口下，一根柱子代表多少方块（<b>连续</b>，随缩放变化）。
     *
     * <p>下限：每细格 ≥ 1 格 ⇒ 每柱 ≥ 2 格。</p>
     */
    public static double blocksPerPillar(float zoom) {
        double b = BASE_BLOCKS_PER_PILLAR / zoomRatio(zoom);
        return Math.max(2.0, b);   // 1 细格 = b/2 ≥ 1
    }

    /**
     * 该缩放 + 视口下的世界窗口边长（方块）。
     *
     * <p>★ <b>硬约束：窗口 ≥ 细格数</b>（即每细格 ≥ 1 格方块）。
     * 地形按方块定义、采样坐标取整 ⇒ 步长 &lt; 1 会让相邻细格落到同一方块
     * = 重复采样 ⇒ 梯田伪影（探针实测：不加约束时 step 会降到 0.46）。</p>
     *
     * @param viewportPx 视口边长（像素）
     */
    public static int windowFor(float zoom, int viewportPx) {
        int grid = gridFor(viewportPx);
        double raw = pillarsFor(viewportPx) * blocksPerPillar(zoom);
        // 量化到 16 格：避免每滚一格就重采（小幅滚动沿用同一窗口）
        int q = (int) (Math.round(raw / 16.0) * 16);
        // 下限 1：窗口至少覆盖 grid 格（每细格 ≥ 1 方块）
        return Math.max(grid, Math.max(64, q));
    }

    /**
     * 实际生效的每细格方块数（= 窗口/网格）。
     *
     * <p>比例尺显示必须用这个值（窗口被量化过），否则读数与画面不符。</p>
     */
    public static double effectiveStep(float zoom, int viewportPx) {
        return windowFor(zoom, viewportPx) / (double) gridFor(viewportPx);
    }

    /** 比例尺文字（地图软件式读数）。 */
    public static String scaleLabel(float zoom, int viewportPx) {
        double step = effectiveStep(zoom, viewportPx);
        int win = windowFor(zoom, viewportPx);
        double perPillar = blocksPerPillar(zoom);
        if (step >= 10.0) {
            return String.format(java.util.Locale.ROOT,
                    "1 柱 = %.0f 格 · 视野 %d 格", perPillar, win);
        }
        return String.format(java.util.Locale.ROOT,
                "1 柱 = %.1f 格 · 视野 %d 格", perPillar, win);
    }

    /**
     * 该缩放 + 视口下窗口覆盖的 tract 数（决定粗层水位是否可负担）。
     *
     * <p>tract = 512 格，粗层 635ms/冷 tract。</p>
     */
    public static int tractsFor(float zoom, int viewportPx) {
        int win = windowFor(zoom, viewportPx);
        int across = (int) Math.ceil(win / 512.0) + 1;
        return across * across;
    }

    /** 该缩放 + 视口是否查<b>粗层水位</b>（画河道/湖面）。25 tract 以内可接受（约 16s 冷启动）。 */
    public static boolean usesCoarseWater(float zoom, int viewportPx) {
        return tractsFor(zoom, viewportPx) <= 25;
    }

    /**
     * 是否走完整水文管线（{@code sampleCell}）。
     *
     * <p><b>恒 false</b>：实测每个新 region 3.6~10.9 秒，沙盘不可用。
     * 河道水面由粗层水位提供。</p>
     */
    public static boolean usesHydroPath(float zoom) {
        return false;
    }

    /**
     * 滚轮步长：<b>远快近慢</b>（用户选定）。
     *
     * <ul>
     *   <li>zoom &lt; 4（广域）：1.30 倍/步 —— 快速跨过格局层；</li>
     *   <li>4 ≤ zoom &lt; 12（中景）：1.20 倍/步；</li>
     *   <li>zoom ≥ 12（近景）：1.12 倍/步 —— 精细调整。</li>
     * </ul>
     */
    public static float nextZoom(float zoom, boolean zoomIn) {
        float step;
        if (zoom < 4.0f) step = 1.30f;
        else if (zoom < 12.0f) step = 1.20f;
        else step = 1.12f;
        float next = zoomIn ? zoom * step : zoom / step;
        return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next));
    }

    /** 人类可读摘要（日志用）。 */
    public static String describe(float zoom, int viewportPx) {
        return String.format(java.util.Locale.ROOT,
                "%s（网格 %d，每细格 %.2f 格）",
                scaleLabel(zoom, viewportPx), gridFor(viewportPx),
                effectiveStep(zoom, viewportPx));
    }
}
