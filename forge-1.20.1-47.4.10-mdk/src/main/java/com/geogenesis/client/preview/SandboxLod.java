package com.geogenesis.client.preview;

/**
 * <b>沙盘 LOD（真·细节层级）</b>—— ★ 2026-11-08 第四次重做。
 *
 * <h2>★ 用户的原始需求（我此前理解错了三次）</h2>
 * <p>原话：「<b>就是缩放近处精模远处粗模</b>」「为什么固定柱数？
 * 你这样我无论精度如何看到的效果不是已经不变吗？」</p>
 *
 * <p>我之前的错误：把"采样网格固定、只改每格代表范围"当成 LOD。
 * 那样<b>柱子数量永远一样</b>，画面构图不变，当然"效果不变"。</p>
 *
 * <h2>正确模型（与参考项目三层视图同一原理）</h2>
 * <pre>
 *   拉远（zoom 小）：窗口大 + 柱子少而粗  → 粗模（看格局）
 *   推近（zoom 大）：窗口小 + 柱子多而细  → 精模（看细节）
 * </pre>
 *
 * <p>参考项目实测参数：</p>
 * <table border="1">
 *   <caption>参考项目三层</caption>
 *   <tr><th>层</th><th>柱子</th><th>每格</th><th>窗口</th></tr>
 *   <tr><td>大沙盘</td><td>128</td><td>15.6 格</td><td>±2000</td></tr>
 *   <tr><td>区块精读</td><td>129</td><td><b>16 格</b></td><td>1024</td></tr>
 *   <tr><td>落点精读</td><td>121</td><td><b>4 格</b></td><td>240</td></tr>
 * </table>
 * <p>注意：<b>柱子数量基本不变（120~130），变的是"每格代表多少方块"</b>。
 * 但它靠<b>切换图层</b>实现，不是连续缩放。</p>
 *
 * <h2>我们的连续 LOD 怎么做</h2>
 * <p>核心矛盾：柱子屏幕尺寸要够大（看得出立体），同时精度要随缩放提高。</p>
 * <p>解法 = <b>网格随缩放增长</b>，柱子屏幕尺寸<b>近似恒定</b>：</p>
 * <pre>
 *   每柱方块 = BASE_BLOCKS_PER_PILLAR / zoomRatio     // 推近 ⇒ 变小
 *   柱子数   = clamp(视口 / PILLAR_PX_SCREEN, 32, 256)  // 屏幕密度恒定
 *   窗口     = 柱子数 × 每柱方块                        // 推近 ⇒ 变小（推进感）
 *   网格     = 柱子数 × perColumn                       // 精度
 * </pre>
 *
 * <p>⇒ 推近时：窗口缩小（推进）、每柱方块变少（更精细）、柱子数量不变（密度稳定）。
 * 这就是"近处精模远处粗模"，且不会出现"构件不变所以看不出变化"。</p>
 *
 * <h2>成本约束</h2>
 * <p>网格 = 柱数 × perColumn。900px 视口、柱 75 根、perColumn=2 ⇒ 150 细格 = 22,500 点。
 * 若 perColumn 随缩放增长（如推近时 2、拉远时 8），网格在 150~600 之间浮动，
 * 采样点 22,500~360,000 —— 拉远时更省，推近时更细，符合 LOD 精神。</p>
 */
public final class SandboxLod {

    /**
     * 柱子目标屏幕尺寸（像素）。
     *
     * <p>参考项目大沙盘：900px 视口 / 128 柱 ≈ <b>7px</b>。
     * 取 7px 与它同档（够看出方块台阶，又不至于密到糊）。</p>
     */
    public static final double PILLAR_PX = 7.0;

    /** 每柱在 zoom = {@link #BASE_ZOOM} 时代表的方块数（对齐参考项目大沙盘 31 格）。 */
    public static final double BASE_BLOCKS_PER_PILLAR = 31.0;

    /** 与 {@link #BASE_BLOCKS_PER_PILLAR} 对应的 zoom。 */
    public static final float BASE_ZOOM = 2.0f;

    /** 缩放下限。 */
    public static final float ZOOM_MIN = 0.6f;
    /**
     * 缩放上限。
     *
     * <p>硬约束：每<b>细格</b> ≥ 1 格方块。
     * 每细格 = 每柱方块 / perColumn，每柱方块 = 31/zoomRatio。
     * 推近到每柱 4 格（÷perColumn=2）⇒ 每细格 2 格，还有余量；
     * zoomRatio=16（zoom=32）时每柱 1.94 格、每细格 0.97 格 → 触底。
     * ⇒ 夹在 16。</p>
     */
    public static final float ZOOM_MAX = 32.0f;

    /** 柱数上下限（防极端视口）。 */
    private static final int MIN_PILLARS = 32;
    private static final int MAX_PILLARS = 256;

    private SandboxLod() { }

    /** 相对基准的缩放倍率（&gt;1 = 更细）。 */
    public static double zoomRatio(float zoom) {
        double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        return z / (double) BASE_ZOOM;
    }

    /**
     * 柱子数（每边）—— <b>屏幕密度恒定</b>，不随缩放变化。
     *
     * @param viewportPx 视口边长（像素）
     */
    public static int pillarsFor(int viewportPx) {
        int p = (int) Math.round(viewportPx / PILLAR_PX);
        return Math.max(MIN_PILLARS, Math.min(MAX_PILLARS, p));
    }

    /**
     * 一根柱子聚合几个细格（每边）。
     *
     * <p><b>拉远时大、推近时小</b> —— 这是"粗模/精模"的关键：
     * 拉远时一根柱合并更多细格（颜色更概括），推近时更少（保留细节）。</p>
     * <p>夹在 1~8：1 = 一细格一根柱（最精），8 = 八细格一根柱（最粗）。</p>
     */
    public static int perColumnFor(float zoom) {
        double r = zoomRatio(zoom);
        if (r < 0.75) return 8;   // 最拉远：粗模
        if (r < 1.5) return 4;
        if (r < 3.0) return 2;
        return 1;                 // 最推近：一细格一根柱（精模）
    }

    /** 该缩放 + 视口下的网格（每边细格数）= 柱数 × 聚合系数。 */
    public static int gridFor(float zoom, int viewportPx) {
        int g = pillarsFor(viewportPx) * perColumnFor(zoom);
        return Math.max(32, Math.min(1024, g));
    }

    /**
     * 一根柱子代表多少方块（<b>推近 ⇒ 变小</b>）。
     *
     * <p>下限 1：每细格至少 1 格方块（否则重复采样 → 梯田伪影）。</p>
     */
    public static double blocksPerPillar(float zoom) {
        double r = zoomRatio(zoom);
        double b = BASE_BLOCKS_PER_PILLAR / r;
        double minB = Math.max(1.0, perColumnFor(zoom));   // 每细格 ≥ 1
        return Math.max(minB, b);
    }

    /**
     * 世界窗口边长（方块）—— <b>推近 ⇒ 变小</b>（这就是"镜头推进"）。
     *
     * @param viewportPx 视口边长（像素）
     */
    public static int windowFor(float zoom, int viewportPx) {
        double raw = pillarsFor(viewportPx) * blocksPerPillar(zoom);
        int q = (int) (Math.round(raw / 16.0) * 16);
        // 窗口不得小于网格数（每细格 ≥ 1 方块）
        return Math.max(gridFor(zoom, viewportPx), Math.max(64, q));
    }

    /** 实际生效的每细格方块数（= 窗口/网格）。 */
    public static double effectiveStep(float zoom, int viewportPx) {
        return windowFor(zoom, viewportPx) / (double) gridFor(zoom, viewportPx);
    }

    /** 比例尺文字。 */
    public static String scaleLabel(float zoom, int viewportPx) {
        double perPillar = blocksPerPillar(zoom);
        int win = windowFor(zoom, viewportPx);
        int pillars = pillarsFor(viewportPx);
        return String.format(java.util.Locale.ROOT,
                "%d 柱 · 1 柱 = %.1f 格 · 视野 %d 格", pillars, perPillar, win);
    }

    /** 该缩放 + 视口下窗口覆盖的 tract 数。 */
    public static int tractsFor(float zoom, int viewportPx) {
        int win = windowFor(zoom, viewportPx);
        int across = (int) Math.ceil(win / 512.0) + 1;
        return across * across;
    }

    /** 该缩放 + 视口是否查粗层水位。25 tract 以内可接受。 */
    public static boolean usesCoarseWater(float zoom, int viewportPx) {
        return tractsFor(zoom, viewportPx) <= 25;
    }

    /** 是否走完整水文管线（恒 false，见 SandboxWaterLookup）。 */
    public static boolean usesHydroPath(float zoom) {
        return false;
    }

    /**
     * 滚轮步长：<b>远快近慢</b>。
     *
     * <ul>
     *   <li>zoom &lt; 4：1.30 倍/步（快速跨过格局层）</li>
     *   <li>4 ≤ zoom &lt; 12：1.20 倍/步</li>
     *   <li>zoom ≥ 12：1.12 倍/步（精细调整）</li>
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
                "%s（网格 %d，聚合 %d，每细格 %.2f 格）",
                scaleLabel(zoom, viewportPx), gridFor(zoom, viewportPx),
                perColumnFor(zoom), effectiveStep(zoom, viewportPx));
    }
}
