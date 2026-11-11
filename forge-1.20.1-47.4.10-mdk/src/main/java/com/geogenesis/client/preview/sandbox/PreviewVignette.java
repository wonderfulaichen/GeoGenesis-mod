package com.geogenesis.client.preview.sandbox;

/**
 * <b>第一阶段（纹理 → 体素）的圆形收拢遮罩</b>（★ v58，零 MC 依赖，探针可验证）。
 *
 * <h2>为什么需要它（用户 2026-11-11）</h2>
 * <blockquote>
 *   「现在只是纹理变化，第一阶段应该先自然从四周往屏幕中间收，原因你其实看沙盘就清楚了，
 *    沙盘是圆的，四周都暗角。请改进第一阶段的动画以便有精致感。」
 * </blockquote>
 *
 * <p>问题很实在：平面图是<b>铺满矩形</b>的，沙盘是<b>圆形盘面 + 四周暗角</b>。
 * 第一阶段只做"整幅矩形淡出/体素淡入"，矩形四角一直保持地图颜色到最后，
 * 视觉上就是"两张矩形图叠化"，根本没有"收拢"的动势。</p>
 *
 * <h2>做法</h2>
 * <p>用一个<b>椭圆遮罩</b>把平面图按沙盘的暗角曲线裁进去：遮罩内半径随第一阶段进度
 * 从"完全不可见（比四角还远）"收缩到沙盘盘面的内切椭圆，外圈填底板色。
 * 于是四角先暗、暗区向中心推进，最终正好是沙盘那个圆形盘面 —— 接缝处
 * 与 {@link SandboxGeometry#edgeFadeAt} 是<b>同一条曲线</b>，不会出现"两个圆不一样大"。</p>
 *
 * <h2>为什么用归一化坐标</h2>
 * <p>遮罩在 {@code [-1,1]²} 里算，再铺满整个预览矩形。这与柱体的
 * {@code model = (列/总列)*2-1} 映射完全相同 ⇒ 屏幕上的椭圆就是模型空间那个圆，
 * 宽屏自动变成横椭圆，无需按 w/h 分别处理。</p>
 */
public final class PreviewVignette {

    /** 底板色（与 {@code SandboxRasterizer} 铺底同色；遮罩外圈必须是它才接得上）。 */
    public static final int PLATE_RGB = 0x171A22;

    /**
     * 盘面内切处（{@code t=1}）正好淡到 0 的<b>内圈半径</b>，对应
     * {@link SandboxGeometry#EDGE_FADE_RATIO}：那里 {@code ring = half*0.55}，
     * 而全不透明区到 {@code half+0.5-ring ≈ 0.45*half} ⇒ 归一化后约 {@code 0.45}。
     */
    public static final float INNER = 0.45f;

    /** 起点内圈：大于四角距离（{@code sqrt(2)≈1.414}）⇒ 整幅都不遮。 */
    public static final float INNER_START = 1.5f;

    private PreviewVignette() { }

    /** 归一化半径：{@code (0,0)} = 盘心，{@code 1} = 盘面内切边。 */
    public static float radius(float nx, float ny) {
        return (float) Math.sqrt(nx * nx + ny * ny);
    }

    /**
     * 遮罩不透明度（0 = 完全不遮，1 = 完全变底板色）。
     *
     * @param t 归一化半径（见 {@link #radius}）
     * @param p 第一阶段进度 0..1（0 = 平面原样，1 = 已是沙盘盘面）
     */
    public static float plateAlpha(float t, float p) {
        float q = p < 0f ? 0f : (p > 1f ? 1f : p);
        q = q * q * (3f - 2f * q);                    // smoothstep：先慢后快再慢
        float inner = INNER_START + (INNER - INNER_START) * q;
        float outer = Math.max(inner + 1e-4f, 1f);    // 外圈固定在内切边
        if (t <= inner) return 0f;
        if (t >= outer) return 1f;
        float u = (t - inner) / (outer - inner);
        // ★ 再套一次 smoothstep：线性渐变在暗区边界会留下一条"发灰的带子"，
        //   两侧都收得平缓才有"暗角"而不是"贴了一圈灰环"。
        return u * u * (3f - 2f * u);
    }

    /**
     * 遮罩的最大不透明度 —— <b>刻意等于沙盘铺底的 0xF2</b>。
     *
     * <p>{@code SandboxRasterizer.draw} 铺底用的是 {@code (0xF2*globalAlpha)<<24 | 0x171A22}，
     * 若这里用 255，第一阶段收完会比稳态盘面"更黑一点"，接缝处能看出色差。
     * 用同一个上限 ⇒ 收拢的终点与沙盘盘面<b>逐像素同色</b>。</p>
     */
    public static final int MAX_ALPHA = 0xF2;

    /** 遮罩颜色的 ARGB（alpha 由 {@link #plateAlpha} 给，上限 {@link #MAX_ALPHA}）。 */
    public static int argb(float t, float p) {
        int a = (int) (plateAlpha(t, p) * MAX_ALPHA);
        return (a << 24) | PLATE_RGB;
    }
}