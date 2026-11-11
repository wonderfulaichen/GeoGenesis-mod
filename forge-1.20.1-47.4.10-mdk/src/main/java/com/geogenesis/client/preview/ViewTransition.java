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

    /**
     * <b>第一段（换材质）占整条时间线的比例</b> —— 用户 2026-11-11 裁定。
     *
     * <p>用户原话：「先从高精度的平面模式平滑变化到沙盘的正俯视视角，
     * 然后进行运镜，这样纹理就不会突变了。」</p>
     *
     * <p>两段的语义完全不同，混在一起就会"不自然"：</p>
     * <ul>
     *   <li><b>第一段</b>（sandboxness 0 → {@code PHASE_TEXTURE}）：相机不动（正俯视、yaw=0），
     *       只把"高精度纹理"换成"体素正俯视"，同时把尺度从 {@code (w/2,h/2)} 收到 {@code fill}。
     *       两张图在这里<b>完全重合</b>（都是同一份数据的正俯视）⇒ 只是材质/分辨率在变，
     *       没有空间位移，所以看不出"跳"；</li>
     *   <li><b>第二段</b>（{@code PHASE_TEXTURE} → 1）：材质已定，只做运镜
     *       —— pitch 0 → 54°、yaw 0 → 用户角度。这时观众看到的<b>已经是</b>体素模型在转，
     *       不会同时看到"贴图忽然被换掉"。</li>
     * </ul>
     */
    public static final float PHASE_TEXTURE = 0.42f;

    private ViewTransition() { }

    /**
     * <b>沙盘度</b>：0 = 平面端，1 = 沙盘端。<b>两个方向共用这一个单调量</b>，
     * 反向切换只是 s 递减 ⇒ 两段顺序自动倒过来（先转回来、再换回纹理）。
     */
    private static float sandboxness(float ease, boolean toSandbox) {
        return clamp01(toSandbox ? ease : 1.0f - ease);
    }

    /** 第一段进度（0 = 还是纹理，1 = 已换成体素）。 */
    static float texturePhase(float ease, boolean toSandbox) {
        return clamp01(sandboxness(ease, toSandbox) / PHASE_TEXTURE);
    }

    /** 第二段进度（0 = 还没开始运镜，1 = 运镜到位）。 */
    static float cameraPhase(float ease, boolean toSandbox) {
        return clamp01((sandboxness(ease, toSandbox) - PHASE_TEXTURE) / (1.0f - PHASE_TEXTURE));
    }

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

    /** 相机俯仰（弧度）：<b>第二段才动</b>（第一段保持正俯视，只换材质）。 */
    public static float pitch(float ease, boolean toSandbox) {
        return PITCH_PLANE + (PITCH_SANDBOX - PITCH_PLANE) * smooth01(cameraPhase(ease, toSandbox));
    }

    /**
     * 相机偏航（弧度）：平面端恒为 0（正俯视时旋转无意义，且必须为 0 才能与平面图逐像素对齐），
     * 沙盘端为<b>用户当前角度</b>（保留用户转过的视角）。
     *
     * <p>用<b>最短角路径</b>插值：从 0 到 5.5 rad 不会绕一大圈，而是反向走 0.78 rad。</p>
     */
    public static float yaw(float ease, boolean toSandbox, float sandboxYaw) {
        return YAW_PLANE + shortestDelta(YAW_PLANE, sandboxYaw) * smooth01(cameraPhase(ease, toSandbox));
    }

    /**
     * <b>地形起伏强度</b>（0 = 平的，1 = 满高）—— 这是 v55 之后过渡的<b>唯一驱动量</b>。
     *
     * <h2>为什么用"起伏"而不是"柱体淡入"</h2>
     * <p>旧版用 {@code pillarAlpha} 让柱体像贴图一样淡入、{@code groundAlpha} 让地面淡出。
     * 用户实机判定：「切换动画依旧是像图片切换那样，完全没有完成我的设计要求」。
     * 病根就在这：<b>两个不透明度的交叉淡变，无论怎么调都是"两张图叠化"</b>。</p>
     *
     * <p>正确的关系是<b>同一个曲面的形变</b>：地形从平（{@code relief=0}，
     * 此时每根柱高为 0，整个场景退化成那张地图）长到满高
     * （{@code relief=1}，柱顶按真实高度抬起）。
     * 高度与不透明度由<b>同一个数</b>驱动 ⇒ 柱子"长出地面"的同时才可见，
     * 不会出现"图上又贴了一层马赛克"。</p>
     */
    public static float relief(float ease, boolean toSandbox) {
        return smooth01(texturePhase(ease, toSandbox));
    }

    /**
     * <b>高精度纹理的不透明度</b>：第一段 1 → 0，第二段恒 0。
     *
     * <p>它只在<b>第一段</b>里参与，而且是与体素正俯视<b>完全重合</b>的两张图之间的
     * 一次材质交接 —— 相机不动、位置不变，所以读起来是"贴图精度在换"，
     * 而不是"一张图淡出、另一张图淡入"。第二段（运镜）里它已经是 0，
     * 观众看到的自始至终是同一个体素场景。</p>
     */
    public static float groundAlpha(float ease, boolean toSandbox) {
        return 1.0f - relief(ease, toSandbox);
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
        // ★ 尺度也只走第一段：材质换完就该是沙盘尺度，运镜期间不再缩
        float t = relief(ease, toSandbox);
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