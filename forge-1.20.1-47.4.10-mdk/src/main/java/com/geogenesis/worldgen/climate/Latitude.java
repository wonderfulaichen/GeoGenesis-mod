package com.geogenesis.worldgen.climate;

/**
 * 纬度工具（保留旧 API 契约）。
 *
 * <p>★ <b>2026-09-11 改为【周期性】纬度（圆柱世界，无极点）</b>：
 * <pre>
 *   lat01 = |sin(z / scale)|        ∈ [0,1]，0=赤道，1=极地
 * </pre>
 *
 * <p><b>为什么必须改</b>：旧实现是 {@code min(|z|/scale, 1)} —— 在 Minecraft 的<b>无限世界</b>里，
 * {@code |z| > scale} 之后纬度<b>永久饱和于 1</b> → 外围形成【永久极冠】且越远越单调
 * （CHANGELOG 已记录该已知问题）。改为正弦后世界变成<b>沿 z 无缝卷绕的圆柱</b>：
 * 赤道 → 极地 → 赤道 → 极地 …… 无限交替重复，永不饱和。</p>
 *
 * <p><b>关键性质</b>（以 {@code scale} 为单位）：
 * <ul>
 *   <li>z = 0 → 赤道（lat01 = 0，最热）；</li>
 *   <li>z = scale·π/2 ≈ 1.571·scale → 极地（lat01 = 1，最冷）；</li>
 *   <li>z = scale·π ≈ 3.142·scale → 又回到赤道；</li>
 *   <li>一个完整"赤道→北极→赤道→南极→赤道"循环 = <b>2π·scale</b>（≈37,700 格 @ scale=6000）。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code |sin(z/scale)|} 而不是 {@code |sin(π/2·z/scale)|}</b>：
 * 前者在 z→0 处的斜率 {@code 1/scale} 与<b>旧线性模型完全一致</b> → 出生点周边的温度梯度不变，
 * 只是把"极点"往外推到 1.571·scale。这样对既有世界的扰动最小。</p>
 */
public final class Latitude {

    private Latitude() {}

    /**
     * 默认尺度（块）。
     *
     * <p>★ 必须与 {@code TerrainParams.defaults()} 里的 {@code latitudeScale}（6000）<b>保持一致</b>；
     * 旧值为 5000，导致"预览显示的纬度"与"生成器实际用的纬度"用了两个不同尺度。
     * 需要真实尺度的调用方请显式传 {@code params.latitudeScale()}。</p>
     */
    private static final double DEFAULT_SCALE = 6000.0;

    /**
     * 将世界 Z 坐标映射到纬度 [0,1]（0=赤道，1=极地）—— <b>周期性</b>。
     *
     * @param worldZ 世界 Z 坐标（块）
     * @param scale  纬度尺度（块）；&le;0 时回退到 {@link #DEFAULT_SCALE}
     */
    public static double latitude01(double worldZ, double scale) {
        double s = scale > 1e-6 ? scale : DEFAULT_SCALE;
        return Math.abs(Math.sin(worldZ / s));
    }

    /** 默认尺度的纬度（<b>需要真实尺度时请显式传入</b>，见 {@link #latitude01(double, double)}）。 */
    public static double latitude01(double worldZ) {
        return latitude01(worldZ, DEFAULT_SCALE);
    }

    /** 一个完整气候循环的周期（块）= 2π·scale：赤道→北极→赤道→南极→赤道。 */
    public static double cycleLength(double scale) {
        double s = scale > 1e-6 ? scale : DEFAULT_SCALE;
        return 2.0 * Math.PI * s;
    }
}
