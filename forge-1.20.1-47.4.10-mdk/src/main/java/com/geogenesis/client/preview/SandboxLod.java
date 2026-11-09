package com.geogenesis.client.preview;

/**
 * <b>沙盘尺度规格</b> —— ★ 2026-11-08 定稿（按参考项目结构，1 柱 ≈ 16 格方块）。
 *
 * <h2>用户裁定（原话）</h2>
 * <blockquote>
 * ①「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。」<br>
 * ②「这个预览功能其实就可以当成我们在外部核心层（地形建模软件）的视图。
 *    这样在这里跑的计算后续可以直接应用到进入世界加载中。」<br>
 * ③「既然这样其实我们改成按参考项目 16x16 方块吧。其他细节你看参考项目。」
 * </blockquote>
 *
 * <h2>★ 三层数据结构（照参考项目 {@code SiteMapSampler}，别再混为一谈）</h2>
 * <pre>
 *   columns  柱子数（高度点数）       默认 128
 *   fine     细格数 = columns × 2    默认 256      ← {@code HEIGHT_STRIDE = 2}
 *   step     相邻高度点的方块距       默认 16
 *   窗口      columns × step         默认 2048 格
 *   每柱      step 格方块 ≈ 16        ← 用户裁定的"16×16 方块"
 * </pre>
 *
 * <p><b>关键</b>：{@code fine} 只由 {@code columns × HEIGHT_STRIDE} 决定，
 * <b>与窗口方块数无关</b>。参考项目源码 {@code SiteMapSampler} 第 74-77 行：</p>
 * <pre>
 *   高度网格间隔（细格）：每 2 格采一个高度，一根柱子正好盖 2×2 细格
 *   ⇒ 柱子数和高度点数是同一个数，细格数是它们的 2 倍
 * </pre>
 *
 * <h2>★ 我此前的致命错误（直接导致 OOM 崩溃）</h2>
 * <p>我把 {@code gridFor()} 写成 {@code windowFor()}（2048）⇒
 * 每帧分配 2048×2048 = <b>419 万</b>个格子的十几个数组
 * ⇒ {@code OutOfMemoryError: Java heap space}（crash-2026-11-09_03.26.03 已取证）。</p>
 * <p>正确：{@code gridFor() = pillarsFor() × HEIGHT_STRIDE = 128 × 2 = 256}，
 * 3 个数量级之差。</p>
 *
 * <h2>参考项目常数对照</h2>
 * <table border="1">
 *   <tr><th>项</th><th>参考项目</th><th>本项目</th></tr>
 *   <tr><td>柱子数基准</td><td>{@code COLUMNS_MIN = 128}</td><td>128</td></tr>
 *   <tr><td>柱子数上限</td><td>{@code COLUMNS_HARD_MAX = 512}</td><td>512</td></tr>
 *   <tr><td>{@code HEIGHT_STRIDE}</td><td>2</td><td>2</td></tr>
 *   <tr><td>每柱方块（{@code step}）</td><td>≈31（±2000 窗口）</td><td><b>16</b>（用户裁定）</td></tr>
 *   <tr><td>窗口</td><td>4000</td><td>128×16 = <b>2048</b></td></tr>
 *   <tr><td>细格</td><td>256</td><td><b>256</b></td></tr>
 *   <tr><td>坡度分母</td><td>{@code 2×step×2 ≈ 124}</td><td>{@code 2×16 = 32}</td></tr>
 *   <tr><td>坡度增益 / 光照范围</td><td>0.55 / clamp(0.55, 1.30)</td><td>同</td></tr>
 *   <tr><td>水面衰减 / RELIEF / WATER_SINK</td><td>0.35 / 0.40 / 0.05</td><td>同</td></tr>
 * </table>
 *
 * <h2>错误史（十版，别再重犯）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>错法</th><th>后果</th></tr>
 *   <tr><td>1</td><td>柱数 ×2 且每柱 ÷2</td><td>窗口恒定，滚轮无反应</td></tr>
 *   <tr><td>2</td><td>每柱 75 格</td><td>柱子过粗</td></tr>
 *   <tr><td>3</td><td>每柱随缩放变</td><td>违背柱宽恒定</td></tr>
 *   <tr><td>4</td><td>独立 {@code ProgressiveSampler} 二次采样</td><td>数据源错</td></tr>
 *   <tr><td>5</td><td>perColumn=1（一柱=一方块）</td><td>450 柱 × 1.8px，地形糊成纹理</td></tr>
 *   <tr><td>6</td><td>perColumn=horizontalScale（2）</td><td>窗口仅 256 格</td></tr>
 *   <tr><td>7</td><td>用归一化 r 算坡度光照</td><td>亮度差 0.003 ⇒ 整幅均匀绿</td></tr>
 *   <tr><td>8</td><td><b>gridFor = windowFor（2048）</b></td><td><b>OOM 崩溃</b></td></tr>
 *   <tr><td>9</td><td>scale = areaSize×0.92</td><td>超出视口 2.6 倍 ⇒ 只看到局部缓坡</td></tr>
 *   <tr><td>10</td><td>圆形盘面裁剪</td><td>大沙盘应为方形铺满</td></tr>
 * </table>
 */
public final class SandboxLod {

    // ===== 三层结构（照参考项目）=====

    /** 细格 / 柱（每边）—— 参考项目 {@code SiteMapSampler.HEIGHT_STRIDE = 2}。 */
    public static final int HEIGHT_STRIDE = 2;

    /** 每柱覆盖的方块数（每边）—— 用户裁定 16。 */
    public static final int STEP_BLOCKS = 16;

    /** 基准柱数（每边）—— 参考项目 {@code COLUMNS_MIN = 128}。 */
    public static final int BASE_PILLARS = 128;

    /** 柱数硬上限 —— 参考项目 {@code COLUMNS_HARD_MAX = 512}。 */
    public static final int MAX_PILLARS = 512;

    /**
     * 基准 zoom（进入沙盘默认值）。
     *
     * <p>★ 2026-11-09 B4 改为 <b>2.0</b>（对齐参考项目 yardsite 默认）。</p>
     * <p>理由：参考项目的竖向立体感来自 {@code scale = areaSize × 0.46 × zoom}，
     * zoom 默认 2.0 ⇒ 盘面超视口 2.6 倍、竖向跨度 245px（视口 30%）。</p>
     * <p>我们此前默认 1.0 ⇒ 竖向仅 93px（11%）⇒ 整片看着是平的。
     * 改的是<b>缩放档位</b>，{@code RELIEF} 保持 yardsite 的 0.40 不动。</p>
     */
    public static final float BASE_ZOOM = 2.0f;

    /** 滚轮步长：每格 ×2 / ÷2（窗口与柱数同步翻倍/减半）。 */
    public static final float WHEEL_STEP = 2.0f;

    /** 允许拉远格数：柱数 128 → 256 → 512。 */
    public static final int MAX_ZOOM_OUT_STEPS = 2;

    /** 允许推近格数：柱数 128 → 64 → 32（柱宽 7→14→28px）。 */
    public static final int MAX_ZOOM_IN_STEPS = 2;

    public static final float ZOOM_MIN = (float) (BASE_ZOOM / Math.pow(WHEEL_STEP, MAX_ZOOM_OUT_STEPS));
    public static final float ZOOM_MAX = (float) (BASE_ZOOM * Math.pow(WHEEL_STEP, MAX_ZOOM_IN_STEPS));

    /** 柱子最小屏幕尺寸（像素）。 */
    public static final double MIN_PILLAR_PX = 2.0;

    private SandboxLod() { }

    /** zoom / BASE_ZOOM。 */
    public static double zoomRatio(float zoom) {
        double z = Math.max((double) ZOOM_MIN, Math.min((double) ZOOM_MAX, (double) zoom));
        return z / (double) BASE_ZOOM;
    }

    /** 每柱细格数（每个方向）= {@link #HEIGHT_STRIDE}。 */
    public static int perColumn() {
        return HEIGHT_STRIDE;
    }

    /** 每柱方块数（恒定 16）。 */
    public static double blocksPerPillar() {
        return STEP_BLOCKS;
    }

    /**
     * <b>柱子数（每边）</b> —— 随缩放变化。
     *
     * <pre>
     *   期望柱数 = 128 / zoomRatio      （推近 ⇒ 少；拉远 ⇒ 多）
     *   夹在 [8, min(512, 视口/2px)]
     * </pre>
     */
    public static int pillarsFor(float zoom, int viewportPx) {
        int want = (int) Math.round(BASE_PILLARS / zoomRatio(zoom));
        int cap = Math.min(MAX_PILLARS, Math.max(8, (int) (viewportPx / MIN_PILLAR_PX)));
        int p = Math.min(want, cap);
        return Math.max(8, p - (p & 1));
    }

    /** 兼容重载（900px 视口）。 */
    public static int pillarsFor(float zoom) {
        return pillarsFor(zoom, 900);
    }

    /**
     * <b>细格数（每边）</b> = 柱数 × {@link #HEIGHT_STRIDE}。
     *
     * <p>★ 这里曾经写成 {@code windowFor()}（2048）⇒ 419 万格 ⇒ OOM 崩溃。
     * 参考项目里细格数<b>只由柱数决定</b>，与窗口方块数无关。</p>
     */
    public static int gridFor(float zoom, int viewportPx) {
        return Math.max(2, pillarsFor(zoom, viewportPx) * HEIGHT_STRIDE);
    }

    /** 兼容重载。 */
    public static int gridFor(float zoom) {
        return gridFor(zoom, 900);
    }

    /** <b>世界窗口（方块）</b> = 柱数 × 每柱方块。 */
    public static int windowFor(float zoom, int viewportPx) {
        return Math.max(16, pillarsFor(zoom, viewportPx) * STEP_BLOCKS);
    }

    /** 兼容重载。 */
    public static int windowFor(float zoom) {
        return windowFor(zoom, 900);
    }

    /** 每细格方块数 = 窗口 / 细格数 = 16/2 = 8。 */
    public static double effectiveStep(float zoom, int viewportPx) {
        return STEP_BLOCKS / (double) HEIGHT_STRIDE;
    }

    /** 兼容重载。 */
    public static double effectiveStep(float zoom) {
        return effectiveStep(zoom, 900);
    }

    /** 比例尺读数。 */
    public static String scaleLabel(float zoom, int viewportPx) {
        return String.format(java.util.Locale.ROOT,
                "视野 %d 格 · %d 柱 · 1 柱 = %d 格方块",
                windowFor(zoom, viewportPx), pillarsFor(zoom, viewportPx), STEP_BLOCKS);
    }

    /** 兼容重载。 */
    public static String scaleLabel(float zoom) {
        return scaleLabel(zoom, 900);
    }

    /** 窗口覆盖的 tract 数（决定粗层水位可负担性）。 */
    public static int tractsFor(float zoom, int viewportPx) {
        int win = windowFor(zoom, viewportPx);
        int across = (int) Math.ceil(win / 512.0) + 1;
        return across * across;
    }

    /** 兼容重载。 */
    public static int tractsFor(float zoom) {
        return tractsFor(zoom, 900);
    }

    /** 是否查粗层水位（25 tract 以内）。 */
    public static boolean usesCoarseWater(float zoom, int viewportPx) {
        return tractsFor(zoom, viewportPx) <= 25;
    }

    /** 兼容重载。 */
    public static boolean usesCoarseWater(float zoom) {
        return usesCoarseWater(zoom, 900);
    }

    /** 滚轮：每格 ×2 / ÷2。 */
    public static float nextZoom(float zoom, boolean zoomIn) {
        float next = zoomIn ? zoom * WHEEL_STEP : zoom / WHEEL_STEP;
        return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next));
    }

    /** 人类可读摘要。 */
    public static String describe(float zoom, int viewportPx) {
        return scaleLabel(zoom, viewportPx);
    }

    /** 兼容重载。 */
    public static String describe(float zoom) {
        return describe(zoom, 900);
    }
}
