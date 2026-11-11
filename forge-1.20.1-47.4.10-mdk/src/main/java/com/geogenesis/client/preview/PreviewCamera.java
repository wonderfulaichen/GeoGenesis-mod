package com.geogenesis.client.preview;

/**
 * <b>预览的唯一相机</b>（★ v54，零 MC 依赖纯数据）。
 *
 * <h2>它解决什么问题（用户批注原文）</h2>
 * <blockquote>
 *   「我定的是可以用 2 种表现力，我明明说了样式可以不同，但你这分明就不是样式不同。」
 * </blockquote>
 *
 * <p>在此之前，平面与沙盘是<b>两套并列实现</b>，连"视口从哪来"都各写一遍：</p>
 * <pre>
 *   平面：originWx = alignDown(centerX + totalDragX - blocksWide/2, scaleBlockPos)
 *   沙盘：originWx = centerX - blocksWide/2
 * </pre>
 * <p>同一个概念、两条推导 ⇒ 任何一处改动都要改两遍，且容易改漏
 * （v25 就出现过"平面拖过之后切沙盘，视口莫名偏一段"）。</p>
 *
 * <h2>统一的定义</h2>
 * <p><b>一个相机 + 一个地形场景，两种画法（style）。</b>
 * 相机负责：世界窗口、原点、俯仰/偏航、对齐粒度。
 * 样式只负责"怎么把这块地形画出来"：
 * 平面 = 俯视纹理（高信息密度），沙盘 = 立体柱体（形态可读）。
 * 二者的视口、中心、窗口<b>由同一个对象给出</b>，不再各自推导。</p>
 *
 * <h2>字段语义</h2>
 * <table border="1">
 *   <caption>字段</caption>
 *   <tr><th>字段</th><th>含义</th></tr>
 *   <tr><td>{@link #centerX}/{@link #centerZ}</td><td>视口中心（世界方块）</td></tr>
 *   <tr><td>{@link #panX}/{@link #panZ}</td><td>屏幕拖拽累积（尚未提交到 center 的临时偏移）</td></tr>
 *   <tr><td>{@link #windowX}/{@link #windowZ}</td><td>世界窗口（方块，每边）</td></tr>
 *   <tr><td>{@link #align}</td><td>原点对齐粒度（平面按缩放档对齐，避免纹理半像素错位）</td></tr>
 *   <tr><td>{@link #pitch}/{@link #yaw}</td><td>俯仰/偏航（弧度）。平面端 pitch=0 即正俯视</td></tr>
 * </table>
 */
public final class PreviewCamera {

    /** 视口中心（世界方块）。 */
    public int centerX;
    public int centerZ;
    /** 尚未提交的屏幕拖拽累积（世界方块）。 */
    public int panX;
    public int panZ;
    /** 世界窗口（方块，每边）。 */
    public int windowX;
    public int windowZ;
    /** 原点对齐粒度（≥1）。 */
    public int align = 1;
    /** 俯仰（弧度）：0 = 正俯视（平面），{@code SandboxGeometry.DEFAULT_PITCH} = 沙盘。 */
    public float pitch;
    /** 偏航（弧度）：正俯视时无意义，平面端恒 0 才能与平面纹理逐像素对齐。 */
    public float yaw;

    /** 视口左上角对应的世界 X（已按 {@link #align} 向下对齐）。 */
    public int originX() {
        return alignDown(centerX + panX - windowX / 2, align);
    }

    /** 视口左上角对应的世界 Z（已按 {@link #align} 向下对齐）。 */
    public int originZ() {
        return alignDown(centerZ + panZ - windowZ / 2, align);
    }

    /** 是否为正俯视（平面姿态）。 */
    public boolean isTopDown() {
        return Math.abs(pitch) < 1e-6f;
    }

    /** 向下对齐到 {@code step} 的整数倍（负数也正确）。 */
    public static int alignDown(int v, int step) {
        if (step <= 1) return v;
        return Math.floorDiv(v, step) * step;
    }

    /** 复制一份（过渡期间要拿插值后的姿态，不能污染用户状态）。 */
    public PreviewCamera copy() {
        PreviewCamera c = new PreviewCamera();
        c.centerX = centerX;
        c.centerZ = centerZ;
        c.panX = panX;
        c.panZ = panZ;
        c.windowX = windowX;
        c.windowZ = windowZ;
        c.align = align;
        c.pitch = pitch;
        c.yaw = yaw;
        return c;
    }

    @Override
    public String toString() {
        return "Camera[center=(" + centerX + "," + centerZ + ") window=" + windowX + "x" + windowZ
                + " origin=(" + originX() + "," + originZ() + ") align=" + align
                + " pitch=" + pitch + " yaw=" + yaw + "]";
    }
}