package com.geogenesis.worldgen.terrain.morph;

/**
 * 陆地高度 → e 的取值口径（唯一来源，勿在别处复制字面量）。
 *
 * <h3>为什么基准是 0.02</h3>
 * <p>morph 各族的原始输出是"相对共享地面基准"的增量，最小值只有 {@code -0.02}（steppe 族，
 * 与参考 {@code makeSteppe} 的 {@code height*0.08-0.02} 一致）。{@code GROUND_E=0.02}
 * <b>刚好</b>把该负值抬到 0，因此 {@code max(0)} 在正常地形上不触发，不会切出平顶。</p>
 *
 * <h3>为什么跨度是 1.50</h3>
 * <p>各族原始输出并不铺满 0..1：实测最大是山地 {@code 0.617}、平原只有 {@code 0.106}。
 * 若按"铺满"取 0.96，整幅地形只用掉垂直范围的 4%–24%，读作一片均匀的中等皱褶。
 * 1.50 是本项目已实机认可的口径（山地占 0.02→0.93 e、平原占 0.02→0.16 e，对比 5.4 倍）。</p>
 */
final class MorphScale {

    /**
     * 从基准面起算的 e 偏移（等价参考 {@code levels.ground} 在本项目的对应量）。
     *
     * <p>⚠ 与参考 {@code Populators.apply} 同构：<b>先加基准再截断</b>
     * （{@code max(GROUND_E + h·E_SPAN, 0)}），而不是先截断增量。
     * 顺序反了会让 steppe 族的负值先被削平，之后加基准已救不回来 ⇒ 图上出现大片平顶。</p>
     */
    static final double GROUND_E = 0.02;

    /** 从基准面到 e 上界的跨度。 */
    static final double E_SPAN = 1.50;

    private MorphScale() { }
}
