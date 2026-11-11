package com.geogenesis.client.preview;

/**
 * <b>平面 ⇄ 沙盘 视图切换过渡的全部数学</b>（★ v48，零 MC 依赖纯函数）。
 *
 * <h2>为什么单独抽出来</h2>
 * <p>此前这些公式散在 {@link PreviewDisplay#renderWidget} 与
 * {@code renderSandbox} 里，<b>只能靠实机肉眼看对不对</b> ——
 * 而本项目已经吃过"探针全 PASS、图上仍是错的"的教训。
 * 抽成纯函数后 {@code runViewTransitionProbe} 可以逐条守住参考项目的不变量。</p>
 *
 * <h2>参考项目出处</h2>
 * <p>{@code dev.yard.site.view.YardSiteScreen}（MIT）：</p>
 * <pre>
 *   第 61 行    TRANSITION_MS = 620
 *   第 386-387 行 p = 1 - (endAt - now)/TRANSITION_MS，ease = p*p*(3-2p)
 *   第 404 行    zoomBoost = fading == null ? 1.0 : 0.84 + 0.16*ease
 *   第 415-418 行 旧图：globalAlpha = 1-ease，heightScale = 1 - 0.55*ease
 *   第 420-421 行 新图：globalAlpha = ease，  heightScale = 0.45 + 0.55*ease
 * </pre>
 *
 * <h2>★ 关键不变量：两张图的 alpha 之和恒为 1</h2>
 * <p>{@code (1-ease) + ease = 1} ⇒ 过渡是<b>交叉溶解</b>，不是"先淡出再淡入"。
 * 这一条决定了观感是"从平面变形成沙盘"，而不是"黑一下再出现"。
 * 探针 {@code [3]} 专门守它。</p>
 *
 * <h2>高度轴的含义</h2>
 * <p>参考项目的 {@code heightScale} 缩的是<b>柱子高度轴</b>（3D）。我们的平面图是
 * 俯视 2D 纹理，没有这个轴，取最接近的等价：整张图按<b>底边锚定</b>向上收缩
 * （见 {@code PreviewDisplay.blitPlaneOnly}）。沙盘侧沿用同一公式 ——
 * 于是过渡读起来就是「柱体摊平成地图 / 地图立起成柱体」。</p>
 */
public final class ViewTransition {

    /** 过渡总时长（毫秒，参考项目 {@code TRANSITION_MS = 620}）。 */
    public static final long DURATION_MS = 620L;

    /** 旧图起始高度比例（= 1，满高）。 */
    public static final float OLD_HEIGHT_FROM = 1.0f;
    /** 旧图结束高度比例（参考项目 {@code 1 - 0.55}）。 */
    public static final float OLD_HEIGHT_TO = 0.45f;
    /** 新图起始高度比例（参考项目 {@code 0.45}）。 */
    public static final float NEW_HEIGHT_FROM = 0.45f;
    /** 新图结束高度比例（= 1，满高）。 */
    public static final float NEW_HEIGHT_TO = 1.0f;
    /** 镜头推近起点（参考项目 {@code 0.84}）。 */
    public static final float ZOOM_BOOST_FROM = 0.84f;
    /** 镜头推近终点（参考项目 {@code 1.0}）。 */
    public static final float ZOOM_BOOST_TO = 1.0f;

    /** 无过渡时的稳态值：不透明、满高、不推近。 */
    public static final float STEADY_ALPHA = 1.0f;
    public static final float STEADY_HEIGHT = 1.0f;
    public static final float STEADY_ZOOM_BOOST = 1.0f;

    private ViewTransition() { }

    /**
     * 线性进度：0 = 刚切换，1 = 切完（或没在过渡）。
     *
     * @param nowMs   当前时刻
     * @param endAtMs 过渡结束时刻（0 = 没有过渡 ⇒ 直接返回 1）
     */
    public static float progress(long nowMs, long endAtMs) {
        if (endAtMs == 0L) return 1.0f;
        float p = 1.0f - (endAtMs - nowMs) / (float) DURATION_MS;
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /**
     * 缓动：{@code p*p*(3-2p)}（smoothstep，先慢后快再慢）。
     *
     * <p>参考项目注释：「先慢后快再慢，推近感才不像跳切」。</p>
     */
    public static float ease(float p) {
        float q = p < 0f ? 0f : (p > 1f ? 1f : p);
        return q * q * (3.0f - 2.0f * q);
    }

    /** 旧图不透明度：1 → 0。 */
    public static float oldAlpha(float ease) { return 1.0f - ease; }

    /** 旧图高度比例：1 → 0.45（"压下去"）。 */
    public static float oldHeight(float ease) {
        return OLD_HEIGHT_FROM + (OLD_HEIGHT_TO - OLD_HEIGHT_FROM) * ease;
    }

    /** 新图不透明度：0 → 1。 */
    public static float newAlpha(float ease) { return ease; }

    /** 新图高度比例：0.45 → 1（"长起来"）。 */
    public static float newHeight(float ease) {
        return NEW_HEIGHT_FROM + (NEW_HEIGHT_TO - NEW_HEIGHT_FROM) * ease;
    }

    /** 镜头推近：0.84 → 1.0。 */
    public static float zoomBoost(float ease) {
        return ZOOM_BOOST_FROM + (ZOOM_BOOST_TO - ZOOM_BOOST_FROM) * ease;
    }
}