package com.geogenesis.worldgen.hydrology.model;

/**
 * <b>床面确定性微噪声（A/B 对照用，诊断层）</b>。
 *
 * <h2>用途</h2>
 * <p>作为 {@link DirectionRule} 三条候选路线中 {@code noise} 那一档的实现，
 * 用于与 {@code edgepsilon}（FastFlow Alg.6，沿水面加 ε 坡）做<b>同参 A/B</b>：
 * 两者都是"打破等水位平局"，但作用对象不同 ——
 * 本类改<b>床面</b>，ε 坡只改<b>水面</b>。</p>
 *
 * <p><b>为什么必须用种子化哈希而不是 {@code Random}</b>：
 * 本项目铁律是"产出必须是 {@code (seed, 坐标)} 的纯函数"，
 * {@code java.util.Random} 依赖调用顺序 ⇒ 违规。
 * 这里用 SplitMix64 式混合，逐位确定、与遍历顺序无关。</p>
 *
 * <h2>⚠ 代价（这是它只是"对照"而非"候选方案"的原因）</h2>
 * <p>噪声进入 {@code bed} 后，会影响<b>所有</b>下游量：湖泊判定（{@code bed <= level}）、
 * 伪洼地过滤（{@code sill - lowest < MIN_VISIBLE_DEPTH}）、侵蚀判定等。
 * 即：<b>它不只是修河道，它改了地形语义</b>。
 * 而 ε 坡只动水面 ⇒ 影响面小得多。这是 A/B 要回答的核心问题之一。</p>
 *
 * <p>幅度取 {@value #AMPLITUDE} 块 —— 比一格的 1/1000 还小，
 * 目的仅是让 {@code ∇η} 非零（从而速度场有定义），<b>不是</b>改造地形起伏。</p>
 */
public final class FlatSurfaceJitter {

    /** 噪声幅度（块）。远小于 1 格：只为破平局，不改地形语义。 */
    public static final double AMPLITUDE = 1e-4;

    private FlatSurfaceJitter() { }

    /** 就地施加微噪声。{@code bed} 为 row-major n×nz 数组。 */
    public static void apply(double[] bed, int nx, int nz, long seed) {
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                bed[i * nz + j] += AMPLITUDE * (unit(mix(seed
                        + (long) i * 0x9E3779B97F4A7C15L
                        + (long) j * 0xC2B2AE3D27D4EB4FL)) * 2.0 - 1.0);
            }
        }
    }

    private static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}