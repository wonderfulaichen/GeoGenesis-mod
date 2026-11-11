package com.geogenesis.client.preview.sandbox;

/**
 * <b>第一阶段的椭圆收拢遮罩 —— 纯数学，不做任何渲染</b>（★ v59）。
 *
 * <h2>为什么需要它（用户 2026-11-11）</h2>
 * <blockquote>
 *   「第一阶段应该先自然从四周往屏幕中间收，原因你其实看沙盘就清楚了，
 *    沙盘是圆的，四周都暗角。」
 * </blockquote>
 *
 * <p>平面图铺满矩形，沙盘是圆形盘面 + 四周暗角。若第一阶段只做整幅矩形淡变，
 * 四角一直保留地图颜色到最后，没有"收"的动势。</p>
 *
 * <h2>★ 为什么不在这里画东西</h2>
 * <p>v58 曾在渲染中途 <b>new NativeImage + new DynamicTexture + 每帧 upload</b>，
 * 实机表现为「闪烁黑屏才出画面」（用户 2026-11-11 复现，已整块回退 6478aae）。
 * 那与本项目此前三次黑屏（POSITION_TEX_COLOR / POSITION_TEX / guiTextured）
 * 是同一类风险：<b>在 GUI 一次绘制过程中新建/上传纹理</b>。</p>
 *
 * <p>⇒ 本类只提供"某个归一化半径、某个进度下该压多暗"这一个函数；
 * 真正的合成由调用方<b>乘进已有平面纹理</b>（改像素 + 原来那条 texture.upload()），
 * 全程只有一个纹理、一条已验证的提交路径。</p>
 *
 * <h2>坐标系</h2>
 * <p>归一化 {@code [-1,1]²}：与柱体 {@code model} 坐标同一映射，
 * 宽屏自然成横椭圆，不需要按宽高分别处理。</p>
 */
public final class PreviewVignette {

    /** 底板色（与 {@code SandboxRasterizer} 铺底同色）。 */
    public static final int PLATE_RGB = 0x171A22;

    /** 底板色的 R/G/B 分量，供逐像素合成直接使用（避免每像素拆位）。 */
    public static final int PLATE_R = (PLATE_RGB >> 16) & 0xFF;
    public static final int PLATE_G = (PLATE_RGB >> 8) & 0xFF;
    public static final int PLATE_B = PLATE_RGB & 0xFF;

    /**
     * 收拢完成时盘面的全不透明内圈半径，对应 {@link SandboxGeometry#EDGE_FADE_RATIO}：
     * 那里 {@code ring = half*0.55}，全不透明区到 {@code half+0.5-ring ≈ 0.45*half}。
     */
    public static final float INNER = 0.45f;

    /** 起点内圈：大于四角距离（{@code sqrt(2)≈1.414}）⇒ 整幅都不遮。 */
    public static final float INNER_START = 1.5f;

    /**
     * 遮罩上限（0..1）。刻意等于沙盘铺底 alpha {@code 0xF2/255}：
     * 收拢终点必须与稳态盘面同色，否则接缝能看出色差。
     */
    public static final float MAX_ALPHA = 0xF2 / 255f;

    private PreviewVignette() { }

    /** 归一化半径：{@code (0,0)} = 盘心，{@code 1} = 盘面内切边。 */
    public static float radius(float nx, float ny) {
        return (float) Math.sqrt(nx * nx + ny * ny);
    }

    /**
     * 遮罩强度（0 = 原样，{@link #MAX_ALPHA} = 全压成底板色）。
     *
     * @param t 归一化半径（见 {@link #radius}）
     * @param p 收拢进度 0..1
     */
    public static float plateAlpha(float t, float p) {
        float q = p < 0f ? 0f : (p > 1f ? 1f : p);
        q = q * q * (3f - 2f * q);                    // smoothstep：先慢后快再慢
        float inner = INNER_START + (INNER - INNER_START) * q;
        float outer = Math.max(inner + 1e-4f, 1f);    // 外圈固定在内切边
        if (t <= inner) return 0f;
        if (t >= outer) return MAX_ALPHA;
        float u = (t - inner) / (outer - inner);
        // 再套一层 smoothstep：线性渐变会在暗区边界留一条"灰带子"
        return MAX_ALPHA * (u * u * (3f - 2f * u));
    }
}