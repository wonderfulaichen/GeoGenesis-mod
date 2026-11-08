package com.geogenesis.client.preview;

/**
 * <b>沙盘 LOD（方案 B：窗口与每柱方块独立）</b>—— ★ 2026-11-08 第八版。
 *
 * <h2>用户选定方案 B</h2>
 * <blockquote>
 * 「选 B：窗口和每柱方块独立，柱数自动算。」
 * </blockquote>
 *
 * <h2>两条独立轴 + 一个派生量</h2>
 * <pre>
 *   窗口 W     = BASE_WINDOW / 2^k     （滚轮控制 k，连续 —— 有推进感）
 *   每柱方块 P = BASE_BLOCKS / 2^j     （滚轮控制 j，连续 —— 控制精度）
 *   柱数 N     = W / P                 （结果：自动算出，无抵消可能）
 * </pre>
 *
 * <p><b>为什么 B 不会抵消</b>：v5 的恒等式是"柱数 ×2 且每柱 ÷2"同时发生。
 * 方案 B 里 k 和 j 是<b>两条独立的滚轮轴</b>：只有当用户恰好让 k = j 时才会抵消 ——
 * 而那时画面确实该不变（窗口减半、柱子加倍、每柱大小不变 = 同一视野更精细），
 * 这是正确行为而不是 bug。</p>
 *
 * <h2>两条轴怎么由一个滚轮驱动</h2>
 * <p>单滚轮设计：滚轮直接驱动窗口 k；精度轴 j 跟随 k 但带速度系数 r：</p>
 * <pre>
 *   k = log2(zoomRatio)          （窗口轴：每滚一格 k+1）
 *   j = r × k                    （精度轴）
 * </pre>
 * <p>r 由缩放段给出（见 {@link #precisionRatio}）：拉远/推进段 r=1（柱数不变），
 * 近景段 r=2（每柱方块减半快于窗口 ⇒ 柱数翻倍 = <b>高模</b>）。</p>
 *
 * <h2>约束（防弧纹/防重复采样）</h2>
 * <ul>
 *   <li><b>每柱 ≥ 1 格方块</b>：P ≥ 1，到地板后精度轴停住。</li>
 *   <li><b>柱子屏幕尺寸 ≥ 12px</b>：P 与 W 组合若使柱子过细则夹住 P。
 *       这是<b>人眼分辨率的物理事实</b>，不是 MC 限制 —— 参考项目同样遵守。</li>
 * </ul>
 *
 * <h2>与 MC 的边界（用户要求"能脱离的脱离"）</h2>
 * <p>本类零 MC 依赖；采样/数据/光照/配色/水位层均无 MC 依赖；
 * 只有绘制层（{@code SandboxRenderer}）耦合 {@code GuiGraphics} —— 与参考项目同一边界。</p>
 */
public final class SandboxLod {

    // ===== 独立轴 1：窗口 =====

    /** 最拉远时的世界窗口（方块）。 */
    public static final double BASE_WINDOW = 2400.0;

    // ===== 独立轴 2：每柱方块 =====

    /** 最拉远时的每柱方块数。 */
    public static final double BASE_BLOCKS = 75.0;

    /** 每柱方块下限（每细格 ≥ 1 方块，防重复采样）。 */
    public static final double MIN_BLOCKS = 1.0;

    // ===== 缩放（滚轮） =====

    /** 基准 zoom（进入沙盘默认值）。 */
    public static final float BASE_ZOOM = 1.0f;
    /** 每格滚轮的缩放步长（×2：窗口减半）。 */
    public static final float WHEEL_STEP = 2.0f;
    /** 允许拉远的格数（视野变大，看到更多）。 */
    public static final int MAX_ZOOM_OUT_STEPS = 3;
    /**
     * 允许推近的格数。
     *
     * <p>推近会让相机 scale ×2/格，2 档后盘面已明显溢出视口（只看到局部）。
     * 参考项目 zoom 上限也仅比默认大 4 倍。取 2 档（×4）。</p>
     */
    public static final int MAX_ZOOM_IN_STEPS = 2;

    public static final float ZOOM_MIN = (float) (BASE_ZOOM / Math.pow(WHEEL_STEP, MAX_ZOOM_OUT_STEPS));
    public static final float ZOOM_MAX = (float) (BASE_ZOOM * Math.pow(WHEEL_STEP, MAX_ZOOM_IN_STEPS));

    // ===== 派生约束 =====

    /** 柱子最小屏幕尺寸（像素）—— 人眼分辨结构下限，防弧纹。 */
    public static final double MIN_PILLAR_PX = 12.0;

    private SandboxLod() { }

    /** zoom / BASE_ZOOM（&gt;1 = 推近）。 */
    public static double zoomRatio(float zoom) {
        double z = Math.max((double) ZOOM_MIN, Math.min((double) ZOOM_MAX, (double) zoom));
        return z / (double) BASE_ZOOM;
    }

    /** 窗口轴指数 k = log2(zoomRatio)，可为负（拉远）。 */
    public static double windowAxis(float zoom) {
        return Math.log(zoomRatio(zoom)) / Math.log(2.0);
    }

    /**
     * 精度轴相对窗口轴的速度 r。
     *
     * <h2>★ 2026-11-08 用户澄清后修正</h2>
     * <p>用户原话：「固定柱子，但柱子本身宽度就对应 1wu 内部单位，
     * 然后随着我们缩小画面我们看到的柱子数就变多了。<b>不是柱子宽度变了。</b>」</p>
     *
     * <p>含义：柱子是固定物理尺寸（1wu）的方块。<b>视野缩小 ⇒ 同样大小的方块
     * 在屏幕上变多</b>。所以柱数必须<b>与窗口同步变化</b>：</p>
     * <pre>
     *   窗口 W = 2400/2^k      （缩小）
     *   每柱 P = 75/2^k        （同步缩小，保持"格子在物理世界里不变大小"）
     *   柱数 N = W/P = 32      ← 恒定！
     * </pre>
     * <p>等等 —— 这样柱数恒定，柱子屏幕尺寸也恒定（因为窗口缩小 + 柱数不变
     * ⇒ 每柱占屏幕更多像素）。<b>那画面就是"同一片地形被放大"</b>，
     * 正是用户说的"固定位置、完全不动"。</p>
     *
     * <h2>真正正确的读法</h2>
     * <p>"柱子数变多"是指：随着缩小画面（拉远），能看到<b>更大范围</b>的地形，
     * 而柱子物理尺寸不变 ⇒ 需要的柱子总数变多。即</p>
     * <pre>
     *   拉远（zoom↓）⇒ 窗口 W 变大 ⇒ 柱数 N = W/P 变大（P 是固定物理尺寸）
     *   推近（zoom↑）⇒ 窗口 W 变小 ⇒ 柱数 N 变小
     * </pre>
     * <p>⇒ <b>每柱方块 P 恒定</b>（1wu = 固定方块数），窗口随缩放变，柱数自动跟随。
     * 这样柱子屏幕尺寸恒定、画面内容真实"推近/拉远"、柱数随视野自动增减。</p>
     */
    public static double precisionRatio(float zoom) {
        return 0.0;   // 精度轴不动：P 恒定 ⇒ 柱数 = W/P 自动跟随窗口
    }

    /** <b>独立轴 1：世界窗口</b>（方块）—— 连续；量化到 16 格避免每滚一格重采。 */
    public static int windowFor(float zoom) {
        double k = windowAxis(zoom);
        double raw = BASE_WINDOW / Math.pow(2.0, k);
        int q = (int) (Math.round(raw / 16.0) * 16);
        return Math.max(64, q);
    }

    /**
     * <b>每柱代表的方块数</b> —— 推近时变小（近处精模）。
     *
     * <h2>★ 2026-11-08 最终确定的关系</h2>
     * <p>用户反复说的核心：「<b>近处精模远处粗模</b>」。标准地图 LOD 语义：</p>
     * <pre>
     *   推近（zoom↑）：每柱方块 P↓，窗口 W↓，柱数 N=W/P 必须↑  ← 精模
     *   拉远（zoom↓）：每柱方块 P↑，窗口 W↑，柱数 N=W/P 必须↓  ← 粗模
     * </pre>
     *
     * <p>要同时满足 W↓ 且 N↑，P 必须比 W 下降得<b>更快</b>。
     * 取 <b>P = BASE_BLOCKS / 4^k</b>（k=log2 zoomRatio）：
     * W = 2400/2^k，则 N = (2400/2^k)/(75/4^k) = 32 × 2^k。</p>
     *
     * <p>⇒ <b>每推近一格：窗口减半、柱数翻倍</b> —— 这就是"近处精模"。
     * 柱屏幕宽 = 视口/N，会随 N 减小，但有 12px 下限保护（防弧纹）。</p>
     *
     * @param viewportPx 视口边长（像素）
     */
    public static double blocksPerPillar(float zoom, int viewportPx) {
        double k = windowAxis(zoom);
        // P = 75 / 4^k  ⇒ 比窗口(÷2^k)降得快一倍 ⇒ N = 32×2^k
        double p = BASE_BLOCKS / Math.pow(4.0, k);
        if (p < MIN_BLOCKS) p = MIN_BLOCKS;

        // 视觉下限：柱数 N = W/P ≤ viewport/12 ⇒ P ≥ W×12/viewport
        //   （柱太细会糊成弧纹，宁可降精度）
        double minP = windowFor(zoom) * MIN_PILLAR_PX / Math.max(1, viewportPx);
        if (p < minP) p = minP;
        return p;
    }

    /** 兼容重载（900px 视口）。 */
    public static double blocksPerPillar(float zoom) {
        return blocksPerPillar(zoom, 900);
    }

    /** <b>派生量</b>：柱子数 = 窗口 / 每柱方块（取偶，便于对齐）。 */
    public static int pillarsFor(float zoom, int viewportPx) {
        double n = windowFor(zoom) / blocksPerPillar(zoom, viewportPx);
        int p = (int) Math.round(n);
        return Math.max(4, p - (p & 1));
    }

    /** 兼容重载。 */
    public static int pillarsFor(float zoom) {
        return pillarsFor(zoom, 900);
    }

    /** 采样网格（每边细格数）= 柱数（一细格一根柱）。 */
    public static int gridFor(float zoom, int viewportPx) {
        return pillarsFor(zoom, viewportPx);
    }

    /** 兼容重载。 */
    public static int gridFor(float zoom) {
        return gridFor(zoom, 900);
    }

    /** 每细格方块数 = 每柱方块。 */
    public static double effectiveStep(float zoom, int viewportPx) {
        return blocksPerPillar(zoom, viewportPx);
    }

    /** 兼容重载。 */
    public static double effectiveStep(float zoom) {
        return effectiveStep(zoom, 900);
    }

    /** 比例尺读数。 */
    public static String scaleLabel(float zoom, int viewportPx) {
        return String.format(java.util.Locale.ROOT,
                "视野 %d 格 · %d 柱 · 1 柱 = %.1f 格",
                windowFor(zoom), pillarsFor(zoom, viewportPx),
                blocksPerPillar(zoom, viewportPx));
    }

    /** 兼容重载。 */
    public static String scaleLabel(float zoom) {
        return scaleLabel(zoom, 900);
    }

    /** 窗口覆盖的 tract 数。 */
    public static int tractsFor(float zoom) {
        int win = windowFor(zoom);
        int across = (int) Math.ceil(win / 512.0) + 1;
        return across * across;
    }

    /** 是否查粗层水位（25 tract 以内）。 */
    public static boolean usesCoarseWater(float zoom) {
        return tractsFor(zoom) <= 25;
    }

    /** 是否走完整水文管线（恒 false）。 */
    public static boolean usesHydroPath(float zoom) {
        return false;
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
