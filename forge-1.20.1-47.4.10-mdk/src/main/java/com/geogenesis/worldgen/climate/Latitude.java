package com.geogenesis.worldgen.climate;

/**
 * 纬度工具（保留旧 API 契约）。
 *
 * <p>★ <b>2026-09-11 改为【周期性】纬度（圆柱世界，无极点）</b>：
 * <pre>
 *   lat01 = (1 − cos(2·z / scale)) / 2      ∈ [0,1]，0=赤道，1=极地
 * </pre>
 *
 * <p><b>为什么必须周期性</b>：最早实现是 {@code min(|z|/scale, 1)} —— 在 Minecraft 的<b>无限世界</b>里，
 * {@code |z| > scale} 之后纬度<b>永久饱和</b> → 外围形成【永久极冠】且越远越单调。
 * 周期化后世界成为<b>沿 z 无缝卷绕的圆柱</b>：赤道 → 极地 → 赤道 → 极地 …… 无限交替，永不饱和。</p>
 *
 * <p><b>为什么是余弦（而不用过 {@code |sin|}）</b> —— 2026-09-11 二次修正：
 * 中间版本用过 {@code |sin(z/scale)|}，它<b>极值处平坦、赤道处陡峭</b>，
 * 导致 z 向<b>冷区宽度是热区的 2 倍</b>（实测 冷:热 = 2.00），
 * 与真实地球（按纬度角看 热:冷 ≈ 1:1）不符 —— 用户的温度图/纬度图都能直接看出来。</p>
 *
 * <p>成因是量纲错配：{@code |sin|} 是"离自转轴的距离比例"（≈ cos 纬度），
 * 而日照 ∝ <b>cos(纬度角)</b>。直接对<b>纬度角</b>取余弦即得到正确形状：
 * {@code T = 1 − 2·lat01 = cos(2z/scale)} —— 恰好就是"日照余弦"，
 * 于是 z 向热/冷宽度自然变成 <b>1:1</b>，且赤道与极地两侧都更宽（真实地球即如此）。</p>
 *
 * <p><b>关键性质</b>（以 {@code scale} 为单位，一个完整循环 {@code 2π·scale}）：
 * <ul>
 *   <li>z = 0 → 赤道（lat01 = 0，最热）；</li>
 *   <li>z = π·scale/2 ≈ 1.571·scale → 极地（lat01 = 1，最冷）；<b>与旧 {@code |sin|} 位置相同</b>；</li>
 *   <li>z = π·scale ≈ 3.142·scale → 又回到赤道；</li>
 *   <li>完整"赤道→北极→赤道→南极→赤道"循环 = <b>2π·scale</b>（≈37,700 格 @ scale=6000）。</li>
 * </ul>
 * 即：<b>冷热带的中心位置不变</b>，只是中纬度过渡被对称展开 —— 所以视觉上"带的位置还在原处"，
 * 但红带（热）变宽、蓝带（冷）变窄。</p>
 */
public final class Latitude {

    private Latitude() {}

    /**
     * 默认尺度（块）。
     *
     * <p>★ 必须与 {@code TerrainParams.defaults()} 里的 {@code latitudeScale}（6000）<b>保持一致</b>；
     * 旧值为 5000，曾导致"预览显示的纬度"与"生成器实际用的纬度"用了两个不同尺度。
     * 需要真实尺度的调用方请显式传 {@code params.latitudeScale()}。</p>
     */
    private static final double DEFAULT_SCALE = 6000.0;

    /**
     * 将世界 Z 坐标映射到纬度 [0,1]（0=赤道，1=极地）—— <b>周期性、严格偶函数</b>。
     *
     * <p>严格偶函数（{@code lat01(z) == lat01(−z)}，逐位）→ 南北半球<b>完全镜像</b>，
     * 不会出现"北半球比南半球宽"这类偏差。</p>
     *
     * @param worldZ 世界 Z 坐标（块）
     * @param scale  纬度尺度（块）；&le;0 时回退到 {@link #DEFAULT_SCALE}
     */
    public static double latitude01(double worldZ, double scale) {
        double s = scale > 1e-6 ? scale : DEFAULT_SCALE;
        return 0.5 * (1.0 - Math.cos(2.0 * worldZ / s));
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

    /**
     * {@link #latitude01} 的<b>反函数</b>：给定 lat01 求对应的 z（取第一个象限的解，即 z ≥ 0）。
     *
     * <p>由 {@code lat01 = (1 − cos(2z/s))/2} 解出 {@code z = (s/2)·acos(1 − 2·lat01)}。</p>
     *
     * <p><b>为什么需要它</b>：探针/测试若要"把某物放在西风带"，应<b>按纬度反解 z</b>，
     * 而不是硬编码一个 z —— 否则纬度映射一改（如 2026-09-11 的 |sin| → cos 修正），
     * 同一 z 就不再对应原来的纬度带，测试会以"风向反了"这类假失败报警。</p>
     */
    public static double zForLatitude(double lat01, double scale) {
        double s = scale > 1e-6 ? scale : DEFAULT_SCALE;
        double v = 1.0 - 2.0 * (lat01 < 0.0 ? 0.0 : (lat01 > 1.0 ? 1.0 : lat01));
        return 0.5 * s * Math.acos(v);
    }

    /**
     * 赤道→极地的 z 距离（块）= π·scale/2 —— 与旧 {@code |sin|} 实现<b>一致</b>
     * （周期化只改变带内形状，不改变极值位置）。
     */
    public static double poleDistance(double scale) {
        double s = scale > 1e-6 ? scale : DEFAULT_SCALE;
        return Math.PI * s / 2.0;
    }
}
