package com.geogenesis.worldgen.terrain;

/**
 * 岩石圈<b>物理量</b>层（2026-11-12，S1）。
 *
 * <h3>它在架构里的位置</h3>
 * <p>「构造决定整体地形」的关键不是"构造给高度"，而是<b>构造给物理量、高度是导出量</b>。
 * 本类只负责第一步：把 {@link TectonicField.Sample} 的连续量（{@code dist} / {@code stress}）
 * 与壳属性 {@code shellS} 转成两个物理属性：</p>
 * <pre>
 *   thickness  岩石圈厚度   （厚 ⇒ 高，因为浮力大）
 *   density    岩石圈密度   （密 ⇒ 低）
 * </pre>
 *
 * <p>并直接给出均衡项 {@code buoyancy = thickness·(ρ_mantle − ρ_litho)}
 * （<b>正=上浮、负=沉降</b>）。</p>
 *
 * <p><b>本类不产出高度</b>，也不改任何现有高度。高度导出在下一层
 * （{@code e = f(thickness, density)}），见
 * {@code audit/GeoGenesis-构造基底架构设计-20261112.md} §三。</p>
 *
 * <h3>为什么高度必须"导出"而不是"直接给"</h3>
 * <p>参考项目 {@code weigert/SimpleTectonics}（MIT）的 {@code Litho::buoyancy}：</p>
 * <pre>
 *   density = mass / (area · thickness)
 *   height  = thickness · (1 − density)
 * </pre>
 * <p>即岩石圈<b>均衡（isostasy）的一阶近似</b>：厚而轻 ⇒ 高；薄而重 ⇒ 低。
 * 这使"高度"成为厚度的导出量，而不是可任意调参的独立常数。</p>
 *
 * <p>相比之下，直接给高程偏置（旧的 {@code elevationOffset}）已被实测否决：
 * 它与权重调制重复造成双重加深，且会<b>连锁增强地形雨</b>
 * （{@code PrecipField.orographicGain} 基于高度差，实测量干旱桶 0.922→0.959）。</p>
 *
 * <h3>连续性约束（项目铁律）</h3>
 * <ul>
 *   <li>只用<b>连续</b>输入：{@code dist}（单调映射）、{@code stress}（连续）、{@code shellS}（连续）。
 *       <b>禁止</b>使用 {@code btype} / {@code rate}——它们是离散标签，边界处跳变会产生线状疤痕。</li>
 *   <li>{@code dist} 只能做<b>单调</b>映射（如饱和增长），<b>绝不能</b>当噪声坐标
 *       ——其等值线平行于 Voronoi 边界，会产生绕板同心环。</li>
 *   <li>所有映射的两端一阶导为 0（smoothstep），避免与其他区段拼接处留折痕。</li>
 * </ul>
 *
 * <h3>量纲</h3>
 * <p>本类输出的是<b>无量纲归一化量</b>（0~1 量级），不是格数。
 * 换算成格数在高度导出层做，这样海陆两侧可以用不同比例
 * （海侧 127 格/e、陆侧 236 格/e，见 {@code HeightCurve}）。</p>
 */
public final class LithoPhysical {

    // ============================================================ 标定常量

    /**
     * 洋壳基准厚度（归一化 0~1）。
     *
     * <p>真实洋壳约 7 km、陆壳约 30~40 km ⇒ 比值约 1:5。
     * 这里取 0.20 作为归一化基准，与陆壳 1.0 形成 1:5。</p>
     */
    static final double THICKNESS_OCEANIC = 0.20;

    /**
     * 年纪增长带来的**岩石圈**厚度增量（归一化）。
     *
     * <p>岩石圈随冷却增厚：脊轴处薄（~10 km），数千万年后可达 ~100 km，
     * 但<b>随年龄开方饱和</b>而非线性（半空间冷却模型）。</p>
     */
    static final double THICKNESS_AGE_GAIN = 0.35;

    /**
     * 年纪增长带来的**密度**增量（归一化）。
     *
     * <h3>为什么密度必须随年龄增长</h3>
     * <p>岩石圈冷却时密度增大。若不建模这一项，洋底会"越老越浮"——与真实相反
     * （真实：脊轴 ~2500 m，深海平原 ~5500 m，越老越深）。</p>
     *
     * <p>本类把密度合成到 {@link #DENSITY_OCEANIC_AGED}（0.92）为止，
     * 始终留在 [0,1] 内，<b>不需要越界</b>——因为下沉来自与地幔的密度差（见 {@code subsidence}）。</p>
     */
    static final double DENSITY_AGE_GAIN = 0.30;

    /**
     * 厚度随离边界距离饱和的距离尺度（wu）。
     *
     * <p>与 {@code PROFILE_SIGMA=110}、{@code PLATE_SPACING=2000} 同量级：
     * 取 900wu 使"从脊轴到深海平原"的过渡发生在板块内部可容纳的范围。</p>
     */
    static final double AGE_REACH_WU = 900.0;

    /**
     * 脊轴半宽（wu）：{@code dist} 小于它时视为"正在扩张的中心"，
     * 厚度取最小值（最薄 ⇒ 最浅/最高）。
     */
    static final double RIDGE_HALF_WU = 120.0;

    /** 洋壳基准密度（脊轴处，最热最轻）。真实洋壳（玄武岩）比陆壳（花岗岩）密。 */
    static final double DENSITY_OCEANIC = 0.62;
    /** 陆壳密度（归一化）。 */
    static final double DENSITY_CONTINENTAL = 0.40;

    /**
     * 地幔参考密度（归一化 0~1 标度内的上界）。
     *
     * <p>均衡（isostasy）的正统一阶形式是<b>相对密度差</b>：</p>
     * <pre>
     *   subsidence ∝ (ρ_litho − ρ_mantle) · thickness
     * </pre>
     * <p>只有"岩石圈比地幔重多少"才产生沉降。脊轴薄且热 ⇒ 差值小 ⇒ 沉降小（高）；
     * 远脊厚且冷 ⇒ 差值大 ⇒ 沉降大（低）。这个方向是式子自带的，
     * 不需要任何符号翻转或密度越界。</p>
     *
     * <p>取 0.95 使 {@code ρ_mantle − ρ_continental = 0.40}、
     * {@code ρ_mantle − ρ_oceanic = 0.05} ⇒ 陆壳"负沉降"（上浮）远大于洋壳。</p>
     */
    static final double DENSITY_MANTLE = 0.95;

    private LithoPhysical() { }

    // ============================================================ 采样结果

    /**
     * 构造物理量采样结果（不可变）。
     *
     * @param shellS     壳属性 0=洋壳 / 1=陆壳（连续）
     * @param thickness  岩石圈厚度（归一化 0.20~0.75，越大越"浮"）
     * @param density    岩石圈密度（归一化 0.55~0.92，越大越"沉"）
     * @param buoyancy   浮力项 {@code thickness·(ρ_mantle − ρ_litho)}，
     *                   <b>正=上浮、负=沉降</b>；陆壳为正、洋壳接近 0 或为负
     */
    public record Sample(double shellS, double thickness, double density, double buoyancy) { }

    // ============================================================ 主查询

    /**
     * 由构造采样 + 壳属性计算物理量。
     *
     * @param ts     构造场采样（只用连续量 {@code dist} / {@code stress}）
     * @param shellS 壳属性（{@link TectonicField#shellFromC} 的结果，0~1）
     * @return 物理量（{@code buoyancy} 是高度层的直接输入）
     */
    public static Sample of(TectonicField.Sample ts, double shellS) {
        double s = clamp01(shellS);
        double dist = Math.max(0.0, ts.dist());

        // ---- 1) 厚度：陆壳基准高，洋壳基准低；洋壳再随离脊距离增厚 ----
        //   陆壳厚度：恒 1.0（大陆岩石圈本来就厚，不随到边界距离变化）
        //   洋壳厚度：THICKNESS_OCEANIC + 年龄增量 × 饱和(dist)
        double oceanThickness = THICKNESS_OCEANIC + THICKNESS_AGE_GAIN * ageFactor(dist);
        //   用 shellS 在两套之间**连续插值**（不是分支！）
        //   ⇒ 岸线处不会出现"两套来源对不上"
        double thickness = lerp(oceanThickness, 1.0, s);

        // ---- 2) 密度：洋壳密、陆壳轻；洋壳再随年龄冷却增密 ----
        //   ★ 密度必须随年龄增长，否则洋底会"越老越浮"（与真实相反）。
        //   全部收在 [0.55, 0.92] 内，不越界 —— 见 DENSITY_AGE_GAIN。
        double oceanDensity = DENSITY_OCEANIC + DENSITY_AGE_GAIN * ageFactor(dist);
        double density = lerp(oceanDensity, DENSITY_CONTINENTAL, s);

        // ---- 3) 均衡：相对地幔的密度差 × 厚度 ----
        //   buoyancy > 0 = 上浮（陆壳）; < 0 = 沉降（老洋底）
        //   这是 isostasy 的一阶式，方向天然正确：
        //     脊轴薄热 ⇒ (ρ_mantle−ρ) 小 × thickness 小 ⇒ 沉降小 ⇒ 高
        //     远脊厚冷 ⇒ 差值变小但 thickness 大增 ⇒ 沉降大 ⇒ 低
        double buoyancy = thickness * (DENSITY_MANTLE - density);

        return new Sample(s, thickness, density, buoyancy);
    }

    /**
     * 年龄因子：{@code dist} 从脊轴半宽开始，随距离饱和增长到 1。
     *
     * <p>⚠ 这是 {@code dist} 的<b>单调</b>映射（只用于"越远越厚"），
     * 不是把它当噪声坐标——后者会产生绕板同心环（项目铁律）。</p>
     */
    static double ageFactor(double dist) {
        return smoothstep(RIDGE_HALF_WU, RIDGE_HALF_WU + AGE_REACH_WU, dist);
    }

    // ============================================================ 数值工具

    /**
     * Hermite smoothstep：两端一阶导为 0。
     *
     * <p>为何不用 {@code clamp} 或线性：硬钳位/线性在端点留折痕，
     * 而地形上的折痕会表现为笔直线段（项目铁律 ④）。</p>
     */
    static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
