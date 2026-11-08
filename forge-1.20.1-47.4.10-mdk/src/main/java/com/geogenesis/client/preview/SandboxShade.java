package com.geogenesis.client.preview;

/**
 * <b>沙盘坡度光照</b>（纯函数，无 MC 依赖）—— ★ 2026-11-08。
 *
 * <h2>为什么独立成一个类</h2>
 * <p>沙盘渲染（{@link SandboxRenderer}）只做「画」，不决定「多亮」；
 * 而「多亮」是<b>视觉正确性</b>的判据，必须能被<b>离线量出来</b>，
 * 不能只能靠实机肉眼。本类因此是纯函数，供渲染器与
 * {@code SandboxShadeProbe} 共用同一份实现 ⇒ 探针量到的就是画出来的。</p>
 *
 * <h2>★ 与 yardsite 的对照（{@code SiteMapSampler.paintBlock()} 第 547-569 行）</h2>
 * <pre>
 *   slopeX = (right - left) / (2 * step * HEIGHT_STRIDE)
 *   slopeZ = (down  - up)   / (2 * step * HEIGHT_STRIDE)
 *   light  = clamp(1 - (slopeX * 0.55 + slopeZ * 0.55), 0.55, 1.30)
 *   water: light = 1 + (light - 1) * 0.35
 * </pre>
 *
 * <p><b>关键差异（上一版的实质量纲错误）</b>：yardsite 的分母是
 * <b>相邻采样点之间的实际方块距离</b> ⇒ {@code slope} 是「每块上升多少」，
 * 是有量纲的坡度。本项目上一版把分母写死成 {@code 2.0} 并注释「step=1」，
 * 等于把「每细格上升的方块数」当成无量纲坡度用
 * ⇒ 坡度被放大一整个采样间距 ⇒ {@code light} 几乎处处触底 0.55
 * ⇒ <b>整幅沙盘死平且偏暗，坡度层次完全丢失</b>。</p>
 *
 * <p>本类的坡度定义在<b>归一化高度 r</b> 上：{@code r = (surfaceY - base) / span}，
 * 与柱高用的是同一个量纲（{@code base = 海平面 - 4}，{@code span} = 竖向比例分母）。
 * 于是 {@code slopeX} 的含义是「走一格，柱高比例变了多少」
 * —— <b>与采样间距无关</b>，缩放视口时明暗关系稳定，不会因网格密度而整体变亮/变暗。</p>
 *
 * <p><b>水域按水面算</b>（yardsite {@code samplePoint()} 第 485 行：水格直接记海平面）：
 * 水面是平的，不该跟海床起伏一起明暗 ⇒ 传进来的 r 已是海平面高度。</p>
 */
public final class SandboxShade {

    /** 光照下限（yardsite 同值）。 */
    public static final float LIGHT_MIN = 0.55f;
    /** 光照上限（yardsite 同值）。 */
    public static final float LIGHT_MAX = 1.30f;
    /** 水面衰减系数：{@code light = 1 + (light - 1) * WATER_FACTOR}（yardsite 0.35）。 */
    public static final double WATER_FACTOR = 0.35;

    /**
     * 光照增益：{@code r} 差一格时亮度下降多少。
     *
     * <p>yardsite 用 {@code 0.55}，但它的 {@code slope} 是「每块上升多少」，
     * 典型值 0.2~1.5 ⇒ 亮度很快触底。本项目 {@code r} 是「柱高比例」，
     * 一格的变化量小一个量级 ⇒ 需要更大的增益才有同样的明暗层次。
     * 实际取值由 {@code SandboxShadeProbe} 在真实地形上量出分布后确定，
     * <b>不在这里拍脑袋</b>。</p>
     */
    public static final double LIGHT_GAIN = 8.0;

    private SandboxShade() { }

    /**
     * 一格的坡度光照。
     *
     * @param rLeft  左边邻格的归一化高度
     * @param rRight 右边邻格的归一化高度
     * @param rUp    上边（Z 小侧）邻格的归一化高度
     * @param rDown  下边（Z 大侧）邻格的归一化高度
     * @param water  是否水面
     * @return 光照倍率（{@code [LIGHT_MIN, LIGHT_MAX]}）
     */
    public static double lightFor(double rLeft, double rRight, double rUp, double rDown, boolean water) {
        double slopeX = (rRight - rLeft) * 0.5;
        double slopeZ = (rDown - rUp) * 0.5;
        double light = 1.0 - (slopeX + slopeZ) * LIGHT_GAIN;
        if (light < LIGHT_MIN) light = LIGHT_MIN;
        if (light > LIGHT_MAX) light = LIGHT_MAX;
        if (water) light = 1.0 + (light - 1.0) * WATER_FACTOR;
        return light;
    }
}
