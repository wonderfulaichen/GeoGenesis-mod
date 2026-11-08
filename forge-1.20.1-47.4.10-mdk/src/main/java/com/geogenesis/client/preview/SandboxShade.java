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
     * 光照增益：{@code r} 差一格时亮度下降多少（<b>基准值</b>）。
     *
     * <p>yardsite 用 {@code 0.55}，但它的 {@code slope} 是「每块上升多少」，
     * 典型值 0.2~1.5 ⇒ 亮度很快触底。本项目 {@code r} 是「柱高比例」，
     * 一格的变化量小一个量级 ⇒ 需要更大的增益才有同样的明暗层次。</p>
     *
     * <h2>★ 为什么必须是自变量（2026-11-08 实测）</h2>
     * <p>曾把增益固定为 {@code 8.0}（按窗口 3072 格标定）。LOD 引入后
     * <b>窗口随缩放变化</b>：细档窗口 384 格时，同样两格方块高差换算到
     * {@code r} 上的差值是粗档的数倍 ⇒ 固定增益会把相邻柱照成棋盘格纹
     * （离线出图实测：整幅绿地上叠一层规整网格，地形起伏反而看不见）。</p>
     *
     * <p>⇒ 增益改为<b>按当前坡度分布自适应</b>：
     * 取坡度绝对值的 P90，令它正好映射到「亮度差 0.45」，
     * 即 {@code gain = 0.45 / P90}；夹在 [{@link #LIGHT_GAIN_MIN}, {@link #LIGHT_GAIN_MAX}]。
     * 于是无论哪一档，画面都用到亮度量程的 45%，且只有最陡的 10% 会饱和。</p>
     */
    public static final double LIGHT_GAIN = 8.0;
    /** 自适应增益下限（极平坦地形别把噪声放大成山）。 */
    public static final double LIGHT_GAIN_MIN = 0.5;
    /** 自适应增益上限。 */
    public static final double LIGHT_GAIN_MAX = 60.0;
    /**
     * 目标：<b>P99.5</b> 坡度映射到的亮度差。
     *
     * <p>★ 为什么不用 P90（2026-11-08 实测）：地形起伏经
     * {@code r=(h-base)/span} 归一后非常平缓，实测坡度均值约 0.004、P90 约 0.02。
     * 按 P90 算出的增益会被上限压住（gain 顶格），整幅仍然没有层次。</p>
     * <p>用 P99.5（只有最陡的 0.5% 饱和）才能让绝大多数格子落在中间灰阶上 ——
     * 这正是"看得出地形、又不糊成一片"的区间。</p>
     */
    public static final double TARGET_P995_DROP = 0.9;

    /**
     * 由坡度绝对值样本算自适应增益（P99.5 → {@link #TARGET_P995_DROP}）。
     *
     * @param absSlopes 坡度绝对值样本（至少 1 个）
     */
    public static double adaptiveGain(double[] absSlopes) {
        if (absSlopes == null || absSlopes.length == 0) return LIGHT_GAIN;
        double[] s = absSlopes.clone();
        java.util.Arrays.sort(s);
        int idx = (int) Math.round(0.995 * (s.length - 1));
        double p = s[idx];
        if (p <= 1e-9) return LIGHT_GAIN_MIN;
        double gain = TARGET_P995_DROP / p;
        if (gain < LIGHT_GAIN_MIN) gain = LIGHT_GAIN_MIN;
        if (gain > LIGHT_GAIN_MAX) gain = LIGHT_GAIN_MAX;
        return gain;
    }

    private SandboxShade() { }

    /**
     * 一格的坡度光照（使用基准增益）。
     *
     * @param rLeft  左边邻格的归一化高度
     * @param rRight 右边邻格的归一化高度
     * @param rUp    上边（Z 小侧）邻格的归一化高度
     * @param rDown  下边（Z 大侧）邻格的归一化高度
     * @param water  是否水面
     * @return 光照倍率（{@code [LIGHT_MIN, LIGHT_MAX]}）
     */
    public static double lightFor(double rLeft, double rRight, double rUp, double rDown, boolean water) {
        return lightFor(rLeft, rRight, rUp, rDown, water, LIGHT_GAIN);
    }

    /**
     * 一格的坡度光照（<b>显式指定增益</b> —— LOD 各档用 {@link #adaptiveGain} 算出的值）。
     *
     * @param gain 坡度增益（见 {@link #adaptiveGain}）
     */
    public static double lightFor(double rLeft, double rRight, double rUp, double rDown,
                                  boolean water, double gain) {
        double slopeX = (rRight - rLeft) * 0.5;
        double slopeZ = (rDown - rUp) * 0.5;
        double light = 1.0 - (slopeX + slopeZ) * gain;
        if (light < LIGHT_MIN) light = LIGHT_MIN;
        if (light > LIGHT_MAX) light = LIGHT_MAX;
        if (water) light = 1.0 + (light - 1.0) * WATER_FACTOR;
        return light;
    }

    /** 单格坡度绝对值（自适应增益与探针共用）。 */
    public static double absSlope(double rLeft, double rRight, double rUp, double rDown) {
        return Math.abs((rRight - rLeft) * 0.5 + (rDown - rUp) * 0.5);
    }
}
