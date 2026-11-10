package com.geogenesis.client.reveal;

import com.geogenesis.client.preview.TerrainFieldData;

/**
 * <b>落成动画的数据 + 正交相机交接</b>（★ v37 数据 / v45 补相机）。
 *
 * <h2>为什么要 static 持有</h2>
 * <p>配置屏（{@code GeoGenesisConfigScreen}）关闭时手里有一份
 * {@code TerrainFieldData}（沙盘刚画过的那份）和<b>沙盘最后一帧的正交相机</b>。
 * 落成动画要紧接着它们继续，但两者之间隔着"屏幕销毁 → 世界创建"的流程，
 * 没有共同的父对象 ⇒ 用一次性交接（与 {@code SpawnSelection} 同模式）。</p>
 *
 * <h2>★ v45：为什么必须交接相机（不能只用固定值）</h2>
 * <p>用户要求：「正交参数继承也一起做对」。</p>
 *
 * <p>参考项目 {@code SiteRevealWorldRenderer.Intro} 交接了整套：
 * {@code field}（数据）、{@code view}（{@code SiteTerrainRenderer.View}：
 * yaw/pitch/roll/zoom/pan/widthScale/scale）、{@code mapX/mapY/mapSize}（图区）。
 * 注释写明开场<b>第一帧必须与精读画面逐像素一致</b>，
 * 「不是两张图在对 dissolve」。</p>
 *
 * <p>我们此前用的是<b>硬编码</b>的 {@code width/2, height/2, height*0.46} 与
 * 固定的 yaw/pitch ⇒ 动画第一帧和玩家刚才看的沙盘<b>完全不是同一个画面</b>，
 * 一开场就"啪"地跳一下。现在改为从沙盘最后一帧继承。</p>
 */
public final class RevealDataHolder {

    /** 超过这么久就当作过期（毫秒）。 */
    private static final long MAX_AGE_MS = 2L * 60L * 1000L;

    /**
     * <b>沙盘最后一帧的正交相机</b>（落成动画开场的起点）。
     *
     * @param scale      像素 / 模型单位（{@code SandboxRasterizer.lastScale}）
     * @param originX    正交屏幕原点 X（已含 pan / 内容质心补偿）
     * @param originY    正交屏幕原点 Y
     * @param yaw        沙盘 yaw（弧度，模型空间）
     * @param pitch      沙盘 pitch（弧度）
     * @param reliefSpan 竖向比例分母（世界方块）
     * @param viewW      画沙盘时的视口宽（像素）
     * @param viewH      画沙盘时的视口高（像素）
     */
    public record OrthoView(float scale, float originX, float originY,
                            float yaw, float pitch, float reliefSpan,
                            int viewW, int viewH) { }

    private static volatile TerrainFieldData data;
    private static volatile OrthoView ortho;
    private static volatile long offeredAtMs;

    private RevealDataHolder() { }

    /** 配置屏关闭前把当前地形数据 + 正交相机交给落成动画。 */
    public static void offer(TerrainFieldData d, OrthoView view) {
        data = d;
        ortho = view;
        offeredAtMs = System.currentTimeMillis();
    }

    /** 兼容重载：只交数据（没有相机时开场退回默认值）。 */
    public static void offer(TerrainFieldData d) {
        offer(d, null);
    }

    /** 取用（不清空 —— 动画期间每帧都要用）。过期或没有 → null。 */
    public static TerrainFieldData current() {
        TerrainFieldData d = data;
        if (d == null) return null;
        if (System.currentTimeMillis() - offeredAtMs > MAX_AGE_MS) {
            data = null;
            ortho = null;
            return null;
        }
        return d;
    }

    /** 取用正交相机；没有 / 过期 → null。 */
    public static OrthoView orthoView() {
        if (current() == null) return null;
        return ortho;
    }

    /** 动画结束后清掉（避免长期占内存）。 */
    public static void clear() {
        data = null;
        ortho = null;
        offeredAtMs = 0L;
    }
}
