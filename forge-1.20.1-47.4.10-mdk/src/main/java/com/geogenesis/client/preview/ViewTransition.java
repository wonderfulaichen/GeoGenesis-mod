package com.geogenesis.client.preview;

import com.geogenesis.client.preview.sandbox.SandboxGeometry;

/**
 * <b>平面 ⇄ 沙盘 视图切换的相机运镜数学</b>（★ v49，零 MC 依赖纯函数）。
 *
 * <h2>为什么是「运镜」而不是「过渡」</h2>
 * <p>用户裁定（2026-11-11）：「这样完全算不是切换动画，只能是一个过渡动画……
 * 二者应该是一个运镜动画变化关系，你当前其实和做图片过渡没区别，完全不是动画。」
 * 以及：「统一相机与交互，保留两套绘制。」</p>
 *
 * <p>⇒ 平面与沙盘是<b>同一个相机的两个姿态</b>，切换 = 相机连续运动。
 * 两套绘制各自保留质量（平面=纹理、沙盘=柱子），但相机只有一套。</p>
 *
 * <h2>姿态的硬事实（{@code runCameraDatumProbe} 实测）</h2>
 * <pre>
 *   pitch=0, yaw=0  → 地面模型 ±1 正好投到视口 (0,0)-(w,h)，r5=0（柱高不进屏幕 y）
 *   pitch=0.95      → |r5| = 0.813（柱高显著可见）
 * </pre>
 * <p>⇒ 平面 = 相机正俯视，切换时 pitch 从 0 抬到 0.95。</p>
 *
 * <h2>★ 与参考项目的差异（有意）</h2>
 * <p>yardsite {@code YardSiteScreen} 第 388-393 行会插值 {@code zoom}/{@code widthScale}，
 * 因为它两张图是<b>同一渲染器的两张场</b>（defaultZoom 2.0 ⇄ 1.8，单位相同）。
 * 我们平面是整数档 {@code scaleBlockPos}、沙盘是浮点 {@code zoom}，
 * 单位不同、插值无意义；且用户早已裁定「谁切换视图还改缩放等信息呢？」。
 * ⇒ 这里<b>不插值</b> zoom/widthScale，只插值相机姿态与画面尺度。</p>
 */
public final class ViewTransition {

    /** 过渡总时长（毫秒）。参考项目 {@code TRANSITION_MS=620}；运镜需要略长才不显得急。 */
    public static final long DURATION_MS = 720L;

    /** 平面端的相机姿态：正俯视、无偏航。 */
    public static final float PITCH_PLANE = 0f;
    public static final float YAW_PLANE = 0f;
    /** 沙盘端的俯仰（{@code SandboxGeometry.DEFAULT_PITCH}，约 54°）。 */
    public static final float PITCH_SANDBOX = SandboxGeometry.DEFAULT_PITCH;

    private ViewTransition() { }

    /**
     * 线性进度：0 = 刚开始切，1 = 切完（或没在过渡）。
     *
     * @param nowMs   当前时刻
     * @param endAtMs 过渡结束时刻（0 = 没有过渡）
     */
    public static float progress(long nowMs, long endAtMs) {
        if (endAtMs == 0L) return 1.0f;
        float p = 1.0f - (endAtMs - nowMs) / (float) DURATION_MS;
        return clamp01(p);
    }

    /**
     * 缓动：{@code p*p*(3-2p)}（smoothstep，先慢后快再慢）。
     *
     * <p>参考项目注释：「先慢后快再慢，推近感才不像跳切」。</p>
     */
    public static float ease(float p) {
        float q = clamp01(p);
        return q * q * (3.0f - 2.0f * q);
    }

    /** 相机俯仰（弧度）：平面端 {@link #PITCH_PLANE} ⇄ 沙盘端 {@link #PITCH_SANDBOX}。 */
    public static float pitch(float ease, boolean toSandbox) {
        return toSandbox
                ? PITCH_PLANE + (PITCH_SANDBOX - PITCH_PLANE) * ease
                : PITCH_SANDBOX + (PITCH_PLANE - PITCH_SANDBOX) * ease;
    }

    /**
     * 相机偏航（弧度）：平面端恒为 0（正俯视时旋转无意义，且必须为 0 才能与平面图逐像素对齐），
     * 沙盘端为<b>用户当前角度</b>（保留用户转过的视角）。
     *
     * <p>用<b>最短角路径</b>插值：从 0 到 5.5 rad 不会绕一大圈，而是反向走 0.78 rad。</p>
     */
    public static float yaw(float ease, boolean toSandbox, float sandboxYaw) {
        float t = toSandbox ? ease : 1.0f - ease;
        return YAW_PLANE + shortestDelta(YAW_PLANE, sandboxYaw) * t;
    }

    /**
     * 柱子不透明度：平面端 0（不可见），沙盘端 1。
     *
     * <p>为什么必须淡入：柱顶是「每柱一个颜色」，平面端若可见就会把精细的地面纹理盖成马赛克。</p>
     */
    public static float pillarAlpha(float ease, boolean toSandbox) {
        return toSandbox ? smooth01(ease) : smooth01(1.0f - ease);
    }

    /**
     * 地面贴图不透明度：全程保持 1，<b>只在末段轻微淡出</b>。
     *
     * <p>地面就是"地基"，柱体长满后会自然盖住它；末段淡出只为消除柱缝间的残留。</p>
     */
    public static float groundAlpha(float ease, boolean toSandbox) {
        float t = toSandbox ? ease : 1.0f - ease;
        // 0.75 之后开始淡出，1.0 时归零
        if (t <= 0.75f) return 1.0f;
        return smooth01(1.0f - (t - 0.75f) / 0.25f);
    }

    /** 光照强度：平面端 0（平面图的光照是烘在纹理里的），沙盘端 1。 */
    public static float lightStrength(float ease, boolean toSandbox) {
        float t = toSandbox ? ease : 1.0f - ease;
        return smooth01(t);
    }

    /** 水面层强度：平面端 0（水面在纹理里），沙盘端 1（半透明薄层）。 */
    public static float waterStrength(float ease, boolean toSandbox) {
        float t = toSandbox ? ease : 1.0f - ease;
        return smooth01(t);
    }

    /**
     * 画面尺度（像素/模型单位）—— <b>分量插值</b>。
     *
     * <p>平面贴图是各向异性铺满视口的：{@code sx=w/2, sy=h/2}；
     * 沙盘是各向同性 {@code fillScale}。两端都必须精确对齐，所以 sx/sy 分开插值。</p>
     *
     * @param ease     缓动后进度
     * @param toSandbox 方向
     * @param w/h      视口尺寸
     * @param fill     沙盘端尺度（{@link SandboxGeometry#fillScale}）
     * @param out      输出 {@code [sx, sy]}
     */
    public static void scale(float ease, boolean toSandbox, int w, int h, float fill, float[] out) {
        float planeSx = w / 2f;
        float planeSy = h / 2f;
        float t = toSandbox ? ease : 1.0f - ease;
        out[0] = planeSx + (fill - planeSx) * t;
        out[1] = planeSy + (fill - planeSy) * t;
    }

    // ------------------------------------------------------------------

    /** 最短角差（结果 ∈ [-π, π]）。公开供探针守「不绕远路」。 */
    public static float shortestDelta(float from, float to) {
        float d = (to - from) % (float) (Math.PI * 2);
        if (d > Math.PI) d -= (float) (Math.PI * 2);
        if (d < -Math.PI) d += (float) (Math.PI * 2);
        return d;
    }

    /** 在 [0,1] 内做一次 smoothstep；区间外夹紧。 */
    static float smooth01(float t) {
        float q = clamp01(t);
        return q * q * (3.0f - 2.0f * q);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}