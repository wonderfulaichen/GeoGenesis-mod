package com.geogenesis.worldgen.terrain.morph;

/**
 * 一个条目对引擎暴露的<b>三件可分别过渡</b>的东西。
 *
 * <p>引擎不把"成品高度"当外区域高度去 lerp —— 那会把形态抹平，且端点处与真配方不等。
 * 它要的是信号层 + 线性外壳，然后由 {@link MorphKernel} 按权重把两者<b>各自</b>混合。</p>
 *
 * <p>只有三个求值方法和一个族标记：其余（权重、票槽、外壳是否截断）都在
 * {@link MorphCatalog.Entry} 上，不需要经过这里。</p>
 */
interface MorphRecipe {

    /** 条目所属的地貌族。内核按它求"族权重和"（台地/劣地/山地各走不同分支）。 */
    enum Role { LOW, PLATEAU, BADLANDS, MOUNTAIN }

    /** 信号层：剥掉最后一道振幅/偏置后的完整内部配方。 */
    double raw(double x, double z);

    /** 线性外壳的振幅，允许随位置连续变化。 */
    double amplitude(double x, double z);

    /** 线性外壳的偏置，允许随位置连续变化。 */
    double bias(double x, double z);

    /** 默认 {@link Role#LOW}。 */
    default Role role() { return Role.LOW; }

    /** 外壳之后是否还有 {@code max(·,0)}（复合条目有，其余没有）。 */
    default boolean clamped() { return false; }
}
