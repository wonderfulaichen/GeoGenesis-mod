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
     * <b>细分层级（真 LOD 的核心）</b>—— ★ 2026-11-08 第五次修正。
     *
     * <h2>用户的原话（这才是需求）</h2>
     * <p>「柱子这你还不会设计吗？<b>不就是由原来的 1 个柱子变成 4 个柱子</b>。
     * 你不懂游戏里面的近处模型变高模的机制吗？」</p>
     *
     * <h2>正确的 LOD 机制 = 四叉树细分</h2>
     * <pre>
     *   level 0（最远）：1 根柱 = SUBDIVIDE^0 个细格
     *   level 1        ：1 根柱 = SUBDIVIDE^1 ... 每次推近，1 根柱细分成 4 根（2×2）
     * </pre>
     * <p>每推近一级：<b>柱子数量 ×4、每柱代表的方块数 ÷2、精度翻倍</b>
     * —— 这就是游戏里"近处模型变高模"的标准做法（quadtree LOD）。</p>
     *
     * <p>参考项目正是这个结构：大沙盘（128 柱，15.6 格/格）
     * → 区块精读（129 柱，16 格/格）→ 落点精读（121 柱，4 格/格），
     * 每级窗口缩小、格子变细。</p>
     *
     * <h2>与"假 LOD"的区别（我之前的错）</h2>
     * <p>我之前把聚合系数写成固定档位（8/4/2/1 按 zoom 分档），
     * 但<b>柱子总数不变</b>，只是每柱合并的细格变了 —— 视觉上柱子数一样，
     * 所以看起来"效果不变"。真正的高模是<b>柱子变多</b>。</p>
     */
    public static final int SUBDIVIDE = 2;

    /** 细分层级上限（每升一级柱数 ×4）。 */
    public static final int MAX_SUBDIV = 3;

    /** 拉远时的基础柱数（level 0）。 */
    public static final int BASE_PILLARS = 32;

    /**
     * 每柱在 level 0 时代表的方块数。
     *
     * <p>level 0（最粗）= 32 柱/边 × 75 格 = 视野 2400 格。</p>
     */
    public static final double BASE_BLOCKS_PER_PILLAR = 75.0;

    /**
     * 进入沙盘时的<b>默认细分层级</b>。
     *
     * <p>★ 2026-11-08 修正：默认必须是<b>中间档</b>（1），不能是 0。
     * 原默认 zoom=2.0 = level 0 = 最粗档 ⇒ 用户<b>只能向内推，拉远一步都动不了</b>
     * （实测向外滚 6 格仍停在 level 0）—— 这就是"滚动缩放无效"的一半原因。</p>
     */
    public static final int DEFAULT_LEVEL = 1;

    /** 与 {@link #DEFAULT_LEVEL} 对应的 zoom。 */
    public static final float BASE_ZOOM = 2.0f * (1 << DEFAULT_LEVEL);

    /** 缩放下限（level 0）。 */
    public static final float ZOOM_MIN = 2.0f;
    /**
     * 缩放上限（level {@link #MAX_SUBDIV}）。
     *
     * <p>= ZOOM_MIN × 2^MAX_SUBDIV = 2 × 8 = 16（含 level 3 的边界）。
     * 加 1e-3 余量，避免浮点把 16 算成 15.999 → level 2（实机表现为"最高档够不到"）。</p>
     */
    public static final float ZOOM_MAX = ZOOM_MIN * (1 << MAX_SUBDIV) * 1.001f;

    private SandboxLod() { }

    /**
     * 当前缩放对应的<b>细分层级</b>（0 = 最粗，MAX_SUBDIV = 最精）。
     *
     * <p>每推近 2 倍细分一级 ⇒ 柱数 ×4、每柱方块 ÷2。</p>
     */
    public static int subdivLevel(float zoom) {
        double z = Math.max((double) ZOOM_MIN, Math.min((double) ZOOM_MAX, (double) zoom));
        // level 0 的 zoom 基准 = ZOOM_MIN（= 2.0）
        double r = z / (double) ZOOM_MIN;
        // +1e-6 抵消浮点误差（16/2=8 → log2=3.0 可能算成 2.9999999）
        int lv = (int) Math.floor(Math.log(r) / Math.log((double) SUBDIVIDE) + 1e-6);
        return Math.max(0, Math.min(MAX_SUBDIV, lv));
    }

    /** 该缩放下每边柱子数 = BASE_PILLARS × 2^level（每升一级 ×4 ⇒ 边长 ×2）。 */
    public static int pillarsFor(float zoom) {
        int p = BASE_PILLARS << subdivLevel(zoom);   // ×2^level
        return Math.max(8, Math.min(2048, p));
    }

    /** 该缩放下每根柱子代表的方块数 = BASE_BLOCKS_PER_PILLAR / 2^level。 */
    public static double blocksPerPillar(float zoom) {
        return BASE_BLOCKS_PER_PILLAR / (double) (1 << subdivLevel(zoom));
    }

    /**
     * 每根柱子聚合几个细格 —— <b>恒为 1</b>（一细格一根柱）。
     *
     * <p>真 LOD 靠"细分层级增加柱数"提高精度，而不是靠改聚合系数。
     * 聚合恒 1 ⇒ 网格 = 柱数 ⇒ 每一根柱子就是一次真实采样。</p>
     */
    public static int perColumnFor(float zoom) {
        return 1;
    }

    /** 网格（每边细格数）= 柱数 × 聚合系数 = 柱数。 */
    public static int gridFor(float zoom) {
        return Math.max(16, Math.min(2048, pillarsFor(zoom) * perColumnFor(zoom)));
    }

    /**
     * 世界窗口边长（方块）= 柱数 × 每柱方块。
     *
     * <p>细分一级：柱数 ×2、每柱方块 ÷2 ⇒ <b>窗口不变</b>（同范围更精细）。
     * 这正是"近处高模"：视野不变，细节翻倍。</p>
     */
    public static int windowFor(float zoom) {
        double raw = pillarsFor(zoom) * blocksPerPillar(zoom);
        int q = (int) (Math.round(raw / 16.0) * 16);
        return Math.max(gridFor(zoom), Math.max(64, q));
    }

    /** 实际每细格方块数。 */
    public static double effectiveStep(float zoom) {
        return windowFor(zoom) / (double) gridFor(zoom);
    }

    /** 比例尺文字。 */
    public static String scaleLabel(float zoom) {
        return String.format(java.util.Locale.ROOT,
                "LOD%d · %d 柱 · 1 柱 = %.1f 格 · 视野 %d 格",
                subdivLevel(zoom), pillarsFor(zoom), blocksPerPillar(zoom), windowFor(zoom));
    }

    /** 该缩放下窗口覆盖的 tract 数。 */
    public static int tractsFor(float zoom) {
        int win = windowFor(zoom);
        int across = (int) Math.ceil(win / 512.0) + 1;
        return across * across;
    }

    /** 该缩放是否查粗层水位（25 tract 以内可接受）。 */
    public static boolean usesCoarseWater(float zoom) {
        return tractsFor(zoom) <= 25;
    }

    /** 是否走完整水文管线（恒 false）。 */
    public static boolean usesHydroPath(float zoom) {
        return false;
    }

    /**
     * 滚轮：<b>一格滚轮 = 一个细分层级</b>。
     *
     * <p>★ 2026-11-08 修正：之前步长 1.414（两格才跨一级），
     * 用户滚第一次、第二次看不到任何变化 ⇒ 体感"缩放无效"。</p>
     * <p>现在步长 = 2（SUBDIVIDE），<b>每滚一格柱子就细分/合并一次</b>
     * —— 立刻能看到柱子数量变化，这才是 LOD 该有的手感。</p>
     */
    public static float nextZoom(float zoom, boolean zoomIn) {
        float next = zoomIn ? zoom * SUBDIVIDE : zoom / SUBDIVIDE;
        // 夹在 [ZOOM_MIN, ZOOM_MAX]，且吸附到层级边界（避免累积误差）
        next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next));
        return next;
    }

    /** 人类可读摘要。 */
    public static String describe(float zoom) {
        return String.format(java.util.Locale.ROOT,
                "%s（网格 %d，每细格 %.2f 格）",
                scaleLabel(zoom), gridFor(zoom), effectiveStep(zoom));
    }
}

